package dev.jaui.squeezessh.data.repository

import dev.jaui.squeezessh.data.crypto.SecretVault
import dev.jaui.squeezessh.data.db.dao.ConnectionDao
import dev.jaui.squeezessh.data.db.dao.ConnectionGroupDao
import dev.jaui.squeezessh.data.db.dao.IdentityDao
import dev.jaui.squeezessh.data.db.dao.KnownHostDao
import dev.jaui.squeezessh.data.db.dao.PortForwardDao
import dev.jaui.squeezessh.data.db.dao.SnippetDao
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.IdentityEntity
import dev.jaui.squeezessh.data.db.entity.KnownHostEntity
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.db.entity.SnippetEntity
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.util.TimeSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val dao: ConnectionDao,
    private val time: TimeSource,
) {
    fun observeAll(): Flow<List<ConnectionEntity>> = dao.observeAll()

    fun observeByGroup(groupId: Long): Flow<List<ConnectionEntity>> = dao.observeByGroup(groupId)

    fun observeFrequentlyUsed(limit: Int = 10): Flow<List<ConnectionEntity>> =
        dao.observeFrequentlyUsed(limit)

    fun observeById(id: Long): Flow<ConnectionEntity?> = dao.observeById(id)

    fun search(query: String): Flow<List<ConnectionEntity>> = dao.search(query)

    suspend fun findById(id: Long): ConnectionEntity? = dao.findById(id)

    /** Nachschlagen ueber die nach aussen sichtbare UUID; so adressiert die Plugin-API. */
    suspend fun findByPublicId(publicId: String?): ConnectionEntity? =
        publicId?.let { dao.findByPublicId(it) }

    suspend fun create(connection: ConnectionEntity): Long {
        val now = time.nowMillis()
        return dao.insert(connection.copy(createdAt = now, updatedAt = now))
    }

    suspend fun update(connection: ConnectionEntity) {
        dao.update(connection.copy(updatedAt = time.nowMillis()))
    }

    /**
     * Loescht eine Verbindung. Verbindungen, die ueber sie sprangen, verlieren dabei ihren
     * Sprung-Host — [dependentsOf] erlaubt es, vorher zu warnen.
     */
    suspend fun delete(connection: ConnectionEntity) = dao.delete(connection)

    suspend fun dependentsOf(connectionId: Long): List<ConnectionEntity> =
        dao.dependentsOf(connectionId)

    suspend fun recordUse(id: Long) = dao.recordUse(id, time.nowMillis())

    suspend fun identitiesFor(connectionId: Long): List<IdentityEntity> =
        dao.identitiesFor(connectionId)

    fun observeIdentitiesFor(connectionId: Long): Flow<List<IdentityEntity>> =
        dao.observeIdentitiesFor(connectionId)

    suspend fun setIdentities(connectionId: Long, identityIds: List<Long>) =
        dao.setIdentities(connectionId, identityIds)

    /**
     * Loest die Sprung-Kette auf, aeussere Verbindung zuerst.
     *
     * Eine zyklische Kette (A via B via A) wuerde sonst endlos laufen; sie wird hier
     * abgebrochen, sobald eine Verbindung ein zweites Mal auftaucht.
     */
    suspend fun resolveChain(connectionId: Long): List<ConnectionEntity> {
        val chain = mutableListOf<ConnectionEntity>()
        val seen = mutableSetOf<Long>()
        var current = dao.findById(connectionId)
        while (current != null && seen.add(current.id)) {
            chain += current
            current = current.viaConnectionId?.let { dao.findById(it) }
        }
        return chain.reversed()
    }
}

@Singleton
class ConnectionGroupRepository @Inject constructor(
    private val dao: ConnectionGroupDao,
    private val time: TimeSource,
) {
    fun observeAll(): Flow<List<ConnectionGroupEntity>> = dao.observeAll()

    suspend fun findById(id: Long): ConnectionGroupEntity? = dao.findById(id)

    /** Nachschlagen ueber die nach aussen sichtbare UUID; so adressiert die Plugin-API. */
    suspend fun findByPublicId(publicId: String?): ConnectionGroupEntity? =
        publicId?.let { dao.findByPublicId(it) }

    suspend fun findByName(name: String): ConnectionGroupEntity? = dao.findByName(name)

    /** Legt die Gruppe an oder liefert die bestehende gleichen Namens — idempotent fuer den Import. */
    suspend fun findOrCreate(name: String): ConnectionGroupEntity {
        dao.findByName(name)?.let { return it }
        val now = time.nowMillis()
        val id = dao.insert(ConnectionGroupEntity(name = name, createdAt = now, updatedAt = now))
        return checkNotNull(dao.findById(id))
    }

    suspend fun create(group: ConnectionGroupEntity): Long {
        val now = time.nowMillis()
        return dao.insert(group.copy(createdAt = now, updatedAt = now))
    }

    suspend fun update(group: ConnectionGroupEntity) =
        dao.update(group.copy(updatedAt = time.nowMillis()))

    suspend fun delete(group: ConnectionGroupEntity) = dao.delete(group)
}

