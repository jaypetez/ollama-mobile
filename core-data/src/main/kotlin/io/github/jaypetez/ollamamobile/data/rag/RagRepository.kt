package io.github.jaypetez.ollamamobile.data.rag

import android.net.Uri
import io.github.jaypetez.ollamamobile.common.dispatcher.AppDispatchers
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.storage.dao.MessageDao
import io.github.jaypetez.ollamamobile.storage.dao.RagDao
import io.github.jaypetez.ollamamobile.storage.entity.MessageCitationEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagChunkEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagDocumentEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagDocumentStatusColumn
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A document as the UI sees it. */
public data class RagDocument(
    public val id: String,
    public val title: String,
    public val uri: String,
    public val state: IndexState,
    public val chunkCount: Int,
    /** Live embedding progress while a worker is running, else null. */
    public val liveProgress: Float?,
    public val sizeBytes: Long?,
    public val embeddingModelId: String?,
    public val errorMessage: String?,
) {
    /** 0f..1f, or null while the chunk count is not yet known. */
    public val progress: Float?
        get() = when {
            state == IndexState.INDEXED -> 1f
            state == IndexState.INDEXING -> liveProgress
            else -> null
        }
}

/** Mirrors [RagDocumentStatusColumn], typed. */
public enum class IndexState {
    PENDING,
    INDEXING,
    INDEXED,
    FAILED,
    ;

    public companion object {
        public fun fromColumn(value: String): IndexState = when (value) {
            RagDocumentStatusColumn.INDEXING -> INDEXING
            RagDocumentStatusColumn.INDEXED -> INDEXED
            RagDocumentStatusColumn.FAILED -> FAILED
            else -> PENDING
        }
    }
}

/**
 * The RAG feature's data surface.
 *
 * Import, index, delete, retrieve, cite. The UI and the indexing worker both go
 * through here so the status transitions live in exactly one place — a document
 * that is `indexing` in the database while nothing is running is a row that
 * shows a spinner forever, and that happens whenever two callers can write the
 * status column.
 */
