package io.github.jaypetez.ollamamobile.data.rag

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.OllamaClient

/**
 * Where the vectors come from.
 *
 * Local and remote are one interface because indexing does not care, and the
 * choice can change between two runs of the same document — the user plugs the
 * phone in and the worker switches to the local model, or the local model is
 * unloaded to make room for a bigger chat model and the worker falls back to a
 * server. What *cannot* change without a full reindex is the model, which is why
 * the profile travels with every request and is recorded on the document.
 */
public interface EmbeddingService {
    public val profile: EmbeddingModelProfile

    /** Embeds passages for the index. */
    public suspend fun embedDocuments(texts: List<String>): AppResult<List<FloatArray>>

    /** Embeds one search query. */
    public suspend fun embedQuery(text: String): AppResult<FloatArray>
}

/**
 * The on-device path.
 *
 * The engine here is an [LlamaEngine] holding a model loaded as
 * `EngineRole.EMBEDDING` — a *second* engine instance from the chat one. Both
 * stay resident: `InferenceArbiter` serialises access so they never run
 * concurrently, and `MemoryEstimator` already accounts for two models when
 * deciding whether the pair fits.
 */
public class LocalEmbeddingService(
    private val engine: LlamaEngine,
    override val profile: EmbeddingModelProfile,
    private val prefixer: TaskPrefixer = TaskPrefixer(profile),
    /**
     * How many chunks go into one engine call.
     *
     * The batch is one arbiter lease, so it is also the longest an interactive
     * chat turn can be stuck behind indexing. Small keeps the app responsive;
     * too small pays the lease and dispatcher cost per chunk. Sixteen is the
     * compromise, and the worker yields between batches anyway.
     */
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : EmbeddingService {
    override suspend fun embedDocuments(texts: List<String>): AppResult<List<FloatArray>> =
        runCatchingApp {
            val vectors = ArrayList<FloatArray>(texts.size)
            for (batch in texts.map(prefixer::forDocument).chunked(batchSize)) {
                vectors += engine.embed(batch)
            }
            vectors
        }

    override suspend fun embedQuery(text: String): AppResult<FloatArray> = runCatchingApp {
        engine.embed(prefixer.forQuery(text))
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 16
    }
}

/**
 * The remote path: `POST /api/embed` on a configured Ollama server.
 *
 * Batching matters far more here than locally — a hundred chunks is a hundred
 * TLS round trips over Wi-Fi if sent one at a time, and `/api/embed` takes an
 * array precisely so it does not have to be. The batch is larger than the local
 * one for the same reason: the cost being amortised is latency, not a mutex.
 */
public class RemoteEmbeddingService(
    private val client: OllamaClient,
    private val server: ServerRef,
    private val modelName: String,
    override val profile: EmbeddingModelProfile,
    private val prefixer: TaskPrefixer = TaskPrefixer(profile),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : EmbeddingService {
    override suspend fun embedDocuments(texts: List<String>): AppResult<List<FloatArray>> {
        val vectors = mutableListOf<FloatArray>()
        for (batch in texts.map(prefixer::forDocument).chunked(batchSize)) {
            when (val result = client.embed(server, modelName, batch)) {
                is AppResult.Success -> vectors += result.value.embeddings.map { it.toFloatArray() }
                is AppResult.Failure -> return result
            }
        }
        return AppResult.Success(vectors)
    }

    override suspend fun embedQuery(text: String): AppResult<FloatArray> =
        when (val result = client.embed(server, modelName, listOf(prefixer.forQuery(text)))) {
            is AppResult.Success -> {
                val vector = result.value.embeddings.firstOrNull()
                if (vector == null) {
                    AppResult.Failure(AppError.Unexpected("The server returned no embedding."))
                } else {
                    AppResult.Success(vector.toFloatArray())
                }
            }

            is AppResult.Failure -> {
                result
            }
        }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 64
    }
}

/**
 * Bridges the engine's throwing contract to [AppResult].
 *
 * [LlamaEngine] throws [AppErrorException] for one-shot calls, which is the
 * right shape there and the wrong one here: indexing a hundred documents wants
 * to record a failure against one of them and carry on, not unwind.
 */
private inline fun <T> runCatchingApp(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (error: AppErrorException) {
        AppResult.Failure(error.error)
    }
