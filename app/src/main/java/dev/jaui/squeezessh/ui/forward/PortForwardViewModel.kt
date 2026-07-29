package dev.jaui.squeezessh.ui.forward

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.repository.PortForwardRepository
import dev.jaui.squeezessh.ssh.forward.PortForwardException
import dev.jaui.squeezessh.ssh.forward.PortForwardManager
import dev.jaui.squeezessh.terminal.TerminalTabs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Weiterleitungen der Verbindung, zu der die aktive Sitzung gehoert.
 *
 * Ohne offene Sitzung lassen sie sich anlegen und bearbeiten, aber nicht einschalten —
 * ein Tunnel braucht die Verbindung, durch die er laeuft.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortForwardViewModel @Inject constructor(
    private val repository: PortForwardRepository,
    private val forwards: PortForwardManager,
    private val tabs: TerminalTabs,
) : ViewModel() {

    private val error = MutableStateFlow<String?>(null)

    private val connectionId = tabs.activeTabId
        .flatMapLatest { activeId ->
            MutableStateFlow(tabs.tabs.value.firstOrNull { it.id == activeId }?.connectionId)
        }

    val state: StateFlow<PortForwardUiState> = combine(
        connectionId.flatMapLatest { id ->
            if (id == null) MutableStateFlow(emptyList()) else repository.observeForConnection(id)
        },
        forwards.active,
        error,
    ) { definitions, active, currentError ->
        PortForwardUiState(
            hasSession = tabs.activeTab != null,
            forwards = definitions.map { definition ->
                val running = active[definition.id]
                PortForwardItem(
                    definition = definition,
                    isRunning = running?.isAlive == true,
                    endpoint = running?.endpoint ?: describe(definition),
                )
            },
            error = currentError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortForwardUiState())

    fun toggle(definition: PortForwardEntity) {
        val tab = tabs.activeTab
        if (tab == null) {
            error.value = "Keine offene Sitzung — erst verbinden."
            return
        }

        viewModelScope.launch {
            try {
                if (forwards.isRunning(definition.id)) {
                    forwards.stop(definition.id)
                } else {
                    forwards.start(tab.id, tab.session.sshConnection, definition)
                }
                error.value = null
            } catch (e: PortForwardException) {
                error.value = e.message
            }
        }
    }

    fun save(draft: PortForwardDraft) {
        val connectionId = tabs.activeTab?.connectionId
        if (connectionId == null) {
            error.value = "Weiterleitungen gehören zu einer Verbindung — erst verbinden."
            return
        }
        viewModelScope.launch {
            val entity = draft.toEntity(connectionId)
            if (entity.id == 0L) repository.create(entity) else repository.update(entity)
        }
    }

    fun delete(definition: PortForwardEntity) {
        viewModelScope.launch {
            forwards.stop(definition.id)
            repository.delete(definition)
        }
    }

    fun dismissError() { error.value = null }

    /** Was der Tunnel tun wuerde, solange er nicht laeuft. */
    private fun describe(definition: PortForwardEntity): String = when (definition.mode) {
        dev.jaui.squeezessh.data.model.PortForwardMode.LOCAL ->
            "127.0.0.1:${definition.localPort} → ${definition.targetHost}:${definition.remotePort}"
        dev.jaui.squeezessh.data.model.PortForwardMode.REMOTE ->
            "Server-Port ${definition.remotePort} → ${definition.targetHost}:${definition.localPort}"
        dev.jaui.squeezessh.data.model.PortForwardMode.DYNAMIC ->
            "SOCKS5 auf 127.0.0.1:${definition.localPort}"
    }
}

data class PortForwardUiState(
    val hasSession: Boolean = false,
    val forwards: List<PortForwardItem> = emptyList(),
    val error: String? = null,
)

data class PortForwardItem(
    val definition: PortForwardEntity,
    val isRunning: Boolean,
    val endpoint: String,
)
