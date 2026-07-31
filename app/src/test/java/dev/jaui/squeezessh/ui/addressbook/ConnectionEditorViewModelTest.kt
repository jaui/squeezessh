package dev.jaui.squeezessh.ui.addressbook

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.IdentityRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wer `root@host` eintippt, hat den Benutzer bereits genannt. Ihn danach noch von Hand als
 * Identität anzulegen ist Doppelarbeit — diese Tests halten fest, dass sie entfällt, und
 * dass dabei kein zweiter Eintrag neben einem vorhandenen gleichen Namens entsteht.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionEditorViewModelTest : DatabaseTestCase() {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var connections: ConnectionRepository
    private lateinit var identities: IdentityRepository

    @Before
    fun createRepositories() {
        connections = ConnectionRepository(db.connectionDao(), time)
        identities = IdentityRepository(db.identityDao(), vault, time)
    }

    private fun editor(prefill: String? = null) = ConnectionEditorViewModel(
        SavedStateHandle(mapOf("connectionId" to ConnectionEditorViewModel.NEW).let {
            if (prefill == null) it else it + ("prefill" to prefill)
        }),
        connections,
        ConnectionGroupRepository(db.connectionGroupDao(), time),
        identities,
    )

    @Test
    fun `Benutzer aus der Adresse wird als Identitaet angelegt und verknuepft`() = runTest {
        val viewModel = editor()
        viewModel.state.first { it.availableIdentities.isEmpty() }
        viewModel.onAddressChanged("root@10.0.2.2:2222")

        assertThat(viewModel.save()).isTrue()

        val created = identities.observeAll().first()
        assertThat(created.map { it.username }).containsExactly("root")

        val connection = connections.observeAll().first().single()
        assertThat(connections.identitiesFor(connection.id).map { it.username })
            .containsExactly("root")
    }

    @Test
    fun `angelegte Identitaet traegt kein Geheimnis`() = runTest {
        val viewModel = editor()
        viewModel.state.first { it.availableIdentities.isEmpty() }
        viewModel.onAddressChanged("deploy@web.example.com")

        assertThat(viewModel.save()).isTrue()

        val identity = identities.observeAll().first().single()
        // Das Passwort kommt beim ersten Anmelden — hier darf noch nichts stehen, sonst
        // waere ein leeres Geheimnis gespeichert und die Rueckfrage bliebe aus.
        assertThat(identities.revealPassword(identity)).isNull()
        assertThat(identities.revealPrivateKey(identity)).isNull()
    }

    @Test
    fun `vorhandener Benutzer wird wiederverwendet statt verdoppelt`() = runTest {
        val existingId = identities.create(nickname = "Prod", username = "root")

        val viewModel = editor()
        viewModel.state.first { it.availableIdentities.isNotEmpty() }
        viewModel.onAddressChanged("root@db.example.com")

        assertThat(viewModel.save()).isTrue()

        assertThat(identities.observeAll().first().map { it.id }).containsExactly(existingId)
        val connection = connections.observeAll().first().single()
        assertThat(connections.identitiesFor(connection.id).map { it.id })
            .containsExactly(existingId)
    }

    @Test
    fun `bereits ausgewaehlte Identitaet wird nicht doppelt verknuepft`() = runTest {
        val existingId = identities.create(nickname = null, username = "root")

        val viewModel = editor()
        viewModel.state.first { it.availableIdentities.isNotEmpty() }
        viewModel.onAddressChanged("root@db.example.com")
        viewModel.onIdentityToggled(existingId)

        assertThat(viewModel.save()).isTrue()

        val connection = connections.observeAll().first().single()
        assertThat(connections.identitiesFor(connection.id).map { it.id })
            .containsExactly(existingId)
    }

    @Test
    fun `ohne Benutzer in der Adresse entsteht keine Identitaet`() = runTest {
        val viewModel = editor()
        viewModel.state.first { it.availableIdentities.isEmpty() }
        viewModel.onAddressChanged("10.0.2.2:2222")

        assertThat(viewModel.save()).isTrue()

        assertThat(identities.observeAll().first()).isEmpty()
    }

    @Test
    fun `Benutzer aus einem ssh-Link wird ebenso angelegt`() = runTest {
        val viewModel = editor(prefill = "ssh://ops@bastion.example.com:2200")
        viewModel.state.first { it.parsed != null }

        assertThat(viewModel.save()).isTrue()

        assertThat(identities.observeAll().first().map { it.username }).containsExactly("ops")
    }
}
