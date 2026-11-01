package dev.jaui.squeezessh.pluginapi

import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.model.PortForwardMode
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.PortForwardRepository
import dev.jaui.squeezessh.data.repository.SnippetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Prüft die Plugin-API gegen ihren veröffentlichten Vertrag.
 *
 * Die Spaltennamen und Zahlwerte stehen hier absichtlich als Literale und nicht über
 * [PluginContract]: der Test soll fehlschlagen, wenn jemand den Vertrag ändert. Ein Plugin
 * draußen wurde gegen genau diese Namen gebaut und lässt sich nicht mitändern.
 */
@RunWith(RobolectricTestRunner::class)
class PluginApiGatewayTest : DatabaseTestCase() {

    private lateinit var connections: ConnectionRepository
    private lateinit var groups: ConnectionGroupRepository
    private lateinit var portForwards: PortForwardRepository
    private lateinit var snippets: SnippetRepository
    private lateinit var auditLog: PluginAuditLog
    private lateinit var gateway: PluginApiGateway

    private val caller = "com.example.plugin"

    @Before
    fun createGateway() {
        connections = ConnectionRepository(db.connectionDao(), time)
        groups = ConnectionGroupRepository(db.connectionGroupDao(), time)
        portForwards = PortForwardRepository(db.portForwardDao(), time)
        snippets = SnippetRepository(db.snippetDao(), time)
        auditLog = PluginAuditLog(db.pluginLogDao(), time)
        gateway = PluginApiGateway(connections, groups, portForwards, snippets, auditLog)
    }

    private fun uri(path: String): Uri =
        Uri.parse("content://${PluginContract.AUTHORITY}/$path")

    private fun Cursor.rows(): List<Map<String, String?>> = use {
        buildList {
            while (moveToNext()) {
                add((0 until columnCount).associate { getColumnName(it) to getString(it) })
            }
        }
    }

    // --- Lesen --------------------------------------------------------------------------

    @Test
    fun `Verbindungen kommen mit den Spalten des Vertrags`() = runTest {
        connections.create(
            ConnectionEntity(
                nickname = "Web",
                address = "web.example.com",
                port = 2222,
                createdAt = 0,
                updatedAt = 0,
            ),
        )

        val row = gateway.query(uri("connections"), caller)!!.rows().single()

        assertThat(row.keys).containsExactly(
            "id", "_id", "modified", "name", "address", "port", "nickname", "type",
        )
        assertThat(row["address"]).isEqualTo("web.example.com")
        assertThat(row["port"]).isEqualTo("2222")
        assertThat(row["nickname"]).isEqualTo("Web")
        assertThat(row["type"]).isEqualTo("0")
    }

    @Test
    fun `id ist die UUID und nicht die Zeilennummer`() = runTest {
        val rowId = connections.create(
            ConnectionEntity(nickname = null, address = "host", createdAt = 0, updatedAt = 0),
        )
        val stored = connections.findById(rowId)!!

        val row = gateway.query(uri("connections"), caller)!!.rows().single()

        assertThat(row["id"]).isEqualTo(stored.publicId)
        assertThat(row["id"]).isNotEqualTo(rowId.toString())
        // `_id` traegt weiterhin die Zeilennummer, die Cursor-Adapter erwarten.
        assertThat(row["_id"]).isEqualTo(rowId.toString())
    }

    @Test
    fun `name faellt auf die Adresse zurueck wenn kein Spitzname gesetzt ist`() = runTest {
        connections.create(
            ConnectionEntity(nickname = null, address = "nur.adresse", createdAt = 0, updatedAt = 0),
        )

        val row = gateway.query(uri("connections"), caller)!!.rows().single()

        assertThat(row["name"]).isEqualTo("nur.adresse")
        assertThat(row["nickname"]).isNull()
    }

