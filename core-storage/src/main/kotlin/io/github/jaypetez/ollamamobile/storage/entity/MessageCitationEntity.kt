package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A retrieved chunk that was put in front of the model for a given message.
 *
 * A RAG answer without a citation is unverifiable, so this is written at the
 * same time as the message rather than reconstructed later — the retrieval
 * result is not reproducible once the index changes.
 *
 * [chunkUuid] is deliberately **not** a foreign key: reindexing a document
 * replaces its chunks, and a citation on an old answer should degrade to
 * "source no longer indexed" rather than delete the answer or block the
 * reindex. [quotedText] is the snapshot that keeps the citation readable in
 * that case.
 */
@Entity(
    tableName = "message_citations",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["messageUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageUuid", "rank"]),
        Index(value = ["chunkUuid"]),
    ],
)
data class MessageCitationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageUuid: String,
    val chunkUuid: String,
    val documentId: String,
    /** Position in the fused ranking, 0-based. */
    val rank: Int,
    /** The reciprocal-rank-fusion score; not comparable across queries. */
    val score: Double? = null,
    val quotedText: String? = null,
)