/**
 * Identitaeten samt ihrer Geheimnisse.
 *
 * Klartext wird ausschliesslich ueber [revealPassword], [revealPrivateKey] und
 * [revealPrivateKeyPassword] herausgegeben, damit im UI-Layer kein Weg entsteht, ein
 * Geheimnis versehentlich zu protokollieren oder zu persistieren.
 */
@Singleton
class IdentityRepository @Inject constructor(
    private val dao: IdentityDao,
    private val vault: SecretVault,
    private val time: TimeSource,
) {
    fun observeAll(): Flow<List<IdentityEntity>> = dao.observeAll()

    fun observeById(id: Long): Flow<IdentityEntity?> = dao.observeById(id)

    suspend fun findById(id: Long): IdentityEntity? = dao.findById(id)

    suspend fun usageCount(identityId: Long): Int = dao.usageCount(identityId)

    suspend fun create(
        nickname: String?,
        username: String,
        password: String? = null,
        privateKey: String? = null,
        privateKeyPassword: String? = null,
        publicKey: String? = null,
    ): Long {
        val now = time.nowMillis()
        return dao.insert(
            IdentityEntity(
                nickname = nickname,
                username = username,
                passwordCipher = vault.seal(password),
                privateKeyCipher = vault.seal(privateKey),
                privateKeyPasswordCipher = vault.seal(privateKeyPassword),
                publicKey = publicKey,
                passwordUpdatedAt = password?.let { now },
                keyUpdatedAt = privateKey?.let { now },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Speichert Stammdaten. Geheimnisse bleiben unangetastet — dafuer gibt es [setPassword] und [setPrivateKey]. */
    suspend fun update(identity: IdentityEntity) =
        dao.update(identity.copy(updatedAt = time.nowMillis()))

    suspend fun setPassword(identityId: Long, password: String?) {
        val identity = dao.findById(identityId) ?: return
        val now = time.nowMillis()
        dao.update(
            identity.copy(
                passwordCipher = vault.seal(password),
                passwordUpdatedAt = password?.let { now },
                updatedAt = now,
            ),
        )
    }

    suspend fun setPrivateKey(
        identityId: Long,
        privateKey: String?,
        privateKeyPassword: String? = null,
        publicKey: String? = null,
    ) {
        val identity = dao.findById(identityId) ?: return
        val now = time.nowMillis()
        dao.update(
            identity.copy(
                privateKeyCipher = vault.seal(privateKey),
                privateKeyPasswordCipher = vault.seal(privateKeyPassword),
                publicKey = publicKey ?: identity.publicKey,
                keyUpdatedAt = privateKey?.let { now },
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(identity: IdentityEntity) = dao.delete(identity)

    fun revealPassword(identity: IdentityEntity): String? = vault.reveal(identity.passwordCipher)

    fun revealPrivateKey(identity: IdentityEntity): String? = vault.reveal(identity.privateKeyCipher)

    fun revealPrivateKeyPassword(identity: IdentityEntity): String? =
        vault.reveal(identity.privateKeyPasswordCipher)
}

@Singleton
class KnownHostRepository @Inject constructor(
    private val dao: KnownHostDao,
    private val time: TimeSource,
) {
    fun observeAll(): Flow<List<KnownHostEntity>> = dao.observeAll()

    suspend fun findFor(hostname: String, port: Int): List<KnownHostEntity> =
        dao.findFor(hostname, port)

    suspend fun findFor(hostname: String, port: Int, keyType: HostKeyType): KnownHostEntity? =
        dao.findFor(hostname, port, keyType)

    /**
     * Vergleicht einen praesentierten Host-Key mit dem gespeicherten.
     *
     * Ein [HostKeyVerdict.Changed] ist der Ernstfall: entweder wurde der Server neu
     * aufgesetzt oder es sitzt jemand dazwischen. Die Entscheidung darueber gehoert ins
     * UI, nicht hierher.
     */
    suspend fun verify(
        hostname: String,
        port: Int,
        keyType: HostKeyType,
        publicKey: String,
    ): HostKeyVerdict {
        val known = dao.findFor(hostname, port, keyType)
            ?: return HostKeyVerdict.Unknown
        return if (known.publicKey == publicKey) {
            dao.touch(known.id, time.nowMillis())
            HostKeyVerdict.Trusted
        } else {
            HostKeyVerdict.Changed(known)
        }
    }

    suspend fun trust(
        hostname: String,
        port: Int,
        keyType: HostKeyType,
        publicKey: String,
        fingerprint: String,
    ) {
        val now = time.nowMillis()
        val existing = dao.findFor(hostname, port, keyType)
        if (existing == null) {
            dao.insert(
                KnownHostEntity(
                    hostname = hostname,
                    port = port,
                    keyType = keyType,
                    publicKey = publicKey,
                    fingerprint = fingerprint,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
        } else {
            dao.update(
                existing.copy(publicKey = publicKey, fingerprint = fingerprint, lastSeenAt = now),
            )
        }
    }

    suspend fun forget(host: KnownHostEntity) = dao.delete(host)

    suspend fun forgetAll() = dao.deleteAll()
}

sealed interface HostKeyVerdict {
    /** Host-Key ist unbekannt — Trust on first use, der Nutzer muss den Fingerprint bestaetigen. */
    data object Unknown : HostKeyVerdict

    /** Host-Key stimmt mit dem gespeicherten ueberein. */
    data object Trusted : HostKeyVerdict

    /** Host-Key weicht ab. Verbindung nicht ohne ausdrueckliche Bestaetigung fortsetzen. */
    data class Changed(val known: KnownHostEntity) : HostKeyVerdict
}

@Singleton
class PortForwardRepository @Inject constructor(
    private val dao: PortForwardDao,
    private val time: TimeSource,
) {
    fun observeAll(): Flow<List<PortForwardEntity>> = dao.observeAll()

    fun observeForConnection(connectionId: Long): Flow<List<PortForwardEntity>> =
        dao.observeForConnection(connectionId)

    suspend fun forConnection(connectionId: Long): List<PortForwardEntity> =
        dao.forConnection(connectionId)

    suspend fun findById(id: Long): PortForwardEntity? = dao.findById(id)

    /** Nachschlagen ueber die nach aussen sichtbare UUID; so adressiert die Plugin-API. */
    suspend fun findByPublicId(publicId: String?): PortForwardEntity? =
        publicId?.let { dao.findByPublicId(it) }

    suspend fun create(portForward: PortForwardEntity): Long {
        val now = time.nowMillis()
        return dao.insert(portForward.copy(createdAt = now, updatedAt = now))
    }

    suspend fun update(portForward: PortForwardEntity) =
        dao.update(portForward.copy(updatedAt = time.nowMillis()))

    suspend fun delete(portForward: PortForwardEntity) = dao.delete(portForward)
}

@Singleton
class SnippetRepository @Inject constructor(
    private val dao: SnippetDao,
    private val time: TimeSource,
) {
    fun observeAll(): Flow<List<SnippetEntity>> = dao.observeAll()

    fun observeMostUsed(limit: Int = 10): Flow<List<SnippetEntity>> = dao.observeMostUsed(limit)

    suspend fun findById(id: Long): SnippetEntity? = dao.findById(id)

    /** Nachschlagen ueber die nach aussen sichtbare UUID; so adressiert die Plugin-API. */
    suspend fun findByPublicId(publicId: String?): SnippetEntity? =
        publicId?.let { dao.findByPublicId(it) }

    /**
     * [publicId] setzt der Import, um die UUID aus JuiceSSH zu uebernehmen — sonst wird eine
     * neue erzeugt.
     */
    suspend fun create(name: String, content: String, publicId: String? = null): Long {
        val now = time.nowMillis()
        val snippet = SnippetEntity(name = name, content = content, createdAt = now, updatedAt = now)
        return dao.insert(publicId?.let { snippet.copy(publicId = it) } ?: snippet)
    }

    suspend fun update(snippet: SnippetEntity) =
        dao.update(snippet.copy(updatedAt = time.nowMillis()))

    suspend fun delete(snippet: SnippetEntity) = dao.delete(snippet)

    suspend fun recordUse(id: Long) = dao.recordUse(id)
}
