package io.github.jaypetez.ollamamobile.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Update
import io.github.jaypetez.ollamamobile.storage.FtsSchema
import io.github.jaypetez.ollamamobile.storage.entity.AttachmentEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageCitationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An abstract class rather than an interface so the search helpers can build
 * their `RoomRawQuery` here instead of at every call site — sanitising the
 * user's text is not optional and must not be something a caller can forget.
 */
@Dao
abstract class MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    abstract fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt DESC LIMIT :limit OFFSET :offset",
    )
    abstract fun observePage(conversationId: String, limit: Int, offset: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE uuid = :uuid")
    abstract fun observe(uuid: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE uuid = :uuid")
    abstract suspend fun find(uuid: String): MessageEntity?

    @Query("SELECT * FROM attachments WHERE messageUuid = :uuid")
    abstract fun observeAttachments(uuid: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM message_citations WHERE messageUuid = :uuid ORDER BY rank ASC")
    abstract fun observeCitations(uuid: String): Flow<List<MessageCitationEntity>>

    /** Returns the assigned [MessageEntity.rowId]; the FTS insert trigger has already fired. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    abstract suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE uuid = :uuid")
    abstract suspend fun deleteByUuid(uuid: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteConversation(conversationId: String)

    /**
     * Appends streamed text.
     *
     * `content = content || :delta` rather than a read-modify-write so a
     * concurrent reader never observes a half-applied token, and so the FTS
     * update trigger sees exactly one change per delta.
     */
    @Query("UPDATE messages SET content = content || :delta WHERE uuid = :uuid")
    abstract suspend fun appendContent(uuid: String, delta: String)

    @Query(
        "UPDATE messages SET status = :status, errorMessage = :errorMessage, " +
            "completionTokens = :completionTokens, evalNanos = :evalNanos, totalNanos = :totalNanos " +
            "WHERE uuid = :uuid",
    )
    abstract suspend fun finish(
        uuid: String,
        status: String,
        errorMessage: String?,
        completionTokens: Int?,
        evalNanos: Long?,
        totalNanos: Long?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCitations(citations: List<MessageCitationEntity>)

    @Transaction
    open suspend fun insertWithAttachments(
        message: MessageEntity,
        attachments: List<AttachmentEntity>,
    ): Long {
        val rowId = insert(message)
        if (attachments.isNotEmpty()) insertAttachments(attachments)
        return rowId
    }

    // -----------------------------------------------------------------
    // Full-text search. @RawQuery because Room's query verifier only knows
    // about @Entity tables and would reject any @Query naming message_fts.
    // -----------------------------------------------------------------

    @RawQuery(observedEntities = [MessageEntity::class])
    abstract fun searchRaw(query: RoomRawQuery): Flow<List<MessageEntity>>

    /** Ranked best-first. Emits nothing for a query that tokenises to nothing. */
    open fun search(text: String, limit: Int = DEFAULT_SEARCH_LIMIT): Flow<List<MessageEntity>> {
        val match = FtsSchema.sanitizeMatchQuery(text)
        if (match.isEmpty()) return flowOf(emptyList())
        return searchRaw(FtsSchema.messageSearchQuery(match, limit))
    }

    open fun searchInConversation(
        conversationId: String,
        text: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): Flow<List<MessageEntity>> {
        val match = FtsSchema.sanitizeMatchQuery(text)
        if (match.isEmpty()) return flowOf(emptyList())
        return searchRaw(FtsSchema.messageSearchInConversationQuery(match, conversationId, limit))
    }

    companion object {
        const val DEFAULT_SEARCH_LIMIT: Int = 50
    }
}
