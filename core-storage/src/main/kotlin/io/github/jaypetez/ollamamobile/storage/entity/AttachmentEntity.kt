package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A file attached to a message.
 *
 * The bytes are never copied into the database — [uri] points at the original,
 * and [documentId] links to a RAG document when the attachment was indexed.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["messageUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageUuid"])],
)
data class AttachmentEntity(
    @PrimaryKey
    val id: String,
    val messageUuid: String,
    /** `AttachmentKind.name`. */
    val kind: String,
    val uri: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val displayName: String? = null,
    val documentId: String? = null,
)
