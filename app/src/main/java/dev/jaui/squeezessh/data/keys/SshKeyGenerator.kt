package dev.jaui.squeezessh.data.keys

import android.util.Base64
import dev.jaui.squeezessh.ssh.BouncyCastleSetup
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erzeugt SSH-Schluesselpaare und schreibt sie in den Formaten, die OpenSSH versteht.
 *
 * Verwendet BouncyCastle direkt statt der JCA: die Kodierung in das OpenSSH-Format ist
 * genau das, was `OpenSSHPrivateKeyUtil` und `OpenSSHPublicKeyUtil` liefern — ueber die
 * JCA muesste sie von Hand nachgebaut werden.
 */
@Singleton
class SshKeyGenerator @Inject constructor() {

    private val random = SecureRandom()

    /**
     * @param comment landet am Ende der Public-Key-Zeile, so wie OpenSSH es macht. Dient
     *   allein der Wiedererkennung in `authorized_keys`.
     */
    fun generate(type: SshKeyType, comment: String = DEFAULT_COMMENT): SshKeyPair {
        BouncyCastleSetup.install()
        return try {
            when (type) {
                SshKeyType.ED25519 -> generateEd25519(comment)
                SshKeyType.RSA_3072, SshKeyType.RSA_4096 -> generateRsa(type, comment)
            }
        } catch (e: Exception) {
            throw SshKeyException("Schluessel konnte nicht erzeugt werden: ${e.message}", e)
        }
    }

    private fun generateEd25519(comment: String): SshKeyPair {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(random))
        }
        return generator.generateKeyPair().toSshKeyPair(SshKeyType.ED25519, comment)
    }

    private fun generateRsa(type: SshKeyType, comment: String): SshKeyPair {
        val generator = RSAKeyPairGenerator().apply {
            init(
                RSAKeyGenerationParameters(
                    PUBLIC_EXPONENT,
                    random,
                    type.bits,
                    RSA_CERTAINTY,
                ),
            )
        }
        return generator.generateKeyPair().toSshKeyPair(type, comment)
    }

    private fun AsymmetricCipherKeyPair.toSshKeyPair(
        type: SshKeyType,
        comment: String,
    ): SshKeyPair {
        val publicWire = OpenSSHPublicKeyUtil.encodePublicKey(public)
        val publicBase64 = Base64.encodeToString(publicWire, Base64.NO_WRAP)

        return SshKeyPair(
            type = type,
            privateKeyPem = toPem(OpenSSHPrivateKeyUtil.encodePrivateKey(private), type),
            publicKeyLine = "${type.wireName} $publicBase64 $comment",
            fingerprint = fingerprintOf(publicWire),
        )
    }

    /**
     * Verpackt den kodierten Schluessel in PEM.
     *
     * Die Ueberschrift haengt am Format, das BouncyCastle liefert: Ed25519 kommt im
     * neuen `openssh-key-v1`-Container, RSA dagegen als PKCS#1. Eine falsche Ueberschrift
     * laesst den Schluessel beim Einlesen scheitern.
     */
    private fun toPem(encoded: ByteArray, type: SshKeyType): String {
        val label = when (type) {
            SshKeyType.ED25519 -> "OPENSSH PRIVATE KEY"
            SshKeyType.RSA_3072, SshKeyType.RSA_4096 -> "RSA PRIVATE KEY"
        }
        val body = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN ").append(label).append("-----\n")
            body.chunked(PEM_LINE_LENGTH).forEach { append(it).append('\n') }
            append("-----END ").append(label).append("-----\n")
        }
    }

    companion object {
        private const val DEFAULT_COMMENT = "squeezessh"
        private const val PEM_LINE_LENGTH = 70
        private const val RSA_CERTAINTY = 100
        private val PUBLIC_EXPONENT: BigInteger = BigInteger.valueOf(65537)

        /** SHA-256-Fingerprint ueber das Wire-Format, wie `ssh-keygen -l` ihn anzeigt. */
        fun fingerprintOf(publicKeyWireBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyWireBytes)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }

        /**
         * Fingerprint einer `authorized_keys`-Zeile.
         *
         * Prueft dabei, dass der kodierte Teil wirklich ein SSH-Schluessel des angegebenen
         * Typs ist. Blosses Base64-Dekodieren genuegt nicht: Androids Dekoder verwirft
         * ungueltige Zeichen stillschweigend und liefert fuer Unsinn einen leeren Puffer —
         * der haette dann brav den Fingerprint der leeren Eingabe ergeben.
         *
         * @return `null`, wenn die Zeile nicht dem Format `<typ> <base64> [kommentar]`
         *   folgt oder der Schluessel nicht zum angegebenen Typ passt.
         */
        fun fingerprintOfPublicKeyLine(line: String?): String? {
            val parts = line?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
                ?: return null
            if (parts.size < 2) return null

            val decoded = try {
                Base64.decode(parts[1], Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (algorithmNameOf(decoded) != parts[0]) return null
            return fingerprintOf(decoded)
        }

        /**
         * Der Algorithmusname aus dem Wire-Format: 4 Byte Laenge, dann der Name.
         */
        private fun algorithmNameOf(wireBytes: ByteArray): String? {
            if (wireBytes.size < 4) return null
            val length = ((wireBytes[0].toInt() and 0xFF) shl 24) or
                ((wireBytes[1].toInt() and 0xFF) shl 16) or
                ((wireBytes[2].toInt() and 0xFF) shl 8) or
                (wireBytes[3].toInt() and 0xFF)
            if (length !in 1..64 || wireBytes.size < 4 + length) return null
            return String(wireBytes, 4, length, Charsets.US_ASCII)
        }
    }
}
