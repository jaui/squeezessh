package dev.jaui.squeezessh.pluginapi

import android.content.Intent
import android.os.IBinder
import com.sonelli.juicessh.pluginlibrary.ISftpPluginService
import com.sonelli.juicessh.pluginlibrary.listeners.ICommandSuccessListener
import com.sonelli.juicessh.pluginlibrary.listeners.ILsEntryListener
import com.sonelli.juicessh.pluginlibrary.listeners.IStatResultListener
import com.sonelli.juicessh.pluginlibrary.listeners.IStringResultListener
import com.sonelli.juicessh.pluginlibrary.parcelables.ParcelableLsEntry
import com.sonelli.juicessh.pluginlibrary.parcelables.ParcelableSftpATTRS
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.sftp.SftpEntry
import dev.jaui.squeezessh.sftp.SftpSession
import dev.jaui.squeezessh.ssh.SshSession
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Dateioperationen für Plugins.
 *
 * Jede Sitzung bekommt beim ersten Zugriff einen eigenen SFTP-Kanal auf der schon
 * bestehenden Verbindung — ein zweiter Verbindungsaufbau würde erneut nach Host-Key und
 * Zugangsdaten fragen und auf dem Server als weitere Anmeldung erscheinen.
 *
 * `get` und `put` aus dem Vertrag fehlen. `put` ist auch im Original auskommentiert, und
 * `get` gibt dort eine Datei ohne Ziel zurück — beides ist ohne Dateiübergabe per
 * `content://` nicht sinnvoll umzusetzen. Sie melden deshalb ehrlich einen Fehler, statt
 * stillschweigend nichts zu tun.
 *
 * Der aktuelle Ordner (`cd`, `pwd`) wird hier geführt und nicht auf dem Server: SFTP kennt
 * kein Arbeitsverzeichnis, das war in JuiceSSH über JSch genauso ein Zustand im Client.
 */
@AndroidEntryPoint
class SftpPluginService : BasePluginService() {

    private val channels = ConcurrentHashMap<Long, SftpSession>()
    private val workingDirectories = ConcurrentHashMap<Long, String>()

