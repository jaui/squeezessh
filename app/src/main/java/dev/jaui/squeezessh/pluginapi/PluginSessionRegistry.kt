package dev.jaui.squeezessh.pluginapi

import dev.jaui.squeezessh.ssh.SshSession
import dev.jaui.squeezessh.ssh.SshSessionManager
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vergibt und prüft die Sitzungsmerkmale, mit denen Plugins arbeiten.
 *
 * Der Vertrag von JuiceSSH gibt ein Paar aus `sessionId` (int) und `sessionKey` (String)
 * heraus; jeder weitere AIDL-Aufruf trägt beide. Die Nummer allein ist wertlos und darf es
 * auch sein: sie ist klein, aufsteigend und für ein anderes Plugin leicht zu raten. Der
 * Schlüssel ist das Sicherheitsmerkmal — 128 zufällige Bit, verglichen in konstanter Zeit,
 * damit ein Plugin nicht über Antwortzeiten an ein fremdes Sitzungsmerkmal kommt.
 *
 * Ein Plugin bekommt nur Zugriff auf Sitzungen, die es selbst geöffnet hat. Das ist strenger
 * als bei JuiceSSH und kostet nichts: das Paar wird ohnehin beim Öffnen ausgegeben.
 */
@Singleton
class PluginSessionRegistry @Inject constructor(
    private val sessions: SshSessionManager,
) {

    /**
     * @param sessionId die nach außen gereichte Nummer
     * @param sessionKey das Geheimnis, das jeden weiteren Aufruf begleitet
     * @param internalId die Kennung der Sitzung in [SshSessionManager]
     * @param owner das Paket, das die Sitzung geöffnet hat
     */
    data class Handle(
        val sessionId: Int,
        val sessionKey: String,
        val internalId: Long,
        val owner: String,
    )

    private val handles = ConcurrentHashMap<Int, Handle>()
    private val nextId = AtomicInteger(1)
    private val random = SecureRandom()

    private val finishedListeners = ConcurrentHashMap<Int, MutableList<() -> Unit>>()

    fun register(session: SshSession, owner: String): Handle {
        val handle = Handle(
            sessionId = nextId.getAndIncrement(),
            sessionKey = newKey(),
            internalId = session.id,
            owner = owner,
        )
        handles[handle.sessionId] = handle
        return handle
    }

    /**
     * @return die Sitzung, wenn Nummer und Schlüssel zusammenpassen und der Aufrufer sie
     *   selbst geöffnet hat — sonst `null`.
     */
    fun resolve(sessionId: Int, sessionKey: String?, caller: String): SshSession? {
        val handle = handles[sessionId] ?: return null
        if (handle.owner != caller) return null
        if (!matches(handle.sessionKey, sessionKey)) return null
        val session = sessions.openSessions.value[handle.internalId]
        if (session == null || !session.isAlive) {
            forget(sessionId)
            return null
        }
        return session
    }

    suspend fun close(sessionId: Int, sessionKey: String?, caller: String) {
        val handle = handles[sessionId] ?: return
        if (handle.owner != caller || !matches(handle.sessionKey, sessionKey)) return
        sessions.close(handle.internalId)
        forget(sessionId)
    }

    /**
     * Merkt sich, dass jemand über das Ende benachrichtigt werden will.
     *
     * Gerufen wird beim Aufräumen in [forget]; ein Plugin, dessen Prozess weg ist, wird
     * dabei einfach übergangen — deshalb steht der Aufruf in einem `runCatching`.
     */
    fun addFinishedListener(sessionId: Int, sessionKey: String?, caller: String, listener: () -> Unit) {
        val handle = handles[sessionId] ?: return
        if (handle.owner != caller || !matches(handle.sessionKey, sessionKey)) return
        finishedListeners.getOrPut(sessionId) { mutableListOf() }.add(listener)
    }

    /** Räumt eine beendete Sitzung ab und benachrichtigt, wer darauf gewartet hat. */
    fun forget(sessionId: Int) {
        handles.remove(sessionId) ?: return
        finishedListeners.remove(sessionId)?.forEach { runCatching { it() } }
    }

    private fun newKey(): String {
        val bytes = ByteArray(KEY_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Vergleich in konstanter Zeit. Ein früher Abbruch beim ersten falschen Zeichen würde
     * verraten, wie viel vom Schlüssel bereits stimmt.
     */
    private fun matches(expected: String, actual: String?): Boolean {
        if (actual == null || actual.length != expected.length) return false
        var difference = 0
        for (index in expected.indices) {
            difference = difference or (expected[index].code xor actual[index].code)
        }
        return difference == 0
    }

    private companion object {
        const val KEY_BYTES = 16
    }
}
