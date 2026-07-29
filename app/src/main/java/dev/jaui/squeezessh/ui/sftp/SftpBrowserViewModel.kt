package dev.jaui.squeezessh.ui.sftp

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.sftp.SftpEntry
import dev.jaui.squeezessh.sftp.SftpSession
import dev.jaui.squeezessh.sftp.SftpTransfer
import dev.jaui.squeezessh.sftp.SftpTransferManager
import dev.jaui.squeezessh.terminal.TerminalTabs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Dateibrowser auf der aktiven Sitzung.
 *
 * Nutzt bewusst dieselbe SSH-Verbindung wie das Terminal: eine zweite aufzubauen wuerde
 * erneut nach Host-Key und Zugangsdaten fragen und auf dem Server als zweite Anmeldung
 * auftauchen.
 */
@HiltViewModel
class SftpBrowserViewModel @Inject constructor(
    private val tabs: TerminalTabs,
    private val transfers: SftpTransferManager,
    private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(SftpBrowserState())
    val state: StateFlow<SftpBrowserState> = _state.asStateFlow()

    val activeTransfers: StateFlow<List<SftpTransfer>> = transfers.transfers

    private var session: SftpSession? = null

    fun open() {
        if (session != null) return
        val tab = tabs.activeTab
        if (tab == null) {
            _state.update { it.copy(error = "Keine offene Sitzung — erst verbinden.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val opened = withContext(io) { SftpSession.open(tab.session.sshConnection) }
                session = opened
                val home = withContext(io) { opened.canonicalize(".") }
                navigateTo(home)
            } catch (e: IOException) {
                _state.update {
                    it.copy(busy = false, error = "SFTP nicht verfügbar: ${e.message}")
                }
            }
        }
    }

    fun navigateTo(path: String) {
        val sftp = session ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val entries = withContext(io) { sftp.list(path) }
                _state.update {
                    it.copy(busy = false, path = path, entries = entries, error = null)
                }
            } catch (e: IOException) {
                _state.update {
                    it.copy(busy = false, error = "Verzeichnis nicht lesbar: ${e.message}")
                }
            }
        }
    }

    fun navigateUp() {
        val current = _state.value.path
        if (current == "/" || current.isEmpty()) return
        navigateTo(current.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" })
    }

    fun download(entry: SftpEntry, destination: Uri) {
        val sftp = session ?: return
        transfers.download(sftp, entry.path, entry.name, destination, entry.sizeBytes)
    }

    fun upload(source: Uri, name: String, size: Long) {
        val sftp = session ?: return
        val target = "${_state.value.path.trimEnd('/')}/$name"
        transfers.upload(sftp, source, target, name, size)
    }

    fun createDirectory(name: String) {
        val sftp = session ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                withContext(io) {
                    sftp.makeDirectory("${_state.value.path.trimEnd('/')}/${name.trim()}")
                }
                navigateTo(_state.value.path)
            } catch (e: IOException) {
                _state.update { it.copy(error = "Anlegen fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun delete(entry: SftpEntry) {
        val sftp = session ?: return
        viewModelScope.launch {
            try {
                withContext(io) { sftp.delete(entry) }
                navigateTo(_state.value.path)
            } catch (e: IOException) {
                _state.update { it.copy(error = "Löschen fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun pauseTransfer(id: Long) = transfers.pause(id)

    fun resumeTransfer(id: Long) = transfers.resume(id)

    fun cancelTransfer(id: Long) = transfers.cancel(id)

    fun clearFinishedTransfers() = transfers.clearFinished()

    fun dismissError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        // Der Kanal gehoert diesem Bildschirm; die SSH-Verbindung darunter bleibt offen.
        //
        // Geschlossen wird im Hintergrund und ausserhalb des ViewModel-Scopes: der ist
        // hier bereits abgebrochen, und ein Kanalabbau auf dem Hauptthread wuerde die
        // gemeinsame Verbindung zerlegen.
        val closing = session
        session = null
        CoroutineScope(SupervisorJob() + io).launch { closing?.close() }
    }
}

data class SftpBrowserState(
    val path: String = "",
    val entries: List<SftpEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)
