package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A reusable system prompt or prompt snippet. */
@Entity(
    tableName = "prompt_templates",
    indices = [Index(value = ["category"]), Index(value = ["usageCount"])],
)
data class PromptTemplateEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val body: String,
    val category: String? = null,
    /** Shipped with the app: editable only by cloning, never deleted by a sync. */
    @ColumnInfo(defaultValue = "0")
    val builtIn: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val usageCount: Int = 0,
)
