package dev.jaui.squeezessh.ssh

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Integrationstests gegen echte sshd-Instanzen in Docker (`tools/test-sshd`).
 *
 * Ohne laufende Container werden sie uebersprungen. Was sie abdecken, laesst sich mit
 * Attrappen nicht sinnvoll pruefen: Aushandlung der Verfahren, Host-Key-Verhalten,
 * Sprung-Ketten und das Verhalten eines echten PTY.
 */
@RunWith(RobolectricTestRunner::class)
class SshConnectorIntegrationTest : DatabaseTestCase() {

    private lateinit var knownHosts: KnownHostRepository
    private lateinit var connector: SshConnector

    /** Nimmt jeden Host-Key an und merkt sich, wonach gefragt wurde. */
    private val prompts = mutableListOf<HostKeyPrompt>()
    private val acceptAll: suspend (HostKeyPrompt) -> Boolean = { prompts += it; true }
    private val rejectAll: suspend (HostKeyPrompt) -> Boolean = { prompts += it; false }

    @Before
    fun setUp() {
        assumeTrue(
            "sshd-Testumgebung laeuft nicht — tools\\test-sshd\\up.ps1 ausfuehren",
            TestSshd.isRunning,
        )
        knownHosts = KnownHostRepository(db.knownHostDao(), time)
        connector = SshConnector(knownHosts, Dispatchers.IO)
        prompts.clear()
    }

    private fun hop(
        user: String,
        credential: SshCredential,
        host: String = TestSshd.HOST,
        port: Int = TestSshd.TARGET_PORT,
    ) = SshHop(host, port, user, listOf(credential))

    // --- Authentifizierung -----------------------------------------------------------

    @Test
    fun `Anmeldung mit Passwort`() = runTest {
        val connection = connector.connect(
            listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
            acceptAll,
        )

        connection.use { assertThat(it.isConnected).isTrue() }
    }

    @Test
    fun `Anmeldung mit Ed25519-Schluessel ohne Passphrase`() = runTest {
        val connection = connector.connect(
            listOf(
                hop(
                    TestSshd.KEY_USER,
                    SshCredential.PrivateKey(TestSshd.privateKey, publicKey = TestSshd.publicKey),
                ),
            ),
            acceptAll,
        )

        connection.use { assertThat(it.isConnected).isTrue() }
    }

    @Test
    fun `Anmeldung mit passphrasegeschuetztem Schluessel`() = runTest {
        val connection = connector.connect(
            listOf(
                hop(
                    TestSshd.LOCKED_KEY_USER,
                    SshCredential.PrivateKey(TestSshd.lockedPrivateKey, TestSshd.KEY_PASSPHRASE),
                ),
            ),
            acceptAll,
        )

        connection.use { assertThat(it.isConnected).isTrue() }
    }

    @Test
    fun `falsche Passphrase meldet einen unbrauchbaren Schluessel`() = runTest {
        val error = runCatching {
            connector.connect(
                listOf(
                    hop(
                        TestSshd.LOCKED_KEY_USER,
                        SshCredential.PrivateKey(TestSshd.lockedPrivateKey, "falsch"),
                    ),
                ),
                acceptAll,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SshException::class.java)
        // Je nachdem, ob SSHJ die Passphrase schon beim Laden oder erst beim Signieren
        // ablehnt, kommt das eine oder das andere heraus. Beides ist richtig, nur
        // „erfolgreich verbunden" waere falsch.
        val kind = (error as SshException).error
        assertThat(kind is SshError.KeyUnusable || kind is SshError.AuthenticationFailed).isTrue()
    }

    @Test
    fun `falsches Passwort meldet Authentifizierungsfehler`() = runTest {
        val error = runCatching {
            connector.connect(
                listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password("falsch"))),
                acceptAll,
            )
        }.exceptionOrNull()

