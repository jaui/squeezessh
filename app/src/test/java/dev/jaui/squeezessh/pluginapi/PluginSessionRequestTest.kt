package dev.jaui.squeezessh.pluginapi

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `ssh://` trägt zwei Bedeutungen: ein neues Ziel und ein Verweis auf eine gespeicherte
 * Verbindung. Verwechselt die App sie, versucht sie eine Verbindung zu einem Server namens
 * „41b4f013-…" aufzubauen — oder öffnet umgekehrt eine fremde gespeicherte Verbindung.
 */
@RunWith(RobolectricTestRunner::class)
class PluginSessionRequestTest {

    private fun publicIdOf(uri: String?) = PluginSessionRequest.publicIdOf(uri?.let(Uri::parse))

    @Test
    fun `eine UUID wird erkannt`() {
        assertThat(publicIdOf("ssh://41b4f013-ca39-49b9-8837-3ee5864b5498"))
            .isEqualTo("41b4f013-ca39-49b9-8837-3ee5864b5498")
    }

    @Test
    fun `Grossschreibung wird vereinheitlicht`() {
        assertThat(publicIdOf("ssh://41B4F013-CA39-49B9-8837-3EE5864B5498"))
            .isEqualTo("41b4f013-ca39-49b9-8837-3ee5864b5498")
    }

    @Test
    fun `ein gewoehnliches Verbindungsziel ist keine Plugin-Anfrage`() {
        assertThat(publicIdOf("ssh://root@web.example.com:2222")).isNull()
        assertThat(publicIdOf("ssh://10.0.2.2:2222")).isNull()
        assertThat(publicIdOf("ssh://server")).isNull()
    }

    @Test
    fun `ein anderes Schema zaehlt nicht`() {
        assertThat(publicIdOf("mosh://41b4f013-ca39-49b9-8837-3ee5864b5498")).isNull()
        assertThat(publicIdOf("https://41b4f013-ca39-49b9-8837-3ee5864b5498")).isNull()
    }

    @Test
    fun `etwas UUID-aehnliches reicht nicht`() {
        // Zu kurz, zu lang, falsche Zeichen: alles drei muss durchfallen, sonst landete es
        // als Hostname im Schnellzugriff.
        assertThat(publicIdOf("ssh://41b4f013-ca39-49b9-8837-3ee5864b549")).isNull()
        assertThat(publicIdOf("ssh://41b4f013-ca39-49b9-8837-3ee5864b54989")).isNull()
        assertThat(publicIdOf("ssh://41b4f013-ca39-49b9-8837-3ee5864b549g")).isNull()
        assertThat(publicIdOf("ssh://41b4f013ca3949b988373ee5864b5498")).isNull()
    }

    @Test
    fun `ein abschliessender Schraegstrich stoert nicht`() {
        assertThat(publicIdOf("ssh://41b4f013-ca39-49b9-8837-3ee5864b5498/"))
            .isEqualTo("41b4f013-ca39-49b9-8837-3ee5864b5498")
    }

    @Test
    fun `ohne URI keine Anfrage`() {
        assertThat(PluginSessionRequest.publicIdOf(null)).isNull()
        assertThat(PluginSessionRequest.isPluginRequest(null)).isFalse()
    }
}
