package dev.jaui.squeezessh.pluginapi

import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.db.entity.SnippetEntity
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.model.PortForwardMode
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.PortForwardRepository
import dev.jaui.squeezessh.data.repository.SnippetRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bildet die Plugin-API auf die Datenschicht ab.
 *
 * Getrennt von [PluginProvider], damit die Abbildung ohne ContentProvider, ohne Binder und
 * ohne Hilt-Aufbau geprüft werden kann — geprüft werden muss vor allem, dass nach außen
 * genau die Spalten und Zahlwerte gehen, die ein bestehendes Plugin erwartet.
 *
 * Nach außen gilt der Vertrag von [PluginContract], nicht das interne Schema: die Zeilen-ID
 * bleibt drinnen, adressiert wird über UUIDs.
 */
@Singleton
class PluginApiGateway @Inject constructor(
    private val connections: ConnectionRepository,
    private val groups: ConnectionGroupRepository,
    private val portForwards: PortForwardRepository,
    private val snippets: SnippetRepository,
    private val auditLog: PluginAuditLog,
) {

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        val authority = PluginContract.AUTHORITY
        addURI(authority, PluginContract.Connections.PATH, CONNECTIONS)
        addURI(authority, "${PluginContract.Connections.PATH}/*", CONNECTION_ITEM)
        addURI(authority, PluginContract.ConnectionGroups.PATH, GROUPS)
        addURI(authority, "${PluginContract.ConnectionGroups.PATH}/*", GROUP_ITEM)
        addURI(authority, PluginContract.ConnectionGroupMemberships.PATH, MEMBERSHIPS)
        addURI(authority, PluginContract.PortForwards.PATH, PORT_FORWARDS)
        addURI(authority, "${PluginContract.PortForwards.PATH}/*", PORT_FORWARD_ITEM)
        addURI(authority, PluginContract.Snippets.PATH, SNIPPETS)
        addURI(authority, "${PluginContract.Snippets.PATH}/*", SNIPPET_ITEM)
        addURI(authority, PluginContract.PluginLog.PATH, PLUGIN_LOG)
    }

    /**
     * Wie [UriMatcher.match], aber nur für Pfade in genau der Schreibweise, die der Vertrag
     * kennt. `UriMatcher` überspringt leere Segmente, Androids `path-permission`-Prüfung
     * vergleicht den Pfad dagegen wörtlich: `//connections` passte auf keine Regel im
     * Manifest und landete hier trotzdem bei den Verbindungen. Siehe [isCanonicalApiPath].
     */
    private fun match(uri: Uri): Int =
        if (isCanonicalApiPath(uri.encodedPath)) matcher.match(uri) else UriMatcher.NO_MATCH

    /**
     * Die Permission, die ein Zugriff auf [uri] verlangt — lesend oder schreibend.
     *
     * `null` heißt: kein Zugriff, für niemanden. Das gilt für Pfade außerhalb des Vertrags
     * und für das Schreiben in den Prüfpfad.
     */
    fun requiredPermission(uri: Uri, write: Boolean): String? = when (match(uri)) {
        CONNECTIONS, CONNECTION_ITEM -> PluginContract.Connections.run {
            if (write) PERMISSION_WRITE else PERMISSION_READ
        }
        GROUPS, GROUP_ITEM -> PluginContract.ConnectionGroups.run {
            if (write) PERMISSION_WRITE else PERMISSION_READ
        }
        MEMBERSHIPS -> PluginContract.ConnectionGroupMemberships.run {
            if (write) PERMISSION_WRITE else PERMISSION_READ
        }
        PORT_FORWARDS, PORT_FORWARD_ITEM -> PluginContract.PortForwards.run {
            if (write) PERMISSION_WRITE else PERMISSION_READ
        }
        SNIPPETS, SNIPPET_ITEM -> PluginContract.Snippets.run {
            if (write) PERMISSION_WRITE else PERMISSION_READ
        }
        PLUGIN_LOG -> if (write) null else PluginContract.PluginLog.PERMISSION_READ
        else -> null
    }

    fun mimeTypeOf(uri: Uri): String? {
        val match = match(uri)
        val model = modelOf(match) ?: return null
        return if (isItem(match)) {
            PluginContract.itemMimeType(model)
        } else {
            PluginContract.dirMimeType(model)
        }
    }

    // --- Lesen --------------------------------------------------------------------------

    suspend fun query(uri: Uri, caller: String): Cursor? {
        val match = match(uri)
        if (match == UriMatcher.NO_MATCH) return null

        val cursor = when (match) {
            CONNECTIONS -> connectionsCursor(connections.observeAll().first())
            CONNECTION_ITEM ->
                connectionsCursor(listOfNotNull(connections.findByPublicId(uri.lastPathSegment)))
            GROUPS -> groupsCursor(groups.observeAll().first())
            GROUP_ITEM -> groupsCursor(listOfNotNull(groups.findByPublicId(uri.lastPathSegment)))
            MEMBERSHIPS -> membershipsCursor()
            PORT_FORWARDS -> portForwardsCursor(portForwards.observeAll().first())
            PORT_FORWARD_ITEM ->
                portForwardsCursor(listOfNotNull(portForwards.findByPublicId(uri.lastPathSegment)))
            SNIPPETS -> snippetsCursor(snippets.observeAll().first())
            SNIPPET_ITEM ->
                snippetsCursor(listOfNotNull(snippets.findByPublicId(uri.lastPathSegment)))
            PLUGIN_LOG -> pluginLogCursor()
            else -> null
        }
        // Das Lesen des Prüfpfads selbst wird nicht vermerkt — sonst wächst er beim Hinsehen.
        if (match != PLUGIN_LOG) {
            auditLog.record(caller, "hat ${describe(match)} gelesen")
        }
        return cursor
    }

    // --- Schreiben ----------------------------------------------------------------------

    suspend fun insert(uri: Uri, values: ContentValues?, caller: String): Uri? {
        val match = match(uri)
        if (match == PLUGIN_LOG) refuseAuditWrite()
        val content = values ?: return null

        val created = when (match) {
            CONNECTIONS -> insertConnection(content)
            GROUPS -> insertGroup(content)
            PORT_FORWARDS -> insertPortForward(content)
            SNIPPETS -> insertSnippet(content)
            MEMBERSHIPS -> insertMembership(content)
            else -> null
        }
        audit(caller, "hat ${describe(match)} angelegt", created != null)
        return created
    }

    /**
     * Geändert wird nur über eine Kennung im Pfad. Ein `selection` wird bewusst ignoriert:
     * die Außenwelt kennt das interne Schema nicht, und eine frei durchgereichte
     * WHERE-Klausel wäre eine Einladung.
     */
    suspend fun update(uri: Uri, values: ContentValues?, caller: String): Int {
        val match = match(uri)
        if (match == PLUGIN_LOG) refuseAuditWrite()
        val content = values ?: return 0

        val changed = when (match) {
            CONNECTION_ITEM -> updateConnection(uri.lastPathSegment, content)
            GROUP_ITEM -> updateGroup(uri.lastPathSegment, content)
            PORT_FORWARD_ITEM -> updatePortForward(uri.lastPathSegment, content)
            SNIPPET_ITEM -> updateSnippet(uri.lastPathSegment, content)
            else -> 0
        }
        audit(caller, "hat ${describe(match)} geändert", changed > 0)
        return changed
    }

    suspend fun delete(uri: Uri, caller: String): Int {
        val match = match(uri)
        if (match == PLUGIN_LOG) refuseAuditWrite()

        val removed = when (match) {
            CONNECTION_ITEM -> connections.findByPublicId(uri.lastPathSegment)
                ?.let { connections.delete(it); 1 } ?: 0
            GROUP_ITEM -> groups.findByPublicId(uri.lastPathSegment)
                ?.let { groups.delete(it); 1 } ?: 0
            PORT_FORWARD_ITEM -> portForwards.findByPublicId(uri.lastPathSegment)
                ?.let { portForwards.delete(it); 1 } ?: 0
            SNIPPET_ITEM -> snippets.findByPublicId(uri.lastPathSegment)
                ?.let { snippets.delete(it); 1 } ?: 0
            else -> 0
        }
        audit(caller, "hat ${describe(match)} gelöscht", removed > 0)
        return removed
    }

    private fun refuseAuditWrite(): Nothing =
        throw UnsupportedOperationException("Der Prüfpfad ist schreibgeschützt")

    private suspend fun insertConnection(values: ContentValues): Uri? {
        val address = values.getAsString(PluginContract.Connections.COLUMN_ADDRESS)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val entity = ConnectionEntity(
            nickname = values.getAsString(PluginContract.Connections.COLUMN_NICKNAME),
            address = address,
            port = values.getAsInteger(PluginContract.Connections.COLUMN_PORT)
                ?.takeIf { it in 1..PORT_MAX }
                ?: ConnectionEntity.DEFAULT_SSH_PORT,
            type = connectionTypeOf(values.getAsInteger(PluginContract.Connections.COLUMN_TYPE)),
            createdAt = 0,
            updatedAt = 0,
        )
        connections.create(entity)
        return itemUri(PluginContract.Connections.PATH, entity.publicId)
    }

    private suspend fun updateConnection(publicId: String?, values: ContentValues): Int {
        val existing = connections.findByPublicId(publicId) ?: return 0
        connections.update(
            existing.copy(
                nickname = if (values.containsKey(PluginContract.Connections.COLUMN_NICKNAME)) {
                    values.getAsString(PluginContract.Connections.COLUMN_NICKNAME)
                } else {
                    existing.nickname
                },
                address = values.getAsString(PluginContract.Connections.COLUMN_ADDRESS)
                    ?.takeIf { it.isNotBlank() } ?: existing.address,
                port = values.getAsInteger(PluginContract.Connections.COLUMN_PORT)
                    ?.takeIf { it in 1..PORT_MAX } ?: existing.port,
                type = values.getAsInteger(PluginContract.Connections.COLUMN_TYPE)
                    ?.let(::connectionTypeOf) ?: existing.type,
            ),
        )
        return 1
    }

    private suspend fun insertGroup(values: ContentValues): Uri? {
        val name = values.getAsString(PluginContract.ConnectionGroups.COLUMN_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val entity = ConnectionGroupEntity(name = name, createdAt = 0, updatedAt = 0)
        groups.create(entity)
        return itemUri(PluginContract.ConnectionGroups.PATH, entity.publicId)
    }

    private suspend fun updateGroup(publicId: String?, values: ContentValues): Int {
        val existing = groups.findByPublicId(publicId) ?: return 0
        val name = values.getAsString(PluginContract.ConnectionGroups.COLUMN_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return 0
        groups.update(existing.copy(name = name))
        return 1
    }

    private suspend fun insertPortForward(values: ContentValues): Uri? {
        val connection = connections.findByPublicId(
            values.getAsString(PluginContract.PortForwards.COLUMN_CONNECTION_ID),
        ) ?: return null
        val entity = PortForwardEntity(
            name = values.getAsString(PluginContract.PortForwards.COLUMN_NAME),
            connectionId = connection.id,
            mode = portForwardModeOf(values.getAsInteger(PluginContract.PortForwards.COLUMN_MODE)),
            localPort = values.getAsInteger(PluginContract.PortForwards.COLUMN_LOCAL_PORT) ?: 0,
            targetHost = values.getAsString(PluginContract.PortForwards.COLUMN_HOST),
            remotePort = values.getAsInteger(PluginContract.PortForwards.COLUMN_REMOTE_PORT),
            openInBrowser =
                values.getAsInteger(PluginContract.PortForwards.COLUMN_OPEN_IN_BROWSER) == 1,
            createdAt = 0,
            updatedAt = 0,
        )
        portForwards.create(entity)
        return itemUri(PluginContract.PortForwards.PATH, entity.publicId)
    }

    private suspend fun updatePortForward(publicId: String?, values: ContentValues): Int {
        val existing = portForwards.findByPublicId(publicId) ?: return 0
        portForwards.update(
            existing.copy(
                name = if (values.containsKey(PluginContract.PortForwards.COLUMN_NAME)) {
                    values.getAsString(PluginContract.PortForwards.COLUMN_NAME)
                } else {
                    existing.name
                },
                mode = values.getAsInteger(PluginContract.PortForwards.COLUMN_MODE)
                    ?.let(::portForwardModeOf) ?: existing.mode,
                localPort = values.getAsInteger(PluginContract.PortForwards.COLUMN_LOCAL_PORT)
                    ?: existing.localPort,
                targetHost = if (values.containsKey(PluginContract.PortForwards.COLUMN_HOST)) {
                    values.getAsString(PluginContract.PortForwards.COLUMN_HOST)
                } else {
                    existing.targetHost
                },
                remotePort = values.getAsInteger(PluginContract.PortForwards.COLUMN_REMOTE_PORT)
                    ?: existing.remotePort,
            ),
        )
        return 1
    }

    private suspend fun insertSnippet(values: ContentValues): Uri? {
        val name = values.getAsString(PluginContract.Snippets.COLUMN_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val content = values.getAsString(PluginContract.Snippets.COLUMN_CONTENT) ?: return null
        val created = snippets.findById(snippets.create(name, content)) ?: return null
        return itemUri(PluginContract.Snippets.PATH, created.publicId)
    }

    private suspend fun updateSnippet(publicId: String?, values: ContentValues): Int {
        val existing = snippets.findByPublicId(publicId) ?: return 0
        snippets.update(
            existing.copy(
                name = values.getAsString(PluginContract.Snippets.COLUMN_NAME)
                    ?.takeIf { it.isNotBlank() } ?: existing.name,
                content = values.getAsString(PluginContract.Snippets.COLUMN_CONTENT)
                    ?: existing.content,
            ),
        )
        return 1
    }

    /**
     * Eine Zuordnung anzulegen heißt hier: die primäre Gruppe der Verbindung setzen. Die
     * Außenwelt sieht nur eine Zuordnungstabelle, so wie bei JuiceSSH.
     */
    private suspend fun insertMembership(values: ContentValues): Uri? {
        val connection = connections.findByPublicId(
            values.getAsString(PluginContract.ConnectionGroupMemberships.COLUMN_CONNECTION_ID),
        ) ?: return null
        val group = groups.findByPublicId(
            values.getAsString(PluginContract.ConnectionGroupMemberships.COLUMN_GROUP_ID),
        ) ?: return null
        connections.update(connection.copy(groupId = group.id))
        return itemUri(
            PluginContract.ConnectionGroupMemberships.PATH,
            membershipId(group.publicId, connection.publicId),
        )
    }

    // --- Cursor-Aufbau ------------------------------------------------------------------

    private fun connectionsCursor(entities: List<ConnectionEntity>): Cursor =
        MatrixCursor(PluginContract.Connections.COLUMNS).apply {
            for (entity in entities) {
                addRow(
                    arrayOf(
                        entity.publicId,
                        entity.id,
                        entity.updatedAt,
                        entity.displayName,
                        entity.address,
                        entity.port,
                        entity.nickname,
                        entity.type.toPluginValue(),
                    ),
                )
            }
        }

    private fun groupsCursor(entities: List<ConnectionGroupEntity>): Cursor =
        MatrixCursor(PluginContract.ConnectionGroups.COLUMNS).apply {
            for (entity in entities) {
                addRow(arrayOf(entity.publicId, entity.id, entity.updatedAt, entity.name))
            }
        }

    private suspend fun membershipsCursor(): Cursor {
        val all = connections.observeAll().first()
        val byId = groups.observeAll().first().associateBy { it.id }
        return MatrixCursor(PluginContract.ConnectionGroupMemberships.COLUMNS).apply {
            for (connection in all) {
                val group = connection.groupId?.let(byId::get) ?: continue
                addRow(
                    arrayOf(
                        membershipId(group.publicId, connection.publicId),
                        connection.id,
                        connection.updatedAt,
                        group.publicId,
                        connection.publicId,
                    ),
                )
            }
        }
    }

    private suspend fun portForwardsCursor(entities: List<PortForwardEntity>): Cursor {
        val byId = connections.observeAll().first().associateBy { it.id }
        return MatrixCursor(PluginContract.PortForwards.COLUMNS).apply {
            for (entity in entities) {
                addRow(
                    arrayOf(
                        entity.publicId,
                        entity.id,
                        entity.updatedAt,
                        entity.name,
                        entity.mode.toPluginValue(),
                        byId[entity.connectionId]?.publicId,
                        entity.targetHost,
                        entity.localPort,
                        entity.remotePort,
                        if (entity.openInBrowser) 1 else 0,
                    ),
                )
            }
        }
    }

    private fun snippetsCursor(entities: List<SnippetEntity>): Cursor =
        MatrixCursor(PluginContract.Snippets.COLUMNS).apply {
            for (entity in entities) {
                addRow(
                    arrayOf(
                        entity.publicId,
                        entity.id,
                        entity.updatedAt,
                        entity.name,
                        entity.content,
                    ),
                )
            }
        }

    private suspend fun pluginLogCursor(): Cursor =
        MatrixCursor(PluginContract.PluginLog.COLUMNS).apply {
            for (entry in auditLog.observeRecent().first()) {
                addRow(
                    arrayOf(
                        entry.id.toString(),
                        entry.id,
                        entry.createdAt,
                        entry.packageName,
                        entry.message,
                    ),
                )
            }
        }

    // --- Hilfsfunktionen ----------------------------------------------------------------

    private suspend fun audit(caller: String, message: String, succeeded: Boolean) {
        auditLog.record(caller, if (succeeded) message else "$message — abgewiesen")
    }

    private fun itemUri(path: String, publicId: String): Uri =
        Uri.parse("content://${PluginContract.AUTHORITY}/$path/$publicId")

    /** Eine Zuordnung hat keine eigene Zeile, ihre Kennung ist deshalb zusammengesetzt. */
    private fun membershipId(groupPublicId: String, connectionPublicId: String): String =
        "$groupPublicId:$connectionPublicId"

    private fun connectionTypeOf(value: Int?): ConnectionType = when (value) {
        PluginContract.Connections.TYPE_MOSH -> ConnectionType.MOSH
        PluginContract.Connections.TYPE_LOCAL -> ConnectionType.LOCAL
        PluginContract.Connections.TYPE_TELNET -> ConnectionType.TELNET
        else -> ConnectionType.SSH
    }

    private fun portForwardModeOf(value: Int?): PortForwardMode = when (value) {
        PluginContract.PortForwards.MODE_REMOTE -> PortForwardMode.REMOTE
        PluginContract.PortForwards.MODE_SOCKS -> PortForwardMode.DYNAMIC
        else -> PortForwardMode.LOCAL
    }

    private fun modelOf(match: Int): String? = when (match) {
        CONNECTIONS, CONNECTION_ITEM -> PluginContract.Connections.MODEL
        GROUPS, GROUP_ITEM -> PluginContract.ConnectionGroups.MODEL
        MEMBERSHIPS -> PluginContract.ConnectionGroupMemberships.MODEL
        PORT_FORWARDS, PORT_FORWARD_ITEM -> PluginContract.PortForwards.MODEL
        SNIPPETS, SNIPPET_ITEM -> PluginContract.Snippets.MODEL
        PLUGIN_LOG -> PluginContract.PluginLog.MODEL
        else -> null
    }

    private fun isItem(match: Int): Boolean =
        match in setOf(CONNECTION_ITEM, GROUP_ITEM, PORT_FORWARD_ITEM, SNIPPET_ITEM)

    private fun describe(match: Int): String = when (match) {
        CONNECTIONS, CONNECTION_ITEM -> "Verbindungen"
        GROUPS, GROUP_ITEM -> "Gruppen"
        MEMBERSHIPS -> "Gruppenzuordnungen"
        PORT_FORWARDS, PORT_FORWARD_ITEM -> "Weiterleitungen"
        SNIPPETS, SNIPPET_ITEM -> "Snippets"
        PLUGIN_LOG -> "das Plugin-Protokoll"
        else -> "einen unbekannten Pfad"
    }

    private companion object {
        const val CONNECTIONS = 1
        const val CONNECTION_ITEM = 2
        const val GROUPS = 3
        const val GROUP_ITEM = 4
        const val MEMBERSHIPS = 5
        const val PORT_FORWARDS = 6
        const val PORT_FORWARD_ITEM = 7
        const val SNIPPETS = 8
        const val SNIPPET_ITEM = 9
        const val PLUGIN_LOG = 10

        const val PORT_MAX = 65535
    }
}

/**
 * Ob [encodedPath] in genau der Form vorliegt, die die Plugin-API kennt:
 * `/<segment>` oder `/<segment>/<kennung>`, jedes Segment nicht leer und nur aus
 * Buchstaben, Ziffern, `-`, `_` und `.`.
 *
 * Abgelehnt werden damit leere Segmente (`//connections`, `/connections/`), `.` und `..`
 * sowie jede Prozent-Kodierung — auch ein kodierter Schrägstrich (`%2F`). Geprüft wird der
 * rohe, nicht dekodierte Pfad; ohne `%` ist er mit dem dekodierten identisch, den Android
 * gegen die `path-permission`-Regeln hält.
 */
internal fun isCanonicalApiPath(encodedPath: String?): Boolean {
    if (encodedPath == null || !encodedPath.startsWith("/")) return false
    val segments = encodedPath.substring(1).split("/")
    return segments.size in 1..2 && segments.all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.all { it.isLetterOrDigit() && it.code < 128 || it in "-_." }
    }
}

/** Die Zahlwerte sind Teil des Vertrags; eine Umbenennung des Enums darf sie nicht bewegen. */
internal fun ConnectionType.toPluginValue(): Int = when (this) {
    ConnectionType.SSH -> PluginContract.Connections.TYPE_SSH
    ConnectionType.MOSH -> PluginContract.Connections.TYPE_MOSH
    ConnectionType.LOCAL -> PluginContract.Connections.TYPE_LOCAL
    ConnectionType.TELNET -> PluginContract.Connections.TYPE_TELNET
}

internal fun PortForwardMode.toPluginValue(): Int = when (this) {
    PortForwardMode.LOCAL -> PluginContract.PortForwards.MODE_LOCAL
    PortForwardMode.REMOTE -> PluginContract.PortForwards.MODE_REMOTE
    PortForwardMode.DYNAMIC -> PluginContract.PortForwards.MODE_SOCKS
}
