package dev.jaui.squeezessh.data.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Werte stammen aus der Fixture-Datenbank in `test/resources/fixtures`, die auf einem
 * Emulator mit JuiceSSH 3.2.2 erzeugt wurde.
 */
@RunWith(RobolectricTestRunner::class)
class JuiceSshCryptoTest {

    private val accountHash = "19a093afcee2a1df8bb6ac14d79843"
    private val masterKeyBlob =
        "du4ampqmqck=#/eFCfZEUl/+LDWQYOwounw==#dEBIoED4Lo5vo6NkUcs6ZIJBPInJ1KDm58aMwg9o7fQ="
    private val identityPasswordBlob =
        "NA9R3fP4csE=#CwzR/W5GlxCkKbmurj27SA==#4doLdIwYZ3JWUM91iTGj+g=="

    @Test
    fun `Master-Key wird mit dem account_hash als Passwort entschluesselt`() {
        assertThat(JuiceSshCrypto.decrypt(masterKeyBlob, accountHash))
            .isEqualTo("49ie3i5qkvj183s3g9ssa62scq")
    }

    @Test
    fun `Feldwert wird mit dem Master-Key entschluesselt`() {
        val masterKey = JuiceSshCrypto.decrypt(masterKeyBlob, accountHash)!!

        assertThat(JuiceSshCrypto.decrypt(identityPasswordBlob, masterKey))
            .isEqualTo("MarkerPass123")
    }

    @Test
    fun `leerer Wert liefert null statt einer Ausnahme`() {
        assertThat(JuiceSshCrypto.decrypt(null, "egal")).isNull()
        assertThat(JuiceSshCrypto.decrypt("", "egal")).isNull()
    }

    @Test
    fun `Wert ohne die drei Abschnitte gilt als unverschluesselt`() {
        assertThat(JuiceSshCrypto.decrypt("nur-text", "egal")).isNull()
        assertThat(JuiceSshCrypto.decrypt("a#b", "egal")).isNull()
    }

    @Test
    fun `falsches Passwort schlaegt fehl statt Muell zu liefern`() {
        // PKCS5-Padding faellt bei falschem Schluessel praktisch immer auf.
        assertThrows(JuiceSshDecryptionException::class.java) {
            JuiceSshCrypto.decrypt(identityPasswordBlob, "falscher-key")
        }
    }
}