    @Test
    fun `Verbindungstypen behalten die Zahlwerte von JuiceSSH`() = runTest {
        for ((type, expected) in listOf(
            ConnectionType.SSH to "0",
            ConnectionType.MOSH to "1",
            ConnectionType.LOCAL to "2",
            ConnectionType.TELNET to "3",
        )) {
            val id = connections.create(
                ConnectionEntity(
                    nickname = type.name,
                    address = "host-${type.name}",
                    type = type,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
            val publicId = connections.findById(id)!!.publicId

            val row = gateway.query(uri("connections/$publicId"), caller)!!.rows().single()
            assertThat(row["type"]).isEqualTo(expected)
        }
    }

    @Test
    fun `Weiterleitungsmodi behalten die Zahlwerte von JuiceSSH`() = runTest {
        val connectionId = connections.create(
            ConnectionEntity(nickname = null, address = "host", createdAt = 0, updatedAt = 0),
        )
        for ((mode, expected) in listOf(
            PortForwardMode.LOCAL to "0",
            PortForwardMode.REMOTE to "1",
            PortForwardMode.DYNAMIC to "2",
        )) {
            portForwards.create(
                PortForwardEntity(
                    name = mode.name,
                    connectionId = connectionId,
                    mode = mode,
                    localPort = 8080,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
        }

        val rows = gateway.query(uri("portforwards"), caller)!!.rows()

        assertThat(rows.map { it["name"] to it["mode"] })
            .containsExactly("LOCAL" to "0", "REMOTE" to "1", "DYNAMIC" to "2")
    }

    @Test
    fun `eine Weiterleitung verweist auf die UUID ihrer Verbindung`() = runTest {
        val connectionId = connections.create(
            ConnectionEntity(nickname = null, address = "host", createdAt = 0, updatedAt = 0),
        )
        portForwards.create(
            PortForwardEntity(
                name = "web",
                connectionId = connectionId,
                mode = PortForwardMode.LOCAL,
                localPort = 8080,
                createdAt = 0,
                updatedAt = 0,
            ),
        )

        val row = gateway.query(uri("portforwards"), caller)!!.rows().single()

        assertThat(row["connection_id"]).isEqualTo(connections.findById(connectionId)!!.publicId)
    }

    @Test
    fun `Gruppenzuordnungen entstehen aus der primaeren Gruppe`() = runTest {
        val groupId = groups.create(ConnectionGroupEntity(name = "Prod", createdAt = 0, updatedAt = 0))
        val connectionId = connections.create(
            ConnectionEntity(
                nickname = null,
                address = "host",
                groupId = groupId,
                createdAt = 0,
                updatedAt = 0,
            ),
        )

        val row = gateway.query(uri("connectiongroupmemberships"), caller)!!.rows().single()

        assertThat(row["group_id"]).isEqualTo(groups.findById(groupId)!!.publicId)
        assertThat(row["connection_id"]).isEqualTo(connections.findById(connectionId)!!.publicId)
    }

    @Test
    fun `eine unbekannte UUID ergibt einen leeren Cursor und keinen Fehler`() = runTest {
        val rows = gateway.query(uri("connections/gibt-es-nicht"), caller)!!.rows()

        assertThat(rows).isEmpty()
    }

    @Test
    fun `ein unbekannter Pfad ergibt null`() = runTest {
        assertThat(gateway.query(uri("identities"), caller)).isNull()
    }

    /** Identitäten und Known Hosts sind nicht Teil der API — dort liegen die Geheimnisse. */
    @Test
    fun `die API bietet keinen Pfad zu Identitaeten oder Known Hosts`() = runTest {
        assertThat(gateway.mimeTypeOf(uri("identities"))).isNull()
        assertThat(gateway.mimeTypeOf(uri("hosts"))).isNull()
        assertThat(gateway.mimeTypeOf(uri("knownhosts"))).isNull()
    }

    // --- Schreiben ----------------------------------------------------------------------

    @Test
    fun `ein Snippet laesst sich anlegen und wiederfinden`() = runTest {
        val values = ContentValues().apply {
            put("name", "Kernel")
            put("content", "uname -sr")
        }

        val created = gateway.insert(uri("snippets"), values, caller)!!

        val row = gateway.query(created, caller)!!.rows().single()
        assertThat(row["name"]).isEqualTo("Kernel")
        assertThat(row["content"]).isEqualTo("uname -sr")
        // Die zurueckgegebene URI muss auf die UUID zeigen, nicht auf die Zeilennummer.
        assertThat(created.lastPathSegment).isEqualTo(row["id"])
    }

    @Test
    fun `eine Verbindung ohne Adresse wird nicht angelegt`() = runTest {
        val values = ContentValues().apply { put("nickname", "ohne Adresse") }

        assertThat(gateway.insert(uri("connections"), values, caller)).isNull()
        assertThat(connections.observeAll().first()).isEmpty()
    }

    @Test
    fun `ein unsinniger Port wird durch den Standard ersetzt`() = runTest {
        val values = ContentValues().apply {
            put("address", "host")
            put("port", 70000)
        }

        val created = gateway.insert(uri("connections"), values, caller)!!

        assertThat(gateway.query(created, caller)!!.rows().single()["port"]).isEqualTo("22")
    }

    @Test
    fun `ein Update laesst nicht genannte Felder unangetastet`() = runTest {
        val id = connections.create(
            ConnectionEntity(
                nickname = "Web",
                address = "web.example.com",
                port = 2222,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        val publicId = connections.findById(id)!!.publicId

        val changed = gateway.update(
            uri("connections/$publicId"),
            ContentValues().apply { put("address", "neu.example.com") },
            caller,
        )

        assertThat(changed).isEqualTo(1)
        val stored = connections.findById(id)!!
        assertThat(stored.address).isEqualTo("neu.example.com")
        assertThat(stored.nickname).isEqualTo("Web")
        assertThat(stored.port).isEqualTo(2222)
    }

    @Test
    fun `ein Update ohne Kennung im Pfad aendert nichts`() = runTest {
        connections.create(
            ConnectionEntity(nickname = "Web", address = "web", createdAt = 0, updatedAt = 0),
        )

        val changed = gateway.update(
            uri("connections"),
            ContentValues().apply { put("address", "gekapert.example.com") },
            caller,
        )

        assertThat(changed).isEqualTo(0)
        assertThat(connections.observeAll().first().single().address).isEqualTo("web")
    }

    @Test
    fun `ein Snippet laesst sich ueber seine UUID loeschen`() = runTest {
        val created = gateway.insert(
            uri("snippets"),
            ContentValues().apply {
                put("name", "weg")
                put("content", "true")
            },
            caller,
        )!!

        assertThat(gateway.delete(created, caller)).isEqualTo(1)
        assertThat(snippets.observeAll().first()).isEmpty()
    }

    @Test
    fun `der Pruefpfad laesst sich nicht beschreiben`() = runTest {
        assertThrows(UnsupportedOperationException::class.java) {
            kotlinx.coroutines.runBlocking {
                gateway.insert(uri("pluginlog"), ContentValues(), caller)
            }
        }
    }

    // --- Prüfpfad -----------------------------------------------------------------------

    @Test
    fun `jeder Zugriff landet mit Paketnamen im Pruefpfad`() = runTest {
        gateway.query(uri("connections"), caller)
        gateway.insert(
            uri("snippets"),
            ContentValues().apply {
                put("name", "x")
                put("content", "y")
            },
            caller,
        )

        val entries = auditLog.observeRecent().first()

        assertThat(entries.map { it.packageName }.toSet()).containsExactly(caller)
        assertThat(entries.map { it.message })
            .containsExactly("hat Verbindungen gelesen", "hat Snippets angelegt")
    }

    @Test
    fun `ein abgewiesener Schreibversuch wird als solcher vermerkt`() = runTest {
        gateway.insert(uri("connections"), ContentValues(), caller)

        assertThat(auditLog.observeRecent().first().single().message)
            .isEqualTo("hat Verbindungen angelegt — abgewiesen")
    }

    @Test
    fun `das Lesen des Pruefpfads erzeugt keinen neuen Eintrag`() = runTest {
        auditLog.record(caller, "etwas")

        gateway.query(uri("pluginlog"), caller)
        gateway.query(uri("pluginlog"), caller)

        assertThat(auditLog.observeRecent().first()).hasSize(1)
    }

    // --- Pfade und Rechte ---------------------------------------------------------------

    @Test
    fun `ein leeres Pfadsegment trifft kein Modell`() = runTest {
        connections.create(
            ConnectionEntity(nickname = null, address = "geheim", createdAt = 0, updatedAt = 0),
        )

        assertThat(gateway.query(uri("/connections"), caller)).isNull()
        assertThat(gateway.query(uri("connections/"), caller)).isNull()
        assertThat(gateway.mimeTypeOf(uri("/connections"))).isNull()
        assertThat(gateway.update(uri("/connections/x"), ContentValues(), caller)).isEqualTo(0)
    }

    @Test
    fun `jedes Modell verlangt seine eigene Permission`() {
        val auth = PluginContract.AUTHORITY
        assertThat(gateway.requiredPermission(uri("connections"), write = false))
            .isEqualTo("$auth.permission.READ_CONNECTIONS")
        assertThat(gateway.requiredPermission(uri("connections/x"), write = true))
            .isEqualTo("$auth.permission.WRITE_CONNECTIONS")
        assertThat(gateway.requiredPermission(uri("connectiongroupmemberships"), write = true))
            .isEqualTo("$auth.permission.WRITE_CONNECTION_GROUPS")
        assertThat(gateway.requiredPermission(uri("portforwards"), write = false))
            .isEqualTo("$auth.permission.READ_PORT_FORWARDS")
        assertThat(gateway.requiredPermission(uri("snippets/x"), write = false))
            .isEqualTo("$auth.permission.READ_SNIPPETS")
        assertThat(gateway.requiredPermission(uri("pluginlog"), write = false))
            .isEqualTo("$auth.permission.READ_PLUGIN_LOG")
    }

    @Test
    fun `ohne passenden Pfad gibt es keine Permission und damit keinen Zugriff`() {
        assertThat(gateway.requiredPermission(uri("/connections"), write = false)).isNull()
        assertThat(gateway.requiredPermission(uri("snippets/"), write = true)).isNull()
        assertThat(gateway.requiredPermission(uri("unbekannt"), write = false)).isNull()
        assertThat(gateway.requiredPermission(uri("pluginlog"), write = true)).isNull()
    }
}
