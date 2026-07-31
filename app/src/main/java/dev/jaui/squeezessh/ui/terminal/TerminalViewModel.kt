package dev.jaui.squeezessh.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.ssh.CredentialAnswer
import dev.jaui.squeezessh.ssh.CredentialPrompt
import dev.jaui.squeezessh.ssh.HostKeyPrompt
import dev.jaui.squeezessh.ssh.SshConnectionState
import dev.jaui.squeezessh.ssh.SshError
import dev.jaui.squeezessh.ssh.SshException
import dev.jaui.squeezessh.data.settings.AppSettings
import dev.jaui.squeezessh.data.settings.TerminalSettings
import dev.jaui.squeezessh.terminal.TerminalTabs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    application: Application,
    val tabs: TerminalTabs,
    settings: AppSettings,
) : AndroidViewModel(application) {

    /**
     * Die Terminal-Einstellungen als Datenstrom: eine Aenderung im Einstellungsbildschirm
     * wirkt sofort auf die offene Sitzung, ohne dass sie neu aufgebaut werden muss. Nur
     * der TERM-Typ ist davon ausgenommen — der steht mit dem PTY fest.
     */
    val terminalSettings: StateFlow<TerminalSettings> = settings.terminal.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        TerminalSettings(
            themeId = AppSettings.DEFAULT_TERMINAL_THEME,
            fontId = AppSettings.DEFAULT_TERMINAL_FONT,
            fontSizePx = dev.jaui.squeezessh.terminal.TerminalView.DEFAULT_TEXT_SIZE_PX,
            marginDp = 0,
            terminalType = AppSettings.DEFAULT_TERMINAL_TYPE,
            utf8 = true,
            bellMode = dev.jaui.squeezessh.data.settings.BellMode.NONE,
            keyboardPosition = dev.jaui.squeezessh.data.settings.KeyboardPosition.BOTTOM,
            horizontalSwipeMode = dev.jaui.squeezessh.data.settings.HorizontalSwipeMode.NONE,
        ),
    )

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
                    onCredentials = ::askForCredentials,
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

    /**
     * Fragt die Zugangsdaten ab, wenn zur Identitaet noch keine hinterlegt sind.
     *
     * Anders als beim Host-Key laeuft der Verbindungsaufbau hier noch **nicht**: gefragt
     * wird, bevor der erste Socket aufgeht. Es kann also kein Timeout mitlaufen, waehrend
     * jemand tippt.
     */
    private suspend fun askForCredentials(prompt: CredentialPrompt): CredentialAnswer? {
        val answer = CompletableDeferred<CredentialAnswer?>()
        _state.update {
            it.copy(credentialRequest = CredentialRequest(prompt, answer), statusLine = null)
        }
        return try {
            answer.await()
        } finally {
            _state.update { it.copy(credentialRequest = null) }
        }
    }

    /** [answer] `null` bricht den Verbindungsaufbau ab. */
    fun answerCredentials(answer: CredentialAnswer?) {
        _state.value.credentialRequest?.answer?.complete(answer)
    }

    fun activateTab(tabId: Long) = tabs.activate(tabId)

    /** Naechste oder vorherige Sitzung, umlaufend. */
    fun switchSession(forward: Boolean) {
        val open = tabs.tabs.value
        if (open.size < 2) return
        val current = open.indexOfFirst { it.id == tabs.activeTabId.value }
        if (current < 0) return
        val next = ((current + if (forward) 1 else -1) + open.size) % open.size
        tabs.activate(open[next].id)
    }

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
    val credentialRequest: CredentialRequest? = null,
)

data class HostKeyRequest(
    val prompt: HostKeyPrompt,
    val answer: CompletableDeferred<Boolean>,
)

data class CredentialRequest(
    val prompt: CredentialPrompt,
    val answer: CompletableDeferred<CredentialAnswer?>,
)
