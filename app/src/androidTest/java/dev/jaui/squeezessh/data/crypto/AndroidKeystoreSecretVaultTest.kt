package dev.jaui.squeezessh.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der echte Vault braucht den Android Keystore und laeuft deshalb instrumentiert,
 * nicht unter Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretVaultTest {

    private val vault = AndroidKeystoreSecretVault()

    @Test
    fun sealThenRevealRoundTrips() {
        val secret = "correct horse battery staple"

        val sealed = vault.seal(secret)!!

        assertNotEquals(secret, sealed)
        assertTrue(sealed.startsWith("v1:"))
        assertEquals(secret, vault.reveal(sealed))
    }

    @Test
    fun handlesUnicodeAndMultilineKeys() {
        val privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nZüge\nabc\n-----END-----\n"

        assertEquals(privateKey, vault.reveal(vault.seal(privateKey)))
    }

    @Test
    fun nullStaysNull() {
        assertNull(vault.seal(null))
        assertNull(vault.reveal(null))
    }

    /** GCM erzeugt bei jedem Aufruf ein neues IV — gleicher Klartext darf nicht gleich aussehen. */
    @Test
    fun sameInputProducesDifferentCiphertext() {
        assertNotEquals(vault.seal("hunter2"), vault.seal("hunter2"))
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val sealed = vault.seal("hunter2")!!
        val tampered = sealed.dropLast(4) + "AAAA"

        assertThrows(SecretVaultException::class.java) { vault.reveal(tampered) }
    }

    @Test
    fun rejectsUnknownFormat() {
        assertThrows(SecretVaultException::class.java) { vault.reveal("plaintext") }
    }
}
