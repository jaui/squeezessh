package dev.jaui.squeezessh.terminal

import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import dev.jaui.squeezessh.ssh.SshShell
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verbindet eine SSH-Shell mit Termux' Terminal-Emulator.
 *
 * Warum nicht Termux' eigene `TerminalSession`: die ist `final` und startet in
 * `initializeEmulator` fest einen lokalen Prozess ueber JNI. Fuer eine Shell am anderen
 * Ende einer SSH-Verbindung ist sie damit nicht verwendbar. Benutzt werden deshalb die
 * Bausteine darunter — [TerminalEmulator] als Parser und [TerminalOutput] als
 * Rueckkanal —, die genau fuer diesen Zweck oeffentlich sind.
 *
 * Threading, und das ist der heikle Teil:
 *
 * - [TerminalEmulator] ist **nicht** threadsicher. Alles, was ihn anfasst, laeuft auf dem
 *   Hauptthread. Der Leser sammelt nur Bytes und stoesst einen Durchlauf an.
 * - Geschrieben wird dagegen **nie** auf dem Hauptthread: ein voller Sende-Puffer wuerde
 *   sonst die Oberflaeche einfrieren.
 */
class TerminalBridge(
    private val shell: SshShell,
    columns: Int,
    rows: Int,
    cellWidthPixels: Int,
    cellHeightPixels: Int,
    transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    private val listener: Listener,
) : AutoCloseable {

    interface Listener {
        /** Es gibt neue Ausgabe — Hauptthread. */
        fun onScreenUpdated()

        /** Der Server hat den Fenstertitel gesetzt (OSC 0/2) — Hauptthread. */
        fun onTitleChanged(title: String?)

        fun onBell()

        /** Der Server will Text in die Zwischenablage legen (OSC 52). */
        fun onCopyToClipboard(text: String)

        /** Der Server fragt den Inhalt der Zwischenablage an. */
        fun onPasteRequested()

        /** Die Gegenstelle ist weg. */
        fun onFinished(reason: String?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            outbox.put(Outbound.Data(data.copyOfRange(offset, offset + count)))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            mainHandler.post { listener.onTitleChanged(newTitle) }
        }

        override fun onCopyTextToClipboard(text: String) {
            mainHandler.post { listener.onCopyToClipboard(text) }
        }

        override fun onPasteTextFromClipboard() {
            mainHandler.post { listener.onPasteRequested() }
        }

        override fun onBell() {
            mainHandler.post { listener.onBell() }
        }

        override fun onColorsChanged() {
            mainHandler.post { listener.onScreenUpdated() }
        }
    }

    /**
     * Der Client darf `null` sein — der Emulator prueft ihn an jeder Stelle. Er dient dort
     * nur der Protokollierung und der Cursor-Form.
     */
    val emulator: TerminalEmulator = TerminalEmulator(
        output,
        columns.coerceAtLeast(MIN_COLUMNS),
        rows.coerceAtLeast(MIN_ROWS),
        cellWidthPixels,
        cellHeightPixels,
        transcriptRows,
        null,
    )

    @Volatile var isRunning: Boolean = true
        private set

    /**
     * Alles, was zum Server hinausgeht.
     *
     * Auch die Groessenaenderung laeuft hierueber statt direkt: sie ist ein SSH-Paket wie
     * jedes andere und muss deshalb (a) vom selben Thread geschrieben werden wie die
     * Nutzdaten und (b) in der richtigen Reihenfolge dazu stehen. Zwei Threads, die
     * gleichzeitig in denselben Kanal schreiben, produzieren sonst kaputte Pakete — der
     * Server quittiert das mit „Bad packet length" und wirft die Verbindung weg.
     */
    private sealed interface Outbound {
        @JvmInline value class Data(val bytes: ByteArray) : Outbound
        data class Resize(val columns: Int, val rows: Int, val width: Int, val height: Int) : Outbound
        data object Stop : Outbound
    }

    private val outbox = LinkedBlockingQueue<Outbound>()

    /** Eingehende Bytes, warten auf den naechsten Durchlauf im Hauptthread. */
    private val inbox = ArrayDeque<ByteArray>()
    private val drainScheduled = AtomicBoolean(false)

    private val readerThread = thread("squeezessh-term-reader") { pumpIncoming(shell.output) }
    private val errorReaderThread = thread("squeezessh-term-stderr") { pumpIncoming(shell.errorOutput) }
    private val writerThread = thread("squeezessh-term-writer") { pumpOutgoing() }

    /** Text vom Nutzer, etwa aus einem Snippet oder der Zwischenablage. */
    fun send(text: String) {
        if (!isRunning) return
        output.write(text)
    }

    fun send(bytes: ByteArray) {
        if (!isRunning || bytes.isEmpty()) return
        outbox.put(Outbound.Data(bytes))
    }

    /**
     * Neue Fenstergroesse, in Zeichen.
     *
     * Muss an beiden Enden ankommen: der Emulator legt seinen Puffer danach aus, und der
     * Server braucht sie, damit `vim` und Co. richtig zeichnen.
     */
    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val safeColumns = columns.coerceAtLeast(MIN_COLUMNS)
        val safeRows = rows.coerceAtLeast(MIN_ROWS)
        if (safeColumns == emulator.mColumns && safeRows == emulator.mRows) return

        // Der Emulator gehoert dem Hauptthread, der Server bekommt es ueber den Schreiber.
        emulator.resize(safeColumns, safeRows, cellWidthPixels, cellHeightPixels)
        if (isRunning) {
            outbox.put(
                Outbound.Resize(
                    columns = safeColumns,
                    rows = safeRows,
                    width = safeColumns * cellWidthPixels,
                    height = safeRows * cellHeightPixels,
                ),
            )
        }
    }

    private fun pumpIncoming(stream: InputStream) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (isRunning) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                synchronized(inbox) { inbox.addLast(buffer.copyOf(read)) }
                scheduleDrain()
            }
            finish(null)
        } catch (e: IOException) {
            finish(e.message)
        }
    }

    /**
     * Sorgt fuer genau einen anstehenden Durchlauf.
     *
     * Ohne das Zusammenfassen wuerde ein `cat` einer grossen Datei den Hauptthread mit
     * tausenden Nachrichten zustellen und die Oberflaeche waere nicht mehr bedienbar.
     */
    private fun scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            mainHandler.post {
                drainScheduled.set(false)
                val chunks = synchronized(inbox) {
                    ArrayList(inbox).also { inbox.clear() }
                }
                chunks.forEach { emulator.append(it, it.size) }
                if (chunks.isNotEmpty()) listener.onScreenUpdated()
            }
        }
    }

    private fun pumpOutgoing() {
        try {
            while (isRunning) {
                when (val item = outbox.take()) {
                    is Outbound.Stop -> return
                    is Outbound.Data -> {
                        shell.input.write(item.bytes)
                        shell.input.flush()
                    }
                    is Outbound.Resize ->
                        shell.resize(item.columns, item.rows, item.width, item.height)
                }
            }
        } catch (e: IOException) {
            finish(e.message)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun finish(reason: String?) {
        if (!isRunning) return
        isRunning = false
        outbox.put(Outbound.Stop)
        mainHandler.post { listener.onFinished(reason) }
    }

    override fun close() {
        isRunning = false
        outbox.put(Outbound.Stop)
        runCatching { shell.close() }
        readerThread.interrupt()
        errorReaderThread.interrupt()
        writerThread.interrupt()
    }

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply { isDaemon = true; start() }

    private companion object {
        const val MIN_COLUMNS = 4
        const val MIN_ROWS = 4
        const val READ_BUFFER_BYTES = 8192
        const val DEFAULT_TRANSCRIPT_ROWS = 2000
    }
}
