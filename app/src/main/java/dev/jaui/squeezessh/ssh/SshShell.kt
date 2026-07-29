package dev.jaui.squeezessh.ssh

import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream

/**
 * Eine interaktive Shell auf einem verbundenen Server.
 *
 * Gibt bewusst die rohen Streams heraus statt sie zu puffern: der Terminal-Emulator in M4
 * liest selbst und jede Zwischenschicht wuerde nur Latenz und Fehlerquellen hinzufuegen.
 */
class SshShell internal constructor(
    private val session: Session,
    private val shell: Session.Shell,
    val terminalType: String,
) : AutoCloseable {

    /** Was der Nutzer tippt, geht hier hinein. */
    val input: OutputStream get() = shell.outputStream

    /** Was der Server ausgibt, kommt hier heraus. */
    val output: InputStream get() = shell.inputStream

    /** Stderr des Kanals. Viele Server nutzen ihn nicht, leer laufen darf er trotzdem nicht. */
    val errorOutput: InputStream get() = shell.errorStream

    val isOpen: Boolean get() = shell.isOpen

    /**
     * Meldet dem Server eine neue Fenstergroesse.
     *
     * Ohne das laufen `vim`, `htop` und alles andere mit Vollbild-Ausgabe nach einer
     * Drehung des Geraets in einer falschen Groesse weiter.
     */
    fun resize(columns: Int, rows: Int, widthPixels: Int = 0, heightPixels: Int = 0) {
        if (!shell.isOpen) return
        shell.changeWindowDimensions(columns, rows, widthPixels, heightPixels)
    }

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
    }

    companion object {
        internal fun open(
            connection: SshConnection,
            terminalType: String,
            columns: Int,
            rows: Int,
        ): SshShell {
            val session = connection.target.startSession()
            session.allocatePTY(
                terminalType,
                columns,
                rows,
                0,
                0,
                // Ohne ECHO liefe die lokale Eingabe doppelt oder gar nicht — der Server
                // entscheidet, was zurueckkommt.
                mapOf(PTYMode.ECHO to 1),
            )
            return SshShell(session, session.startShell(), terminalType)
        }
    }
}
