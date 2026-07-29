package dev.jaui.squeezessh.ssh

import dev.jaui.squeezessh.data.repository.KnownHostRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baut Verbindungen auf, inklusive Sprung ueber Bastion-Hosts.
 *
 * Eine Kette wird von aussen nach innen aufgebaut: zuerst der aeusserste Bastion, dann
 * durch dessen Tunnel der naechste, bis das Ziel erreicht ist. Schlaegt ein Glied fehl,
 * werden alle bereits offenen Verbindungen wieder geschlossen — sonst blieben
 * Bastion-Sitzungen als Leichen zurueck.
 */
@Singleton
class SshConnector @Inject constructor(
    private val knownHosts: KnownHostRepository,
    private val io: CoroutineDispatcher,
) {

    /**
     * @param chain aeusserster Sprung zuerst, Ziel zuletzt. Ein einzelnes Element ist der
     *   Normalfall ohne Bastion.
     * @param onHostKey wird gefragt, wenn ein Host-Key unbekannt oder geaendert ist.
     * @param onProgress bekommt jeden Schritt gemeldet, damit die Oberflaeche zeigen kann,
     *   an welchem Glied der Kette es gerade haengt.
     */
    /**
     * Baut die Kette auf und fragt bei unbekannten Host-Keys nach.
     *
     * Die Frage stellt sich **zwischen** zwei Versuchen, nicht waehrend eines: taucht ein
     * unbekannter Schluessel auf, wird der Aufbau abgebrochen, gefragt, das Ergebnis
     * gespeichert und neu verbunden. Waehrend jemand einen Fingerprint prueft, laeuft so
     * kein Protokoll-Timeout mit.
     *
     * Jeder Sprung braucht hoechstens eine Entscheidung, deshalb genuegen entsprechend
     * viele Versuche.
     */
    suspend fun connect(
        chain: List<SshHop>,
        onHostKey: suspend (HostKeyPrompt) -> Boolean,
        onProgress: (SshConnectionState) -> Unit = {},
    ): SshConnection {
        require(chain.isNotEmpty()) { "Verbindungskette ist leer" }

        repeat(chain.size + 1) {
            try {
                return connectOnce(chain, onProgress)
            } catch (e: SshException) {
                val error = e.error
                if (error !is SshError.HostKeyUnverified) throw e

                if (!onHostKey(error.prompt)) {
                    throw SshException(
                        SshError.HostKeyRejected(
                            "Host-Key von ${error.prompt.host}:${error.prompt.port} " +
                                "wurde nicht bestaetigt",
                        ),
                        e,
                    )
                }
                with(error.prompt) {
                    knownHosts.trust(host, port, keyType, publicKey, fingerprint)
                }
            }
        }
        throw SshException(SshError.Unexpected("Host-Key-Pruefung kam zu keinem Ergebnis"))
    }

    private suspend fun connectOnce(
        chain: List<SshHop>,
        onProgress: (SshConnectionState) -> Unit,
    ): SshConnection = withContext(io) {
        val opened = mutableListOf<SSHClient>()
        val verifiers = mutableListOf<KnownHostVerifier>()
        try {
            for ((index, hop) in chain.withIndex()) {
                onProgress(SshConnectionState.Connecting(hop.host))

                // Bekannte Schluessel vorab laden: der Verifier darf spaeter nicht mehr
                // auf die Datenbank warten.
                val verifier = KnownHostVerifier(
                    known = knownHosts.findFor(hop.host, hop.port),
                    logicalHost = hop.host,
                    logicalPort = hop.port,
                )
                verifiers += verifier

                val client = SSHClient().apply {
                    addHostKeyVerifier(verifier)
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    timeout = READ_TIMEOUT_MILLIS
                }

                if (index == 0) {
                    client.connect(hop.host, hop.port)
                } else {
                    // Durch den Tunnel des vorherigen Sprungs statt ueber einen eigenen Socket.
                    val tunnel = opened.last().newDirectConnection(hop.host, hop.port)
                    client.connectVia(tunnel)
                }
                opened += client
                authenticate(client, hop)
            }
            onProgress(SshConnectionState.Connected)
            SshConnection(clients = opened.toList())
        } catch (e: Throwable) {
            opened.asReversed().forEach { runCatching { it.close() } }
            // Ein abgelehnter Host-Key sieht in SSHJ wie ein Transportfehler aus. Die
            // eigentliche Ursache steht im Verifier.
            verifiers.firstNotNullOfOrNull { it.pendingPrompt }?.let { prompt ->
                throw SshException(SshError.HostKeyUnverified(prompt), e)
            }
            throw e.asSshException()
        }
    }

    /**
     * Probiert die hinterlegten Verfahren der Reihe nach.
     *
     * SSHJ wuerde bei `auth(user, methods...)` selbst durchprobieren, meldet dann aber nur
     * den letzten Fehler. Die Schleife hier behaelt den ersten — der ist meist der
     * aussagekraeftigere, weil er zum bevorzugten Verfahren gehoert.
     */
    private fun authenticate(client: SSHClient, hop: SshHop) {
        if (hop.credentials.isEmpty()) {
            throw SshException(SshError.AuthenticationFailed("Keine Zugangsdaten hinterlegt"))
        }

        var firstFailure: Throwable? = null
        for (credential in hop.credentials) {
            try {
                client.auth(hop.username, credential.toAuthMethod(client))
                return
            } catch (e: UserAuthException) {
                if (firstFailure == null) firstFailure = e
            } catch (e: IOException) {
                if (firstFailure == null) firstFailure = e
            }
        }
        throw SshException(
            SshError.AuthenticationFailed(
                "Anmeldung als ${hop.username}@${hop.host} abgelehnt: ${firstFailure?.message}",
            ),
            firstFailure,
        )
    }

    private fun SshCredential.toAuthMethod(client: SSHClient): AuthMethod = when (this) {
        is SshCredential.Password -> AuthPassword(staticPassword(password))

        is SshCredential.PrivateKey -> AuthPublickey(loadKey(client, this))

        is SshCredential.KeyboardInteractive -> AuthKeyboardInteractive(
            object : ChallengeResponseProvider {
                private var lastPrompts = emptyList<String>()

                override fun getSubmethods(): List<String> = emptyList()

                override fun init(resource: Resource<*>, name: String, instruction: String) = Unit

                override fun getResponse(prompt: String, echo: Boolean): CharArray {
                    lastPrompts = listOf(prompt)
                    return respond(lastPrompts).firstOrNull().orEmpty().toCharArray()
                }

                override fun shouldRetry(): Boolean = false
            },
        )
    }

    private fun loadKey(client: SSHClient, credential: SshCredential.PrivateKey): KeyProvider =
        try {
            client.loadKeys(
                credential.privateKey,
                credential.publicKey,
                credential.passphrase?.let(::staticPassword),
            )
        } catch (e: IOException) {
            throw SshException(
                SshError.KeyUnusable("Privater Schluessel unlesbar: ${e.message}"),
                e,
            )
        }

    /**
     * Liefert das Geheimnis genau einmal.
     *
     * [PasswordFinder.shouldRetry] gibt `false` zurueck: bei einem falschen Passwort
     * wuerde SSHJ sonst denselben Wert erneut senden und den Server nach drei Versuchen
     * die Verbindung schliessen lassen.
     */
    private fun staticPassword(secret: String): PasswordFinder = object : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = secret.toCharArray()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    private fun Throwable.asSshException(): SshException = when (this) {
        is SshException -> this
        is UnknownHostException -> SshException(SshError.Unreachable("Host nicht gefunden"), this)
        is ConnectException -> SshException(SshError.Unreachable("Verbindung abgelehnt"), this)
        is SocketTimeoutException ->
            SshException(SshError.Unreachable("Zeitueberschreitung"), this)
        is UserAuthException -> SshException(SshError.AuthenticationFailed(message), this)
        is SSHException -> SshException(SshError.ConnectionLost(message), this)
        is IOException -> SshException(SshError.Unreachable(message), this)
        else -> SshException(SshError.Unexpected(message), this)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}

/**
 * Eine offene Verbindung samt aller Sprünge davor.
 *
 * [close] raeumt die Kette von innen nach aussen ab, damit kein Tunnel geschlossen wird,
 * durch den noch etwas laeuft.
 */
class SshConnection internal constructor(
    private val clients: List<SSHClient>,
) : AutoCloseable {

    /** Der eigentliche Zielserver — das letzte Glied der Kette. */
    internal val target: SSHClient get() = clients.last()

    val isConnected: Boolean get() = clients.all { it.isConnected }

    override fun close() {
        clients.asReversed().forEach { runCatching { it.close() } }
    }
}
