package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A document that has been (or is being) indexed for retrieval.
 *
 * [embeddingModelId] and [embeddingDimensions] are part of the index format,
 * not decoration: vectors from two different embedding models are not
 * comparable, and comparing them produces plausible-looking scores over
 * nonsense results. A mismatch at query time is a hard error.
 */
@Entity(
    tableName = "rag_documents",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["status"]),
    ],
)
data class RagDocumentEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val uri: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val chunkCount: Int = 0,
    val embeddingModelId: String? = null,
    val embeddingDimensions: Int? = null,
    /** One of [RagDocumentStatusColumn]. */
    @ColumnInfo(defaultValue = "pending")
    val status: String = RagDocumentStatusColumn.PENDING,
    val errorMessage: String? = null,
    val createdAt: Long,
    val indexedAt: Long? = null,
)

/** Indexing lifecycle. Indexing is resumable at chunk granularity, so INDEXING is a durable state. */
object RagDocumentStatusColumn {
    const val PENDING: String = "pending"
    const val INDEXING: String = "indexing"
    const val INDEXED: String = "indexed"
    const val FAILED: String = "failed"
}
