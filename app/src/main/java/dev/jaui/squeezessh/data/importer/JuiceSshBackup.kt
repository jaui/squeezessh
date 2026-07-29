package dev.jaui.squeezessh.data.importer

import java.io.File

/**
 * Was der Nutzer vom alten Geraet mitbringt.
 *
 * @param databaseFile die SQLite-Datei aus `/data/data/com.sonelli.juicessh/databases/`.
 *   Sie traegt keinen sprechenden Namen, sondern heisst wie der `account_hash`,
 *   etwa `19a093afcee2a1df8bb6ac14d79843`.
 * @param preferencesFile `com.sonelli.juicessh_preferences.xml` aus `shared_prefs/`.
 *   Fehlt sie, laesst sich alles ausser den Geheimnissen importieren.
 */
data class JuiceSshBackup(
    val databaseFile: File,
    val preferencesFile: File? = null,
)

/** Was ein Import mitbringen wuerde — Grundlage fuer die Vorschau vor dem Zugriff. */
data class ImportPreview(
    val connections: Int,
    val groups: Int,
    val identities: Int,
    val identitiesWithSecrets: Int,
    val snippets: Int,
    val portForwards: Int,
    val knownHosts: Int,
    /**
     * `false` wenn die Preferences fehlen oder der Master-Key nicht aufgeht. Der Import
     * laeuft dann trotzdem, Passwoerter und Private Keys bleiben aber leer.
     */
    val secretsReadable: Boolean,
    val accountName: String? = null,
) {
    val total: Int
        get() = connections + groups + identities + snippets + portForwards + knownHosts

    val isEmpty: Boolean get() = total == 0
}

/** Ergebnis eines Imports. */
data class ImportReport(
    val importedConnections: Int = 0,
    val importedGroups: Int = 0,
    val importedIdentities: Int = 0,
    val importedSnippets: Int = 0,
    val importedPortForwards: Int = 0,
    val importedKnownHosts: Int = 0,
    val skippedDuplicates: Int = 0,
    /** Was nicht uebernommen werden konnte, in fuer Menschen lesbarer Form. */
    val warnings: List<String> = emptyList(),
) {
    val importedTotal: Int
        get() = importedConnections + importedGroups + importedIdentities +
            importedSnippets + importedPortForwards + importedKnownHosts
}

/** Wie mit bereits vorhandenen Eintraegen umgegangen wird. */
enum class DuplicateStrategy {
    /** Vorhandene behalten, gleichnamige aus dem Backup ueberspringen. */
    SKIP,

    /** Alles anlegen, auch wenn es dadurch doppelte Namen gibt. */
    IMPORT_ANYWAY,
}
