package dev.jaui.squeezessh.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.jaui.squeezessh.data.model.BackspaceMode
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.data.model.PortForwardMode

/**
 * Room-Entities.
 *
 * Fachlich am Schema von JuiceSSH orientiert (siehe docs/reverse-engineering-notes.md),
 * damit der Import in M2 verlustfrei bleibt. Bewusste Abweichungen:
 *
 * - Die Sync-Spalten `encrypted`, `isEncrypted`, `owner`, `lastUpdatedBy` entfallen; sie
 *   existierten nur wegen CloudSync und Teams.
 * - `connectioncounter` ist keine eigene Tabelle mehr, sondern liegt als `useCount` und
 *   `lastUsedAt` direkt an der Verbindung.
 * - Einstellungen liegen in DataStore statt in `configfield`/`configvalue`.
 * - Geheimnisse stehen ausschliesslich verschluesselt in den `*Cipher`-Spalten, erzeugt
 *   von [dev.jaui.squeezessh.data.crypto.SecretVault].
 */

@Entity(
    tableName = "connection_group",
    indices = [Index(value = ["name"], unique = true)],
)
data class ConnectionGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "connection",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        // Selbstreferenz: Sprung ueber einen Bastion-Host. In JuiceSSH hiess das `via`.
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["viaConnectionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = SnippetEntity::class,
            parentColumns = ["id"],
            childColumns = ["startupSnippetId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("groupId"),
        Index("viaConnectionId"),
        Index("startupSnippetId"),
        Index("nickname"),
    ],
)
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String?,
    val address: String,
    val port: Int = DEFAULT_SSH_PORT,
    val type: ConnectionType = ConnectionType.SSH,
    val groupId: Long? = null,
    val viaConnectionId: Long? = null,
    val startupSnippetId: Long? = null,
    val backspaceMode: BackspaceMode = BackspaceMode.DELETE,
    /** Nur fuer [ConnectionType.MOSH] relevant; heute mitgefuehrt, aber nicht ausgewertet. */
    val moshCommand: String? = null,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Anzeigename: der Spitzname, sonst die Adresse. */
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: address

    companion object {
        const val DEFAULT_SSH_PORT = 22
    }
}

/**
 * Zusaetzliche Gruppenzugehoerigkeiten. `connection.groupId` bleibt die primaere Gruppe;
 * JuiceSSH erlaubte darueber hinaus Mehrfachzuordnung.
 */
@Entity(
    tableName = "connection_group_membership",
    primaryKeys = ["connectionId", "groupId"],
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConnectionGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class ConnectionGroupMembershipEntity(
    val connectionId: Long,
    val groupId: Long,
)

@Entity(
    tableName = "identity",
    foreignKeys = [
        ForeignKey(
            entity = SnippetEntity::class,
            parentColumns = ["id"],
            childColumns = ["startupSnippetId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("startupSnippetId"), Index("nickname")],
)
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String?,
    val username: String,
    /** Versiegelt. */
    val passwordCipher: String? = null,
    /** Versiegelt; PEM bzw. openssh-key-v1 im Klartext waere hier ein Sicherheitsproblem. */
    val privateKeyCipher: String? = null,
    /** Versiegelt. */
    val privateKeyPasswordCipher: String? = null,
    /** Oeffentlicher Teil, unverschluesselt — praktisch fuer „Public Key kopieren". */
    val publicKey: String? = null,
    val startupSnippetId: Long? = null,
    val passwordUpdatedAt: Long? = null,
    val keyUpdatedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
    val hasPassword: Boolean get() = passwordCipher != null
    val hasPrivateKey: Boolean get() = privateKeyCipher != null
}

/** Verknuepfung Verbindung <-> Identitaet; eine Verbindung kann mehrere anbieten. */
@Entity(
    tableName = "connection_identity",
    primaryKeys = ["connectionId", "identityId"],
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = IdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["identityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("identityId")],
)
data class ConnectionIdentityEntity(
    val connectionId: Long,
    val identityId: Long,
    /** Reihenfolge, in der Identitaeten beim Verbinden durchprobiert werden. */
    val sortOrder: Int = 0,
)

/**
 * Known Hosts. Entspricht `host` im Original.
 *
 * Der Port gehoert zum Schluessel: derselbe Rechner kann auf verschiedenen Ports
 * verschiedene Host-Keys anbieten (etwa Container hinter einem Reverse-Proxy).
 */
@Entity(
    tableName = "known_host",
    indices = [Index(value = ["hostname", "port", "keyType"], unique = true)],
)
data class KnownHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostname: String,
    val port: Int = ConnectionEntity.DEFAULT_SSH_PORT,
    val keyType: HostKeyType,
    /** Base64 des Host-Keys, wie ihn der Server sendet. */
    val publicKey: String,
    /** SHA-256-Fingerprint zur Anzeige, Format `SHA256:...`. */
    val fingerprint: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

@Entity(
    tableName = "port_forward",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("connectionId")],
)
data class PortForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?,
    val connectionId: Long,
    val mode: PortForwardMode,
    val localPort: Int,
    /** Zielhost; bei [PortForwardMode.DYNAMIC] ohne Bedeutung. */
    val targetHost: String? = null,
    /** Zielport; bei [PortForwardMode.DYNAMIC] ohne Bedeutung. */
    val remotePort: Int? = null,
    val openInBrowser: Boolean = false,
    val useAsProxy: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "snippet",
    indices = [Index("name")],
)
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val content: String,
    val useCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
