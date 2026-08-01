package io.github.jaypetez.ollamamobile.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import io.github.jaypetez.ollamamobile.storage.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY favourite DESC, displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE originType = :originType ORDER BY displayName COLLATE NOCASE ASC")
    fun observeByOrigin(originType: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE serverId = :serverId ORDER BY displayName COLLATE NOCASE ASC")
    fun observeForServer(serverId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    fun observe(id: String): Flow<ModelEntity?>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun find(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ModelEntity>>

    @Upsert
    suspend fun upsert(model: ModelEntity)

    @Upsert
    suspend fun upsertAll(models: List<ModelEntity>)

    @Delete
    suspend fun delete(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Removing a server must take its cached model list with it. */
    @Query("DELETE FROM models WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    @Query("UPDATE models SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun markUsed(id: String, timestamp: Long)

    @Query("UPDATE models SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: String, favourite: Boolean)
}
