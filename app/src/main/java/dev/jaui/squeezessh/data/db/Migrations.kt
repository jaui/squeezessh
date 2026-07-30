package dev.jaui.squeezessh.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 → 2: nach aussen sichtbare UUIDs fuer die Plugin-API.
 *
 * Die Zeilen-ID bleibt der Primaerschluessel; nach aussen geht eine UUID, weil die
 * Plugin-API Objekte so adressiert (`ssh://41b4f013-…`). Bestehende Zeilen bekommen eine
 * nachtraeglich erzeugte — sie werden von keinem Plugin referenziert, weil es vor dieser
 * Version keine gab.
 *
 * `DEFAULT ''` muss genau so stehen: Room vergleicht den Vorgabewert beim Start gegen das
 * erwartete Schema, und dort steht derselbe Ausdruck.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in TABLES_WITH_PUBLIC_ID) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `publicId` TEXT NOT NULL DEFAULT ''")
            // SQLite kennt keine UUID-Funktion; das ist die uebliche Zusammensetzung einer
            // Variante-4-UUID aus Zufallsbytes.
            db.execSQL(
                """
                UPDATE `$table` SET `publicId` = lower(
                    hex(randomblob(4)) || '-' ||
                    hex(randomblob(2)) || '-4' ||
                    substr(hex(randomblob(2)), 2) || '-' ||
                    substr('89ab', abs(random()) % 4 + 1, 1) ||
                    substr(hex(randomblob(2)), 2) || '-' ||
                    hex(randomblob(6))
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_publicId` " +
                    "ON `$table` (`publicId`)",
            )
        }

        // Pruefpfad der Plugin-API. Neu, deshalb ohne Datenuebernahme.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `plugin_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `packageName` TEXT NOT NULL,
                `message` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plugin_log_packageName` ON `plugin_log` (`packageName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plugin_log_createdAt` ON `plugin_log` (`createdAt`)")
    }
}

private val TABLES_WITH_PUBLIC_ID = listOf(
    "connection",
    "connection_group",
    "port_forward",
    "snippet",
)
