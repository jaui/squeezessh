package dev.jaui.squeezessh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.IdentityEntity
import dev.jaui.squeezessh.data.db.entity.KnownHostEntity
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.db.entity.SnippetEntity
import dev.jaui.squeezessh.data.model.HostKeyType
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionGroupDao {

    @Query("SELECT * FROM connection_group ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ConnectionGroupEntity>>

    @Query("SELECT * FROM connection_group WHERE id = :id")
    suspend fun findById(id: Long): ConnectionGroupEntity?

    @Query("SELECT * FROM connection_group WHERE name = :name COLLATE NOCASE")
    suspend fun findByName(name: String): ConnectionGroupEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: ConnectionGroupEntity): Long

    @Update
    suspend fun update(group: ConnectionGroupEntity)

    @Delete
    suspend fun delete(group: ConnectionGroupEntity)
}

@Dao
interface IdentityDao {

    @Query("SELECT * FROM identity ORDER BY COALESCE(NULLIF(nickname, ''), username) COLLATE NOCASE")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identity WHERE id = :id")
    suspend fun findById(id: Long): IdentityEntity?

    @Query("SELECT * FROM identity WHERE id = :id")
    fun observeById(id: Long): Flow<IdentityEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(identity: IdentityEntity): Long

    @Update
    suspend fun update(identity: IdentityEntity)

    @Delete
    suspend fun delete(identity: IdentityEntity)

    /** Wie viele Verbindungen die Identitaet nutzen — Warnung vor dem Loeschen. */
    @Query("SELECT COUNT(*) FROM connection_identity WHERE identityId = :identityId")
    suspend fun usageCount(identityId: Long): Int
}

@Dao
interface KnownHostDao {

    @Query("SELECT * FROM known_host ORDER BY hostname COLLATE NOCASE, port")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_host WHERE hostname = :hostname AND port = :port")
    suspend fun findFor(hostname: String, port: Int): List<KnownHostEntity>

    @Query(
        "SELECT * FROM known_host WHERE hostname = :hostname AND port = :port AND keyType = :keyType",
    )
    suspend fun findFor(hostname: String, port: Int, keyType: HostKeyType): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(host: KnownHostEntity): Long

    @Update
    suspend fun update(host: KnownHostEntity)

    @Delete
    suspend fun delete(host: KnownHostEntity)

    @Query("UPDATE known_host SET lastSeenAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    /** Entspricht `action:clear_known_hosts` aus den JuiceSSH-Einstellungen. */
    @Query("DELETE FROM known_host")
    suspend fun deleteAll()
}

@Dao
interface PortForwardDao {

    @Query("SELECT * FROM port_forward ORDER BY localPort")
    fun observeAll(): Flow<List<PortForwardEntity>>

    @Query("SELECT * FROM port_forward WHERE connectionId = :connectionId ORDER BY localPort")
    fun observeForConnection(connectionId: Long): Flow<List<PortForwardEntity>>

    @Query("SELECT * FROM port_forward WHERE connectionId = :connectionId ORDER BY localPort")
    suspend fun forConnection(connectionId: Long): List<PortForwardEntity>

    @Query("SELECT * FROM port_forward WHERE id = :id")
    suspend fun findById(id: Long): PortForwardEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(portForward: PortForwardEntity): Long

    @Update
    suspend fun update(portForward: PortForwardEntity)

    @Delete
    suspend fun delete(portForward: PortForwardEntity)
}

@Dao
interface SnippetDao {

    @Query("SELECT * FROM snippet ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippet ORDER BY useCount DESC, name COLLATE NOCASE LIMIT :limit")
    fun observeMostUsed(limit: Int = 10): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippet WHERE id = :id")
    suspend fun findById(id: Long): SnippetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snippet: SnippetEntity): Long

    @Update
    suspend fun update(snippet: SnippetEntity)

    @Delete
    suspend fun delete(snippet: SnippetEntity)

    @Query("UPDATE snippet SET useCount = useCount + 1 WHERE id = :id")
    suspend fun recordUse(id: Long)
}
