package dev.jaui.squeezessh.data.importer

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.model.BackspaceMode
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.data.model.PortForwardMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class JuiceSshMappingTest {

    /** Ordinalzahlen auf dem Emulator gegen die laufende JuiceSSH 3.2.2 verifiziert. */
    @Test
    fun `Verbindungstypen entsprechen den Ordinalzahlen des Originals`() {
        assertThat(JuiceSshMapping.connectionType(0)).isEqualTo(ConnectionType.SSH)
        assertThat(JuiceSshMapping.connectionType(1)).isEqualTo(ConnectionType.MOSH)
        assertThat(JuiceSshMapping.connectionType(2)).isEqualTo(ConnectionType.LOCAL)
        assertThat(JuiceSshMapping.connectionType(3)).isEqualTo(ConnectionType.TELNET)
    }

    @Test
    fun `unbekannter oder fehlender Typ faellt auf SSH zurueck`() {
        assertThat(JuiceSshMapping.connectionType(null)).isEqualTo(ConnectionType.SSH)
        assertThat(JuiceSshMapping.connectionType(99)).isEqualTo(ConnectionType.SSH)
    }

    @Test
    fun `Backspace-Modus unterscheidet DEL von Legacy`() {
        assertThat(JuiceSshMapping.backspaceMode(0)).isEqualTo(BackspaceMode.DELETE)
        assertThat(JuiceSshMapping.backspaceMode(1)).isEqualTo(BackspaceMode.BACKSPACE)
        assertThat(JuiceSshMapping.backspaceMode(null)).isEqualTo(BackspaceMode.DELETE)
    }

    @Test
    fun `Port-Forward-Modi folgen MODE_LOCAL MODE_REMOTE MODE_SOCKS`() {
        assertThat(JuiceSshMapping.portForwardMode(0)).isEqualTo(PortForwardMode.LOCAL)
        assertThat(JuiceSshMapping.portForwardMode(1)).isEqualTo(PortForwardMode.REMOTE)
        assertThat(JuiceSshMapping.portForwardMode(2)).isEqualTo(PortForwardMode.DYNAMIC)
    }

    @Test
    fun `Hostname in OpenSSH-Klammerform wird in Host und Port zerlegt`() {
        assertThat(JuiceSshMapping.parseHostname("[web.example.com]:2222"))
            .isEqualTo("web.example.com" to 2222)
        assertThat(JuiceSshMapping.parseHostname("[10.0.0.5]:22"))
            .isEqualTo("10.0.0.5" to 22)
    }

    @Test
    fun `Hostname ohne Klammern gilt als Standardport`() {
        assertThat(JuiceSshMapping.parseHostname("web.example.com"))
            .isEqualTo("web.example.com" to 22)
    }

    @Test
    fun `leerer Hostname liefert nichts statt einer kaputten Zeile`() {
        assertThat(JuiceSshMapping.parseHostname(null)).isNull()
        assertThat(JuiceSshMapping.parseHostname("  ")).isNull()
    }

    @Test
    fun `Host-Key-Typ wird aus dem Wire-Format des Keys gelesen`() {
        // Die Spalte host.type fuellt JuiceSSH nicht — der Algorithmus steht im Key selbst.
        assertThat(JuiceSshMapping.hostKeyType(sshKey("ssh-ed25519")))
            .isEqualTo(HostKeyType.SSH_ED25519)
        assertThat(JuiceSshMapping.hostKeyType(sshKey("ssh-rsa")))
            .isEqualTo(HostKeyType.SSH_RSA)
        assertThat(JuiceSshMapping.hostKeyType(sshKey("ecdsa-sha2-nistp256")))
            .isEqualTo(HostKeyType.ECDSA_SHA2_NISTP256)
    }

    @Test
    fun `angegebener Typ hat Vorrang vor dem Key`() {
        assertThat(JuiceSshMapping.hostKeyType(sshKey("ssh-rsa"), declaredType = "ssh-ed25519"))
            .isEqualTo(HostKeyType.SSH_ED25519)
    }

    @Test
    fun `unlesbarer Key ergibt UNKNOWN statt einer Ausnahme`() {
        assertThat(JuiceSshMapping.hostKeyType(null)).isEqualTo(HostKeyType.UNKNOWN)
        assertThat(JuiceSshMapping.hostKeyType("kein base64 ###")).isEqualTo(HostKeyType.UNKNOWN)
        assertThat(JuiceSshMapping.hostKeyType(Base64.encodeToString(byteArrayOf(1, 2), Base64.NO_WRAP)))
            .isEqualTo(HostKeyType.UNKNOWN)
    }

    @Test
    fun `gespeicherter Fingerprint wird uebernommen`() {
        assertThat(JuiceSshMapping.fingerprint("SHA256:vorhanden", sshKey("ssh-rsa")))
            .isEqualTo("SHA256:vorhanden")
    }

    @Test
    fun `fehlender Fingerprint wird aus dem Key berechnet`() {
        val fingerprint = JuiceSshMapping.fingerprint(null, sshKey("ssh-ed25519"))

        assertThat(fingerprint).startsWith("SHA256:")
        assertThat(fingerprint).doesNotContain("=") // ohne Padding, wie bei OpenSSH
    }

    /** Baut einen SSH-Key im Wire-Format: 4-Byte-Laenge, Algorithmusname, Rest. */
    private fun sshKey(algorithm: String): String {
        val name = algorithm.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, (name.size shr 8).toByte(), name.size.toByte()))
        out.write(name)
        out.write(ByteArray(32) { it.toByte() })
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
