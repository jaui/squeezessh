package dev.jaui.squeezessh.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SecretVault] auf Basis des Android Keystore.
 *
 * Der AES-Schluessel ist nicht exportierbar und verlaesst die Hardware nie. Damit sind die
 * Geheimnisse an dieses Geraet gebunden: ein Backup der Datenbank allein nuetzt einem
 * Angreifer nichts, ein Geraetewechsel erfordert aber einen bewussten Export.
 *
 * Format eines versiegelten Werts: `v1:` + Base64(IV ‖ Ciphertext ‖ GCM-Tag).
 */
@Singleton
class AndroidKeystoreSecretVault @Inject constructor() : SecretVault {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    override fun seal(plaintext: String?): String? {
        if (plaintext == null) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            throw SecretVaultException("Geheimnis konnte nicht verschluesselt werden", e)
        }
    }

    override fun reveal(ciphertext: String?): String? {
        if (ciphertext == null) return null
        if (!ciphertext.startsWith(PREFIX)) {
            throw SecretVaultException("Unbekanntes Format eines versiegelten Werts")
        }
        return try {
            val raw = Base64.decode(ciphertext.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) {
                throw SecretVaultException("Versiegelter Wert ist zu kurz")
            }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_LENGTH_BITS, raw, 0, IV_LENGTH),
                )
            }
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw SecretVaultException("Geheimnis konnte nicht entschluesselt werden", e)
        } catch (e: IllegalArgumentException) {
            throw SecretVaultException("Versiegelter Wert ist beschaedigt", e)
        }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Bewusst ohne setUserAuthenticationRequired: der App-Lock (M7) regelt den
                // Zugriff auf App-Ebene. Sonst waeren Hintergrund-Reconnects unmoeglich.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "squeezessh.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
