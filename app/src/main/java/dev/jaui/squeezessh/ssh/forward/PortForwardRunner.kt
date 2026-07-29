package dev.jaui.squeezessh.ssh.forward

import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.model.PortForwardMode
import dev.jaui.squeezessh.ssh.SshConnection
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Eine laufende Weiterleitung.
 *
 * Alle drei Arten binden entweder einen lokalen Port oder einen auf dem Server; [close]
 * gibt ihn wieder frei. Ohne das bliebe der Port belegt, bis der Prozess endet — und ein
 * erneutes Starten scheiterte mit „Address already in use".
 */
sealed class RunningForward(val definition: PortForwardEntity) : AutoCloseable {

    abstract val isAlive: Boolean

    /** Wo sich der Tunnel benutzen laesst, in fuer Menschen lesbarer Form. */
    abstract val endpoint: String

    companion object {

        /**
         * @param bindAddress Standard ist 127.0.0.1. Ein Tunnel, der versehentlich am
         *   ganzen Geraet lauscht, waere im WLAN fuer jeden offen.
         */
        @Throws(IOException::class)
        fun start(
            connection: SshConnection,
            definition: PortForwardEntity,
            bindAddress: String = LOOPBACK,
        ): RunningForward = when (definition.mode) {
            PortForwardMode.LOCAL -> LocalForward(connection, definition, bindAddress)
            PortForwardMode.DYNAMIC -> DynamicForward(connection, definition, bindAddress)
            PortForwardMode.REMOTE -> RemoteForward(connection, definition)
        }

        const val LOOPBACK = "127.0.0.1"
    }
}

/**
 * `ssh -L`: ein lokaler Port zeigt auf ein Ziel, das vom Server aus erreichbar ist.
 */
private class LocalForward(
    connection: SshConnection,
    definition: PortForwardEntity,
    bindAddress: String,
) : RunningForward(definition) {

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(bindAddress), definition.localPort))
    }

    private val forwarder = LocalPortForwarder(
        connection.target.connection,
        Parameters(
            bindAddress,
            serverSocket.localPort,
            definition.targetHost.orEmpty(),
            definition.remotePort ?: definition.localPort,
        ),
        serverSocket,
        connection.target.transport.config.loggerFactory,
    )

    private val thread = Thread({ runCatching { forwarder.listen() } }, "squeezessh-forward-local")
        .apply { isDaemon = true; start() }

    override val isAlive: Boolean get() = !serverSocket.isClosed && thread.isAlive

    override val endpoint: String
        get() = "$bindAddressShown:${serverSocket.localPort} → " +
            "${definition.targetHost}:${definition.remotePort}"

    private val bindAddressShown = bindAddress

    override fun close() {
        runCatching { forwarder.close() }
        runCatching { serverSocket.close() }
        thread.interrupt()
    }
}

/**
 * `ssh -D`: ein lokaler SOCKS5-Proxy, dessen Ziel jede Verbindung selbst mitbringt.
 */
private class DynamicForward(
    connection: SshConnection,
    definition: PortForwardEntity,
    private val bindAddress: String,
) : RunningForward(definition) {

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(bindAddress), definition.localPort))
    }

    private val server = Socks5Server(connection.target, serverSocket)

    private val thread = Thread(server::listen, "squeezessh-forward-socks").apply {
        isDaemon = true
        start()
    }

    override val isAlive: Boolean get() = !serverSocket.isClosed && thread.isAlive

    override val endpoint: String get() = "SOCKS5 auf $bindAddress:${serverSocket.localPort}"

    override fun close() {
        server.close()
        thread.interrupt()
    }
}

/**
 * `ssh -R`: ein Port auf dem Server zeigt auf ein Ziel, das von hier aus erreichbar ist.
 *
 * Ob der Server das ueberhaupt zulaesst, entscheidet dessen `AllowTcpForwarding` — ein
 * abgelehnter Bind kommt als [IOException] zurueck.
 */
private class RemoteForward(
    private val connection: SshConnection,
    definition: PortForwardEntity,
) : RunningForward(definition) {

    private val forwarder: RemotePortForwarder = connection.target.remotePortForwarder
    private val forward: RemotePortForwarder.Forward = forwarder.bind(
        RemotePortForwarder.Forward(definition.remotePort ?: definition.localPort),
        SocketForwardingConnectListener(
            InetSocketAddress(
                definition.targetHost ?: RunningForward.LOOPBACK,
                definition.localPort,
            ),
        ),
    )

    override val isAlive: Boolean
        get() = connection.isConnected && forward in forwarder.activeForwards

    override val endpoint: String
        get() = "Server-Port ${forward.port} → ${definition.targetHost}:${definition.localPort}"

    override fun close() {
        runCatching { forwarder.cancel(forward) }
    }
}
