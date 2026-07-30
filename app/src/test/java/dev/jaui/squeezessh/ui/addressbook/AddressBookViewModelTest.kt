package dev.jaui.squeezessh.ui.addressbook

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Die Abkürzung „Häufig genutzt" steht über der vollständigen Liste. Zeigt sie ohnehin
 * jede Verbindung, steht dieselbe Liste zweimal untereinander — das sah im Betrieb nach
 * einem Fehler aus und war der Anlass für diesen Test.
 */
@RunWith(RobolectricTestRunner::class)
class AddressBookViewModelTest : DatabaseTestCase() {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var connections: ConnectionRepository
    private lateinit var viewModel: AddressBookViewModel

    @Before
    fun createViewModel() {
        connections = ConnectionRepository(db.connectionDao(), time)
        viewModel = AddressBookViewModel(
            connections,
            ConnectionGroupRepository(db.connectionGroupDao(), time),
        )
    }

    private suspend fun addConnection(nickname: String, uses: Int) {
        val id = connections.create(
            ConnectionEntity(
                nickname = nickname,
                address = "$nickname.example.com",
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        repeat(uses) { connections.recordUse(id) }
    }

    @Test
    fun `ohne unbenutzte Verbindung entfaellt die Abkuerzung`() = runTest {
        addConnection("web", uses = 3)
        addConnection("db", uses = 1)

        val state = viewModel.state.first { it.ungrouped.size == 2 }

        assertThat(state.frequentlyUsed).isEmpty()
        assertThat(state.ungrouped.map { it.nickname }).containsExactly("web", "db")
    }

    @Test
    fun `sobald sie etwas abkuerzt erscheint sie`() = runTest {
        addConnection("web", uses = 3)
        addConnection("db", uses = 1)
        addConnection("kalt", uses = 0)

        val state = viewModel.state.first { it.ungrouped.size == 3 }

        assertThat(state.frequentlyUsed.map { it.nickname }).containsExactly("web", "db").inOrder()
        assertThat(state.ungrouped).hasSize(3)
    }

    @Test
    fun `beim Suchen entfaellt sie immer`() = runTest {
        addConnection("web", uses = 3)
        addConnection("db", uses = 1)
        addConnection("kalt", uses = 0)

        viewModel.onQueryChanged("web")
        val state = viewModel.state.first { it.query == "web" && it.ungrouped.size == 1 }

        assertThat(state.frequentlyUsed).isEmpty()
        assertThat(state.ungrouped.single().nickname).isEqualTo("web")
    }

    @Test
    fun `eine nie benutzte Verbindung allein ergibt keine Abkuerzung`() = runTest {
        addConnection("kalt", uses = 0)

        val state = viewModel.state.first { it.ungrouped.size == 1 }

        assertThat(state.frequentlyUsed).isEmpty()
    }
}
