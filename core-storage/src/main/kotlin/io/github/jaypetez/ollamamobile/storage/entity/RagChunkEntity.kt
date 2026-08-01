package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One retrievable chunk of a document, with its int8 embedding.
 *
 * ## Why there are two identifiers
 *
 * Exactly the same reason as `MessageEntity`: this table is the content table
 * of the `rag_chunk_fts` FTS5 external-content virtual table, and FTS5 joins an
 * external content table on an **INTEGER rowid**. `content_rowid=` must name an
 * `INTEGER PRIMARY KEY` column — SQLite's alias for the implicit rowid — so a
 * `TEXT` primary key is not an option. [rowId] is that physical key; [uuid]
 * carries the domain `ChunkId` that citations reference and that survives a
 * reindex only if the chunking is unchanged.
 *
 * [embedding] is the L2-normalised vector quantised to int8, with
 * [embeddingScale] as the per-vector dequantisation factor. Cosine similarity
 * is then `dot_int8(a, b) * a.scale * b.scale` with no per-candidate
 * normalisation.
 */
@Entity(
    tableName = "rag_chunks",
    foreignKeys = [
        ForeignKey(
            entity = RagDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["documentId", "ordinal"]),
    ],
)
class RagChunkEntity(
    /** FTS5 external-content rowid. See the class doc before changing this. */
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    /** The domain `ChunkId`. */
    val uuid: String,
    val documentId: String,
    /** Position within the document; the citation renders as "chunk N". */
    val ordinal: Int,
    /**
     * The chunk body, already prefixed with the document title and section
     * heading if the chunker added them — this is the exact text that was
     * embedded, so it must be the exact text that is indexed for bm25.
     */
    val text: String,
    /** Indexed as a separate FTS column so bm25 can weight a heading hit higher than a body hit. */
    val heading: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val tokenCount: Int? = null,
    /** int8 vector, `dimensions` bytes. Null until the embedding job reaches this chunk. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,
    val embeddingScale: Float? = null,
) {
    // Not a data class: the generated equals would compare `embedding` by
    // identity, so two rows read back from the same query would test unequal.
    // Room does not need equals at all; tests and set semantics do.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RagChunkEntity) return false
        return rowId == other.rowId &&
            uuid == other.uuid &&
            documentId == other.documentId &&
            ordinal == other.ordinal &&
            text == other.text &&
            heading == other.heading &&
            startOffset == other.startOffset &&
            endOffset == other.endOffset &&
            tokenCount == other.tokenCount &&
            embedding.contentEqualsOrBothNull(other.embedding) &&
            embeddingScale == other.embeddingScale
    }

    override fun hashCode(): Int {
        var result = rowId.hashCode()
        result = 31 * result + uuid.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + ordinal
        result = 31 * result + text.hashCode()
        result = 31 * result + (heading?.hashCode() ?: 0)
        result = 31 * result + (startOffset ?: 0)
        result = 31 * result + (endOffset ?: 0)
        result = 31 * result + (tokenCount ?: 0)
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + (embeddingScale?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RagChunkEntity(rowId=$rowId, uuid=$uuid, documentId=$documentId, ordinal=$ordinal, " +
            "textLength=${text.length}, embeddingBytes=${embedding?.size ?: 0})"
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
