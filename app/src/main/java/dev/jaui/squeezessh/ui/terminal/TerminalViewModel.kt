package dev.jaui.squeezessh.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.ssh.HostKeyPrompt
import dev.jaui.squeezessh.ssh.SshError
import dev.jaui.squeezessh.ssh.SshException
import dev.jaui.squeezessh.ssh.SshSession
import dev.jaui.squeezessh.ssh.SshSessionManager
import dev.jaui.squeezessh.ssh.SshSessionService
import dev.jaui.squeezessh.terminal.TerminalBridge
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
    private val sessions: SshSessionManager,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var session: SshSession? = null
    private var bridge: TerminalBridge? = null

    fun connect(connectionId: Long) {
        if (_state.value.phase != TerminalPhase.Idle) return
        _state.update { it.copy(phase = TerminalPhase.Connecting, statusLine = "Verbinde…") }

        viewModelScope.launch {
            try {
                val opened = sessions.open(
                    connectionId = connectionId,
                    onHostKey = ::askAboutHostKey,
                    onProgress = { progress ->
                        _state.update { it.copy(statusLine = progress.describe()) }
                    },
                )
                session = opened
                SshSessionService.start(getApplication())
                _state.update {
                    it.copy(
                        phase = TerminalPhase.Connected,
                        title = opened.title,
                        statusLine = null,
                    )
                }
            } catch (e: SshException) {
                _state.update { it.copy(phase = TerminalPhase.Failed, error = e.error.describe()) }
            }
        }
    }

    /**
     * Uebergibt der Oberflaeche die Frage und wartet auf die Antwort.
     *
     * Der [SshConnector] blockiert derweil seinen Verbindungsaufbau — genau so soll es
     * sein: eine Verbindung darf erst weiterlaufen, wenn der Host bestaetigt ist.
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

    /** Wird von der View gerufen, sobald sie ihre Groesse kennt. */
    fun attachTerminal(create: (SshSession) -> TerminalBridge): TerminalBridge? {
        val current = session ?: return null
        return bridge ?: create(current).also { bridge = it }
    }

    fun onSessionFinished(reason: String?) {
        _state.update {
            it.copy(
                phase = TerminalPhase.Disconnected,
                statusLine = reason ?: "Verbindung beendet",
            )
        }
    }

    fun disconnect() {
        val current = session ?: return
        viewModelScope.launch {
            bridge?.close()
            bridge = null
            sessions.close(current.id)
            session = null
            if (sessions.openSessions.value.isEmpty()) {
                SshSessionService.stop(getApplication())
            }
            _state.update { it.copy(phase = TerminalPhase.Disconnected) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Sitzungen ueberleben das ViewModel bewusst: ein Bildschirmwechsel darf keine
        // Verbindung kappen. Geschlossen wird nur ueber disconnect().
    }

    private fun dev.jaui.squeezessh.ssh.SshConnectionState.describe(): String? = when (this) {
        is dev.jaui.squeezessh.ssh.SshConnectionState.Connecting -> "Verbinde mit $step…"
        dev.jaui.squeezessh.ssh.SshConnectionState.Connected -> null
        else -> null
    }

    private fun SshError.describe(): String = when (this) {
        is SshError.Unreachable -> "Server nicht erreichbar${detail?.let { " ($it)" }.orEmpty()}"
        is SshError.AuthenticationFailed -> "Anmeldung fehlgeschlagen${detail?.let { ": $it" }.orEmpty()}"
        is SshError.HostKeyRejected -> "Host-Key nicht bestätigt"
        // Kommt hier normalerweise nicht an: der Connector fragt nach und verbindet neu.
        is SshError.HostKeyUnverified -> "Host-Key von ${prompt.host} konnte nicht geprüft werden"
        is SshError.KeyUnusable -> "Privater Schlüssel unbrauchbar${detail?.let { ": $it" }.orEmpty()}"
        is SshError.ConnectionLost -> "Verbindung verloren${detail?.let { ": $it" }.orEmpty()}"
        is SshError.Unexpected -> detail ?: "Unerwarteter Fehler"
    }
}

data class TerminalUiState(
    val phase: TerminalPhase = TerminalPhase.Idle,
    val title: String = "",
    val statusLine: String? = null,
    val error: String? = null,
    val hostKeyRequest: HostKeyRequest? = null,
    val controlActive: Boolean = false,
    val altActive: Boolean = false,
)

enum class TerminalPhase { Idle, Connecting, Connected, Disconnected, Failed }

data class HostKeyRequest(
    val prompt: HostKeyPrompt,
    val answer: CompletableDeferred<Boolean>,
)
