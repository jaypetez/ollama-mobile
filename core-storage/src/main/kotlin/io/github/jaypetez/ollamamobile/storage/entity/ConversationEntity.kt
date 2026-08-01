package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chat thread.
 *
 * The sampling parameters are flattened into columns rather than stored as a
 * JSON blob so the settings screen can query "which conversations override the
 * default temperature" without deserialising every row. Every one of them is
 * nullable because null means "use the engine/server default", which is a
 * distinct state from any numeric value.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["modelId"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String? = null,
    val systemPrompt: String? = null,
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val archived: Boolean = false,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    val repeatLastN: Int? = null,
    val seed: Long? = null,
    val numPredict: Int? = null,
    val numCtx: Int? = null,
    val stopSequences: List<String> = emptyList(),
)
