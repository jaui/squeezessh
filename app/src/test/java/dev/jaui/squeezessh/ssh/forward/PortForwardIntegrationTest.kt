package dev.jaui.squeezessh.ssh.forward

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.model.PortForwardMode
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import dev.jaui.squeezessh.ssh.SshConnection
import dev.jaui.squeezessh.ssh.SshConnector
import dev.jaui.squeezessh.ssh.SshCredential
import dev.jaui.squeezessh.ssh.SshHop
import dev.jaui.squeezessh.ssh.TestSshd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Prueft Weiterleitungen gegen echte sshd-Instanzen.
 *
 * Als Tunnelziel dient der sshd selbst: sein Banner `SSH-2.0-…` ist eine eindeutige
 * Antwort, die keinen zusaetzlichen Dienst im Container braucht. Kommt sie am lokalen
 * Ende an, hat der Tunnel wirklich getragen.
 */
@RunWith(RobolectricTestRunner::class)
class PortForwardIntegrationTest : DatabaseTestCase() {

    private lateinit var connector: SshConnector

    @Before
    fun setUp() {
        assumeTrue(
            "sshd-Testumgebung laeuft nicht — tools\\test-sshd\\up.ps1 ausfuehren",
            TestSshd.isRunning,
        )
        connector = SshConnector(KnownHostRepository(db.knownHostDao(), time), Dispatchers.IO)
    }

    private suspend fun connect(): SshConnection = connector.connect(
        listOf(
            SshHop(
                TestSshd.HOST,
                TestSshd.TARGET_PORT,
                TestSshd.PASSWORD_USER,
                listOf(SshCredential.Password(TestSshd.PASSWORD)),
            ),
        ),
        onHostKey = { true },
    )

