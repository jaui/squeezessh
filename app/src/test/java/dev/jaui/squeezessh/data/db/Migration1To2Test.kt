package dev.jaui.squeezessh.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Fährt [MIGRATION_1_2] gegen eine echte Datenbank im Stand 1.
 *
 * Der eigentliche Prüfer ist Room selbst: es vergleicht beim Öffnen das Schema mit dem
 * erwarteten und wirft, wenn etwas abweicht — bis hin zum Vorgabewert einer Spalte. Genau
 * daran scheitert eine per `ALTER TABLE` hinzugefügte Spalte gern, deshalb steht hier eine
 * echte Datenbank und nicht bloß eine Prüfung des abgesetzten SQL.
 *
 * Das Ausgangsschema kommt aus `schemas/…/1.json`, damit der Test nicht veraltet, wenn
 * jemand die Entities weiterentwickelt.
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {

    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseFile = File(context.cacheDir, "migration-test.db")
        databaseFile.delete()
    }

    @After
    fun tearDown() {
        databaseFile.delete()
    }

    @Test
    fun `bestehende Zeilen bekommen eine gueltige UUID`() {
        createVersion1Database {
            it.execSQL(
                """
                INSERT INTO connection
                    (nickname, address, port, type, backspaceMode, useCount, createdAt, updatedAt)
                VALUES ('Alt', 'alt.example.com', 22, 'SSH', 'DELETE', 3, 0, 0)
                """.trimIndent(),
            )
            it.execSQL(
                "INSERT INTO snippet (name, content, useCount, createdAt, updatedAt) " +
                    "VALUES ('Kernel', 'uname -sr', 0, 0, 0)",
            )
        }

        val database = openWithRoom()
        try {
            val connection = runBlocking { database.connectionDao().observeAll().first() }.single()
            assertThat(connection.nickname).isEqualTo("Alt")
            assertThat(connection.publicId).matches(UUID_PATTERN)

            // Über die UUID nachschlagen ist der Weg, den die Plugin-API nimmt.
            val found = runBlocking { database.connectionDao().findByPublicId(connection.publicId) }
            assertThat(found?.id).isEqualTo(connection.id)
        } finally {
            database.close()
        }
    }

    @Test
    fun `zwei Zeilen bekommen verschiedene UUIDs`() {
        createVersion1Database {
            repeat(20) { index ->
                it.execSQL(
                    "INSERT INTO snippet (name, content, useCount, createdAt, updatedAt) " +
                        "VALUES ('S$index', 'echo $index', 0, 0, 0)",
                )
            }
        }

        val database = openWithRoom()
        try {
            val ids = runBlocking { database.snippetDao().observeAll().first() }.map { it.publicId }
            assertThat(ids).hasSize(20)
            assertThat(ids.toSet()).hasSize(20)
            ids.forEach { assertThat(it).matches(UUID_PATTERN) }
        } finally {
            database.close()
        }
    }

    @Test
    fun `das Plugin-Protokoll ist nach der Migration benutzbar`() {
        createVersion1Database { }

        val database = openWithRoom()
        try {
            runBlocking {
                database.pluginLogDao().insert(
                    dev.jaui.squeezessh.data.db.entity.PluginLogEntity(
                        packageName = "com.example.plugin",
                        message = "hat die Verbindungen gelesen",
                        createdAt = 42,
                    ),
                )
                val entries = database.pluginLogDao().observeRecent().first()
                assertThat(entries.single().packageName).isEqualTo("com.example.plugin")
            }
        } finally {
            database.close()
        }
    }

    /**
     * Legt die Datenbank im Stand 1 an — Tabellen und `user_version` genau so, wie Room sie
     * damals hinterlassen hätte, samt Eintrag in `room_master_table`. Ohne den hält Room die
     * Datenbank für fremd und weigert sich zu migrieren.
     */
    private fun createVersion1Database(fill: (SupportSQLiteDatabase) -> Unit) {
        val schema = JSONObject(readSchema(version = 1)).getJSONObject("database")
        val entities = schema.getJSONArray("entities")

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

        helper.writableDatabase.use { db ->
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(i).getString("createSql")
                            .replace("\${TABLE_NAME}", table),
                    )
                }
            }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(schema.getString("identityHash")),
            )
            db.version = 1
            fill(db)
        }
    }

    private fun openWithRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.absolutePath)
            .addMigrations(MIGRATION_1_2)
            .build()

    private fun readSchema(version: Int): String {
        // Der Test läuft mit dem Modulverzeichnis als Arbeitsverzeichnis.
        val file = File("schemas/${AppDatabase::class.java.name}/$version.json")
        check(file.exists()) { "Schema nicht gefunden: ${file.absolutePath}" }
        return file.readText()
    }

    private companion object {
        const val UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    }
}
