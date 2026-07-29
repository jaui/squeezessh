package dev.jaui.squeezessh.ssh

import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.IdentityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Haelt die offenen Sitzungen.
 *
 * Sitzungen ueberleben Bildschirmwechsel und den Wechsel in den Hintergrund, weil sie hier
 * und nicht an einer Activity haengen. Damit der Prozess dabei nicht eingesammelt wird,
 * laeuft parallel [SshSessionService].
 */
@Singleton
class SshSessionManager @Inject constructor(
    private val connector: SshConnector,
    private val connections: ConnectionRepository,
    private val identities: IdentityRepository,
    private val io: CoroutineDispatcher,
) {

    private val sessions = MutableStateFlow<Map<Long, SshSession>>(emptyMap())
    val openSessions: StateFlow<Map<Long, SshSession>> = sessions.asStateFlow()

    private val nextId = AtomicLong(1)
    private val lock = Mutex()

    /**
     * Baut die Kette zu [connectionId] auf und oeffnet eine Shell.
     *
     * Die Sprung-Kette und die Zugangsdaten werden hier aufgeloest, damit der
     * [SshConnector] nichts von der Datenbank wissen muss.
     */
    suspend fun open(
        connectionId: Long,
        terminalType: String = DEFAULT_TERMINAL_TYPE,
        columns: Int = DEFAULT_COLUMNS,
        rows: Int = DEFAULT_ROWS,
        onHostKey: suspend (HostKeyPrompt) -> Boolean,
        onProgress: (SshConnectionState) -> Unit = {},
    ): SshSession {
        val chain = connections.resolveChain(connectionId)
        if (chain.isEmpty()) {
            throw SshException(SshError.Unexpected("Verbindung $connectionId existiert nicht"))
        }

        val hops = chain.map { it.toHop() }
        val connection = connector.connect(hops, onHostKey, onProgress)

        // Das Oeffnen der Shell schickt Pakete ueber die Leitung und darf deshalb
        // nicht auf dem Hauptthread laufen: dort wirft Android bei Netzzugriff eine
        // NetworkOnMainThreadException — mitten im Schreiben eines SSH-Pakets. Der
        // Server sieht dann ein halbes Paket und wirft die Verbindung als „Bad packet
        // length" weg.
        val shell = try {
            withContext(io) { SshShell.open(connection, terminalType, columns, rows) }
        } catch (e: Throwable) {
            connection.close()
            throw SshException(SshError.ConnectionLost("Shell liess sich nicht oeffnen"), e)
        }

        val session = SshSession(
            id = nextId.getAndIncrement(),
            connectionId = connectionId,
            title = chain.last().displayName,
            connection = connection,
            shell = shell,
        )
        lock.withLock { sessions.update { it + (session.id to session) } }
        connections.recordUse(connectionId)
        return session
    }

    suspend fun close(sessionId: Long) {
        lock.withLock {
            val session = sessions.value[sessionId] ?: return
            session.close()
            sessions.update { it - sessionId }
        }
    }

    suspend fun closeAll() {
        lock.withLock {
            sessions.value.values.forEach { it.close() }
            sessions.value = emptyMap()
        }
    }

    /**
     * Entfernt Sitzungen, deren Gegenstelle weg ist.
     *
     * Ein weggebrochener Server schliesst den Kanal, ohne dass wir es aktiv merken —
     * ohne Aufraeumen bliebe eine tote Kachel in der Oberflaeche stehen.
     */
    suspend fun pruneDead(): List<Long> = lock.withLock {
        val dead = sessions.value.filterValues { !it.isAlive }.keys.toList()
        if (dead.isNotEmpty()) {
            dead.forEach { sessions.value[it]?.close() }
            sessions.update { current -> current - dead.toSet() }
        }
        dead
    }

    private suspend fun ConnectionEntity.toHop(): SshHop {
        val linked = connections.identitiesFor(id)
        val credentials = linked.flatMap { identity ->
            buildList {
                identities.revealPrivateKey(identity)?.let { key ->
                    add(
                        SshCredential.PrivateKey(
                            privateKey = key,
                            passphrase = identities.revealPrivateKeyPassword(identity),
                            publicKey = identity.publicKey,
                        ),
                    )
                }
                identities.revealPassword(identity)?.let { add(SshCredential.Password(it)) }
            }
        }
        return SshHop(
            host = address,
            port = port,
            // Ohne verknuepfte Identitaet gibt es keinen Benutzernamen — das faellt beim
            // Verbinden als Authentifizierungsfehler auf, nicht hier still.
            username = linked.firstOrNull()?.username.orEmpty(),
            credentials = credentials,
        )
    }

    companion object {
        const val DEFAULT_TERMINAL_TYPE = "xterm-256color"
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
    }
}

/** Eine laufende Sitzung: Verbindung, Shell und die Kennzeichnung fuer die Oberflaeche. */
class SshSession internal constructor(
    val id: Long,
    val connectionId: Long,
    val title: String,
    private val connection: SshConnection,
    val shell: SshShell,
) : AutoCloseable {

    val isAlive: Boolean get() = connection.isConnected && shell.isOpen

    override fun close() {
        shell.close()
        connection.close()
    }
}
