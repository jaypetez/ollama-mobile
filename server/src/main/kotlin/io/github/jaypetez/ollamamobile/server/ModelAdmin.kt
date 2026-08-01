package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.remote.dto.PullProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Model management, as the HTTP surface is allowed to see it.
 *
 * `/api/pull`, `/api/delete` and `/api/copy` are inherently storage
 * operations, and `checkModuleGraph` forbids `:server` from reaching
 * `:core-download` or `:core-storage`. So the routes talk to this port and
 * `:app` binds the implementation that actually moves bytes — the same
 * arrangement as [InferenceGateway][io.github.jaypetez.ollamamobile.llm.InferenceGateway],
 * and for the same reason.
 *
 * Every method reports failure by emitting or returning a message rather than
 * throwing, because each one is already streaming an NDJSON progress channel
 * where an error is a line, not an exception.
 */
interface ModelAdmin {
    /**
     * Streams Ollama's pull progress. The last line must be
     * `{"status":"success"}` for the CLI's progress bar to complete.
     */
    fun pull(model: String, insecure: Boolean): Flow<PullProgress>

    /** @return null on success, or an error message for the `{"error": …}` body. */
    suspend fun delete(model: String): String?

    /** @return null on success, or an error message. */
    suspend fun copy(source: String, destination: String): String?
}

/**
 * The binding used when the host has no model store — tests, and any build
 * where the download stack is absent.
 *
 * It answers with Ollama's own vocabulary rather than a 501, because a client
 * that receives an unexpected status frequently retries forever, while one that
 * receives `{"error": …}` shows it to the user and stops.
 */
object UnsupportedModelAdmin : ModelAdmin {
    const val MESSAGE: String = "model management is not available on this server"

    override fun pull(model: String, insecure: Boolean): Flow<PullProgress> =
        flowOf(PullProgress(status = MESSAGE))

    override suspend fun delete(model: String): String? = MESSAGE

    override suspend fun copy(source: String, destination: String): String? = MESSAGE
}

/**
 * Embeddings, as a port for the same reason as [ModelAdmin].
 *
 * [InferenceGateway][io.github.jaypetez.ollamamobile.llm.InferenceGateway] does
 * not embed — it generates — and the engine that does lives behind
 * `:core-llm`, which this module may not reach. So `/api/embed` and
 * `/v1/embeddings` call this, and `:app` binds whatever can actually pool a
 * vector.
 */
fun interface EmbeddingProvider {
    /** @throws IllegalStateException when the host cannot embed at all. */
    suspend fun embed(text: String): List<Float>
}

/**
 * Fails loudly rather than returning zeroes.
 *
 * A vector of zeroes is a *valid-looking* embedding that poisons a similarity
 * index silently — every document becomes equidistant from every query, and
 * the symptom shows up weeks later as "search got worse". An error at the
 * boundary is recoverable; a poisoned index is not.
 */
object UnsupportedEmbeddingProvider : EmbeddingProvider {
    const val MESSAGE: String = "embeddings are not available on this server"

    override suspend fun embed(text: String): List<Float> = error(MESSAGE)
}
