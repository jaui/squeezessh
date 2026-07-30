package dev.jaui.squeezessh.pluginapi

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.sonelli.juicessh.pluginlibrary.IConnectionPluginService
import com.sonelli.juicessh.pluginlibrary.IPluginService
import com.sonelli.juicessh.pluginlibrary.listeners.IPingListener
import com.sonelli.juicessh.pluginlibrary.listeners.ISessionExecuteListener
import com.sonelli.juicessh.pluginlibrary.listeners.ISessionFinishedListener
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.ssh.SshExec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gemeinsamer Unterbau der AIDL-Dienste.
 *
 * Binder-Aufrufe kommen auf einem Pool-Thread an und dürfen nicht blockieren. Alles, was
 * über die Leitung geht, läuft deshalb in [scope] — und zwar auf dem IO-Dispatcher: eine
 * SSH-Operation auf dem Hauptthread zerlegt die gesamte Verbindung, weil Android mitten im
 * Schreiben eines Pakets wirft.
 */
abstract class BasePluginService : Service() {

    @Inject lateinit var registry: PluginSessionRegistry

    @Inject lateinit var auditLog: PluginAuditLog

    @Inject lateinit var io: CoroutineDispatcher

    protected val scope by lazy { CoroutineScope(SupervisorJob() + io) }

    /**
     * Der Paketname des Aufrufers, aus der UID ermittelt.
     *
     * Nicht aus einem Extra oder Parameter: das könnte ein Plugin frei setzen und sich
     * damit als ein anderes ausgeben. Die UID stammt vom Binder und ist nicht fälschbar.
     */
    protected fun caller(): String? =
        packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull()

    protected fun audit(message: String) {
        val caller = caller() ?: return
        scope.launch { auditLog.record(caller, message) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

/**
 * Der volle Dienst: Sitzungen aufbauen, Befehle darin ausführen, Eingaben schicken.
 *
 * `attach` gibt es im Vertrag, tut hier aber nichts. Bei JuiceSSH holte es eine im
 * Hintergrund geöffnete Sitzung in den Vordergrund. Hier bleibt eine Plugin-Sitzung
 * bewusst getrennt von den Terminal-Tabs des Nutzers: sie unter der Hand in dessen
 * Oberfläche zu schieben wäre eine Überraschung, und ein Plugin, das nur Befehle
 * ausführen will, braucht es nicht.
 */
@AndroidEntryPoint
class PluginService : BasePluginService() {

    private val binder = object : IPluginService.Stub() {

        override fun ping(listener: IPingListener?) {
            runCatching { listener?.pong() }
        }

        /** Nur fürs Protokoll — das Verbinden selbst läuft über [PluginConnectActivity]. */
        override fun connect(id: String?) {
            audit("hat eine Verbindung angefragt (${id ?: "ohne Kennung"})")
        }

        override fun disconnect(sessionId: Int, sessionKey: String?) {
            val caller = caller() ?: return
            audit("hat eine Sitzung geschlossen")
            scope.launch { registry.close(sessionId, sessionKey, caller) }
        }

        override fun addSessionFinishedListener(
            sessionId: Int,
            sessionKey: String?,
            listener: ISessionFinishedListener?,
        ) {
            val caller = caller() ?: return
            registry.addFinishedListener(sessionId, sessionKey, caller) {
                runCatching { listener?.onSessionFinished() }
            }
        }

        override fun attach(sessionId: Int, sessionKey: String?) {
            audit("wollte eine Sitzung in den Vordergrund holen — nicht unterstützt")
        }

        override fun executeCommandOnSession(
            sessionId: Int,
            sessionKey: String?,
            command: String?,
            listener: ISessionExecuteListener?,
        ) {
            val caller = caller() ?: return
            val session = registry.resolve(sessionId, sessionKey, caller)
            if (session == null || command == null) {
                runCatching { listener?.onError(ERROR_NO_SESSION, "Unbekannte Sitzung") }
                return
            }

            // Der Befehl steht im Protokoll: er läuft mit den Rechten des Nutzers auf
            // dessen Server, und das soll nachvollziehbar bleiben.
            audit("hat ausgeführt: ${command.take(AUDIT_COMMAND_LIMIT)}")

            scope.launch {
                try {
                    val result = SshExec.run(session.sshConnection, command) { line ->
                        runCatching { listener?.onOutputLine(line) }
                    }
                    runCatching { listener?.onCompleted(result) }
                } catch (e: Exception) {
                    runCatching {
                        listener?.onError(ERROR_FAILED, e.message ?: "Befehl fehlgeschlagen")
                    }
                }
            }
        }

        override fun writeToSession(sessionId: Int, sessionKey: String?, command: String?) {
            val caller = caller() ?: return
            val session = registry.resolve(sessionId, sessionKey, caller) ?: return
            val text = command ?: return
            audit("hat in eine Sitzung geschrieben")
            scope.launch {
                runCatching {
                    session.shell.input.write(text.toByteArray())
                    session.shell.input.flush()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val ERROR_NO_SESSION = 1
        const val ERROR_FAILED = 2

        /** Lange Befehle werden im Protokoll gekürzt; es soll lesbar bleiben. */
        const val AUDIT_COMMAND_LIMIT = 200
    }
}

/**
 * Der kleine Dienst für Plugins, die nur verbinden und wieder trennen wollen.
 *
 * Dieselben Methoden wie bei [PluginService], nur ohne Ausführen und Schreiben — ein Plugin,
 * das bloß eine Verbindung aufbauen soll, muss dafür nicht das Recht bekommen, beliebige
 * Befehle abzusetzen.
 */
@AndroidEntryPoint
class ConnectionPluginService : BasePluginService() {

    private val binder = object : IConnectionPluginService.Stub() {

        override fun ping(listener: IPingListener?) {
            runCatching { listener?.pong() }
        }

        override fun connect(id: String?) {
            audit("hat eine Verbindung angefragt (${id ?: "ohne Kennung"})")
        }

        override fun disconnect(sessionId: Int, sessionKey: String?) {
            val caller = caller() ?: return
            audit("hat eine Sitzung geschlossen")
            scope.launch { registry.close(sessionId, sessionKey, caller) }
        }

        override fun addSessionFinishedListener(
            sessionId: Int,
            sessionKey: String?,
            listener: ISessionFinishedListener?,
        ) {
            val caller = caller() ?: return
            registry.addFinishedListener(sessionId, sessionKey, caller) {
                runCatching { listener?.onSessionFinished() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
