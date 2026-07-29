package dev.jaui.squeezessh.terminal

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import dev.jaui.squeezessh.ssh.SshConnector
import dev.jaui.squeezessh.ssh.SshCredential
import dev.jaui.squeezessh.ssh.SshHop
import dev.jaui.squeezessh.ssh.SshShell
import dev.jaui.squeezessh.ssh.TestSshd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Prueft den gesamten Weg vom SSH-Kanal bis in den Bildschirmpuffer des Emulators:
 * Bytes lesen, an den Emulator geben, Ausgabe zurueckschreiben, Groesse melden.
 *
 * Laeuft gegen den echten sshd aus `tools/test-sshd`; ohne den wird uebersprungen.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalBridgeIntegrationTest : DatabaseTestCase() {

    private lateinit var connector: SshConnector

    @Before
    fun setUp() {
        assumeTrue(
            "sshd-Testumgebung laeuft nicht — tools\\test-sshd\\up.ps1 ausfuehren",
            TestSshd.isRunning,
        )
        connector = SshConnector(KnownHostRepository(db.knownHostDao(), time), Dispatchers.IO)
    }

    private class RecordingListener : TerminalBridge.Listener {
        var screenUpdates = 0
        var title: String? = null
        var finishedReason: String? = null
        var finished = false

        override fun onScreenUpdated() { screenUpdates++ }
        override fun onTitleChanged(title: String?) { this.title = title }
        override fun onBell() = Unit
        override fun onCopyToClipboard(text: String) = Unit
        override fun onPasteRequested() = Unit
        override fun onFinished(reason: String?) { finished = true; finishedReason = reason }
    }

    private suspend fun withBridge(
        columns: Int = 80,
        rows: Int = 24,
        block: (TerminalBridge, RecordingListener) -> Unit,
    ) {
        val connection = connector.connect(
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
        connection.use {
            SshShell.open(it, "xterm-256color", columns, rows).use { shell ->
                val listener = RecordingListener()
                TerminalBridge(
                    shell = shell,
                    columns = columns,
                    rows = rows,
                    cellWidthPixels = 10,
                    cellHeightPixels = 20,
                    listener = listener,
                ).use { bridge -> block(bridge, listener) }
            }
        }
    }

    /**
     * Der Emulator wird ausschliesslich vom Hauptthread gefuettert. Unter Robolectric
     * laeuft dessen Schleife nicht von selbst, sie muss hier angestossen werden.
     */
    private fun TerminalBridge.awaitScreen(marker: String, timeoutMillis: Long = 15_000): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val text = emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
            if (text.contains(marker)) return text
            Thread.sleep(50)
        }
        return emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
    }

    @Test
    fun `Ausgabe des Servers landet im Bildschirmpuffer`() = runTest {
        withBridge { bridge, listener ->
            bridge.send("echo HALLO-AUS-DEM-EMULATOR\n")

            val screen = bridge.awaitScreen("HALLO-AUS-DEM-EMULATOR")

            assertThat(screen).contains("HALLO-AUS-DEM-EMULATOR")
            assertThat(listener.screenUpdates).isGreaterThan(0)
        }
    }

    @Test
    fun `Begruessung des Servers kommt an`() = runTest {
        withBridge { bridge, _ ->
            // Der Container schreibt eine erkennbare motd.
            assertThat(bridge.awaitScreen("SqueezeSSH test host"))
                .contains("SqueezeSSH test host")
        }
    }

    @Test
    fun `Steuerzeichen erreichen den Server`() = runTest {
        withBridge { bridge, _ ->
            bridge.send("sleep 60\n")
            bridge.awaitScreen("sleep 60")

            bridge.send(byteArrayOf(3)) // Strg-C
            bridge.send("echo NACH-STRG-C\n")

            assertThat(bridge.awaitScreen("NACH-STRG-C")).contains("NACH-STRG-C")
        }
    }

    @Test
    fun `Groessenaenderung erreicht den Server`() = runTest {
        withBridge(columns = 80, rows = 24) { bridge, _ ->
            bridge.awaitScreen("SqueezeSSH test host")

            bridge.resize(132, 50, 10, 20)
            assertThat(bridge.emulator.mColumns).isEqualTo(132)
            assertThat(bridge.emulator.mRows).isEqualTo(50)

            bridge.send("stty size\n")

            assertThat(bridge.awaitScreen("50 132")).contains("50 132")
        }
    }

    @Test
    fun `zu kleine Groesse wird auf ein bedienbares Minimum angehoben`() = runTest {
        withBridge { bridge, _ ->
            bridge.resize(0, 0, 10, 20)

            assertThat(bridge.emulator.mColumns).isAtLeast(4)
            assertThat(bridge.emulator.mRows).isAtLeast(4)
        }
    }

    // --- Textauswahl ------------------------------------------------------------------

    /** Zeile, in der die Ausgabe steht: der Emulator schreibt sie unter Begruessung und Prompt. */
    private fun TerminalBridge.rowContaining(marker: String): Int {
        for (row in 0 until emulator.mRows) {
            val line = emulator.screen.getSelectedText(0, row, emulator.mColumns - 1, row)
            if (line.contains(marker)) return row
        }
        error("Zeile mit \"$marker\" nicht gefunden")
    }

    private fun TerminalBridge.columnOf(marker: String, row: Int): Int =
        emulator.screen.getSelectedText(0, row, emulator.mColumns - 1, row).indexOf(marker)

    @Test
    fun `Wortauswahl fasst einen Pfad als Ganzes`() = runTest {
        withBridge { bridge, _ ->
            bridge.send("echo /etc/ssh/sshd_config ende\n")
            bridge.awaitScreen("/etc/ssh/sshd_config ende")

            // Zeile der Ausgabe, nicht die des Kommandos.
            val row = (0 until bridge.emulator.mRows).last { row ->
                bridge.emulator.screen
                    .getSelectedText(0, row, bridge.emulator.mColumns - 1, row)
                    .startsWith("/etc/ssh")
            }
            val column = bridge.columnOf("ssh/sshd", row) + 2

            val selection = TerminalSelection.wordAt(bridge.emulator, column, row)
            val text = bridge.emulator.screen.getSelectedText(
                selection.startColumn,
                selection.startRow,
                selection.endColumn,
                selection.endRow,
            )

            // Schraegstriche und Unterstriche sind bewusst keine Wortgrenzen.
            assertThat(text).isEqualTo("/etc/ssh/sshd_config")
        }
    }

    @Test
    fun `Wortauswahl haelt host colon port zusammen`() = runTest {
        withBridge { bridge, _ ->
            bridge.send("echo web.example.com:2222\n")
            bridge.awaitScreen("web.example.com:2222")

            val row = (0 until bridge.emulator.mRows).last { row ->
                bridge.emulator.screen
                    .getSelectedText(0, row, bridge.emulator.mColumns - 1, row)
                    .startsWith("web.example.com")
            }

            val selection = TerminalSelection.wordAt(bridge.emulator, 3, row)
            val text = bridge.emulator.screen.getSelectedText(
                selection.startColumn,
                selection.startRow,
                selection.endColumn,
                selection.endRow,
            )

            assertThat(text).isEqualTo("web.example.com:2222")
        }
    }

    @Test
    fun `Auswahl auf leerem Bereich liefert nichts`() = runTest {
        withBridge { bridge, _ ->
            bridge.awaitScreen("SqueezeSSH test host")

            // Weit unterhalb der Ausgabe ist der Bildschirm leer.
            val emptyRow = bridge.emulator.mRows - 1
            val selection = TerminalSelection.wordAt(bridge.emulator, 10, emptyRow)
            val text = bridge.emulator.screen.getSelectedText(
                selection.startColumn,
                selection.startRow,
                selection.endColumn,
                selection.endRow,
            )

            assertThat(text).isEmpty()
        }
    }

    @Test
    fun `normalized dreht eine rueckwaerts gezogene Auswahl um`() {
        val backwards = TerminalSelection(startColumn = 20, startRow = 5, endColumn = 3, endRow = 2)

        val normalized = backwards.normalized()

        assertThat(normalized.startRow).isEqualTo(2)
        assertThat(normalized.startColumn).isEqualTo(3)
        assertThat(normalized.endRow).isEqualTo(5)
        assertThat(normalized.endColumn).isEqualTo(20)
    }

    @Test
    fun `normalized dreht auch innerhalb einer Zeile um`() {
        val backwards = TerminalSelection(startColumn = 30, startRow = 4, endColumn = 10, endRow = 4)

        assertThat(backwards.normalized())
            .isEqualTo(TerminalSelection(10, 4, 30, 4))
    }

    @Test
    fun `Ende der Sitzung wird gemeldet`() = runTest {
        withBridge { bridge, listener ->
            bridge.awaitScreen("SqueezeSSH test host")

            bridge.send("exit\n")

            val deadline = System.currentTimeMillis() + 15_000
            while (!listener.finished && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
            }

            assertThat(listener.finished).isTrue()
            assertThat(bridge.isRunning).isFalse()
        }
    }
}
