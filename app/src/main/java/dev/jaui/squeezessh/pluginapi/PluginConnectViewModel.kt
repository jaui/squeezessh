package dev.jaui.squeezessh.pluginapi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.ssh.HostKeyPrompt
import dev.jaui.squeezessh.ssh.SshConnectionState
import dev.jaui.squeezessh.ssh.SshException
import dev.jaui.squeezessh.ssh.SshSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PluginConnectViewModel @Inject constructor(
    private val connections: ConnectionRepository,
    private val sessions: SshSessionManager,
    private val registry: PluginSessionRegistry,
    private val auditLog: PluginAuditLog,
) : ViewModel() {

    private val _state = MutableStateFlow(PluginConnectState())
    val state: StateFlow<PluginConnectState> = _state.asStateFlow()

    private var started = false

    /**
     * @param publicId die UUID aus `ssh://<uuid>`
     * @param caller das Paket des Plugins — es wird Eigentümer der Sitzung
     * @param forSftp nur für den Prüfpfad; die Sitzung selbst ist dieselbe
     */
    fun connect(publicId: String, caller: String, forSftp: Boolean) {
        if (started) return
        started = true
        _state.update { it.copy(callerLabel = caller) }

        viewModelScope.launch {
            val connection = connections.findByPublicId(publicId)
            if (connection == null) {
                // Der Prüfpfad hält auch den vergeblichen Versuch fest: er zeigt, dass ein
                // Plugin eine Verbindung ansprechen wollte, die es nicht (mehr) gibt.
                auditLog.record(caller, "wollte eine unbekannte Verbindung öffnen")
                _state.update { it.copy(error = "Diese Verbindung gibt es nicht.") }
                return@launch
            }

            auditLog.record(
                caller,
                if (forSftp) {
                    "hat eine SFTP-Sitzung zu ${connection.displayName} geöffnet"
                } else {
                    "hat eine Sitzung zu ${connection.displayName} geöffnet"
                },
            )

            try {
                val session = sessions.open(
                    connectionId = connection.id,
                    onHostKey = ::askAboutHostKey,
                    onProgress = { progress ->
                        _state.update { it.copy(status = progress.describe()) }
                    },
                )
                _state.update { it.copy(result = registry.register(session, caller)) }
            } catch (e: SshException) {
                auditLog.record(caller, "konnte ${connection.displayName} nicht erreichen")
                _state.update { it.copy(error = e.error.describe()) }
            }
        }
    }

    private suspend fun askAboutHostKey(prompt: HostKeyPrompt): Boolean {
        val answer = CompletableDeferred<Boolean>()
        _state.update { it.copy(hostKeyRequest = HostKeyAsk(prompt, answer)) }
        return try {
            answer.await()
        } finally {
            _state.update { it.copy(hostKeyRequest = null) }
        }
    }

    fun answerHostKey(accepted: Boolean) {
        _state.value.hostKeyRequest?.answer?.complete(accepted)
    }

    private fun SshConnectionState.describe(): String? = when (this) {
        is SshConnectionState.Connecting -> "Verbinde mit $step…"
        else -> null
    }

    private fun dev.jaui.squeezessh.ssh.SshError.describe(): String = when (this) {
        is dev.jaui.squeezessh.ssh.SshError.Unreachable ->
            "Server nicht erreichbar${detail?.let { " ($it)" }.orEmpty()}"
        is dev.jaui.squeezessh.ssh.SshError.AuthenticationFailed ->
            "Anmeldung fehlgeschlagen${detail?.let { ": $it" }.orEmpty()}"
        is dev.jaui.squeezessh.ssh.SshError.HostKeyRejected -> "Host-Key nicht bestätigt"
        is dev.jaui.squeezessh.ssh.SshError.HostKeyUnverified ->
            "Host-Key von ${prompt.host} konnte nicht geprüft werden"
        is dev.jaui.squeezessh.ssh.SshError.KeyUnusable ->
            "Privater Schlüssel unbrauchbar${detail?.let { ": $it" }.orEmpty()}"
        is dev.jaui.squeezessh.ssh.SshError.ConnectionLost ->
            "Verbindung verloren${detail?.let { ": $it" }.orEmpty()}"
        is dev.jaui.squeezessh.ssh.SshError.Unexpected -> detail ?: "Unerwarteter Fehler"
    }
}

data class PluginConnectState(
    val status: String? = null,
    val callerLabel: String? = null,
    val error: String? = null,
    val hostKeyRequest: HostKeyAsk? = null,
    val result: PluginSessionRegistry.Handle? = null,
)

data class HostKeyAsk(
    val prompt: HostKeyPrompt,
    val answer: CompletableDeferred<Boolean>,
)
