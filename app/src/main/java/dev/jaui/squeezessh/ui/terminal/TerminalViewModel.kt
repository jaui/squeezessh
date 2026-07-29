package dev.jaui.squeezessh.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.ssh.HostKeyPrompt
import dev.jaui.squeezessh.ssh.SshConnectionState
import dev.jaui.squeezessh.ssh.SshError
import dev.jaui.squeezessh.ssh.SshException
import dev.jaui.squeezessh.terminal.TerminalTabs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    application: Application,
    val tabs: TerminalTabs,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /**
     * Oeffnet eine weitere Sitzung.
     *
     * Mehrfaches Oeffnen derselben Verbindung ist ausdruecklich erlaubt — zwei Shells auf
     * demselben Server nebeneinander sind der Normalfall, nicht der Fehlerfall.
     */
    fun openTab(connectionId: Long) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, statusLine = "Verbinde…", error = null) }

        viewModelScope.launch {
            try {
                tabs.open(
                    context = getApplication(),
                    connectionId = connectionId,
                    onHostKey = ::askAboutHostKey,
                    onProgress = { progress ->
                        _state.update { it.copy(statusLine = progress.describe()) }
                    },
                )
                _state.update { it.copy(connecting = false, statusLine = null) }
            } catch (e: SshException) {
                _state.update {
                    it.copy(connecting = false, statusLine = null, error = e.error.describe())
                }
            }
        }
    }

    /**
     * Uebergibt der Oberflaeche die Frage und wartet auf die Antwort.
     *
     * Der Verbindungsaufbau ist zu diesem Zeitpunkt bereits abgebrochen — der Connector
     * verbindet nach der Antwort neu. Es laeuft also kein Protokoll-Timeout mit, waehrend
     * jemand einen Fingerprint vergleicht.
     */
    private suspend fun askAboutHostKey(prompt: HostKeyPrompt): Boolean {
        val answer = CompletableDeferred<Boolean>()
        _state.update { it.copy(hostKeyRequest = HostKeyRequest(prompt, answer)) }
        return try {
            answer.await()
        } finally {
            _state.update { it.copy(hostKeyRequest = null) }
        }
    }

    fun answerHostKey(accepted: Boolean) {
        _state.value.hostKeyRequest?.answer?.complete(accepted)
    }

    fun activateTab(tabId: Long) = tabs.activate(tabId)

    fun closeTab(tabId: Long) {
        viewModelScope.launch { tabs.close(getApplication(), tabId) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun SshConnectionState.describe(): String? = when (this) {
        is SshConnectionState.Connecting -> "Verbinde mit $step…"
        else -> null
    }

    private fun SshError.describe(): String = when (this) {
        is SshError.Unreachable -> "Server nicht erreichbar${detail?.let { " ($it)" }.orEmpty()}"
        is SshError.AuthenticationFailed ->
            "Anmeldung fehlgeschlagen${detail?.let { ": $it" }.orEmpty()}"
        is SshError.HostKeyRejected -> "Host-Key nicht bestätigt"
        is SshError.HostKeyUnverified -> "Host-Key von ${prompt.host} konnte nicht geprüft werden"
        is SshError.KeyUnusable ->
            "Privater Schlüssel unbrauchbar${detail?.let { ": $it" }.orEmpty()}"
        is SshError.ConnectionLost -> "Verbindung verloren${detail?.let { ": $it" }.orEmpty()}"
        is SshError.Unexpected -> detail ?: "Unerwarteter Fehler"
    }
}

data class TerminalUiState(
    val connecting: Boolean = false,
    val statusLine: String? = null,
    val error: String? = null,
    val hostKeyRequest: HostKeyRequest? = null,
)

data class HostKeyRequest(
    val prompt: HostKeyPrompt,
    val answer: CompletableDeferred<Boolean>,
)