        assertThat((error as SshException).error)
            .isInstanceOf(SshError.AuthenticationFailed::class.java)
    }

    /** Mehrere Verfahren an einer Verbindung: das zweite muss greifen, wenn das erste scheitert. */
    @Test
    fun `zweites Verfahren greift wenn das erste abgelehnt wird`() = runTest {
        val connection = connector.connect(
            listOf(
                SshHop(
                    TestSshd.HOST,
                    TestSshd.TARGET_PORT,
                    TestSshd.PASSWORD_USER,
                    listOf(
                        SshCredential.PrivateKey(TestSshd.rsaPrivateKey), // nicht hinterlegt
                        SshCredential.Password(TestSshd.PASSWORD),
                    ),
                ),
            ),
            acceptAll,
        )

        connection.use { assertThat(it.isConnected).isTrue() }
    }

    @Test
    fun `nicht erreichbarer Host meldet Unreachable`() = runTest {
        val error = runCatching {
            connector.connect(
                listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD), port = 2)),
                acceptAll,
            )
        }.exceptionOrNull()

        assertThat((error as SshException).error).isInstanceOf(SshError.Unreachable::class.java)
    }

    // --- Host-Keys -------------------------------------------------------------------

    @Test
    fun `erster Kontakt fragt nach und merkt sich den Schluessel`() = runTest {
        connector.connect(
            listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
            acceptAll,
        ).close()

        val prompt = prompts.single()
        assertThat(prompt.host).isEqualTo(TestSshd.HOST)
        assertThat(prompt.port).isEqualTo(TestSshd.TARGET_PORT)
        assertThat(prompt.fingerprint).startsWith("SHA256:")
        assertThat(prompt.isChange).isFalse()

        val stored = knownHosts.observeAll().first().single()
        assertThat(stored.hostname).isEqualTo(TestSshd.HOST)
        assertThat(stored.fingerprint).isEqualTo(prompt.fingerprint)
    }

    @Test
    fun `zweiter Verbindungsaufbau fragt nicht erneut`() = runTest {
        val credential = SshCredential.Password(TestSshd.PASSWORD)
        connector.connect(listOf(hop(TestSshd.PASSWORD_USER, credential)), acceptAll).close()
        prompts.clear()

        connector.connect(listOf(hop(TestSshd.PASSWORD_USER, credential)), acceptAll).close()

        assertThat(prompts).isEmpty()
    }

    @Test
    fun `abgelehnter Host-Key bricht die Verbindung ab`() = runTest {
        val error = runCatching {
            connector.connect(
                listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
                rejectAll,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SshException::class.java)
        assertThat(prompts).hasSize(1)
        // Abgelehnt heisst auch: nicht gespeichert.
        assertThat(knownHosts.observeAll().first()).isEmpty()
    }

    /**
     * Der Ernstfall: derselbe Host, anderer Schluessel. Das darf nicht als Erstkontakt
     * durchgehen, sondern muss als Aenderung erkennbar sein.
     */
    @Test
    fun `geaenderter Host-Key wird als Aenderung gemeldet`() = runTest {
        connector.connect(
            listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
            acceptAll,
        ).close()
        val original = knownHosts.observeAll().first().single()

        // Gespeicherten Schluessel verbiegen — als haette der Server einen neuen bekommen.
        knownHosts.trust(
            hostname = original.hostname,
            port = original.port,
            keyType = original.keyType,
            publicKey = "AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            fingerprint = "SHA256:fruehererFingerprint",
        )
        prompts.clear()

        connector.connect(
            listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
            acceptAll,
        ).close()

        val prompt = prompts.single()
        assertThat(prompt.isChange).isTrue()
        assertThat(prompt.previousFingerprint).isEqualTo("SHA256:fruehererFingerprint")
        assertThat(prompt.fingerprint).isNotEqualTo(prompt.previousFingerprint)
    }

    // --- Sprung-Kette ----------------------------------------------------------------

    /**
     * Das Ziel ist von aussen nur ueber 2222 erreichbar; unter `target:22` existiert es
     * ausschliesslich im Docker-Netz. Klappt dieser Test, lief die Verbindung wirklich
     * durch den Bastion und nicht daran vorbei.
     */
    @Test
    fun `Verbindung durch einen Bastion-Host`() = runTest {
        val connection = connector.connect(
            listOf(
                SshHop(
                    TestSshd.HOST,
                    TestSshd.BASTION_PORT,
                    TestSshd.KEY_USER,
                    listOf(SshCredential.PrivateKey(TestSshd.privateKey)),
                ),
                SshHop(
                    TestSshd.TARGET_INTERNAL_HOST,
                    TestSshd.TARGET_INTERNAL_PORT,
                    TestSshd.PASSWORD_USER,
                    listOf(SshCredential.Password(TestSshd.PASSWORD)),
                ),
            ),
            acceptAll,
        )

        connection.use { assertThat(it.isConnected).isTrue() }

        // Beide Sprünge wurden einzeln geprueft und getrennt gespeichert.
        assertThat(prompts.map { it.host to it.port })
            .containsExactly(
                TestSshd.HOST to TestSshd.BASTION_PORT,
                TestSshd.TARGET_INTERNAL_HOST to TestSshd.TARGET_INTERNAL_PORT,
            )
            .inOrder()
        assertThat(knownHosts.observeAll().first()).hasSize(2)
    }

    @Test
    fun `scheitert der zweite Sprung bleibt keine offene Bastion-Verbindung zurueck`() = runTest {
        val error = runCatching {
            connector.connect(
                listOf(
                    SshHop(
                        TestSshd.HOST,
                        TestSshd.BASTION_PORT,
                        TestSshd.KEY_USER,
                        listOf(SshCredential.PrivateKey(TestSshd.privateKey)),
                    ),
                    SshHop(
                        TestSshd.TARGET_INTERNAL_HOST,
                        TestSshd.TARGET_INTERNAL_PORT,
                        TestSshd.PASSWORD_USER,
                        listOf(SshCredential.Password("falsch")),
                    ),
                ),
                acceptAll,
            )
        }.exceptionOrNull()

        assertThat((error as SshException).error)
            .isInstanceOf(SshError.AuthenticationFailed::class.java)
    }

    // --- Shell -----------------------------------------------------------------------

    @Test
    fun `Shell fuehrt Kommandos aus und liefert Ausgabe`() = runTest {
        connector.connect(
            listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
            acceptAll,
        ).use { connection ->
            SshShell.open(connection, "xterm-256color", 80, 24).use { shell ->
                assertThat(shell.isOpen).isTrue()

                shell.input.write("echo HALLO-VOM-SERVER\n".toByteArray())
                shell.input.flush()

                val output = withTimeout(15_000) { shell.readUntil("HALLO-VOM-SERVER") }
                assertThat(output).contains("HALLO-VOM-SERVER")
            }
        }
    }

    @Test
    fun `PTY meldet die gesetzte Groesse an den Server`() = runTest {
        connector.connect(
            listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
            acceptAll,
        ).use { connection ->
            SshShell.open(connection, "xterm-256color", 120, 40).use { shell ->
                shell.input.write("stty size\n".toByteArray())
                shell.input.flush()

                val output = withTimeout(15_000) { shell.readUntil("40 120") }
                assertThat(output).contains("40 120")
            }
        }
    }

    @Test
    fun `resize aendert die Groesse der laufenden Sitzung`() = runTest {
        connector.connect(
            listOf(hop(TestSshd.PASSWORD_USER, SshCredential.Password(TestSshd.PASSWORD))),
            acceptAll,
        ).use { connection ->
            SshShell.open(connection, "xterm-256color", 80, 24).use { shell ->
                shell.resize(132, 50)

                shell.input.write("stty size\n".toByteArray())
                shell.input.flush()

                val output = withTimeout(15_000) { shell.readUntil("50 132") }
                assertThat(output).contains("50 132")
            }
        }
    }

    /** Liest, bis [marker] auftaucht — Shell-Ausgabe kommt haeppchenweise. */
    private fun SshShell.readUntil(marker: String): String {
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = output.read(chunk)
            if (read <= 0) break
            collected.write(chunk, 0, read)
            val text = collected.toString(Charsets.UTF_8.name())
            if (text.contains(marker)) return text
        }
        return collected.toString(Charsets.UTF_8.name())
    }
}
