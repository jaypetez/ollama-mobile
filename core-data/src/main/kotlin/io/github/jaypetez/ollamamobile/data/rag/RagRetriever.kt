package io.github.jaypetez.ollamamobile.data.rag

import io.github.jaypetez.ollamamobile.common.dispatcher.AppDispatchers
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.storage.dao.RagDao
import io.github.jaypetez.ollamamobile.storage.entity.RagChunkEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A retrieved chunk with the text and provenance the injector needs. */
public data class RetrievalHit(
    public val chunkUuid: String,
    public val documentId: String,
    public val documentTitle: String,
    public val text: String,
    public val headingPath: String?,
    public val startOffset: Int?,
    public val endOffset: Int?,
    public val fusedScore: Double,
    public val lexicalRank: Int?,
    public val denseRank: Int?,
)

/**
 * Runs both retrievers and fuses them.
 *
 * The lexical half is FTS5 bm25 straight out of SQLite — the index is already
 * there, maintained by triggers, and it costs nothing to query. The dense half
 * loads the corpus' quantised vectors into a [VectorStore] and scans them. Both
 * sides are capped at [RetrievalConfig.candidatesPerSide] before
 * [ReciprocalRankFusion] combines them.
 *
 * ## On rebuilding the vector store per query
 *
 * The arena is rebuilt from the DAO on each retrieval rather than cached. At a
 * few thousand chunks that is a single indexed query and one contiguous copy —
 * cheap next to the query embedding it runs beside, which is a full forward pass
 * through a transformer. A cache would have to be invalidated by document
 * import, deletion, reindex and embedding-model change, and a stale vector index
 * is not a stale cache: it silently retrieves passages from a document the user
 * deleted.
 */
public class RagRetriever(
    private val ragDao: RagDao,
    private val dispatchers: AppDispatchers,
    private val config: RetrievalConfig = RetrievalConfig(),
) {
    private val fusion = ReciprocalRankFusion(config.fusionK)

    public suspend fun retrieve(
        query: String,
        embeddings: EmbeddingService,
        documentTitles: Map<String, String>,
    ): AppResult<List<RetrievalHit>> = withContext(dispatchers.default) {
        val modelId = embeddings.profile.modelId

        // Lexical first: it needs no model and, if the embedding call fails, a
        // bm25-only result is far better than no result. Retrieval degrading to
        // keyword search is a bad day; retrieval throwing is a broken feature.
        val lexicalHits = ragDao.searchChunks(query, config.candidatesPerSide).first()

        val denseHits = when (val queryVector = embeddings.embedQuery(query)) {
            is AppResult.Failure -> {
                if (lexicalHits.isEmpty()) return@withContext queryVector else emptyList()
            }

            is AppResult.Success -> {
                denseSearch(queryVector.value, modelId)
            }
        }

        val byUuid = HashMap<String, RagChunkEntity>()
        lexicalHits.forEach { byUuid[it.uuid] = it }
        val missing = denseHits.filter { it.chunkUuid !in byUuid }.map { it.chunkUuid }
        if (missing.isNotEmpty()) {
            ragDao.findChunks(missing).forEach { byUuid[it.uuid] = it }
        }

        val fused = fusion.fuse(
            lexical = lexicalHits.map { it.uuid },
            dense = denseHits.map { it.chunkUuid },
            limit = config.topK,
        )

        AppResult.Success(
            fused.mapNotNull { result ->
                val chunk = byUuid[result.chunkUuid] ?: return@mapNotNull null
                RetrievalHit(
                    chunkUuid = chunk.uuid,
                    documentId = chunk.documentId,
                    documentTitle = documentTitles[chunk.documentId] ?: chunk.documentId,
                    text = chunk.text,
                    headingPath = chunk.heading,
                    startOffset = chunk.startOffset,
                    endOffset = chunk.endOffset,
                    fusedScore = result.fusedScore,
                    lexicalRank = result.lexicalRank,
                    denseRank = result.denseRank,
                )
            },
        )
    }

    private suspend fun denseSearch(queryVector: FloatArray, modelId: String): List<ScoredChunk> {
        val embedded = ragDao.findEmbeddedChunks(modelId)
        if (embedded.isEmpty()) return emptyList()

        val entries = embedded.mapNotNull { chunk ->
            val bytes = chunk.embedding ?: return@mapNotNull null
            val scale = chunk.embeddingScale ?: return@mapNotNull null
            // A vector of a different width belongs to a different model and is
            // skipped rather than crashing the query: the DAO already filters by
            // model id, so reaching here means an interrupted model switch left
            // a stale row, and one bad row should not break search.
            if (bytes.size != queryVector.size) {
                null
            } else {
                VectorStore.Entry(chunk.uuid, QuantizedVector(bytes, scale))
            }
        }
        if (entries.isEmpty()) return emptyList()

        val store = VectorStore()
        store.load(entries)
        return store.search(queryVector, config.candidatesPerSide)
    }
}