    /** Ein freier Port vom Betriebssystem, damit parallele Laeufe sich nicht behindern. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun definition(
        mode: PortForwardMode,
        localPort: Int,
        targetHost: String? = null,
        remotePort: Int? = null,
    ) = PortForwardEntity(
        id = 1,
        name = "test",
        connectionId = 1,
        mode = mode,
        localPort = localPort,
        targetHost = targetHost,
        remotePort = remotePort,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun readBanner(socket: Socket): String {
        socket.soTimeout = 10_000
        return socket.getInputStream().bufferedReader().readLine().orEmpty()
    }

    // --- ssh -L ------------------------------------------------------------------------

    @Test
    fun `lokale Weiterleitung traegt bis zum Ziel`() = runTest {
        val localPort = freePort()
        connect().use { connection ->
            RunningForward.start(
                connection,
                // Aus Sicht des Servers ist sein eigener sshd unter 127.0.0.1:22 erreichbar.
                definition(PortForwardMode.LOCAL, localPort, "127.0.0.1", 22),
            ).use { forward ->
                assertThat(forward.isAlive).isTrue()

                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", localPort), 10_000)
                    assertThat(readBanner(socket)).startsWith("SSH-2.0-")
                }
            }
        }
    }

    @Test
    fun `geschlossene Weiterleitung gibt den Port wieder frei`() = runTest {
        val localPort = freePort()
        connect().use { connection ->
            val first = RunningForward.start(
                connection,
                definition(PortForwardMode.LOCAL, localPort, "127.0.0.1", 22),
            )
            first.close()

            // Ohne Freigabe scheiterte das hier mit „Address already in use".
            RunningForward.start(
                connection,
                definition(PortForwardMode.LOCAL, localPort, "127.0.0.1", 22),
            ).use { second ->
                assertThat(second.isAlive).isTrue()
            }
        }
    }

    // --- ssh -D (SOCKS5) ---------------------------------------------------------------

    /**
     * Spricht das SOCKS5-Protokoll von Hand: Begruessung, CONNECT, dann die Nutzdaten.
     * So laesst sich pruefen, dass wirklich RFC 1928 herauskommt und nicht irgendetwas,
     * das nur zufaellig mit unserem eigenen Client funktioniert.
     */
    private fun socksConnect(proxyPort: Int, host: String, port: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 10_000)
        socket.soTimeout = 10_000

        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())

        output.write(byteArrayOf(0x05, 0x01, 0x00)) // VER, 1 Methode, "keine Auth"
        output.flush()
        check(input.readUnsignedByte() == 0x05) { "falsche SOCKS-Version" }
        check(input.readUnsignedByte() == 0x00) { "Methode abgelehnt" }

        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        output.write(
            byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                hostBytes +
                byteArrayOf((port shr 8).toByte(), port.toByte()),
        )
        output.flush()

        check(input.readUnsignedByte() == 0x05) { "falsche SOCKS-Version in der Antwort" }
        val reply = input.readUnsignedByte()
        check(reply == 0x00) { "CONNECT abgelehnt mit Code $reply" }
        input.readUnsignedByte() // reserviert
        // Gebundene Adresse ueberlesen: IPv4 plus Port.
        check(input.readUnsignedByte() == 0x01) { "unerwarteter Adresstyp" }
        input.readFully(ByteArray(6))

        return socket
    }

    @Test
    fun `dynamische Weiterleitung arbeitet als SOCKS5-Proxy`() = runTest {
        val localPort = freePort()
        connect().use { connection ->
            RunningForward.start(
                connection,
                definition(PortForwardMode.DYNAMIC, localPort),
            ).use { forward ->
                assertThat(forward.isAlive).isTrue()
                assertThat(forward.endpoint).contains("SOCKS5")

                socksConnect(localPort, "127.0.0.1", 22).use { socket ->
                    assertThat(readBanner(socket)).startsWith("SSH-2.0-")
                }
            }
        }
    }

    @Test
    fun `SOCKS5 lehnt unbekannte Kommandos ab statt sie zu ignorieren`() = runTest {
        val localPort = freePort()
        connect().use { connection ->
            RunningForward.start(connection, definition(PortForwardMode.DYNAMIC, localPort))
                .use {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", localPort), 10_000)
                        socket.soTimeout = 10_000
                        val output = socket.getOutputStream()
                        val input = DataInputStream(socket.getInputStream())

                        output.write(byteArrayOf(0x05, 0x01, 0x00))
                        output.flush()
                        input.readUnsignedByte()
                        input.readUnsignedByte()

                        // 0x02 = BIND, von uns bewusst nicht unterstuetzt.
                        output.write(
                            byteArrayOf(0x05, 0x02, 0x00, 0x01, 127, 0, 0, 1, 0, 22),
                        )
                        output.flush()

                        assertThat(input.readUnsignedByte()).isEqualTo(0x05)
                        assertThat(input.readUnsignedByte()).isEqualTo(0x07) // command not supported
                    }
                }
        }
    }

    @Test
    fun `SOCKS5 lehnt ab wenn keine passende Anmeldemethode angeboten wird`() = runTest {
        val localPort = freePort()
        connect().use { connection ->
            RunningForward.start(connection, definition(PortForwardMode.DYNAMIC, localPort))
                .use {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", localPort), 10_000)
                        socket.soTimeout = 10_000
                        // 0x02 = Benutzername/Passwort, das wir nicht anbieten.
                        socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02))
                        socket.getOutputStream().flush()

                        val input = DataInputStream(socket.getInputStream())
                        assertThat(input.readUnsignedByte()).isEqualTo(0x05)
                        assertThat(input.readUnsignedByte()).isEqualTo(0xFF)
                    }
                }
        }
    }

    // --- ssh -R ------------------------------------------------------------------------

    /**
     * Der Gegenbeweis fuer die Richtung: ein Port **auf dem Server** zeigt hierher. Der
     * Test oeffnet dafuer selbst einen Server-Socket und laesst den entfernten Rechner
     * eine Verbindung dorthin aufbauen.
     */
    @Test
    fun `entfernte Weiterleitung zeigt zurueck auf das Geraet`() = runTest {
        val localServer = ServerSocket(0)
        val remotePort = 18099
        val received = StringBuilder()

        val acceptor = Thread {
            runCatching {
                localServer.accept().use { socket ->
                    socket.soTimeout = 10_000
                    received.append(socket.getInputStream().bufferedReader().readLine())
                }
            }
        }.apply { isDaemon = true; start() }

        connect().use { connection ->
            RunningForward.start(
                connection,
                definition(
                    PortForwardMode.REMOTE,
                    localPort = localServer.localPort,
                    targetHost = "127.0.0.1",
                    remotePort = remotePort,
                ),
            ).use { forward ->
                assertThat(forward.isAlive).isTrue()

                // Vom Server aus in den zurueckfuehrenden Tunnel schreiben.
                connection.target.startSession().use { session ->
                    session.exec("echo HALLO-VOM-SERVER | nc 127.0.0.1 $remotePort")
                        .join(15, java.util.concurrent.TimeUnit.SECONDS)
                }
                acceptor.join(15_000)
            }
        }

        localServer.close()
        assertThat(received.toString()).isEqualTo("HALLO-VOM-SERVER")
    }

    // --- Fehlerfaelle -------------------------------------------------------------------

    @Test
    fun `belegter Port meldet einen verstaendlichen Fehler`() = runTest {
        val manager = PortForwardManager(Dispatchers.IO)
        ServerSocket().use { blocker ->
            blocker.reuseAddress = true
            blocker.bind(InetSocketAddress("127.0.0.1", 0))

            connect().use { connection ->
                val error = runCatching {
                    manager.start(
                        sessionId = 1,
                        connection = connection,
                        definition = definition(
                            PortForwardMode.LOCAL,
                            blocker.localPort,
                            "127.0.0.1",
                            22,
                        ),
                    )
                }.exceptionOrNull()

                assertThat(error).isInstanceOf(PortForwardException::class.java)
                assertThat(error?.message).contains("schon belegt")
            }
        }
    }

    @Test
    fun `Manager fuehrt laufende Weiterleitungen und raeumt sie ab`() = runTest {
        val manager = PortForwardManager(Dispatchers.IO)
        val localPort = freePort()

        connect().use { connection ->
            val active = manager.start(
                sessionId = 42,
                connection = connection,
                definition = definition(PortForwardMode.LOCAL, localPort, "127.0.0.1", 22),
            )

            assertThat(active.isAlive).isTrue()
            assertThat(manager.isRunning(1)).isTrue()
            assertThat(manager.active.value).hasSize(1)

            manager.stopAllFor(sessionId = 42)

            assertThat(manager.active.value).isEmpty()
            assertThat(manager.isRunning(1)).isFalse()
        }
    }
}
