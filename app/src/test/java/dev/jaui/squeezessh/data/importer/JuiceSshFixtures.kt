package dev.jaui.squeezessh.data.importer

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Testdatenbanken im JuiceSSH-Format.
 *
 * Zwei Quellen: die echte Datenbank aus `test/resources/fixtures` fuer den Nachweis, dass
 * der Import gegen das reale Format laeuft, und synthetisch aufgebaute Datenbanken fuer
 * die Faelle, die sich in der Free-Version nicht erzeugen lassen (Port-Forwards,
 * Snippets, Known Hosts, Sprung-Ketten).
 */
internal object JuiceSshFixtures {

    fun realDatabase(target: File): File = copyResource("fixtures/juicessh-3.2.2.db", target)

    fun realPreferences(target: File): File =
        copyResource("fixtures/juicessh-3.2.2-prefs.xml", target)

    private fun copyResource(name: String, target: File): File {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Fixture $name fehlt"
        }
        stream.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    /**
     * Baut eine leere Datenbank mit dem originalen DDL auf.
     *
     * Das Schema ist woertlich das von JuiceSSH 3.2.2 — ohne Fremdschluessel, mit
     * UUID-Textspalten als Primaerschluessel und ORMLites `<feld>_id`-Konvention.
     */
    fun emptyDatabase(target: File): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(target, null)
        listOf(
            """CREATE TABLE `connectiongroup` (`name` VARCHAR, `id` VARCHAR, `modified` BIGINT,
               `encrypted` VARCHAR, `isEncrypted` SMALLINT, `owner_id` VARCHAR,
               `lastUpdatedBy_id` VARCHAR, PRIMARY KEY (`id`))""",
            """CREATE TABLE `connection` (`group_id` VARCHAR, `via_id` VARCHAR, `nickname` VARCHAR,
               `address` VARCHAR, `snippet_id` VARCHAR, `mosh_command` VARCHAR, `port` INTEGER,
               `type` INTEGER, `backspaceMode` INTEGER, `id` VARCHAR, `modified` BIGINT,
               `encrypted` VARCHAR, `isEncrypted` SMALLINT, `owner_id` VARCHAR,
               `lastUpdatedBy_id` VARCHAR, PRIMARY KEY (`id`))""",
            """CREATE TABLE `connectiongroupmembership` (`group_id` VARCHAR,
               `connection_id` VARCHAR, `id` VARCHAR, `modified` BIGINT, `encrypted` VARCHAR,
               `isEncrypted` SMALLINT, `owner_id` VARCHAR, `lastUpdatedBy_id` VARCHAR,
               PRIMARY KEY (`id`))""",
            """CREATE TABLE `connectionidentity` (`connection_id` VARCHAR, `identity_id` VARCHAR,
               `id` VARCHAR, `modified` BIGINT, `encrypted` VARCHAR, `isEncrypted` SMALLINT,
               `owner_id` VARCHAR, `lastUpdatedBy_id` VARCHAR, PRIMARY KEY (`id`))""",
            """CREATE TABLE `connectioncounter` (`connection_id` VARCHAR, `count` INTEGER,
               `id` VARCHAR, `modified` BIGINT, `encrypted` VARCHAR, `isEncrypted` SMALLINT,
               `owner_id` VARCHAR, `lastUpdatedBy_id` VARCHAR, PRIMARY KEY (`id`))""",
            """CREATE TABLE `identity` (`username` VARCHAR, `password` VARCHAR,
               `privatekey` VARCHAR, `privatekeyPassword` VARCHAR, `nickname` VARCHAR,
               `passUpdated` BIGINT, `keyUpdated` BIGINT, `snippet_id` VARCHAR, `id` VARCHAR,
               `modified` BIGINT, `encrypted` VARCHAR, `isEncrypted` SMALLINT,
               `owner_id` VARCHAR, `lastUpdatedBy_id` VARCHAR, PRIMARY KEY (`id`))""",
            """CREATE TABLE `snippet` (`name` VARCHAR, `content` VARCHAR, `count` INTEGER,
               `id` VARCHAR, `modified` BIGINT, `encrypted` VARCHAR, `isEncrypted` SMALLINT,
               `owner_id` VARCHAR, `lastUpdatedBy_id` VARCHAR, PRIMARY KEY (`id`))""",
            """CREATE TABLE `portforward` (`name` VARCHAR, `mode` INTEGER,
               `connection_id` VARCHAR, `host` VARCHAR, `localPort` INTEGER,
               `remotePort` INTEGER, `openInBrowser` SMALLINT, `useAsProxy` SMALLINT,
               `id` VARCHAR, `modified` BIGINT, `encrypted` VARCHAR, `isEncrypted` SMALLINT,
               `owner_id` VARCHAR, `lastUpdatedBy_id` VARCHAR, PRIMARY KEY (`id`))""",
            """CREATE TABLE `host` (`hostname` VARCHAR, `fingerprint` VARCHAR, `key` VARCHAR,
               `type` VARCHAR, `id` VARCHAR, `modified` BIGINT, `encrypted` VARCHAR,
               `isEncrypted` SMALLINT, `owner_id` VARCHAR, `lastUpdatedBy_id` VARCHAR,
               PRIMARY KEY (`id`))""",
        ).forEach(db::execSQL)
        return db
    }

    /** Preferences-Datei ohne Master-Key — simuliert ein unvollstaendiges Backup. */
    fun preferencesWithoutMasterKey(target: File, accountHash: String = "abc123"): File {
        target.writeText(
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="account_hash">$accountHash</string>
                <string name="account">New User</string>
            </map>
            """.trimIndent(),
        )
        return target
    }
}
