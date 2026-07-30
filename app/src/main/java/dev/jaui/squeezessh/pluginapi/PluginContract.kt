package dev.jaui.squeezessh.pluginapi

import android.content.ContentResolver
import android.net.Uri
import dev.jaui.squeezessh.BuildConfig

/**
 * Der nach außen veröffentlichte Vertrag der Plugin-API.
 *
 * Spalten, Pfade und Zahlwerte sind absichtlich Zeichen für Zeichen die von JuiceSSHs
 * `PluginContract` (Apache-2.0, aus `Sonelli/juicessh-pluginlibrary`). Ein bestehendes
 * Plugin fragt `content://…/connections` mit genau diesen Spaltennamen ab; jede Abweichung
 * wäre ein stiller Fehler beim Plugin, nicht hier.
 *
 * Die Authority kommt aus der Build-Variante: `standalone` benutzt eine eigene,
 * `juicesshcompat` die von JuiceSSH. Beide gleichzeitig sind nicht möglich — zwei Apps mit
 * derselben Provider-Authority lassen sich nicht nebeneinander installieren.
 *
 * Nach außen werden UUIDs verwendet, nicht die Zeilen-IDs. Das ist keine Vorsichtsmaßnahme,
 * sondern der Vertrag: `IPluginService.connect(String id)` und `ssh://<id>` erwarten eine
 * UUID.
 */
object PluginContract {

    val AUTHORITY: String = BuildConfig.PLUGIN_API_AUTHORITY

    /** Erlaubt das Aufbauen von Sitzungen über die AIDL-Dienste. */
    val PERMISSION_OPEN_SESSIONS: String get() = "$AUTHORITY.permission.OPEN_SESSIONS"

    /** Erlaubt SFTP-Operationen über [dev.jaui.squeezessh.pluginapi.SftpPluginService]. */
    val PERMISSION_OPEN_SFTP_SESSIONS: String get() = "$AUTHORITY.permission.OPEN_SFTP_SESSIONS"

    private fun uri(path: String): Uri = Uri.parse("content://$AUTHORITY/$path")

    /**
     * MIME-Typ eines Modells, abgeleitet aus der Authority: `…api.v1` wird zu
     * `….models.<name>`. In der Kompatibilitaetsvariante ergibt das genau die Typen, die
     * JuiceSSH gemeldet hat.
     */
    internal fun dirMimeType(model: String): String =
        "${ContentResolver.CURSOR_DIR_BASE_TYPE}/${modelPrefix()}.$model"

    internal fun itemMimeType(model: String): String =
        "${ContentResolver.CURSOR_ITEM_BASE_TYPE}/${modelPrefix()}.$model"

    private fun modelPrefix(): String = "${AUTHORITY.removeSuffix(".api.v1")}.models"

    object Connections {
        const val PATH = "connections"
        val CONTENT_URI: Uri get() = uri(PATH)
        val PERMISSION_READ: String get() = "${AUTHORITY}.permission.READ_CONNECTIONS"
        val PERMISSION_WRITE: String get() = "${AUTHORITY}.permission.WRITE_CONNECTIONS"

        val CONTENT_TYPE: String get() = dirMimeType(MODEL)
        val CONTENT_ITEM_TYPE: String get() = itemMimeType(MODEL)

        const val COLUMN_ID = "id"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_NAME = "name"
        const val COLUMN_ADDRESS = "address"
        const val COLUMN_PORT = "port"
        const val COLUMN_NICKNAME = "nickname"
        const val COLUMN_TYPE = "type"

        val COLUMNS = arrayOf(
            COLUMN_ID,
            BaseColumns_ID,
            COLUMN_MODIFIED,
            COLUMN_NAME,
            COLUMN_ADDRESS,
            COLUMN_PORT,
            COLUMN_NICKNAME,
            COLUMN_TYPE,
        )

        // Die Zahlwerte stammen aus JuiceSSHs Vertrag und dürfen sich nicht verschieben.
        const val TYPE_SSH = 0
        const val TYPE_MOSH = 1
        const val TYPE_LOCAL = 2
        const val TYPE_TELNET = 3

        const val MODEL = "connection"
    }

    object ConnectionGroups {
        const val MODEL = "connectiongroup"
        const val PATH = "connectiongroups"
        val CONTENT_URI: Uri get() = uri(PATH)
        val PERMISSION_READ: String get() = "${AUTHORITY}.permission.READ_CONNECTION_GROUPS"
        val PERMISSION_WRITE: String get() = "${AUTHORITY}.permission.WRITE_CONNECTION_GROUPS"

