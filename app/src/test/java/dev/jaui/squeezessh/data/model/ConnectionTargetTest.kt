package dev.jaui.squeezessh.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Reine Textverarbeitung — laeuft ohne Android-Umgebung. */
class ConnectionTargetTest {

    @Test
    fun `nur ein Hostname`() {
        val target = ConnectionTarget.parse("web.example.com")!!

        assertThat(target.host).isEqualTo("web.example.com")
        assertThat(target.port).isEqualTo(22)
        assertThat(target.username).isNull()
    }

    @Test
    fun `Benutzer und Host`() {
        val target = ConnectionTarget.parse("root@web.example.com")!!

        assertThat(target.username).isEqualTo("root")
        assertThat(target.host).isEqualTo("web.example.com")
        assertThat(target.port).isEqualTo(22)
    }

    @Test
    fun `Benutzer Host und Port`() {
        val target = ConnectionTarget.parse("deploy@10.0.0.5:2222")!!

        assertThat(target.username).isEqualTo("deploy")
        assertThat(target.host).isEqualTo("10.0.0.5")
        assertThat(target.port).isEqualTo(2222)
    }

    @Test
    fun `ssh-Schema wird abgeschnitten`() {
        val target = ConnectionTarget.parse("ssh://deploy@web.example.com:2200")!!

        assertThat(target.username).isEqualTo("deploy")
        assertThat(target.host).isEqualTo("web.example.com")
        assertThat(target.port).isEqualTo(2200)
    }

    @Test
    fun `weitere Schemata werden ebenfalls verstanden`() {
        assertThat(ConnectionTarget.parse("sftp://host")!!.host).isEqualTo("host")
        assertThat(ConnectionTarget.parse("mosh://host")!!.host).isEqualTo("host")
        assertThat(ConnectionTarget.parse("telnet://host")!!.host).isEqualTo("host")
    }

    /** Ein Benutzername darf selbst ein @ enthalten — getrennt wird am letzten. */
    @Test
    fun `Benutzername mit at-Zeichen`() {
        val target = ConnectionTarget.parse("vorname.nachname@firma.de@bastion.example.com")!!

        assertThat(target.username).isEqualTo("vorname.nachname@firma.de")
        assertThat(target.host).isEqualTo("bastion.example.com")
    }

    @Test
    fun `IPv6 in eckigen Klammern mit Port`() {
        val target = ConnectionTarget.parse("root@[2001:db8::1]:2222")!!

        assertThat(target.username).isEqualTo("root")
        assertThat(target.host).isEqualTo("2001:db8::1")
        assertThat(target.port).isEqualTo(2222)
    }

    @Test
    fun `IPv6 in eckigen Klammern ohne Port`() {
        val target = ConnectionTarget.parse("[::1]")!!

        assertThat(target.host).isEqualTo("::1")
        assertThat(target.port).isEqualTo(22)
    }

    /** Ohne Klammern ist ein Doppelpunkt nicht vom Port zu unterscheiden — dann kein Port. */
    @Test
    fun `IPv6 ohne Klammern gilt komplett als Host`() {
        val target = ConnectionTarget.parse("2001:db8::1")!!

        assertThat(target.host).isEqualTo("2001:db8::1")
        assertThat(target.port).isEqualTo(22)
    }

    @Test
    fun `Pfad hinter dem Host wird verworfen`() {
        val target = ConnectionTarget.parse("ssh://root@host.example.com:22/some/path")!!

        assertThat(target.host).isEqualTo("host.example.com")
        assertThat(target.port).isEqualTo(22)
    }

    @Test
    fun `umgebende Leerzeichen stoeren nicht`() {
        assertThat(ConnectionTarget.parse("  root@host  ")!!.host).isEqualTo("host")
    }

    @Test
    fun `leere Eingabe liefert nichts`() {
        assertThat(ConnectionTarget.parse(null)).isNull()
        assertThat(ConnectionTarget.parse("")).isNull()
        assertThat(ConnectionTarget.parse("   ")).isNull()
        assertThat(ConnectionTarget.parse("ssh://")).isNull()
    }

    @Test
    fun `ohne Host liefert nichts`() {
        assertThat(ConnectionTarget.parse("root@")).isNull()
    }

    @Test
    fun `unbrauchbarer Port wird abgelehnt statt still ignoriert`() {
        assertThat(ConnectionTarget.parse("host:0")).isNull()
        assertThat(ConnectionTarget.parse("host:99999")).isNull()
        assertThat(ConnectionTarget.parse("host:abc")).isNull()
    }

    @Test
    fun `Rueckweg in die Kurzschreibweise`() {
        assertThat(ConnectionTarget("host").toString()).isEqualTo("host")
        assertThat(ConnectionTarget("host", 2222).toString()).isEqualTo("host:2222")
        assertThat(ConnectionTarget("host", 22, "root").toString()).isEqualTo("root@host")
        assertThat(ConnectionTarget("host", 2222, "root").toString()).isEqualTo("root@host:2222")
        assertThat(ConnectionTarget("::1", 2222).toString()).isEqualTo("[::1]:2222")
    }

    @Test
    fun `Hin- und Rueckweg bleiben stabil`() {
        listOf("host", "host:2222", "root@host", "root@host:2222", "[2001:db8::1]:2222")
            .forEach { input ->
                assertThat(ConnectionTarget.parse(input).toString()).isEqualTo(input)
            }
    }
}
