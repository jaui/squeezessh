package dev.jaui.squeezessh.data

import dev.jaui.squeezessh.data.crypto.SecretVault
import dev.jaui.squeezessh.data.crypto.SecretVaultException

/**
 * Ersetzt den Android Keystore im Unit-Test.
 *
 * Die „Verschluesselung" ist bewusst nur ein Praefix — geprueft werden soll, dass die
 * Repositories konsequent [SecretVault.seal] und [SecretVault.reveal] benutzen und niemals
 * Klartext in die Datenbank schreiben. Der echte Vault wird instrumentiert getestet.
 */
class FakeSecretVault : SecretVault {

    override fun seal(plaintext: String?): String? = plaintext?.let { PREFIX + it }

    override fun reveal(ciphertext: String?): String? {
        if (ciphertext == null) return null
        if (!ciphertext.startsWith(PREFIX)) {
            throw SecretVaultException("Nicht versiegelt: $ciphertext")
        }
        return ciphertext.removePrefix(PREFIX)
    }

    companion object {
        const val PREFIX = "sealed:"
    }
}
