package dev.jaui.squeezessh.ssh.forward

import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.ssh.SshConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Haelt die laufenden Weiterleitungen.
 *
 * Sie haengen an einer offenen Sitzung: faellt die weg, sind auch die Tunnel weg. Deshalb
 * werden sie hier nach Sitzung gefuehrt und beim Schliessen mit abgeraeumt — ein
 * verwaister Tunnel wuerde sonst den lokalen Port bis zum App-Ende blockieren.
 */
@Singleton
class PortForwardManager @Inject constructor(
    private val io: CoroutineDispatcher,
) {

    private val _active = MutableStateFlow<Map<Long, ActiveForward>>(emptyMap())
    val active: StateFlow<Map<Long, ActiveForward>> = _active.asStateFlow()

    private val lock = Mutex()

    fun isRunning(portForwardId: Long): Boolean = _active.value[portForwardId]?.isAlive == true

    /**
     * Startet eine Weiterleitung.
     *
     * @throws PortForwardException wenn der lokale Port belegt ist oder der Server den
     *   Bind ablehnt — beides sind Faelle, die dem Nutzer etwas sagen muessen.
     */
    suspend fun start(
        sessionId: Long,
        connection: SshConnection,
        definition: PortForwardEntity,
    ): ActiveForward = withContext(io) {
        lock.withLock {
            _active.value[definition.id]?.let { existing ->
                if (existing.isAlive) return@withContext existing
                stopInternal(definition.id)
            }

            val running = try {
                RunningForward.start(connection, definition)
            } catch (e: IOException) {
                throw PortForwardException(describeFailure(definition, e), e)
            }

            val active = ActiveForward(sessionId, running)
            _active.update { it + (definition.id to active) }
            active
        }
    }

    suspend fun stop(portForwardId: Long) = withContext(io) {
        lock.withLock { stopInternal(portForwardId) }
    }

    /** Beim Schliessen einer Sitzung: alles abraeumen, was daran hing. */
    suspend fun stopAllFor(sessionId: Long) = withContext(io) {
        lock.withLock {
            _active.value.filterValues { it.sessionId == sessionId }.keys.forEach(::stopInternal)
        }
    }

    suspend fun stopAll() = withContext(io) {
        lock.withLock { _active.value.keys.toList().forEach(::stopInternal) }
    }

    private fun stopInternal(portForwardId: Long) {
        _active.value[portForwardId]?.runCatching { running.close() }
        _active.update { it - portForwardId }
    }

    private fun describeFailure(definition: PortForwardEntity, cause: IOException): String {
        val message = cause.message.orEmpty()
        return when {
            message.contains("Address already in use", ignoreCase = true) ->
                "Port ${definition.localPort} ist auf diesem Gerät schon belegt."
            message.contains("Permission denied", ignoreCase = true) ->
                "Port ${definition.localPort} darf nicht belegt werden — Ports unter 1024 " +
                    "sind auf Android gesperrt."
            else ->
                "Weiterleitung ließ sich nicht starten: ${cause.message}"
        }
    }
}

data class ActiveForward(
    val sessionId: Long,
    val running: RunningForward,
) {
    val definition: PortForwardEntity get() = running.definition
    val isAlive: Boolean get() = running.isAlive
    val endpoint: String get() = running.endpoint
}

class PortForwardException(message: String, cause: Throwable? = null) : Exception(message, cause)
