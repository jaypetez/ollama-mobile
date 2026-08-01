package io.github.jaypetez.ollamamobile.storage.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jaypetez.ollamamobile.storage.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE key = :key")
    fun observeValue(key: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun findValue(key: String): String?

    /** Prefix scan for the per-model and per-server override namespaces. */
    @Query("SELECT * FROM settings WHERE key LIKE :prefix || '%'")
    fun observeWithPrefix(prefix: String): Flow<List<SettingEntity>>

    @Upsert
    suspend fun upsert(setting: SettingEntity)

    @Upsert
    suspend fun upsertAll(settings: List<SettingEntity>)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM settings WHERE key LIKE :prefix || '%'")
    suspend fun deleteWithPrefix(prefix: String)
}