        const val COLUMN_ID = "id"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_NAME = "name"

        val COLUMNS = arrayOf(COLUMN_ID, BaseColumns_ID, COLUMN_MODIFIED, COLUMN_NAME)
    }

    object ConnectionGroupMemberships {
        const val MODEL = "connectiongroupmembership"
        const val PATH = "connectiongroupmemberships"
        val CONTENT_URI: Uri get() = uri(PATH)

        /** Teilt sich die Rechte mit den Gruppen selbst — so war es auch im Original. */
        val PERMISSION_READ: String get() = ConnectionGroups.PERMISSION_READ
        val PERMISSION_WRITE: String get() = ConnectionGroups.PERMISSION_WRITE

        const val COLUMN_ID = "id"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_GROUP_ID = "group_id"
        const val COLUMN_CONNECTION_ID = "connection_id"

        val COLUMNS = arrayOf(
            COLUMN_ID,
            BaseColumns_ID,
            COLUMN_MODIFIED,
            COLUMN_GROUP_ID,
            COLUMN_CONNECTION_ID,
        )
    }

    object PortForwards {
        const val MODEL = "portforward"
        const val PATH = "portforwards"
        val CONTENT_URI: Uri get() = uri(PATH)
        val PERMISSION_READ: String get() = "${AUTHORITY}.permission.READ_PORT_FORWARDS"
        val PERMISSION_WRITE: String get() = "${AUTHORITY}.permission.WRITE_PORT_FORWARDS"

        const val COLUMN_ID = "id"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_NAME = "name"
        const val COLUMN_MODE = "mode"
        const val COLUMN_CONNECTION_ID = "connection_id"
        const val COLUMN_HOST = "host"
        const val COLUMN_LOCAL_PORT = "localPort"
        const val COLUMN_REMOTE_PORT = "remotePort"
        const val COLUMN_OPEN_IN_BROWSER = "openInBrowser"

        val COLUMNS = arrayOf(
            COLUMN_ID,
            BaseColumns_ID,
            COLUMN_MODIFIED,
            COLUMN_NAME,
            COLUMN_MODE,
            COLUMN_CONNECTION_ID,
            COLUMN_HOST,
            COLUMN_LOCAL_PORT,
            COLUMN_REMOTE_PORT,
            COLUMN_OPEN_IN_BROWSER,
        )

        const val MODE_LOCAL = 0
        const val MODE_REMOTE = 1
        const val MODE_SOCKS = 2
    }

    object Snippets {
        const val MODEL = "snippet"
        const val PATH = "snippets"
        val CONTENT_URI: Uri get() = uri(PATH)
        val PERMISSION_READ: String get() = "${AUTHORITY}.permission.READ_SNIPPETS"
        val PERMISSION_WRITE: String get() = "${AUTHORITY}.permission.WRITE_SNIPPETS"

        const val COLUMN_ID = "id"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_NAME = "name"
        const val COLUMN_CONTENT = "content"

        val COLUMNS = arrayOf(
            COLUMN_ID,
            BaseColumns_ID,
            COLUMN_MODIFIED,
            COLUMN_NAME,
            COLUMN_CONTENT,
        )
    }

    /** Nur lesbar: der Prüfpfad ist eine Auskunft an den Nutzer, kein Ablageort für Plugins. */
    object PluginLog {
        const val MODEL = "pluginlog"
        const val PATH = "pluginlog"
        val CONTENT_URI: Uri get() = uri(PATH)
        val PERMISSION_READ: String get() = "${AUTHORITY}.permission.READ_PLUGIN_LOG"

        const val COLUMN_ID = "id"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_PACKAGE_NAME = "packageName"
        const val COLUMN_MESSAGE = "message"

        val COLUMNS = arrayOf(
            COLUMN_ID,
            BaseColumns_ID,
            COLUMN_MODIFIED,
            COLUMN_PACKAGE_NAME,
            COLUMN_MESSAGE,
        )
    }
}

/**
 * `_id` erwarten Androids Cursor-Adapter. Die Spalte trägt die Zeilen-ID; sie ist für
 * Listenansichten da und nicht als Kennung gedacht — dafür ist `id` (die UUID) zuständig.
 */
@Suppress("ObjectPropertyName", "ktlint:standard:property-naming")
private const val BaseColumns_ID = "_id"
