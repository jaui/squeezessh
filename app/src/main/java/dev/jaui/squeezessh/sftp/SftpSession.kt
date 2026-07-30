package dev.jaui.squeezessh.sftp

import dev.jaui.squeezessh.ssh.SshConnection
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * Dateizugriff ueber eine bestehende SSH-Verbindung.
 *
 * Uebertragen wird bewusst haeppchenweise ueber [net.schmizz.sshj.sftp.RemoteFile] statt
 * mit den bequemen `get`/`put`-Methoden von SSHJ: nur so lassen sich Fortschritt melden,
 * ein Abbruch beachten und eine abgebrochene Uebertragung spaeter fortsetzen. Ausserdem
 * arbeitet Android mit `content://`-Verweisen und nicht mit Dateipfaden — die fertigen
 * Methoden verlangen aber echte Dateien.
 */
class SftpSession(private val client: SFTPClient) : AutoCloseable {

    /** Loest `.`, `~` und relative Pfade in einen absoluten auf. */
    @Throws(IOException::class)
    fun canonicalize(path: String): String = client.canonicalize(path)

    @Throws(IOException::class)
    fun list(path: String): List<SftpEntry> =
        client.ls(path)
            .map { info ->
                SftpEntry(
                    name = info.name,
                    path = info.path,
                    isDirectory = info.isDirectory,
                    isSymlink = info.attributes.type == FileMode.Type.SYMLINK,
                    sizeBytes = info.attributes.size,
                    modifiedEpochSeconds = info.attributes.mtime,
                    permissions = info.attributes.mode.toString(),
                )
            }
            // Verzeichnisse zuerst, danach alphabetisch — so wie es jeder Dateimanager tut.
            .sortedWith(compareByDescending<SftpEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

    @Throws(IOException::class)
    fun makeDirectory(path: String) = client.mkdir(path)

    @Throws(IOException::class)
    fun delete(entry: SftpEntry) {
        if (entry.isDirectory) client.rmdir(entry.path) else client.rm(entry.path)
    }

    @Throws(IOException::class)
    fun rename(from: String, to: String) = client.rename(from, to)

    @Throws(IOException::class)
    fun sizeOf(path: String): Long = client.stat(path).size

    /**
     * Loescht ueber einen Pfad statt ueber einen [SftpEntry].
     *
     * Die Plugin-API kennt nur Pfade; ob dahinter ein Verzeichnis steckt, muss der Aufrufer
     * wissen — `rm` und `rmdir` sind dort getrennte Aufrufe.
     */
    @Throws(IOException::class)
    fun deletePath(path: String, directory: Boolean) {
        if (directory) client.rmdir(path) else client.rm(path)
    }

    /** Ziel eines symbolischen Links, ohne ihm zu folgen. */
    @Throws(IOException::class)
    fun readLink(path: String): String = client.readlink(path)

    /** Einzelner Eintrag, wenn nur der Pfad bekannt ist und kein Listing vorliegt. */
    @Throws(IOException::class)
    fun statAsEntry(path: String): SftpEntry {
        val attributes = client.stat(path)
        return SftpEntry(
            name = path.substringAfterLast('/'),
            path = path,
            isDirectory = attributes.type == FileMode.Type.DIRECTORY,
            isSymlink = attributes.type == FileMode.Type.SYMLINK,
            sizeBytes = attributes.size,
            modifiedEpochSeconds = attributes.mtime,
            permissions = attributes.mode.toString(),
        )
    }

    @Throws(IOException::class)
    fun chmod(path: String, permissions: Int) = client.chmod(path, permissions)

    @Throws(IOException::class)
    fun chown(path: String, uid: Int) = client.chown(path, uid)

    @Throws(IOException::class)
    fun chgrp(path: String, gid: Int) = client.chgrp(path, gid)

    /**
     * Laedt [remotePath] nach [destination].
     *
     * @param startOffset Byte, ab dem gelesen wird — fuer das Fortsetzen einer
     *   abgebrochenen Uebertragung. Der Aufrufer muss die Zieldatei entsprechend
     *   positioniert oeffnen.
     * @param shouldContinue wird zwischen den Bloecken gefragt; `false` bricht sauber ab.
     * @return Anzahl der uebertragenen Bytes.
     */
    @Throws(IOException::class)
    fun download(
        remotePath: String,
        destination: OutputStream,
        startOffset: Long = 0,
        onProgress: (transferred: Long) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): Long {
        client.sftpEngine.open(remotePath, EnumSet.of(OpenMode.READ)).use { file ->
            val buffer = ByteArray(BUFFER_BYTES)
            var offset = startOffset
            while (shouldContinue()) {
                val read = file.read(offset, buffer, 0, buffer.size)
                if (read < 0) break
                destination.write(buffer, 0, read)
                offset += read
                onProgress(offset)
            }
            destination.flush()
            return offset - startOffset
        }
    }

    /**
     * Schreibt [source] nach [remotePath].
     *
     * Legt die Datei an oder kuerzt sie: ein Upload ersetzt, er haengt nicht an. Wer
     * fortsetzen will, gibt [startOffset] an — dann bleibt der Anfang stehen.
     */
    @Throws(IOException::class)
    fun upload(
        source: InputStream,
        remotePath: String,
        startOffset: Long = 0,
        onProgress: (transferred: Long) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): Long {
        val modes = if (startOffset > 0) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }

        client.sftpEngine.open(remotePath, modes).use { file ->
            val buffer = ByteArray(BUFFER_BYTES)
            var offset = startOffset
            while (shouldContinue()) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                file.write(offset, buffer, 0, read)
                offset += read
                onProgress(offset)
            }
            return offset - startOffset
        }
    }

    override fun close() {
        runCatching { client.close() }
    }

    companion object {
        /**
         * 32 KiB. Groessere Bloecke bringen bei SFTP wenig, weil das Protokoll ohnehin in
         * Paketen arbeitet, kosten aber Speicher auf einem Telefon.
         */
        private const val BUFFER_BYTES = 32 * 1024

        @Throws(IOException::class)
        fun open(connection: SshConnection): SftpSession =
            SftpSession(connection.target.newSFTPClient())
    }
}

data class SftpEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val permissions: String,
) {
    val isParentLink: Boolean get() = name == ".."
}
