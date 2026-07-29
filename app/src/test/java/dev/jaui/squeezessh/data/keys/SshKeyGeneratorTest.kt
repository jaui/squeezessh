package dev.jaui.squeezessh.data.keys

import com.google.common.truth.Truth.assertThat
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.StringReader
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Prueft nicht nur, dass etwas herauskommt, sondern dass SSHJ es auch wieder einlesen kann
 * — sonst faellt ein Formatfehler erst beim ersten Verbindungsversuch auf.
 */
@RunWith(RobolectricTestRunner::class)
class SshKeyGeneratorTest {

    private val generator = SshKeyGenerator()

    /**
     * Liest den Schluessel genauso ein, wie es die App beim Verbinden tut: ueber die
     * Format-Erkennung und die dazu registrierte Factory von SSHJ. Klassennamen direkt zu
     * verwenden waere bruechig — welche Klasse welches Format bedient, aendert sich.
     */
    private fun readWithSshj(pair: SshKeyPair): java.security.KeyPair {
        val format = KeyProviderUtil.detectKeyFileFormat(StringReader(pair.privateKeyPem), false)
        val provider = DefaultConfig().fileKeyProviderFactories
            .first { it.name == format.toString() }
            .create() as FileKeyProvider
        provider.init(StringReader(pair.privateKeyPem))
        return java.security.KeyPair(provider.public, provider.private)
    }

    @Test
    fun `Ed25519-Schluessel laesst sich von SSHJ lesen`() {
        val pair = generator.generate(SshKeyType.ED25519, comment = "test@squeezessh")

        assertThat(pair.privateKeyPem).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
        assertThat(pair.privateKeyPem.trimEnd()).endsWith("-----END OPENSSH PRIVATE KEY-----")

        val keys = readWithSshj(pair)
        assertThat(KeyType.fromKey(keys.public)).isEqualTo(KeyType.ED25519)
    }

    @Test
    fun `RSA-Schluessel laesst sich von SSHJ lesen und hat die verlangte Laenge`() {
        val pair = generator.generate(SshKeyType.RSA_3072)

        val keys = readWithSshj(pair)
        assertThat(KeyType.fromKey(keys.public)).isEqualTo(KeyType.RSA)
        assertThat((keys.public as RSAPublicKey).modulus.bitLength()).isEqualTo(3072)
    }

    @Test
    fun `RSA 4096 erzeugt auch 4096 Bit`() {
        val pair = generator.generate(SshKeyType.RSA_4096)

        val keys = readWithSshj(pair)
        assertThat((keys.public as RSAPublicKey).modulus.bitLength()).isEqualTo(4096)
    }

    /**
     * Der wichtigste Test: der exportierte oeffentliche Teil muss zum privaten gehoeren.
     * Passt er nicht, laesst sich der Schluessel zwar anlegen, aber nirgends hinterlegen.
     */
    @Test
    fun `oeffentlicher Teil passt zum privaten`() {
        listOf(SshKeyType.ED25519, SshKeyType.RSA_3072).forEach { type ->
            val pair = generator.generate(type)
            val keys = readWithSshj(pair)

            val fromPrivate = Buffer.PlainBuffer()
                .also { KeyType.fromKey(keys.public).putPubKeyIntoBuffer(keys.public, it) }
                .compactData
            val fromExport = Base64.getDecoder().decode(pair.publicKeyLine.split(" ")[1])

            assertThat(fromExport).isEqualTo(fromPrivate)
        }
    }

    @Test
    fun `Public-Key-Zeile hat das Format von authorized_keys`() {
        val pair = generator.generate(SshKeyType.ED25519, comment = "jaui@laptop")

        val parts = pair.publicKeyLine.split(" ")
        assertThat(parts).hasSize(3)
        assertThat(parts[0]).isEqualTo("ssh-ed25519")
        assertThat(parts[2]).isEqualTo("jaui@laptop")
        // Base64 ohne Zeilenumbrueche — eine authorized_keys-Zeile ist genau eine Zeile.
        assertThat(pair.publicKeyLine).doesNotContain("\n")
    }

    @Test
    fun `Fingerprint stimmt mit dem aus der Public-Key-Zeile ueberein`() {
        val pair = generator.generate(SshKeyType.ED25519)

        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine(pair.publicKeyLine))
            .isEqualTo(pair.fingerprint)
        assertThat(pair.fingerprint).startsWith("SHA256:")
        assertThat(pair.fingerprint).doesNotContain("=")
    }

    @Test
    fun `zwei Aufrufe liefern verschiedene Schluessel`() {
        val first = generator.generate(SshKeyType.ED25519)
        val second = generator.generate(SshKeyType.ED25519)

        assertThat(first.privateKeyPem).isNotEqualTo(second.privateKeyPem)
        assertThat(first.fingerprint).isNotEqualTo(second.fingerprint)
    }

    @Test
    fun `unlesbare Public-Key-Zeile liefert keinen Fingerprint`() {
        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine(null)).isNull()
        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("")).isNull()
        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("ssh-ed25519")).isNull()
        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("ssh-ed25519 ###")).isNull()
        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("ssh-ed25519 bm90IGEga2V5")).isNull()
    }

    /** Ein echter Schluessel mit falsch angegebenem Typ ist ebenfalls unbrauchbar. */
    @Test
    fun `Typangabe muss zum Schluessel passen`() {
        val pair = generator.generate(SshKeyType.ED25519)
        val base64 = pair.publicKeyLine.split(" ")[1]

        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("ssh-rsa $base64")).isNull()
        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("ssh-ed25519 $base64"))
            .isEqualTo(pair.fingerprint)
    }

    @Test
    fun `Kommentar darf fehlen oder Leerzeichen enthalten`() {
        val pair = generator.generate(SshKeyType.ED25519, comment = "jaui@laptop")
        val base64 = pair.publicKeyLine.split(" ")[1]

        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("ssh-ed25519 $base64"))
            .isEqualTo(pair.fingerprint)
        assertThat(SshKeyGenerator.fingerprintOfPublicKeyLine("  ssh-ed25519  $base64  mein rechner  "))
            .isEqualTo(pair.fingerprint)
    }
}
