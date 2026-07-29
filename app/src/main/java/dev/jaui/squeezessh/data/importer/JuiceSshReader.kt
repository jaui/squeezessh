package dev.jaui.squeezessh.data.importer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable

/**
 * Liest eine JuiceSSH-Datenbank ohne sie zu veraendern.
 *
 * Eigenheiten des Quellschemas, die hier gekapselt werden:
 *
 * - Primaerschluessel sind UUID-Strings, keine Zahlen.
 * - Fremdschluessel heissen nach ORMLite-Konvention `<feld>_id`.
 * - `type`, `backspaceMode` und `mode` sind Enum-Ordinalzahlen.
 * - Gruppenzugehoerigkeit steht ausschliesslich in `connectiongroupmembership`;
 *   `connection.group_id` bleibt in der Praxis leer.
 * - Nur `identity.password`, `identity.privatekey` und `identity.privatekeyPassword`
 *   sind verschluesselt. Alles andere steht im Klartext.
 */
internal class JuiceSshReader private constructor(
    private val db: SQLiteDatabase,
) : Closeable {

    companion object {
        /** @throws JuiceSshFormatException wenn die Datei keine JuiceSSH-Datenbank ist. */
        fun open(backup: JuiceSshBackup): JuiceSshReader {
            // openDatabase liest die Datei noch nicht — ob sie ueberhaupt SQLite ist,
            // stellt sich erst bei der ersten Abfrage heraus. Beides gehoert deshalb in
            // denselben Versuch.
            var reader: JuiceSshReader? = null
            try {
                @Suppress("DEPRECATION")
                val db = SQLiteDatabase.openDatabase(
                    backup.databaseFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
                reader = JuiceSshReader(db)
                if (!reader.looksLikeJuiceSsh()) {
                    throw JuiceSshFormatException("Datenbank enthaelt keine JuiceSSH-Tabellen")
                }
                return reader
            } catch (e: SQLiteException) {
                reader?.close()
                throw JuiceSshFormatException("Datei ist keine lesbare SQLite-Datenbank", e)
            } catch (e: JuiceSshFormatException) {
                reader?.close()
                throw e
            }
        }
    }

    /**
     * Vorhandene Tabellen, einmal eingelesen.
     *
     * Nicht jede JuiceSSH-Datenbank hat alle: aeltere Versionen kannten manche Tabellen
     * noch nicht. Ein fehlendes `snippet` darf den ganzen Import nicht scheitern lassen.
     */
    private val tables: Set<String> by lazy {
        buildSet {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun looksLikeJuiceSsh(): Boolean =
        tables.containsAll(setOf("connection", "identity", "connectiongroup"))

    override fun close() = db.close()

    fun countRows(table: String): Int {
        if (table !in tables) return 0
        return db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun readGroups(): List<RawGroup> = query(
        "connectiongroup",
        "SELECT `id`, `name` FROM `connectiongroup`",
    ) { RawGroup(id = it.str("id"), name = it.strOrNull("name")) }

    fun readConnections(): List<RawConnection> = query(
        "connection",
        """
        SELECT `id`, `nickname`, `address`, `port`, `type`, `backspaceMode`,
               `via_id`, `snippet_id`, `mosh_command`
        FROM `connection`
        """,
    ) {
        RawConnection(
            id = it.str("id"),
            nickname = it.strOrNull("nickname"),
            address = it.strOrNull("address"),
            port = it.intOrNull("port"),
            type = it.intOrNull("type"),
            backspaceMode = it.intOrNull("backspaceMode"),
            viaId = it.strOrNull("via_id"),
            snippetId = it.strOrNull("snippet_id"),
            moshCommand = it.strOrNull("mosh_command"),
        )
    }

    fun readGroupMemberships(): List<RawPair> = query(
        "connectiongroupmembership",
        "SELECT `group_id`, `connection_id` FROM `connectiongroupmembership`",
    ) { RawPair(first = it.strOrNull("group_id"), second = it.strOrNull("connection_id")) }

    fun readConnectionIdentities(): List<RawPair> = query(
        "connectionidentity",
        "SELECT `connection_id`, `identity_id` FROM `connectionidentity`",
    ) { RawPair(first = it.strOrNull("connection_id"), second = it.strOrNull("identity_id")) }

    fun readIdentities(): List<RawIdentity> = query(
        "identity",
        """
        SELECT `id`, `nickname`, `username`, `password`, `privatekey`,
               `privatekeyPassword`, `passUpdated`, `keyUpdated`, `snippet_id`
        FROM `identity`
        """,
    ) {
        RawIdentity(
            id = it.str("id"),
            nickname = it.strOrNull("nickname"),
            username = it.strOrNull("username"),
            passwordBlob = it.strOrNull("password"),
            privateKeyBlob = it.strOrNull("privatekey"),
            privateKeyPasswordBlob = it.strOrNull("privatekeyPassword"),
            passwordUpdatedAt = it.longOrNull("passUpdated"),
            keyUpdatedAt = it.longOrNull("keyUpdated"),
            snippetId = it.strOrNull("snippet_id"),
        )
    }

    fun readSnippets(): List<RawSnippet> = query(
        "snippet",
        "SELECT `id`, `name`, `content`, `count` FROM `snippet`",
    ) {
        RawSnippet(
            id = it.str("id"),
            name = it.strOrNull("name"),
            content = it.strOrNull("content"),
            count = it.intOrNull("count"),
        )
    }

    fun readPortForwards(): List<RawPortForward> = query(
        "portforward",
        """
        SELECT `id`, `name`, `mode`, `connection_id`, `host`,
               `localPort`, `remotePort`, `openInBrowser`, `useAsProxy`
        FROM `portforward`
        """,
    ) {
        RawPortForward(
            id = it.str("id"),
            name = it.strOrNull("name"),
            mode = it.intOrNull("mode"),
            connectionId = it.strOrNull("connection_id"),
            host = it.strOrNull("host"),
            localPort = it.intOrNull("localPort"),
            remotePort = it.intOrNull("remotePort"),
            openInBrowser = it.intOrNull("openInBrowser") == 1,
            useAsProxy = it.intOrNull("useAsProxy") == 1,
        )
    }

    fun readKnownHosts(): List<RawKnownHost> = query(
        "host",
        "SELECT `id`, `hostname`, `fingerprint`, `key`, `type` FROM `host`",
    ) {
        RawKnownHost(
            id = it.str("id"),
            hostname = it.strOrNull("hostname"),
            fingerprint = it.strOrNull("fingerprint"),
            key = it.strOrNull("key"),
            type = it.strOrNull("type"),
        )
    }

    /** Nutzungszaehler, abgelegt als eigene Tabelle statt als Spalte an der Verbindung. */
    fun readConnectionCounters(): Map<String, Int> = buildMap {
        query("connectioncounter", "SELECT `connection_id`, `count` FROM `connectioncounter`") {
            it.strOrNull("connection_id") to (it.intOrNull("count") ?: 0)
        }.forEach { (id, count) -> if (id != null) put(id, count) }
    }

    /** Liefert eine leere Liste, wenn es die Tabelle in dieser Datenbank nicht gibt. */
    private fun <T> query(table: String, sql: String, map: (Cursor) -> T): List<T> {
        if (table !in tables) return emptyList()
        return db.rawQuery(sql, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(map(cursor)) }
        }
    }

    private fun Cursor.str(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.strOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    private fun Cursor.intOrNull(column: String): Int? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) }

    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
}

class JuiceSshFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class RawGroup(val id: String, val name: String?)

internal data class RawConnection(
    val id: String,
    val nickname: String?,
    val address: String?,
    val port: Int?,
    val type: Int?,
    val backspaceMode: Int?,
    val viaId: String?,
    val snippetId: String?,
    val moshCommand: String?,
)

internal data class RawIdentity(
    val id: String,
    val nickname: String?,
    val username: String?,
    val passwordBlob: String?,
    val privateKeyBlob: String?,
    val privateKeyPasswordBlob: String?,
    val passwordUpdatedAt: Long?,
    val keyUpdatedAt: Long?,
    val snippetId: String?,
) {
    val hasSecrets: Boolean
        get() = !passwordBlob.isNullOrEmpty() || !privateKeyBlob.isNullOrEmpty()
}

internal data class RawSnippet(
    val id: String,
    val name: String?,
    val content: String?,
    val count: Int?,
)

internal data class RawPortForward(
    val id: String,
    val name: String?,
    val mode: Int?,
    val connectionId: String?,
    val host: String?,
    val localPort: Int?,
    val remotePort: Int?,
    val openInBrowser: Boolean,
    val useAsProxy: Boolean,
)

internal data class RawKnownHost(
    val id: String,
    val hostname: String?,
    val fingerprint: String?,
    val key: String?,
    val type: String?,
)

internal data class RawPair(val first: String?, val second: String?)
