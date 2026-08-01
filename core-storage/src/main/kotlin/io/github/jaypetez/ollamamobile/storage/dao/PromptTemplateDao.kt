package io.github.jaypetez.ollamamobile.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import io.github.jaypetez.ollamamobile.storage.entity.PromptTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptTemplateDao {
    @Query("SELECT * FROM prompt_templates ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates WHERE category = :category ORDER BY title COLLATE NOCASE ASC")
    fun observeByCategory(category: String): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates ORDER BY usageCount DESC, title COLLATE NOCASE ASC LIMIT :limit")
    fun observeMostUsed(limit: Int): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates WHERE id = :id")
    suspend fun find(id: String): PromptTemplateEntity?

    @Upsert
    suspend fun upsert(template: PromptTemplateEntity)

    @Upsert
    suspend fun upsertAll(templates: List<PromptTemplateEntity>)

    @Delete
    suspend fun delete(template: PromptTemplateEntity)

    /** Built-ins are cloned to be edited, never removed by a sync. */
    @Query("DELETE FROM prompt_templates WHERE id = :id AND builtIn = 0")
    suspend fun deleteUserTemplate(id: String)

    @Query("UPDATE prompt_templates SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun recordUse(id: String)
}