@Singleton
public class RagRepository
    @Inject
    constructor(
        private val ragDao: RagDao,
        private val messageDao: MessageDao,
        private val extractor: TextExtractor,
        private val dispatchers: AppDispatchers,
    ) {
        private val chunker = Chunker()
        private val injector = RagContextInjector()

        /**
         * Live per-document embedding progress, keyed by document id.
         *
         * In memory rather than a column because it changes once per slice of eight
         * chunks — writing that to SQLite would be thousands of transactions per
         * import, each one waking every observer of the documents table. The
         * durable state is the status column plus the count of chunks that already
         * have a vector; this is only the smooth number for the bar.
         */
        private val _indexProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
        public val indexProgress: StateFlow<Map<String, Float>> = _indexProgress.asStateFlow()

        public fun observeDocuments(): Flow<List<RagDocument>> =
            combine(ragDao.observeDocuments(), indexProgress) { rows, progress ->
                rows.map { it.toDomain(progress[it.id]) }
            }

        /**
         * Registers an imported file. Does not index it — [DocumentIndexWorker] does.
         *
         * Split because import is interactive (the user is looking at a file picker)
         * and indexing is not (it is minutes of model inference). Doing both here
         * would block the picker's callback on the whole pipeline.
         */
        public suspend fun import(
            uri: Uri,
            title: String,
            mimeType: String?,
            sizeBytes: Long?,
        ): AppResult<String> =
            withContext(dispatchers.io) {
                // Reject the format now, while the user is still in the flow and can
                // pick a different file, rather than at index time when the failure
                // is a red row in a list they have navigated away from.
                if (TextExtractor.SupportedFormat.of(mimeType, title) == null) {
                    return@withContext AppResult.Failure(
                        AppError.Storage.Io(UNSUPPORTED_FORMAT_MESSAGE),
                    )
                }
                val id = UUID.randomUUID().toString()
                ragDao.upsertDocument(
                    RagDocumentEntity(
                        id = id,
                        title = title,
                        uri = uri.toString(),
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        status = RagDocumentStatusColumn.PENDING,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                AppResult.Success(id)
            }

        public suspend fun delete(documentId: String): Unit = withContext(dispatchers.io) {
            // The chunk rows cascade from the foreign key, and the FTS triggers
            // follow them. Citations deliberately do not cascade: they hold a
            // snapshot of the quoted text, so an old conversation keeps showing what
            // the model was actually given even after the source is gone.
            ragDao.deleteDocument(documentId)
        }

        /**
         * Extracts, chunks and embeds one document.
         *
         * Chunks are written *before* their vectors so progress is observable and so
         * an interrupted run resumes instead of restarting: the rows exist with a
         * null embedding, and the next pass embeds only those. On a phone that is
         * the normal case, not the exception — the screen goes off, the process is
         * frozen, and the job resumes ten minutes later.
         */
        public suspend fun index(
            documentId: String,
            embeddings: EmbeddingService,
            onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        ): AppResult<Unit> = withContext(dispatchers.default) {
            val document = ragDao.findDocument(documentId)
                ?: return@withContext AppResult.Failure(
                    AppError.Storage.NotFound("document $documentId"),
                )

            val indexing = RagDocumentStatusColumn.INDEXING
            ragDao.setDocumentStatus(documentId, indexing, null, document.chunkCount, null)

            val text = try {
                extractor.extract(Uri.parse(document.uri), document.mimeType, document.title)
            } catch (error: AppErrorException) {
                return@withContext fail(documentId, error.error)
            }

            val chunks = chunker.chunk(text)
            if (chunks.isEmpty()) {
                val empty = AppError.Storage.Io("${document.title} contains no indexable text.")
                return@withContext fail(documentId, empty)
            }

            val rows = chunks.map { chunk ->
                RagChunkEntity(
                    uuid = UUID.randomUUID().toString(),
                    documentId = documentId,
                    ordinal = chunk.ordinal,
                    text = chunk.text,
                    heading = chunk.headingPath,
                    startOffset = chunk.startOffset,
                    endOffset = chunk.endOffset,
                    tokenCount = chunk.tokenCount,
                )
            }
            ragDao.replaceChunks(documentId, rows)
            ragDao.setDocumentStatus(documentId, indexing, null, rows.size, null)
            publishProgress(documentId, 0f)
            onProgress(0, rows.size)

            // Embedded in slices so progress advances and so a cancellation loses at
            // most one slice of work rather than the whole document.
            var done = 0
            for (slice in rows.zip(chunks).chunked(EMBED_SLICE)) {
                val texts = slice.map { it.second.embeddableText }
                val vectors = when (val result = embeddings.embedDocuments(texts)) {
                    is AppResult.Failure -> return@withContext fail(documentId, result.error)
                    is AppResult.Success -> result.value
                }
                slice.forEachIndexed { index, (row, _) ->
                    val quantized = VectorQuantizer.quantize(vectors[index])
                    ragDao.setEmbedding(row.uuid, quantized.bytes, quantized.scale)
                }
                done += slice.size
                publishProgress(documentId, done.toFloat() / rows.size)
                onProgress(done, rows.size)
            }

            ragDao.setDocumentStatus(
                id = documentId,
                status = RagDocumentStatusColumn.INDEXED,
                errorMessage = null,
                chunkCount = rows.size,
                indexedAt = System.currentTimeMillis(),
            )
            ragDao.upsertDocument(
                (ragDao.findDocument(documentId) ?: document).copy(
                    embeddingModelId = embeddings.profile.modelId,
                    embeddingDimensions = embeddings.profile.dimensions,
                ),
            )
            clearProgress(documentId)
            AppResult.Success(Unit)
        }

        /**
         * Retrieves for [query] and returns the prompt block plus unsaved citations.
         *
         * The citations are returned rather than written because the assistant
         * message they reference does not exist yet — it is about to be streamed.
         * [saveCitations] is called once it does.
         */
        public suspend fun buildContext(
            query: String,
            embeddings: EmbeddingService,
            assistantMessageUuid: String,
        ): AppResult<InjectedContext> {
            val titles = ragDao.observeDocuments().first().associate { it.id to it.title }

            val retriever = RagRetriever(ragDao, dispatchers)
            return when (val hits = retriever.retrieve(query, embeddings, titles)) {
                is AppResult.Failure -> hits
                is AppResult.Success -> AppResult.Success(injector.inject(hits.value, assistantMessageUuid))
            }
        }

        public suspend fun saveCitations(citations: List<MessageCitationEntity>): Unit =
            withContext(dispatchers.io) {
                if (citations.isNotEmpty()) messageDao.insertCitations(citations)
            }

        public fun observeCitations(messageUuid: String): Flow<List<MessageCitationEntity>> =
            messageDao.observeCitations(messageUuid)

        /** The chunk behind a citation chip, for "open the source span". */
        public suspend fun findChunk(chunkUuid: String): RagChunkEntity? = ragDao.findChunk(chunkUuid)

        private suspend fun fail(documentId: String, error: AppError): AppResult<Unit> {
            ragDao.setDocumentStatus(documentId, RagDocumentStatusColumn.FAILED, error.message, 0, null)
            clearProgress(documentId)
            return AppResult.Failure(error)
        }

        private fun publishProgress(documentId: String, fraction: Float) {
            _indexProgress.update { it + (documentId to fraction.coerceIn(0f, 1f)) }
        }

        private fun clearProgress(documentId: String) {
            _indexProgress.update { it - documentId }
        }

        private fun RagDocumentEntity.toDomain(liveProgress: Float?) = RagDocument(
            id = id,
            title = title,
            uri = uri,
            state = IndexState.fromColumn(status),
            chunkCount = chunkCount,
            liveProgress = liveProgress,
            sizeBytes = sizeBytes,
            embeddingModelId = embeddingModelId,
            errorMessage = errorMessage,
        )

        private companion object {
            /** Chunks per progress step. Small enough that the bar moves, large enough to batch. */
            const val EMBED_SLICE = 8

            const val UNSUPPORTED_FORMAT_MESSAGE =
                "Only .txt and .md files can be indexed. PDF support is not available yet."
        }
    }
