package dev.jaui.squeezessh.data

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.IdentityRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectionRepositoryTest : DatabaseTestCase() {

    private lateinit var connections: ConnectionRepository
    private lateinit var groups: ConnectionGroupRepository
    private lateinit var identities: IdentityRepository

    @Before
    fun createRepositories() {
        connections = ConnectionRepository(db.connectionDao(), time)
        groups = ConnectionGroupRepository(db.connectionGroupDao(), time)
        identities = IdentityRepository(db.identityDao(), vault, time)
    }

    private fun connection(nickname: String?, address: String, via: Long? = null) =
        ConnectionEntity(
            nickname = nickname,
            address = address,
            viaConnectionId = via,
            createdAt = 0,
            updatedAt = 0,
        )

    @Test
    fun `create setzt Zeitstempel selbst`() = runTest {
        val id = connections.create(connection("web", "web.example.com"))

        val stored = connections.findById(id)!!
        assertThat(stored.createdAt).isGreaterThan(0)
        assertThat(stored.updatedAt).isEqualTo(stored.createdAt)
    }

    @Test
    fun `update laesst createdAt unveraendert und schiebt updatedAt vor`() = runTest {
        val id = connections.create(connection("web", "web.example.com"))
        val original = connections.findById(id)!!

        connections.update(original.copy(nickname = "web-neu"))

        val updated = connections.findById(id)!!
        assertThat(updated.nickname).isEqualTo("web-neu")
        assertThat(updated.createdAt).isEqualTo(original.createdAt)
        assertThat(updated.updatedAt).isGreaterThan(original.updatedAt)
    }

    @Test
    fun `displayName faellt auf die Adresse zurueck`() = runTest {
        val withNickname = connections.findById(
            connections.create(connection("Produktion", "10.0.0.5")),
        )!!
        val withoutNickname = connections.findById(
            connections.create(connection(null, "10.0.0.6")),
        )!!
        val blankNickname = connections.findById(
            connections.create(connection("   ", "10.0.0.7")),
        )!!

        assertThat(withNickname.displayName).isEqualTo("Produktion")
        assertThat(withoutNickname.displayName).isEqualTo("10.0.0.6")
        assertThat(blankNickname.displayName).isEqualTo("10.0.0.7")
    }

    @Test
    fun `resolveChain liefert Bastion zuerst und Ziel zuletzt`() = runTest {
        val bastionId = connections.create(connection("bastion", "bastion.example.com"))
        val middleId = connections.create(connection("middle", "middle.internal", via = bastionId))
        val targetId = connections.create(connection("db", "db.internal", via = middleId))

        val chain = connections.resolveChain(targetId)

        assertThat(chain.map { it.id }).containsExactly(bastionId, middleId, targetId).inOrder()
    }

    @Test
    fun `resolveChain bricht bei einem Zyklus ab statt endlos zu laufen`() = runTest {
        val firstId = connections.create(connection("a", "a.example.com"))
        val secondId = connections.create(connection("b", "b.example.com", via = firstId))
        // a springt jetzt ueber b — die Kette beisst sich in den Schwanz.
        connections.update(connections.findById(firstId)!!.copy(viaConnectionId = secondId))

        val chain = connections.resolveChain(secondId)

        assertThat(chain.map { it.id }).containsExactly(firstId, secondId).inOrder()
    }

    @Test
    fun `dependentsOf findet Verbindungen die ueber diese springen`() = runTest {
        val bastionId = connections.create(connection("bastion", "bastion.example.com"))
        connections.create(connection("db", "db.internal", via = bastionId))
        connections.create(connection("app", "app.internal", via = bastionId))
        connections.create(connection("extern", "extern.example.com"))

        val dependents = connections.dependentsOf(bastionId)

        assertThat(dependents.map { it.nickname }).containsExactly("db", "app")
    }

    @Test
    fun `frequently used sortiert nach Nutzung und bei Gleichstand nach Zeitpunkt`() = runTest {
        val oftId = connections.create(connection("oft", "oft.example.com"))
        val seltenId = connections.create(connection("selten", "selten.example.com"))
        val nieId = connections.create(connection("nie", "nie.example.com"))

        repeat(3) { connections.recordUse(oftId) }
        connections.recordUse(seltenId)

        val frequent = connections.observeFrequentlyUsed().first()

        assertThat(frequent.map { it.id }).containsExactly(oftId, seltenId).inOrder()
        assertThat(frequent.map { it.id }).doesNotContain(nieId)
    }

    @Test
    fun `search findet ueber Spitzname und Adresse`() = runTest {
        connections.create(connection("Produktion Web", "web.example.com"))
        connections.create(connection("Test", "web-test.example.com"))
        connections.create(connection("Datenbank", "db.internal"))

        assertThat(connections.search("web").first()).hasSize(2)
        assertThat(connections.search("PRODUKTION").first()).hasSize(1)
        assertThat(connections.search("internal").first()).hasSize(1)
    }

    @Test
    fun `setIdentities ersetzt die Zuordnung und behaelt die Reihenfolge`() = runTest {
        val connectionId = connections.create(connection("web", "web.example.com"))
        val rootId = identities.create(nickname = "root", username = "root")
        val deployId = identities.create(nickname = "deploy", username = "deploy")

        connections.setIdentities(connectionId, listOf(deployId, rootId))
        assertThat(connections.identitiesFor(connectionId).map { it.id })
            .containsExactly(deployId, rootId).inOrder()

        connections.setIdentities(connectionId, listOf(rootId))
        assertThat(connections.identitiesFor(connectionId).map { it.id }).containsExactly(rootId)
    }

    @Test
    fun `Loeschen einer Verbindung raeumt ihre Identitaets-Zuordnung mit ab`() = runTest {
        val connectionId = connections.create(connection("web", "web.example.com"))
        val identityId = identities.create(nickname = "root", username = "root")
        connections.setIdentities(connectionId, listOf(identityId))

        connections.delete(connections.findById(connectionId)!!)

        // Die Identitaet selbst bleibt bestehen, nur die Verknuepfung faellt weg.
        assertThat(identities.findById(identityId)).isNotNull()
        assertThat(identities.usageCount(identityId)).isEqualTo(0)
    }

    @Test
    fun `findOrCreate legt eine Gruppe nur einmal an`() = runTest {
        val first = groups.findOrCreate("Produktion")
        val second = groups.findOrCreate("produktion")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(groups.observeAll().first()).hasSize(1)
    }
}
