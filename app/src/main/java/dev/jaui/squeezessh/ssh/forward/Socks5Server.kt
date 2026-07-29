package dev.jaui.squeezessh.ssh.forward

import net.schmizz.sshj.SSHClient
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ein minimaler SOCKS5-Proxy, der eingehende Verbindungen durch die SSH-Verbindung leitet.
 *
 * SSHJ bringt fuer dynamische Weiterleitung (`ssh -D`) nichts mit: es kennt nur feste
 * Ziel-Paare. Ein SOCKS-Proxy bestimmt das Ziel dagegen pro Verbindung, weshalb er hier
 * selbst implementiert ist.
 *
 * Umgesetzt ist genau der Teil von RFC 1928, den Browser und `curl --socks5` benutzen:
 * Anmeldung ohne Authentifizierung und das Kommando CONNECT. BIND und UDP ASSOCIATE
 * fehlen — beides braucht praktisch niemand ueber einen SSH-Tunnel, und beides waere ohne
 * Gegenstelle nicht testbar.
 */
class Socks5Server(
    private val client: SSHClient,
    private val serverSocket: ServerSocket,
) : AutoCloseable {

    private val running = AtomicBoolean(true)

    /** Ein Thread pro Verbindung: die Kopierschleifen blockieren beide Richtungen. */
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "squeezessh-socks5").apply { isDaemon = true }
    }

    val localPort: Int get() = serverSocket.localPort

    /** Blockiert bis [close]. Gehoert in einen eigenen Thread. */
    fun listen() {
        while (running.get() && !serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (running.get()) continue else break
            }
            workers.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                val input = DataInputStream(client.getInputStream().buffered())
                val output = client.getOutputStream()

                if (!negotiateNoAuth(input, output)) return
                val target = readConnectRequest(input, output) ?: return
                openTunnel(client, target, input, output)
            }
        } catch (e: IOException) {
            // Ein abgebrochener Browser-Tab ist der Normalfall, kein Fehler.
        }
    }

    /**
     * Aushandlung der Methode. Angeboten wird nur „keine Authentifizierung": der Tunnel
     * ist bereits durch SSH geschuetzt, eine zweite Anmeldung waere sinnlos.
     */
    private fun negotiateNoAuth(input: DataInputStream, output: OutputStream): Boolean {
        if (input.readUnsignedByte() != VERSION) return false
        val methodCount = input.readUnsignedByte()
        val methods = ByteArray(methodCount).also(input::readFully)

        if (methods.none { it.toInt() == METHOD_NO_AUTH }) {
            output.write(byteArrayOf(VERSION.toByte(), METHOD_NONE_ACCEPTABLE.toByte()))
            output.flush()
            return false
        }
        output.write(byteArrayOf(VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()
        return true
    }

    private fun readConnectRequest(
        input: DataInputStream,
        output: OutputStream,
    ): Target? {
        if (input.readUnsignedByte() != VERSION) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte() // reserviert

        val addressType = input.readUnsignedByte()
        val host = when (addressType) {
            ATYP_IPV4 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
            ATYP_DOMAIN -> {
                val length = input.readUnsignedByte()
                String(ByteArray(length).also(input::readFully), Charsets.US_ASCII)
            }
            else -> {
                reply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                return null
            }
        }
        val port = input.readUnsignedShort()

        if (command != COMMAND_CONNECT) {
            reply(output, REPLY_COMMAND_NOT_SUPPORTED)
            return null
        }
        return Target(host.orEmpty(), port)
    }

    private fun openTunnel(
        socket: Socket,
        target: Target,
        input: InputStream,
        output: OutputStream,
    ) {
        val tunnel = try {
            client.newDirectConnection(target.host, target.port)
        } catch (e: IOException) {
            reply(output, REPLY_HOST_UNREACHABLE)
            return
        }

        reply(output, REPLY_SUCCEEDED)

        // Beide Richtungen laufen parallel; endet eine, wird die Verbindung geschlossen
        // und die andere laeuft in ihr IOException-Ende.
        val upstream = workers.submit {
            runCatching { input.copyTo(tunnel.outputStream, COPY_BUFFER_BYTES) }
            runCatching { socket.close() }
        }
        runCatching { tunnel.inputStream.copyTo(output, COPY_BUFFER_BYTES) }
        upstream.cancel(true)
        runCatching { tunnel.close() }
    }

    /**
     * Antwort auf die CONNECT-Anfrage.
     *
     * Als gebundene Adresse wird 0.0.0.0:0 gemeldet. Der tatsaechliche lokale Endpunkt der
     * Verbindung liegt auf der anderen Seite des Tunnels und ist hier gar nicht bekannt;
     * Clients werten das Feld bei CONNECT ohnehin nicht aus.
     */
    private fun reply(output: OutputStream, code: Int) {
        output.write(
            byteArrayOf(
                VERSION.toByte(), code.toByte(), 0, ATYP_IPV4.toByte(),
                0, 0, 0, 0, // 0.0.0.0
                0, 0, // Port 0
            ),
        )
        output.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        workers.shutdownNow()
    }

    private data class Target(val host: String, val port: Int)

    private companion object {
        const val VERSION = 0x05
        const val METHOD_NO_AUTH = 0x00
        const val METHOD_NONE_ACCEPTABLE = 0xFF
        const val COMMAND_CONNECT = 0x01
        const val ATYP_IPV4 = 0x01
        const val ATYP_DOMAIN = 0x03
        const val ATYP_IPV6 = 0x04
        const val REPLY_SUCCEEDED = 0x00
        const val REPLY_HOST_UNREACHABLE = 0x04
        const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
        const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
        const val COPY_BUFFER_BYTES = 16 * 1024
    }
}
