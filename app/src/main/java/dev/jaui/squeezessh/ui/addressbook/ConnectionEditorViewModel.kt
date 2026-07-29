package dev.jaui.squeezessh.ui.addressbook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.IdentityEntity
import dev.jaui.squeezessh.data.model.BackspaceMode
import dev.jaui.squeezessh.data.model.ConnectionTarget
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.IdentityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val connections: ConnectionRepository,
    private val groups: ConnectionGroupRepository,
    private val identities: IdentityRepository,
) : ViewModel() {

    private val connectionId: Long = savedStateHandle["connectionId"] ?: NEW

    /** Aus einem `ssh://`-Link hereingereichtes Ziel. */
    private val prefill: String? = savedStateHandle["prefill"]

    private val _state = MutableStateFlow(ConnectionEditorState())
    val state: StateFlow<ConnectionEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val allGroups = groups.observeAll().first()
        val allIdentities = identities.observeAll().first()
        // Eine Verbindung darf nicht ueber sich selbst springen — sie faellt deshalb aus
        // der Auswahl heraus.
        val possibleJumpHosts = connections.observeAll().first().filter { it.id != connectionId }

        val existing = connectionId.takeIf { it != NEW }?.let { connections.findById(it) }
        val fromLink = existing?.let { null } ?: ConnectionTarget.parse(prefill)
        val linkedIdentityIds = existing?.let { connection ->
            connections.identitiesFor(connection.id).map { it.id }
        }

        _state.value = ConnectionEditorState(
            isNew = existing == null,
            nickname = existing?.nickname.orEmpty(),
            address = existing?.address ?: fromLink?.host.orEmpty(),
            port = (existing?.port ?: fromLink?.port ?: ConnectionEntity.DEFAULT_SSH_PORT).toString(),
            type = existing?.type ?: ConnectionType.SSH,
            backspaceMode = existing?.backspaceMode ?: BackspaceMode.DELETE,
            groupId = existing?.groupId,
            viaConnectionId = existing?.viaConnectionId,
            selectedIdentityIds = linkedIdentityIds.orEmpty().toSet(),
            availableGroups = allGroups,
            availableIdentities = allIdentities,
            possibleJumpHosts = possibleJumpHosts,
            pendingUsername = fromLink?.username,
            parsed = fromLink ?: existing?.let { ConnectionTarget(it.address, it.port) },
        )
    }

    fun onNicknameChanged(value: String) = _state.update { it.copy(nickname = value) }

    /**
     * Nimmt die Eingabe unveraendert entgegen und merkt sich nur, was daraus zu lesen ist.
     *
     * Bewusst **kein** Zerlegen waehrend des Tippens: bei `10.0.2.2:2222` waeren Host und
     * Port nach dem ersten Ziffernanschlag getrennt, und die restlichen Ziffern landeten
     * im falschen Feld. Aufgeloest wird deshalb erst beim Speichern; was verstanden wurde,
     * zeigt der Bildschirm vorher an.
     */
    fun onAddressChanged(value: String) = _state.update {
        it.copy(address = value, parsed = ConnectionTarget.parse(value))
    }

    fun onPortChanged(value: String) = _state.update { it.copy(port = value.filter(Char::isDigit)) }

    fun onTypeChanged(value: ConnectionType) = _state.update { it.copy(type = value) }

    fun onBackspaceModeChanged(value: BackspaceMode) =
        _state.update { it.copy(backspaceMode = value) }

    fun onGroupChanged(value: Long?) = _state.update { it.copy(groupId = value) }

    fun onJumpHostChanged(value: Long?) = _state.update { it.copy(viaConnectionId = value) }

    fun onIdentityToggled(identityId: Long) = _state.update { current ->
        val selected = current.selectedIdentityIds
        current.copy(
            selectedIdentityIds = if (identityId in selected) {
                selected - identityId
            } else {
                selected + identityId
            },
        )
    }

    fun createGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val group = groups.findOrCreate(name.trim())
            _state.update {
                it.copy(
                    availableGroups = groups.observeAll().first(),
                    groupId = group.id,
                )
            }
        }
    }

    /** @return `true`, wenn gespeichert wurde. */
    suspend fun save(): Boolean {
        val current = _state.value
        if (!current.canSave) return false

        // Trug das Adressfeld selbst einen Port oder Benutzer, gewinnt das: dort steht
        // die vollstaendigere Angabe.
        val parsed = current.parsed
        val carriesMore = parsed != null && parsed.host != current.address.trim()
        val entity = ConnectionEntity(
            id = if (current.isNew) 0 else connectionId,
            nickname = current.nickname.takeIf { it.isNotBlank() },
            address = parsed?.host ?: current.address.trim(),
            port = if (carriesMore) {
                parsed.port
            } else {
                current.port.toIntOrNull() ?: ConnectionEntity.DEFAULT_SSH_PORT
            },
            type = current.type,
            groupId = current.groupId,
            viaConnectionId = current.viaConnectionId,
            backspaceMode = current.backspaceMode,
            createdAt = 0,
            updatedAt = 0,
        )

        val id = if (current.isNew) {
            connections.create(entity)
        } else {
            val existing = connections.findById(connectionId) ?: return false
            connections.update(
                entity.copy(
                    createdAt = existing.createdAt,
                    useCount = existing.useCount,
                    lastUsedAt = existing.lastUsedAt,
                    startupSnippetId = existing.startupSnippetId,
                    moshCommand = existing.moshCommand,
                ),
            )
            connectionId
        }
        connections.setIdentities(id, current.selectedIdentityIds.toList())
        return true
    }

    companion object {
        const val NEW = -1L
    }
}

data class ConnectionEditorState(
    val isNew: Boolean = true,
    val nickname: String = "",
    val address: String = "",
    val port: String = "22",
    val type: ConnectionType = ConnectionType.SSH,
    val backspaceMode: BackspaceMode = BackspaceMode.DELETE,
    val groupId: Long? = null,
    val viaConnectionId: Long? = null,
    val selectedIdentityIds: Set<Long> = emptySet(),
    val availableGroups: List<ConnectionGroupEntity> = emptyList(),
    val availableIdentities: List<IdentityEntity> = emptyList(),
    val possibleJumpHosts: List<ConnectionEntity> = emptyList(),
    /** Aus einer Eingabe wie `root@host` mitgelesen — als Hinweis, welche Identitaet passt. */
    val pendingUsername: String? = null,
    /** Was aus dem Adressfeld zu lesen ist; `null` heisst unbrauchbar. */
    val parsed: ConnectionTarget? = null,
) {
    /** Der Benutzername aus dem Adressfeld, falls einer darin steht. */
    val addressUsername: String? get() = parsed?.username ?: pendingUsername

    /** Was beim Speichern herauskaeme — als Rueckmeldung noch vor dem Speichern. */
    val effectiveTarget: String?
        get() = parsed?.let { target ->
            val port = if (target.host != address.trim()) {
                target.port
            } else {
                port.toIntOrNull() ?: ConnectionTarget.DEFAULT_PORT
            }
            ConnectionTarget(target.host, port).toString()
        }

    val canSave: Boolean
        get() = parsed != null && (port.toIntOrNull() ?: 0) in 1..65535
}