    private val binder = object : ISftpPluginService.Stub() {

        override fun cd(sessionId: Int, sessionKey: String?, path: String?, listener: ICommandSuccessListener?) =
            withSftp(sessionId, sessionKey, listener) { session, sftp ->
                val target = sftp.canonicalize(resolve(session, path))
                // Nur wechseln, wenn es den Ordner auch gibt — sonst schlüge erst der
                // nächste Aufruf fehl, und zwar an einer ganz anderen Stelle.
                sftp.list(target)
                workingDirectories[session.id] = target
            }

        override fun pwd(sessionId: Int, sessionKey: String?, listener: IStringResultListener?) =
            withSftpResult(sessionId, sessionKey, listener) { session, sftp ->
                workingDirectories[session.id] ?: sftp.canonicalize(".").also {
                    workingDirectories[session.id] = it
                }
            }

        /** Es gibt kein lokales Arbeitsverzeichnis; die App hat keinen Dateipfad-Zugriff. */
        override fun lcd(sessionId: Int, sessionKey: String?, path: String?, listener: ICommandSuccessListener?) {
            audit("wollte das lokale Verzeichnis wechseln — nicht unterstützt")
            runCatching { listener?.onCommandFailure("Kein lokales Verzeichnis vorhanden") }
        }

        override fun lpwd(sessionId: Int, sessionKey: String?, listener: IStringResultListener?) {
            audit("wollte das lokale Verzeichnis lesen — nicht unterstützt")
            runCatching { listener?.onCommandFailure("Kein lokales Verzeichnis vorhanden") }
        }

        override fun ls(sessionId: Int, sessionKey: String?, path: String?, listener: ILsEntryListener?) {
            val session = session(sessionId, sessionKey)
            if (session == null) {
                runCatching { listener?.onSessionFailure("Unbekannte Sitzung") }
                return
            }
            audit("hat ein Verzeichnis gelistet")
            scope.launch {
                try {
                    val sftp = channelFor(session)
                    val entries = sftp.list(sftp.canonicalize(resolve(session, path)))
                    runCatching { listener?.onSuccess(entries.map { it.toParcelable() }.toTypedArray()) }
                } catch (e: Exception) {
                    runCatching { listener?.onCommandFailure(e.message ?: "Auflisten fehlgeschlagen") }
                }
            }
        }

        override fun stat(sessionId: Int, sessionKey: String?, path: String?, listener: IStatResultListener?) =
            statInternal(sessionId, sessionKey, path, listener)

        /**
         * SSHJs `SFTPClient` folgt beim Auflisten Symlinks nicht — für unsere Zwecke deckt
         * sich `lstat` damit mit `stat`. Der Unterschied fällt nur bei einem Symlink auf,
         * der direkt abgefragt wird.
         */
        override fun lstat(sessionId: Int, sessionKey: String?, path: String?, listener: IStatResultListener?) =
            statInternal(sessionId, sessionKey, path, listener)

        override fun rename(
            sessionId: Int,
            sessionKey: String?,
            oldpath: String?,
            newpath: String?,
            listener: ICommandSuccessListener?,
        ) = withSftp(sessionId, sessionKey, listener) { session, sftp ->
            sftp.rename(resolve(session, oldpath), resolve(session, newpath))
        }

        override fun rm(sessionId: Int, sessionKey: String?, path: String?, listener: ICommandSuccessListener?) =
            withSftp(sessionId, sessionKey, listener) { session, sftp ->
                audit("hat eine Datei gelöscht")
                sftp.deletePath(resolve(session, path), directory = false)
            }

        override fun rmdir(sessionId: Int, sessionKey: String?, path: String?, listener: ICommandSuccessListener?) =
            withSftp(sessionId, sessionKey, listener) { session, sftp ->
                audit("hat ein Verzeichnis gelöscht")
                sftp.deletePath(resolve(session, path), directory = true)
            }

        override fun readlink(sessionId: Int, sessionKey: String?, path: String?, listener: IStringResultListener?) =
            withSftpResult(sessionId, sessionKey, listener) { session, sftp ->
                sftp.readLink(resolve(session, path))
            }

        override fun realpath(sessionId: Int, sessionKey: String?, path: String?, listener: IStringResultListener?) =
            withSftpResult(sessionId, sessionKey, listener) { session, sftp ->
                sftp.canonicalize(resolve(session, path))
            }

        override fun chmod(
            sessionId: Int,
            sessionKey: String?,
            permissions: Int,
            path: String?,
            listener: ICommandSuccessListener?,
        ) = withSftp(sessionId, sessionKey, listener) { session, sftp ->
            audit("hat Rechte geändert")
            sftp.chmod(resolve(session, path), permissions)
        }

        override fun chown(
            sessionId: Int,
            sessionKey: String?,
            uid: Int,
            path: String?,
            listener: ICommandSuccessListener?,
        ) = withSftp(sessionId, sessionKey, listener) { session, sftp ->
            audit("hat den Eigentümer geändert")
            sftp.chown(resolve(session, path), uid)
        }

        override fun chgrp(
            sessionId: Int,
            sessionKey: String?,
            gid: Int,
            path: String?,
            listener: ICommandSuccessListener?,
        ) = withSftp(sessionId, sessionKey, listener) { session, sftp ->
            audit("hat die Gruppe geändert")
            sftp.chgrp(resolve(session, path), gid)
        }

        /**
         * Im Vertrag ohne Ziel: `get` nennt nur die Quelle. Ohne einen Ort, an den die Datei
         * gehört, lässt sich das nicht sinnvoll erfüllen — ein stilles Nichtstun wäre
         * schlimmer als eine klare Absage.
         */
        override fun get(sessionId: Int, sessionKey: String?, src: String?, listener: ICommandSuccessListener?) {
            audit("wollte eine Datei herunterladen — nicht unterstützt")
            runCatching {
                listener?.onCommandFailure("Herunterladen über die Plugin-API wird nicht unterstützt")
            }
        }

        private fun statInternal(
            sessionId: Int,
            sessionKey: String?,
            path: String?,
            listener: IStatResultListener?,
        ) {
            val session = session(sessionId, sessionKey)
            if (session == null) {
                runCatching { listener?.onSessionFailure("Unbekannte Sitzung") }
                return
            }
            scope.launch {
                try {
                    val sftp = channelFor(session)
                    val target = sftp.canonicalize(resolve(session, path))
                    val entry = sftp.list(target).firstOrNull { it.path == target }
                        ?: sftp.statAsEntry(target)
                    runCatching { listener?.onSuccess(entry.toParcelable().attrs) }
                } catch (e: Exception) {
                    runCatching { listener?.onCommandFailure(e.message ?: "Abfrage fehlgeschlagen") }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        channels.values.forEach { runCatching { it.close() } }
        channels.clear()
        super.onDestroy()
    }

    // --- Hilfsfunktionen ----------------------------------------------------------------

    private fun session(sessionId: Int, sessionKey: String?): SshSession? {
        val caller = caller() ?: return null
        return registry.resolve(sessionId, sessionKey, caller)
    }

    private fun channelFor(session: SshSession): SftpSession =
        channels.getOrPut(session.id) { SftpSession.open(session.sshConnection) }

    /** Relative Pfade beziehen sich auf den zuletzt mit `cd` gesetzten Ordner. */
    private fun resolve(session: SshSession, path: String?): String {
        val given = path?.takeIf { it.isNotBlank() } ?: "."
        if (given.startsWith("/") || given.startsWith("~")) return given
        val base = workingDirectories[session.id] ?: return given
        return "$base/$given"
    }

    private fun withSftp(
        sessionId: Int,
        sessionKey: String?,
        listener: ICommandSuccessListener?,
        body: (SshSession, SftpSession) -> Unit,
    ) {
        val session = session(sessionId, sessionKey)
        if (session == null) {
            runCatching { listener?.onSessionFailure("Unbekannte Sitzung") }
            return
        }
        scope.launch {
            try {
                body(session, channelFor(session))
                runCatching { listener?.onSuccess() }
            } catch (e: Exception) {
                runCatching { listener?.onCommandFailure(e.message ?: "Befehl fehlgeschlagen") }
            }
        }
    }

    private fun withSftpResult(
        sessionId: Int,
        sessionKey: String?,
        listener: IStringResultListener?,
        body: (SshSession, SftpSession) -> String,
    ) {
        val session = session(sessionId, sessionKey)
        if (session == null) {
            runCatching { listener?.onSessionFailure("Unbekannte Sitzung") }
            return
        }
        scope.launch {
            try {
                val result = body(session, channelFor(session))
                runCatching { listener?.onSuccess(result) }
            } catch (e: Exception) {
                runCatching { listener?.onCommandFailure(e.message ?: "Befehl fehlgeschlagen") }
            }
        }
    }
}

/**
 * Füllt die Felder, die JuiceSSH aus JSchs `SftpATTRS` gefüllt hat.
 *
 * Die Zeitangaben sind im Vertrag `int` — Sekunden seit 1970. Das läuft 2038 über; das ist
 * dem Vertrag geschuldet und nicht hier zu reparieren, ohne ihn zu brechen.
 */
internal fun SftpEntry.toParcelable(): ParcelableLsEntry {
    val seconds = modifiedEpochSeconds.toInt()
    val attrs = ParcelableSftpATTRS(
        permissions,
        seconds.toString(),
        seconds.toString(),
        !isDirectory && !isSymlink,
        isDirectory,
        false,
        false,
        false,
        isSymlink,
        false,
        0,
        sizeBytes,
        0,
        0,
        0,
        seconds,
        seconds,
        emptyArray(),
        permissions,
    )
    return ParcelableLsEntry(attrs, name, "$permissions $name", name)
}
