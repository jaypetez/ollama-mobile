package io.github.jaypetez.ollamamobile.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.jaypetez.ollamamobile.storage.FtsSchema
import io.github.jaypetez.ollamamobile.storage.entity.RagChunkEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagDocumentEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagDocumentStatusColumn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Dao
abstract class RagDao {
    // --- documents -----------------------------------------------------

    @Query("SELECT * FROM rag_documents ORDER BY createdAt DESC")
    abstract fun observeDocuments(): Flow<List<RagDocumentEntity>>

    @Query("SELECT * FROM rag_documents WHERE status = :status ORDER BY createdAt ASC")
    abstract fun observeDocumentsByStatus(status: String): Flow<List<RagDocumentEntity>>

    @Query("SELECT * FROM rag_documents WHERE id = :id")
    abstract suspend fun findDocument(id: String): RagDocumentEntity?

    @Upsert
    abstract suspend fun upsertDocument(document: RagDocumentEntity)

    @Query("DELETE FROM rag_documents WHERE id = :id")
    abstract suspend fun deleteDocument(id: String)

    @Query(
        "UPDATE rag_documents SET status = :status, errorMessage = :errorMessage, " +
            "chunkCount = :chunkCount, indexedAt = :indexedAt WHERE id = :id",
    )
    abstract suspend fun setDocumentStatus(
        id: String,
        status: String,
        errorMessage: String?,
        chunkCount: Int,
        indexedAt: Long?,
    )

    // --- chunks --------------------------------------------------------

    @Query("SELECT * FROM rag_chunks WHERE documentId = :documentId ORDER BY ordinal ASC")
    abstract fun observeChunks(documentId: String): Flow<List<RagChunkEntity>>

    @Query("SELECT * FROM rag_chunks WHERE uuid = :uuid")
    abstract suspend fun findChunk(uuid: String): RagChunkEntity?

    @Query("SELECT * FROM rag_chunks WHERE uuid IN (:uuids)")
    abstract suspend fun findChunks(uuids: List<String>): List<RagChunkEntity>

    /**
     * The candidate set for the dense scan.
     *
     * Scoped to documents indexed with one embedding model, because vectors
     * from two models are not comparable and mixing them produces
     * plausible-looking scores over nonsense.
     */
    @Query(
        "SELECT rag_chunks.* FROM rag_chunks " +
            "JOIN rag_documents ON rag_documents.id = rag_chunks.documentId " +
            "WHERE rag_chunks.embedding IS NOT NULL " +
            "AND rag_documents.embeddingModelId = :embeddingModelId " +
            "AND rag_documents.status = '" + RagDocumentStatusColumn.INDEXED + "'",
    )
    abstract suspend fun findEmbeddedChunks(embeddingModelId: String): List<RagChunkEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertChunks(chunks: List<RagChunkEntity>): List<Long>

    @Query("UPDATE rag_chunks SET embedding = :embedding, embeddingScale = :scale WHERE uuid = :uuid")
    abstract suspend fun setEmbedding(uuid: String, embedding: ByteArray, scale: Float)

    @Query("DELETE FROM rag_chunks WHERE documentId = :documentId")
    abstract suspend fun deleteChunks(documentId: String)

    @Query("SELECT COUNT(*) FROM rag_chunks WHERE documentId = :documentId AND embedding IS NOT NULL")
    abstract suspend fun countEmbeddedChunks(documentId: String): Int

    /** Reindexing replaces the chunk set wholesale; doing it in one transaction keeps the FTS index consistent. */
    @Transaction
    open suspend fun replaceChunks(documentId: String, chunks: List<RagChunkEntity>) {
        deleteChunks(documentId)
        if (chunks.isNotEmpty()) insertChunks(chunks)
    }

    // --- lexical retrieval ---------------------------------------------

    @RawQuery(observedEntities = [RagChunkEntity::class])
    abstract fun searchChunksRaw(query: RoomRawQuery): Flow<List<RagChunkEntity>>

    /** The bm25 half of hybrid retrieval, ranked best-first. */
    open fun searchChunks(text: String, limit: Int = DEFAULT_SEARCH_LIMIT): Flow<List<RagChunkEntity>> {
        val match = FtsSchema.sanitizeMatchQuery(text)
        if (match.isEmpty()) return flowOf(emptyList())
        return searchChunksRaw(FtsSchema.ragChunkSearchQuery(match, limit))
    }

    companion object {
        const val DEFAULT_SEARCH_LIMIT: Int = 50
    }
}
