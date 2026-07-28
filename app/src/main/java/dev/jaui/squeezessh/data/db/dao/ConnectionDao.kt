package dev.jaui.squeezessh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupMembershipEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionIdentityEntity
import dev.jaui.squeezessh.data.db.entity.IdentityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {

    @Query("SELECT * FROM connection ORDER BY COALESCE(NULLIF(nickname, ''), address) COLLATE NOCASE")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connection WHERE groupId = :groupId ORDER BY COALESCE(NULLIF(nickname, ''), address) COLLATE NOCASE")
    fun observeByGroup(groupId: Long): Flow<List<ConnectionEntity>>

    /**
     * Meistgenutzte Verbindungen. `useCount` schlaegt die Zeit; bei Gleichstand entscheidet
     * die juengste Nutzung, damit die Liste nicht auf alten Favoriten einfriert.
     */
    @Query(
        """
        SELECT * FROM connection
        WHERE useCount > 0
        ORDER BY useCount DESC, COALESCE(lastUsedAt, 0) DESC
        LIMIT :limit
        """,
    )
    fun observeFrequentlyUsed(limit: Int = 10): Flow<List<ConnectionEntity>>

    @Query(
        """
        SELECT * FROM connection
        WHERE nickname LIKE '%' || :query || '%' COLLATE NOCASE
           OR address  LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY COALESCE(NULLIF(nickname, ''), address) COLLATE NOCASE
        """,
    )
    fun search(query: String): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connection WHERE id = :id")
    suspend fun findById(id: Long): ConnectionEntity?

    @Query("SELECT * FROM connection WHERE id = :id")
    fun observeById(id: Long): Flow<ConnectionEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Delete
    suspend fun delete(connection: ConnectionEntity)

    @Query("UPDATE connection SET useCount = useCount + 1, lastUsedAt = :timestamp WHERE id = :id")
    suspend fun recordUse(id: Long, timestamp: Long)

    /**
     * Identitaeten einer Verbindung in der Reihenfolge, in der sie durchprobiert werden.
     *
     * Bewusst als expliziter Join statt ueber @Relation: die Sortierung steckt in der
     * Join-Tabelle, und @Relation kann darauf nicht sortieren.
     */
    @Query(
        """
        SELECT i.* FROM identity i
        INNER JOIN connection_identity ci ON ci.identityId = i.id
        WHERE ci.connectionId = :connectionId
        ORDER BY ci.sortOrder
        """,
    )
    suspend fun identitiesFor(connectionId: Long): List<IdentityEntity>

    @Query(
        """
        SELECT i.* FROM identity i
        INNER JOIN connection_identity ci ON ci.identityId = i.id
        WHERE ci.connectionId = :connectionId
        ORDER BY ci.sortOrder
        """,
    )
    fun observeIdentitiesFor(connectionId: Long): Flow<List<IdentityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun linkIdentity(link: ConnectionIdentityEntity)

    @Query("DELETE FROM connection_identity WHERE connectionId = :connectionId")
    suspend fun unlinkAllIdentities(connectionId: Long)

    @Transaction
    suspend fun setIdentities(connectionId: Long, identityIds: List<Long>) {
        unlinkAllIdentities(connectionId)
        identityIds.forEachIndexed { index, identityId ->
            linkIdentity(ConnectionIdentityEntity(connectionId, identityId, index))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToGroup(membership: ConnectionGroupMembershipEntity)

    @Delete
    suspend fun removeFromGroup(membership: ConnectionGroupMembershipEntity)

    @Query(
        """
        SELECT g.* FROM connection_group g
        INNER JOIN connection_group_membership m ON m.groupId = g.id
        WHERE m.connectionId = :connectionId
        ORDER BY g.sortOrder, g.name COLLATE NOCASE
        """,
    )
    suspend fun additionalGroupsFor(connectionId: Long): List<ConnectionGroupEntity>

    /**
     * Verbindungen, die ueber [connectionId] springen. Wird vor dem Loeschen gebraucht:
     * die Kette darf nicht ins Leere zeigen.
     */
    @Query("SELECT * FROM connection WHERE viaConnectionId = :connectionId")
    suspend fun dependentsOf(connectionId: Long): List<ConnectionEntity>
}
