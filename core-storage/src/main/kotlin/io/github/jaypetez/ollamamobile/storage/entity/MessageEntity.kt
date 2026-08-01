package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One turn in a conversation.
 *
 * ## Why there are two identifiers
 *
 * [rowId] looks like gratuitous duplication of [uuid] and is not. This table is
 * the *content table* of the `message_fts` FTS5 external-content virtual table
 * (see `FtsSchema`). FTS5 joins an external content table on an **INTEGER
 * rowid** — `content_rowid=` must name an `INTEGER PRIMARY KEY` column, which
 * is SQLite's alias for the implicit rowid. A `TEXT` primary key cannot be used
 * there at all: the virtual table would have no way to fetch the row backing a
 * hit, and the sync triggers would have nothing to pass to the `'delete'`
 * command.
 *
 * So the physical key is [rowId] and the domain key — the `MessageId` the rest
 * of the app passes around, stable across export/import and referenced by
 * attachments and citations — is [uuid], carried in a separate uniquely indexed
 * column. Foreign keys from other tables point at [uuid], not [rowId], because
 * [rowId] is an implementation detail of the search index.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["conversationId", "createdAt"]),
    ],
)
data class MessageEntity(
    /** FTS5 external-content rowid. See the class doc before changing this. */
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    /** The domain `MessageId`. */
    val uuid: String,
    val conversationId: String,
    /** `Role.wireName`. Stored as text so an unknown role from an import survives a round trip. */
    val role: String,
    val content: String,
    val createdAt: Long,
    val reasoning: String? = null,
    /** One of [MessageStatusColumn]. */
    @ColumnInfo(defaultValue = "complete")
    val status: String = MessageStatusColumn.COMPLETE,
    /** Set only when [status] is [MessageStatusColumn.FAILED]. */
    val errorMessage: String? = null,
    /** Which model produced this turn; kept per message because a thread can switch models. */
    val modelId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val promptEvalNanos: Long? = null,
    val evalNanos: Long? = null,
    val loadNanos: Long? = null,
    val totalNanos: Long? = null,
)

/** The persisted spelling of `MessageStatus`. */
object MessageStatusColumn {
    const val PENDING: String = "pending"
    const val COMPLETE: String = "complete"
    const val FAILED: String = "failed"
}
