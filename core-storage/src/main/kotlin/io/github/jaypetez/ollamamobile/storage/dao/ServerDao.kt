package io.github.jaypetez.ollamamobile.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import io.github.jaypetez.ollamamobile.storage.entity.ServerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM servers WHERE enabled = 1 ORDER BY sortOrder ASC, label COLLATE NOCASE ASC")
    fun observeEnabled(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observe(id: String): Flow<ServerConfigEntity?>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun find(id: String): ServerConfigEntity?

    @Query("SELECT * FROM servers WHERE baseUrl = :baseUrl")
    suspend fun findByBaseUrl(baseUrl: String): ServerConfigEntity?

    @Upsert
    suspend fun upsert(server: ServerConfigEntity)

    @Delete
    suspend fun delete(server: ServerConfigEntity)

    /**
     * Deletes the row only. The caller must also drop the server's secrets and
     * cached models — a "forget server" that leaves the bearer token in the
     * Keystore-backed store behind is a bug, not an optimisation.
     */
    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE servers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE servers SET lastSeenAt = :timestamp WHERE id = :id")
    suspend fun markSeen(id: String, timestamp: Long)

    /**
     * A changed certificate is either a renewal or an attack and only the user
     * can tell you which, so this is only ever called from an explicit accept.
     */
    @Query("UPDATE servers SET pinnedCertSha256 = :pin, allowPinnedSelfSignedTls = 1 WHERE id = :id")
    suspend fun setPin(id: String, pin: String)

    @Query("UPDATE servers SET pinnedCertSha256 = NULL, allowPinnedSelfSignedTls = 0 WHERE id = :id")
    suspend fun clearPin(id: String)
}
