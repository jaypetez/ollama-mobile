package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.llm.InferenceGateway
import io.github.jaypetez.ollamamobile.model.ModelRef
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the route table needs, assembled once per server start.
 *
 * A single object rather than a dozen route parameters so that
 * `ktor-server-test-host` can stand the whole surface up with one line and no
 * Android, which is the property that keeps these tests fast enough to run on
 * every commit.
 */
class ServerEnvironment(
    val config: ServerConfig,
    val gateway: InferenceGateway,
    val admin: ModelAdmin = UnsupportedModelAdmin,
    val embeddings: EmbeddingProvider = UnsupportedEmbeddingProvider,
    val clock: ServerClock = ServerClock.System,
    val residency: ModelResidency = ModelResidency(clock),
    val admission: RequestAdmission = RequestAdmission(config),
) {
    private val requests = AtomicLong(0L)

    private val requestsState = MutableStateFlow(0L)

    /** Total requests served since start. Drives the notification's counter. */
    val requestCount: StateFlow<Long> = requestsState.asStateFlow()

    fun recordRequest() {
        requestsState.value = requests.incrementAndGet()
    }

    /**
     * Finds the [ModelRef] a request named, or null.
     *
     * Matching is on [ModelRef.name] and then on the name with `:latest`
     * appended or removed. Ollama treats `qwen3` and `qwen3:latest` as the same
     * tag, and a client that omits the tag — which the CLI does by default —
     * would otherwise get "model not found" for a model that is right there.
     */
    suspend fun resolveModel(requested: String): ModelRef? {
        val wanted = requested.trim()
        if (wanted.isEmpty()) return null
        val models = gateway.listAvailableModels()
        return models.firstOrNull { it.name == wanted }
            ?: models.firstOrNull { it.name == "$wanted:$LATEST_TAG" }
            ?: models.firstOrNull { it.name == wanted.removeSuffix(":$LATEST_TAG") }
    }

    private companion object {
        const val LATEST_TAG = "latest"
    }
}
