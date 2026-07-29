package dev.jaui.squeezessh.data.importer

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.model.BackspaceMode
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.data.model.PortForwardMode
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.IdentityRepository
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import dev.jaui.squeezessh.data.repository.PortForwardRepository
import dev.jaui.squeezessh.data.repository.SnippetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class JuiceSshImporterTest : DatabaseTestCase() {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var connections: ConnectionRepository
    private lateinit var groups: ConnectionGroupRepository
    private lateinit var identities: IdentityRepository
    private lateinit var snippets: SnippetRepository
    private lateinit var portForwards: PortForwardRepository
    private lateinit var knownHosts: KnownHostRepository
    private lateinit var importer: JuiceSshImporter

    @Before
    fun createImporter() {
        connections = ConnectionRepository(db.connectionDao(), time)
        groups = ConnectionGroupRepository(db.connectionGroupDao(), time)
        identities = IdentityRepository(db.identityDao(), vault, time)
        snippets = SnippetRepository(db.snippetDao(), time)
        portForwards = PortForwardRepository(db.portForwardDao(), time)
        knownHosts = KnownHostRepository(db.knownHostDao(), time)
        importer = JuiceSshImporter(
            connections = connections,
            connectionDao = db.connectionDao(),
            groups = groups,
            identities = identities,
            snippets = snippets,
            portForwards = portForwards,
            knownHosts = knownHosts,
            io = Dispatchers.Unconfined,
        )
    }

    private fun realBackup(withPreferences: Boolean = true) = JuiceSshBackup(
        databaseFile = JuiceSshFixtures.realDatabase(temp.newFile("juicessh.db")),
        preferencesFile = if (withPreferences) {
            JuiceSshFixtures.realPreferences(temp.newFile("prefs.xml"))
        } else {
            null
        },
    )

    // --- Gegen die echte, auf dem Emulator erzeugte Datenbank -------------------------

    @Test
    fun `Vorschau meldet den Inhalt der echten Datenbank`() = runTest {
        val preview = importer.preview(realBackup())

        assertThat(preview.connections).isEqualTo(2)
        assertThat(preview.groups).isEqualTo(1)
        assertThat(preview.identities).isEqualTo(1)
        assertThat(preview.identitiesWithSecrets).isEqualTo(1)
        assertThat(preview.secretsReadable).isTrue()
        assertThat(preview.accountName).isEqualTo("New User")
        assertThat(preview.isEmpty).isFalse()
    }

    @Test
    fun `Vorschau schreibt nichts`() = runTest {
        importer.preview(realBackup())

        assertThat(connections.observeAll().first()).isEmpty()
        assertThat(identities.observeAll().first()).isEmpty()
    }

    /**
     * Der eigentliche Nachweis: die Entschluesselungskette
     * Preferences → Master-Key → Feld laeuft bis zum Klartext durch.
     */
    @Test
    fun `Passwort kommt entschluesselt an und liegt danach im eigenen Vault`() = runTest {
        importer.import(realBackup())

        val identity = identities.observeAll().first().single()
        assertThat(identity.username).isEqualTo("root")
        assertThat(identity.nickname).isEqualTo("Prod-Root")
        assertThat(identities.revealPassword(identity)).isEqualTo("MarkerPass123")
        // Nicht mehr im JuiceSSH-Format, sondern im eigenen.
        assertThat(identity.passwordCipher).doesNotContain("#")
        assertThat(identity.passwordCipher).startsWith("sealed:")
    }

    @Test
    fun `Verbindungen kommen mit Typ Port und Backspace-Modus an`() = runTest {
        importer.import(realBackup())

        val all = connections.observeAll().first()
        assertThat(all).hasSize(2)

        val web = all.single { it.nickname == "Web-Produktion" }
        assertThat(web.address).isEqualTo("web.example.com")
        assertThat(web.port).isEqualTo(22)
        assertThat(web.type).isEqualTo(ConnectionType.SSH)
        assertThat(web.backspaceMode).isEqualTo(BackspaceMode.DELETE)

        val database = all.single { it.nickname == "DB-Intern" }
        assertThat(database.address).isEqualTo("db.internal")
        assertThat(database.type).isEqualTo(ConnectionType.TELNET)
        assertThat(database.backspaceMode).isEqualTo(BackspaceMode.BACKSPACE)
    }

    @Test
    fun `Gruppenzugehoerigkeit aus der Join-Tabelle kommt mit`() = runTest {
        importer.import(realBackup())

        val group = groups.observeAll().first().single()
        assertThat(group.name).isEqualTo("Produktion")

        val database = connections.observeAll().first().single { it.nickname == "DB-Intern" }
        assertThat(database.groupId).isEqualTo(group.id)
        assertThat(connections.observeByGroup(group.id).first().map { it.nickname })
            .containsExactly("DB-Intern")
    }

    @Test
    fun `Verknuepfung zwischen Verbindung und Identitaet bleibt erhalten`() = runTest {
        importer.import(realBackup())

        val web = connections.observeAll().first().single { it.nickname == "Web-Produktion" }
        assertThat(connections.identitiesFor(web.id).map { it.username }).containsExactly("root")
    }

    @Test
    fun `ohne Preferences laeuft der Import weiter und warnt statt abzubrechen`() = runTest {
        val report = importer.import(realBackup(withPreferences = false))

        assertThat(report.importedConnections).isEqualTo(2)
        assertThat(report.importedIdentities).isEqualTo(1)
        assertThat(report.warnings).isNotEmpty()

        val identity = identities.observeAll().first().single()
        assertThat(identity.username).isEqualTo("root")
        assertThat(identity.passwordCipher).isNull()
    }

    @Test
    fun `vorhandene Gruppe wird nicht dupliziert`() = runTest {
        groups.findOrCreate("Produktion")

        val report = importer.import(realBackup())

        assertThat(groups.observeAll().first()).hasSize(1)
        assertThat(report.skippedDuplicates).isEqualTo(1)
        assertThat(report.importedGroups).isEqualTo(0)
    }

    @Test
    fun `Fremdformat wird abgewiesen statt halb importiert`() = runTest {
        val notJuiceSsh = temp.newFile("fremd.db").apply { writeText("kein SQLite") }

        val error = runCatching {
            importer.preview(JuiceSshBackup(notJuiceSsh))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(JuiceSshFormatException::class.java)
    }

    // --- Synthetische Faelle, die die Free-Version nicht erzeugen kann ---------------

    @Test
    fun `Port-Forwards Snippets und Known Hosts kommen an`() = runTest {
        val file = temp.newFile("synthetisch.db")
        JuiceSshFixtures.emptyDatabase(file).use { source ->
            source.execSQL(
                "INSERT INTO connection (id, nickname, address, port, type, backspaceMode) " +
                    "VALUES ('c1', 'Bastion', 'bastion.example.com', 22, 0, 0)",
            )
            source.execSQL("INSERT INTO snippet (id, name, content, count) VALUES ('s1', 'df', 'df -h', 4)")
            source.execSQL(
                "INSERT INTO portforward (id, name, mode, connection_id, host, localPort, " +
                    "remotePort, openInBrowser, useAsProxy) " +
                    "VALUES ('p1', 'Grafana', 0, 'c1', 'grafana.internal', 3000, 3000, 1, 0)",
            )
            source.execSQL(
                "INSERT INTO portforward (id, name, mode, connection_id, localPort, useAsProxy) " +
                    "VALUES ('p2', 'SOCKS', 2, 'c1', 1080, 1)",
            )
            source.execSQL(
                "INSERT INTO host (id, hostname, fingerprint, key) VALUES " +
                    "('h1', '[bastion.example.com]:2222', 'SHA256:abc', " +
                    "'AAAAC3NzaC1lZDI1NTE5AAAAIHxKQhTBpVCK+7Ax0mm3aVGxLg1U1KDhVBUw6UBW1p9n')",
            )
        }

        val report = importer.import(JuiceSshBackup(file))

        assertThat(report.importedSnippets).isEqualTo(1)
        assertThat(report.importedPortForwards).isEqualTo(2)
        assertThat(report.importedKnownHosts).isEqualTo(1)

        val forwards = portForwards.observeAll().first()
        val local = forwards.single { it.name == "Grafana" }
        assertThat(local.mode).isEqualTo(PortForwardMode.LOCAL)
        assertThat(local.targetHost).isEqualTo("grafana.internal")
        assertThat(local.openInBrowser).isTrue()
        assertThat(forwards.single { it.name == "SOCKS" }.mode).isEqualTo(PortForwardMode.DYNAMIC)

        val host = knownHosts.observeAll().first().single()
        assertThat(host.hostname).isEqualTo("bastion.example.com")
        assertThat(host.port).isEqualTo(2222)
        assertThat(host.keyType).isEqualTo(HostKeyType.SSH_ED25519)
    }

    @Test
    fun `Sprung-Kette wird nach dem Anlegen korrekt verdrahtet`() = runTest {
        val file = temp.newFile("kette.db")
        JuiceSshFixtures.emptyDatabase(file).use { source ->
            // Das Ziel steht absichtlich vor dem Bastion-Host: beim Anlegen zeigt via_id
            // damit auf etwas, das es noch nicht gibt.
            source.execSQL(
                "INSERT INTO connection (id, nickname, address, port, type, via_id) " +
                    "VALUES ('c2', 'Datenbank', 'db.internal', 22, 0, 'c1')",
            )
            source.execSQL(
                "INSERT INTO connection (id, nickname, address, port, type) " +
                    "VALUES ('c1', 'Bastion', 'bastion.example.com', 22, 0)",
            )
        }

        importer.import(JuiceSshBackup(file))

        val database = connections.observeAll().first().single { it.nickname == "Datenbank" }
        assertThat(connections.resolveChain(database.id).map { it.nickname })
            .containsExactly("Bastion", "Datenbank").inOrder()
    }

    @Test
    fun `Nutzungszaehler wird zur Verbindung uebernommen`() = runTest {
        val file = temp.newFile("zaehler.db")
        JuiceSshFixtures.emptyDatabase(file).use { source ->
            source.execSQL(
                "INSERT INTO connection (id, nickname, address, port, type) " +
                    "VALUES ('c1', 'Oft', 'oft.example.com', 22, 0)",
            )
            source.execSQL(
                "INSERT INTO connectioncounter (id, connection_id, count) VALUES ('cc1', 'c1', 17)",
            )
        }

        importer.import(JuiceSshBackup(file))

        assertThat(connections.observeAll().first().single().useCount).isEqualTo(17)
    }

    @Test
    fun `verwaister Port-Forward wird uebersprungen und gemeldet`() = runTest {
        val file = temp.newFile("verwaist.db")
        JuiceSshFixtures.emptyDatabase(file).use { source ->
            source.execSQL(
                "INSERT INTO portforward (id, name, mode, connection_id, localPort) " +
                    "VALUES ('p1', 'Waise', 0, 'gibtsnicht', 8080)",
            )
        }

        val report = importer.import(JuiceSshBackup(file))

        assertThat(report.importedPortForwards).isEqualTo(0)
        assertThat(report.warnings.any { "Waise" in it }).isTrue()
    }

    @Test
    fun `Preferences ohne Master-Key gelten als nicht lesbar`() = runTest {
        val file = temp.newFile("leer.db")
        JuiceSshFixtures.emptyDatabase(file).close()
        val prefs = JuiceSshFixtures.preferencesWithoutMasterKey(temp.newFile("prefs.xml"))

        val preview = importer.preview(JuiceSshBackup(file, prefs))

        assertThat(preview.secretsReadable).isFalse()
        assertThat(preview.isEmpty).isTrue()
    }
}
