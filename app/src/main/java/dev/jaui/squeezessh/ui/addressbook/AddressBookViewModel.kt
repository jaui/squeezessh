package dev.jaui.squeezessh.ui.addressbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.model.ConnectionTarget
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddressBookViewModel @Inject constructor(
    private val connections: ConnectionRepository,
    private val groups: ConnectionGroupRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Bei leerer Suche die vollstaendige Liste, sonst die Treffer.
     *
     * `flatMapLatest` sorgt dafuer, dass eine noch laufende Suche verworfen wird, sobald
     * weitergetippt wird — sonst blitzen bei schnellem Tippen alte Ergebnisse auf.
     */
    private val visibleConnections = _query.flatMapLatest { query ->
        if (query.isBlank()) connections.observeAll() else connections.search(query.trim())
    }

    val state: StateFlow<AddressBookState> = combine(
        visibleConnections,
        groups.observeAll(),
        connections.observeFrequentlyUsed(FREQUENT_LIMIT),
        _query,
    ) { visible, allGroups, frequent, query ->
        val grouped = visible.groupBy { it.groupId }
        AddressBookState(
            // Nur Gruppen zeigen, in denen etwas steht — sonst wird die Liste beim Suchen
            // von leeren Ueberschriften zerhackt.
            sections = allGroups
                .mapNotNull { group ->
                    grouped[group.id]?.takeIf { it.isNotEmpty() }?.let { Section(group, it) }
                },
            ungrouped = grouped[null].orEmpty(),
            // Die Abkuerzung lohnt nur, wenn sie etwas abkuerzt: enthaelt sie ohnehin jede
            // Verbindung, steht dieselbe Liste zweimal untereinander und sieht nach einem
            // Fehler aus. Beim Suchen entfaellt sie ganz — dann zaehlt der Treffer.
            frequentlyUsed = if (query.isBlank() && frequent.size < visible.size) {
                frequent
            } else {
                emptyList()
            },
            query = query,
            isEmpty = visible.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddressBookState())

    fun onQueryChanged(value: String) {
        _query.value = value
    }

    /**
     * Legt aus einer Eingabe wie `root@host:2222` eine Verbindung an.
     *
     * @return die ID der neuen Verbindung, oder `null` wenn die Eingabe unbrauchbar war.
     */
    suspend fun createFromQuickConnect(input: String): Long? {
        val target = ConnectionTarget.parse(input) ?: return null
        return connections.create(
            ConnectionEntity(
                nickname = null,
                address = target.host,
                port = target.port,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
    }

    fun delete(connection: ConnectionEntity) {
        viewModelScope.launch { connections.delete(connection) }
    }

    /** Verbindungen, die ueber diese springen — vor dem Loeschen zu zeigen. */
    suspend fun dependentsOf(connectionId: Long): List<ConnectionEntity> =
        connections.dependentsOf(connectionId)

    private companion object {
        const val FREQUENT_LIMIT = 5
    }
}

data class AddressBookState(
    val sections: List<Section> = emptyList(),
    val ungrouped: List<ConnectionEntity> = emptyList(),
    val frequentlyUsed: List<ConnectionEntity> = emptyList(),
    val query: String = "",
    val isEmpty: Boolean = true,
)

data class Section(val group: ConnectionGroupEntity, val connections: List<ConnectionEntity>)
