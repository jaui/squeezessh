package dev.jaui.squeezessh.pluginapi

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.ssh.SshSession
import dev.jaui.squeezessh.ssh.SshSessionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test

/**
 * Die Zugriffsregeln der Plugin-Sitzungen.
 *
 * Ein Plugin, das ein fremdes Merkmal errät oder mitliest, könnte sonst über eine Sitzung
 * verfügen, die ihm nicht gehört — und damit über den Server dahinter.
 */
class PluginSessionRegistryTest {

    private val openSessions = MutableStateFlow<Map<Long, SshSession>>(emptyMap())
    private lateinit var registry: PluginSessionRegistry

    private val owner = "com.example.plugin"
    private val stranger = "com.example.fremd"

    @Before
    fun setUp() {
        val manager = mockk<SshSessionManager>(relaxed = true)
        every { manager.openSessions } returns openSessions
        registry = PluginSessionRegistry(manager)
    }

    private fun addSession(id: Long, alive: Boolean = true): SshSession {
        val session = mockk<SshSession>(relaxed = true)
        every { session.id } returns id
        every { session.isAlive } returns alive
        openSessions.value = openSessions.value + (id to session)
        return session
    }

    @Test
    fun `wer die Sitzung geoeffnet hat kommt an sie heran`() {
        val session = addSession(7)
        val handle = registry.register(session, owner)

        assertThat(registry.resolve(handle.sessionId, handle.sessionKey, owner)).isSameInstanceAs(session)
    }

    @Test
    fun `ein anderes Plugin kommt nicht an sie heran`() {
        val handle = registry.register(addSession(7), owner)

        assertThat(registry.resolve(handle.sessionId, handle.sessionKey, stranger)).isNull()
    }

    @Test
    fun `ohne den richtigen Schluessel geht nichts`() {
        val handle = registry.register(addSession(7), owner)

        assertThat(registry.resolve(handle.sessionId, "falsch", owner)).isNull()
        assertThat(registry.resolve(handle.sessionId, null, owner)).isNull()
        // Auch ein Schlüssel richtiger Länge darf nicht durchkommen.
        val sameLength = "0".repeat(handle.sessionKey.length)
        assertThat(registry.resolve(handle.sessionId, sameLength, owner)).isNull()
    }

    @Test
    fun `die Nummer allein oeffnet nichts`() {
        val handle = registry.register(addSession(7), owner)

        // Die Nummer ist klein und aufsteigend — sie zu erraten muss folgenlos bleiben.
        assertThat(handle.sessionId).isEqualTo(1)
        assertThat(registry.resolve(1, "", owner)).isNull()
    }

    @Test
    fun `zwei Sitzungen bekommen verschiedene Schluessel`() {
        val first = registry.register(addSession(1), owner)
        val second = registry.register(addSession(2), owner)

        assertThat(first.sessionKey).isNotEqualTo(second.sessionKey)
        assertThat(first.sessionId).isNotEqualTo(second.sessionId)
        // 128 Bit als Hex.
        assertThat(first.sessionKey).matches("[0-9a-f]{32}")
    }

    @Test
    fun `eine tote Sitzung wird nicht mehr herausgegeben`() {
        val handle = registry.register(addSession(7, alive = false), owner)

        assertThat(registry.resolve(handle.sessionId, handle.sessionKey, owner)).isNull()
        // Und sie ist danach vergessen, nicht nur einmal abgewiesen.
        openSessions.value = emptyMap()
        assertThat(registry.resolve(handle.sessionId, handle.sessionKey, owner)).isNull()
    }

    @Test
    fun `wer auf das Ende wartet wird benachrichtigt`() {
        val handle = registry.register(addSession(7), owner)
        var notified = false
        registry.addFinishedListener(handle.sessionId, handle.sessionKey, owner) { notified = true }

        registry.forget(handle.sessionId)

        assertThat(notified).isTrue()
    }

    @Test
    fun `ein Fremder kann sich nicht auf das Ende anmelden`() {
        val handle = registry.register(addSession(7), owner)
        var notified = false
        registry.addFinishedListener(handle.sessionId, handle.sessionKey, stranger) { notified = true }

        registry.forget(handle.sessionId)

        assertThat(notified).isFalse()
    }

    @Test
    fun `eine in der Oberflaeche geschlossene Sitzung wird ungueltig`() {
        val handle = registry.register(addSession(7), owner)

        // So sieht es aus, wenn der Nutzer den Tab schliesst: die Sitzung verschwindet aus
        // dem Manager, ohne dass die Registrierung davon erfaehrt.
        openSessions.value = emptyMap()

        assertThat(registry.resolve(handle.sessionId, handle.sessionKey, owner)).isNull()
    }
}
