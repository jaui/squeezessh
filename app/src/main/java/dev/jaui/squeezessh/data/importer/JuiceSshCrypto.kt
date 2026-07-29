package dev.jaui.squeezessh.data.importer

import android.util.Base64
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Entschluesselt Felder aus einer JuiceSSH-Datenbank.
 *
 * Verfahren (aus der APK ermittelt, siehe docs/juicessh-datenformat.md):
 *
 * ```
 * Wert  = Base64(salt[8]) "#" Base64(iv[16]) "#" Base64(ciphertext)
 * Key   = PBKDF2WithHmacSHA1(passwort, salt, 1000 Runden, 256 Bit)
 * Klar  = AES/CBC/PKCS5Padding
 * ```
 *
 * Nur Entschluesselung — SqueezeSSH schreibt nie zurueck in eine JuiceSSH-Datenbank.
 */
internal object JuiceSshCrypto {

    /**
     * @return Klartext, oder `null` wenn [blob] leer ist oder nicht dem Format entspricht.
     * @throws JuiceSshDecryptionException wenn das Format stimmt, der Schluessel aber nicht passt.
     */
    fun decrypt(blob: String?, password: String): String? {
        if (blob.isNullOrEmpty()) return null
        val parts = blob.split('#')
        if (parts.size != 3) return null

        return try {
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, deriveKey(password, salt), IvParameterSpec(iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            // Kein gueltiges Base64 — der Wert war nie verschluesselt.
            null
        } catch (e: GeneralSecurityException) {
            throw JuiceSshDecryptionException("Wert liess sich nicht entschluesseln", e)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val ITERATIONS = 1000
    private const val KEY_BITS = 256
}

class JuiceSshDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
