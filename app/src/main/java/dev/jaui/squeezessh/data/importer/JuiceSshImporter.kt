package dev.jaui.squeezessh.data.importer

import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupMembershipEntity
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.repository.ConnectionGroupRepository
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.IdentityRepository
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import dev.jaui.squeezessh.data.repository.PortForwardRepository
import dev.jaui.squeezessh.data.repository.SnippetRepository
import dev.jaui.squeezessh.data.db.dao.ConnectionDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uebernimmt eine JuiceSSH-Datenbank in den eigenen Bestand.
 *
 * Ablauf in zwei Schritten: [preview] liest nur und sagt, was kaeme; [import] schreibt.
 * So sieht der Nutzer vorher, ob die Geheimnisse lesbar sind — das ist der Punkt, an dem
 * ein unvollstaendiges Backup auffaellt, und nicht erst hinterher.
 *
 * Beim Schreiben werden alle Geheimnisse sofort in die eigene Keystore-Verschluesselung
 * ueberfuehrt; JuiceSSHs Klartext-Master-Key wird nirgends abgelegt.
 */
@Singleton
class JuiceSshImporter @Inject constructor(
    private val connections: ConnectionRepository,
    private val connectionDao: ConnectionDao,
    private val groups: ConnectionGroupRepository,
    private val identities: IdentityRepository,
    private val snippets: SnippetRepository,
    private val portForwards: PortForwardRepository,
    private val knownHosts: KnownHostRepository,
    private val io: CoroutineDispatcher,
) {

    suspend fun preview(backup: JuiceSshBackup): ImportPreview = withContext(io) {
        val preferences = backup.preferencesFile?.let(JuiceSshPreferences::parse)
        JuiceSshReader.open(backup).use { reader ->
            val sourceIdentities = reader.readIdentities()
            ImportPreview(
                connections = reader.countRows("connection"),
                groups = reader.countRows("connectiongroup"),
                identities = sourceIdentities.size,
                identitiesWithSecrets = sourceIdentities.count { it.hasSecrets },
                snippets = reader.countRows("snippet"),
                portForwards = reader.countRows("portforward"),
                knownHosts = reader.countRows("host"),
                secretsReadable = preferences?.masterKey != null,
                accountName = preferences?.accountName,
            )
        }
    }

    suspend fun import(
        backup: JuiceSshBackup,
        duplicates: DuplicateStrategy = DuplicateStrategy.SKIP,
    ): ImportReport = withContext(io) {
        val preferences = backup.preferencesFile?.let(JuiceSshPreferences::parse)
        val masterKey = preferences?.masterKey
        val warnings = mutableListOf<String>()

        if (masterKey == null) {
            warnings += "Ohne die Datei com.sonelli.juicessh_preferences.xml lassen sich " +
                "Passwoerter und Private Keys nicht entschluesseln. Alles andere wurde " +
                "uebernommen; die Zugangsdaten muessen neu hinterlegt werden."
        }

        JuiceSshReader.open(backup).use { reader ->
            // JuiceSSH-UUID -> eigene Room-ID, fuer alle Verweise zwischen den Tabellen.
            val groupIds = mutableMapOf<String, Long>()
            val connectionIds = mutableMapOf<String, Long>()
            val identityIds = mutableMapOf<String, Long>()
            val snippetIds = mutableMapOf<String, Long>()
            var skipped = 0

            // --- Snippets zuerst: Verbindungen und Identitaeten verweisen darauf. ---
            var importedSnippets = 0
            for (raw in reader.readSnippets()) {
                val name = raw.name?.takeIf { it.isNotBlank() } ?: continue
                val content = raw.content ?: ""
                snippetIds[raw.id] = snippets.create(name, content, publicId = raw.id)
                importedSnippets++
            }

            // --- Gruppen ---
            var importedGroups = 0
            for (raw in reader.readGroups()) {
                val name = raw.name?.takeIf { it.isNotBlank() } ?: continue
                val existing = groups.findByName(name)
                if (existing != null && duplicates == DuplicateStrategy.SKIP) {
                    groupIds[raw.id] = existing.id
                    skipped++
                    continue
                }
                groupIds[raw.id] = groups.create(
                    ConnectionGroupEntity(name = name, publicId = raw.id, createdAt = 0, updatedAt = 0),
                )
                importedGroups++
            }

            // --- Identitaeten ---
            var importedIdentities = 0
            for (raw in reader.readIdentities()) {
                val username = raw.username?.takeIf { it.isNotBlank() } ?: continue
                val secrets = decryptSecrets(raw, masterKey, warnings)
                identityIds[raw.id] = identities.create(
                    nickname = raw.nickname,
                    username = username,
                    password = secrets.password,
                    privateKey = secrets.privateKey,
                    privateKeyPassword = secrets.privateKeyPassword,
                )
                importedIdentities++
            }

            // --- Verbindungen, danach die Sprung-Ketten ---
            val counters = reader.readConnectionCounters()
            val sourceConnections = reader.readConnections()
            var importedConnections = 0
            for (raw in sourceConnections) {
                val address = raw.address?.takeIf { it.isNotBlank() } ?: continue
                connectionIds[raw.id] = connections.create(
                    ConnectionEntity(
                        // Die UUID aus JuiceSSH wird uebernommen, damit bereits
                        // installierte Plugins nach dem Import auf dieselben Verbindungen
                        // zeigen wie vorher.
                        publicId = raw.id,
                        nickname = raw.nickname,
                        address = address,
                        port = raw.port?.takeIf { it > 0 } ?: ConnectionEntity.DEFAULT_SSH_PORT,
                        type = JuiceSshMapping.connectionType(raw.type),
                        backspaceMode = JuiceSshMapping.backspaceMode(raw.backspaceMode),
                        startupSnippetId = raw.snippetId?.let(snippetIds::get),
                        moshCommand = raw.moshCommand,
                        useCount = counters[raw.id] ?: 0,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                )
                importedConnections++
            }

            // Erst jetzt aufloesbar: `via` kann auf eine Verbindung zeigen, die beim
            // Anlegen noch nicht existierte.
            for (raw in sourceConnections) {
                val viaSourceId = raw.viaId ?: continue
                val ownId = connectionIds[raw.id] ?: continue
                val viaId = connectionIds[viaSourceId]
                if (viaId == null) {
                    warnings += "Sprung-Host von \"${raw.nickname ?: raw.address}\" fehlt im " +
                        "Backup und wurde entfernt."
                    continue
                }
                connections.findById(ownId)?.let { connections.update(it.copy(viaConnectionId = viaId)) }
            }

            // --- Gruppenzugehoerigkeit ---
            for (membership in reader.readGroupMemberships()) {
                val groupId = membership.first?.let(groupIds::get) ?: continue
                val connectionId = membership.second?.let(connectionIds::get) ?: continue
                connectionDao.addToGroup(ConnectionGroupMembershipEntity(connectionId, groupId))
                // Die erste Gruppe wird zusaetzlich als primaere gesetzt, damit die
                // Verbindung in der nach Gruppen sortierten Liste auftaucht.
                connections.findById(connectionId)
                    ?.takeIf { it.groupId == null }
                    ?.let { connections.update(it.copy(groupId = groupId)) }
            }

            // --- Identitaeten an Verbindungen ---
            reader.readConnectionIdentities()
                .mapNotNull { link ->
                    val connectionId = link.first?.let(connectionIds::get) ?: return@mapNotNull null
                    val identityId = link.second?.let(identityIds::get) ?: return@mapNotNull null
                    connectionId to identityId
                }
                .groupBy({ it.first }, { it.second })
                .forEach { (connectionId, ids) -> connections.setIdentities(connectionId, ids) }

            // --- Port-Forwards ---
            var importedPortForwards = 0
            for (raw in reader.readPortForwards()) {
                val connectionId = raw.connectionId?.let(connectionIds::get)
                if (connectionId == null) {
                    warnings += "Port-Forward \"${raw.name ?: raw.id}\" gehoert zu keiner " +
                        "importierten Verbindung und wurde uebersprungen."
                    continue
                }
                portForwards.create(
                    PortForwardEntity(
                        publicId = raw.id,
                        name = raw.name,
                        connectionId = connectionId,
                        mode = JuiceSshMapping.portForwardMode(raw.mode),
                        localPort = raw.localPort ?: 0,
                        targetHost = raw.host,
                        remotePort = raw.remotePort,
                        openInBrowser = raw.openInBrowser,
                        useAsProxy = raw.useAsProxy,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                )
                importedPortForwards++
            }

            // --- Known Hosts ---
            var importedKnownHosts = 0
            for (raw in reader.readKnownHosts()) {
                val parsed = JuiceSshMapping.parseHostname(raw.hostname) ?: continue
                val key = raw.key?.takeIf { it.isNotBlank() } ?: continue
                val (hostname, port) = parsed
                knownHosts.trust(
                    hostname = hostname,
                    port = port,
                    keyType = JuiceSshMapping.hostKeyType(key, raw.type),
                    publicKey = key,
                    fingerprint = JuiceSshMapping.fingerprint(raw.fingerprint, key),
                )
                importedKnownHosts++
            }

            ImportReport(
                importedConnections = importedConnections,
                importedGroups = importedGroups,
                importedIdentities = importedIdentities,
                importedSnippets = importedSnippets,
                importedPortForwards = importedPortForwards,
                importedKnownHosts = importedKnownHosts,
                skippedDuplicates = skipped,
                warnings = warnings.distinct(),
            )
        }
    }

    private fun decryptSecrets(
        raw: RawIdentity,
        masterKey: String?,
        warnings: MutableList<String>,
    ): Secrets {
        if (masterKey == null) return Secrets()
        return try {
            Secrets(
                password = JuiceSshCrypto.decrypt(raw.passwordBlob, masterKey),
                privateKey = JuiceSshCrypto.decrypt(raw.privateKeyBlob, masterKey),
                privateKeyPassword = JuiceSshCrypto.decrypt(raw.privateKeyPasswordBlob, masterKey),
            )
        } catch (e: JuiceSshDecryptionException) {
            warnings += "Zugangsdaten von \"${raw.nickname ?: raw.username}\" liessen sich " +
                "nicht entschluesseln und wurden ausgelassen."
            Secrets()
        }
    }

    private data class Secrets(
        val password: String? = null,
        val privateKey: String? = null,
        val privateKeyPassword: String? = null,
    )
}
