package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.modelfile.ModelParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One turn of a conversation, as the client needs it.
 *
 * A wire-independent shape rather than `:core-model`'s [ChatMessage][
 * io.github.jaypetez.ollamamobile.model.ChatMessage]: that type carries
 * database identity, a status and [AttachmentRef][
 * io.github.jaypetez.ollamamobile.model.AttachmentRef]s pointing at content
 * URIs, and `:core-remote` can read none of those — it has no storage
 * dependency and must not grow one. Whoever owns the conversation resolves the
 * attachments to bytes and hands them over already encoded.
 */
data class RemoteMessage(
    val role: Role,
    val content: String,
    /**
     * Base64, with **no** `data:image/png;base64,` prefix. The server does not
     * strip one and fails the decode instead.
     */
    val imagesBase64: List<String> = emptyList(),
    /** Present on an assistant turn that called tools; replayed as context. */
    val toolCalls: List<RemoteToolCall> = emptyList(),
    /** The tool whose result this message carries. Only meaningful for [Role.TOOL]. */
    val toolName: String? = null,
    /**
     * The id of the call this message answers. Only meaningful for [Role.TOOL].
     *
     * The two protocols correlate a tool result differently: the native API
     * matches on function *name*, while `/v1` matches on the `id` it issued
     * alongside the call. A caller driving a tool loop over `/v1` must echo
     * that id back or the server cannot pair the result with the request, so
     * the field has to survive from [RemoteToolCall.id] on the assistant turn
     * to here on the tool turn. Null on the native path, where no id exists.
     */
    val toolCallId: String? = null,
)

/**
 * A tool call, in the one shape callers see.
 *
 * [arguments] is a parsed [JsonObject] on both protocol surfaces even though
 * the wire disagrees with itself: `/api/chat` sends an object and
 * `/v1/chat/completions` sends a string containing JSON. Converting at the
 * boundary is the whole reason this type exists — a shared DTO cannot
 * deserialise both, and pushing the difference up to callers would mean every
 * consumer handling two shapes forever.
 */
data class RemoteToolCall(
    /** `/v1` correlates results by id; the native API has no equivalent and leaves this null. */
    val id: String? = null,
    val name: String,
    val arguments: JsonObject,
)

/** A tool the model may call. [parametersSchema] is JSON Schema, passed through untouched. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
)

/** A chat request. */
data class ChatTurn(
    val model: String,
    val messages: List<RemoteMessage>,
    val sampling: SamplingParams = SamplingParams.Default,
    val tools: List<ToolDefinition> = emptyList(),
    /**
     * How long the server keeps the model resident after answering: `"10m"`,
     * `"0"` to unload at once, a negative value to keep it forever. Null leaves
     * the server's own setting alone, which is the polite default for a shared
     * machine.
     */
    val keepAlive: String? = null,
    /** Opt a reasoning model in or out of emitting its `<think>` block. Null means "server default". */
    val think: Boolean? = null,
)

/** A single-turn completion request. */
data class CompletionTurn(
    val model: String,
    val prompt: String,
    val system: String? = null,
    val template: String? = null,
    /** True bypasses the model's prompt template entirely. The only way to reach a raw prompt. */
    val raw: Boolean = false,
    val imagesBase64: List<String> = emptyList(),
    val sampling: SamplingParams = SamplingParams.Default,
    val keepAlive: String? = null,
)

/** Why generation stopped. `"length"` is a truncation and has to be visible to the user. */
enum class DoneReason {
    STOP,
    LENGTH,
    LOAD,
    TOOL_CALLS,
    CONTENT_FILTER,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(wire: String?): DoneReason? = when (wire?.trim()?.lowercase()) {
            null, "" -> null
            "stop" -> STOP
            "length" -> LENGTH
            "load" -> LOAD
            "tool_calls" -> TOOL_CALLS
            "content_filter" -> CONTENT_FILTER
            else -> UNKNOWN
        }
    }
}

/**
 * One thing that happened during a generation.
 *
 * Failures are an *emitted* [Failed] event rather than a thrown exception. The
 * reason is trap 4: a mid-stream error arrives at HTTP 200 as one more line in
 * the body, so "the stream ended" and "the generation failed" are the same
 * observation at the transport layer and must not be the same observation
 * here. A collector that has to `when` over the event type cannot accidentally
 * present a failed generation as a complete answer, which is exactly what
 * happens when the error is a `catch` somebody forgot to write.
 *
 * A stream that ends without [Completed] or [Failed] has been truncated; the
 * implementations synthesise a [Failed] rather than letting that happen.
 */
sealed interface StreamEvent {
    /** Answer text. Deltas, not cumulative. */
    data class Text(
        val delta: String,
    ) : StreamEvent

    /** Chain-of-thought from a reasoning model, already separated from [Text]. */
    data class Reasoning(
        val delta: String,
    ) : StreamEvent

    /** A complete tool call. `/v1` fragments these across chunks; they are reassembled first. */
    data class ToolCall(
        val call: RemoteToolCall,
    ) : StreamEvent

    /** The terminal chunk. [stats] is [GenerationStats.Empty] when the server reported nothing. */
    data class Completed(
        val doneReason: DoneReason?,
        val stats: GenerationStats,
    ) : StreamEvent

    /** Terminal failure, from any layer. Nothing is emitted after this. */
    data class Failed(
        val error: AppError,
    ) : StreamEvent
}

/** What `/api/version` said. */
data class ServerVersion(
    val version: String,
)

/** Structured `/api/show` output. See `ModelfileService` for how [parameters] is recovered. */
data class ModelDetails(
    val model: String,
    val system: String? = null,
    val template: String? = null,
    val license: String? = null,
    val parameters: ModelParameters = ModelParameters.Empty,
    /** `model_info`, passed through: architecture, block count, context length, and whatever is added next. */
    val modelInfo: Map<String, JsonElement> = emptyMap(),
    val capabilities: Set<ModelCapability> = emptySet(),
    val parameterCount: Long? = null,
    val quantizationLabel: String? = null,
    val contextLength: Int? = null,
)

/** A model currently resident on the server, from `/api/ps`. */
data class RunningModel(
    val name: String,
    val sizeBytes: Long? = null,
    /** How much of [sizeBytes] is on the GPU. Equal to it for a fully offloaded model, absent when unknown. */
    val sizeVramBytes: Long? = null,
    /** Epoch milliseconds at which the server will unload it, when it said. */
    val expiresAtMillis: Long? = null,
    val digest: String? = null,
)

/** Embedding vectors, in input order. */
data class EmbeddingResult(
    val embeddings: List<List<Float>>,
    val stats: GenerationStats = GenerationStats.Empty,
)

/**
 * One progress line from `/api/pull`.
 *
 * [total] and [completed] are absent for the manifest and verification phases,
 * which is why they are nullable rather than zero — a progress bar that
 * flicks to 0% between layers is worse than one that shows the status text.
 */
data class PullProgress(
    val status: String,
    val digest: String? = null,
    val totalBytes: Long? = null,
    val completedBytes: Long? = null,
    val done: Boolean = false,
    /** Terminal failure. Emitted rather than thrown, for the reason given on [StreamEvent]. */
    val error: AppError? = null,
) {
    /** 0..1, or null when the server has not said how big this layer is. */
    val fraction: Double?
        get() {
            val total = totalBytes ?: return null
            val completed = completedBytes ?: return null
            return if (total <= 0L) null else (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        }
}

/**
 * The structured form of a model definition, for reading and writing.
 *
 * There is no `modelfile: String` field, and that is not an oversight — see
 * `ModelfileService`.
 */
data class ModelDefinition(
    val model: String,
    /** The model this one derives from. Required by `/api/create` unless building from files. */
    val from: String,
    val system: String? = null,
    val template: String? = null,
    val license: String? = null,
    val parameters: ModelParameters = ModelParameters.Empty,
)

/**
 * The subset of the client both protocol surfaces implement.
 *
 * `ServerClientFactory` hands one of these back so a caller that only wants to
 * chat does not have to know which protocol the server speaks. Everything
 * Ollama-specific — pulls, `/api/ps`, `keep_alive`, timing statistics — lives
 * on [OllamaClient] alone, because `/v1` genuinely cannot express it.
 */
interface RemoteChatClient {
    suspend fun listModels(server: ServerRef): AppResult<List<ModelRef>>

    fun chat(server: ServerRef, request: ChatTurn): Flow<StreamEvent>

    suspend fun embed(server: ServerRef, model: String, inputs: List<String>): AppResult<EmbeddingResult>
}

/**
 * The Ollama native API, in domain terms.
 *
 * Every method takes the [ServerRef] it applies to rather than the client being
 * bound to one server. There is no per-server state worth holding — the socket
 * pool is shared and lives in `:core-common` — and a stateless client means the
 * router can fan a request out to a second server without constructing
 * anything.
 *
 * Streaming methods return a [Flow] and are cold: nothing is sent until
 * collection starts, and cancelling the collector cancels the HTTP call.
 * One-shot methods suspend and return an
 * [AppResult][io.github.jaypetez.ollamamobile.common.result.AppResult] — they
 * do not throw. The only exception that ever leaves this boundary is
 * `CancellationException`, which must propagate for structured concurrency to
 * work.
 */
interface OllamaClient : RemoteChatClient {
    /** `GET /api/version`. The cheapest liveness probe there is. */
    suspend fun version(server: ServerRef): AppResult<ServerVersion>

    /** `GET /api/tags`. */
    override suspend fun listModels(server: ServerRef): AppResult<List<ModelRef>>

    /** `POST /api/show`. A read, despite the verb. */
    suspend fun showModel(server: ServerRef, model: String): AppResult<ModelDetails>

    /** `POST /api/chat`, streamed as NDJSON. */
    override fun chat(server: ServerRef, request: ChatTurn): Flow<StreamEvent>

    /** `POST /api/generate`, streamed as NDJSON. */
    fun generate(server: ServerRef, request: CompletionTurn): Flow<StreamEvent>

    /** `POST /api/embed`. */
    override suspend fun embed(server: ServerRef, model: String, inputs: List<String>): AppResult<EmbeddingResult>

    /** `POST /api/pull`, streamed. Progress is emitted; failure arrives as [PullProgress.error]. */
    fun pullModel(server: ServerRef, model: String, allowInsecure: Boolean = false): Flow<PullProgress>

    /** `DELETE /api/delete`. */
    suspend fun deleteModel(server: ServerRef, model: String): AppResult<Unit>

    /** `POST /api/copy`. */
    suspend fun copyModel(server: ServerRef, source: String, destination: String): AppResult<Unit>

    /** `GET /api/ps` — what the server currently has loaded. */
    suspend fun runningModels(server: ServerRef): AppResult<List<RunningModel>>

    /**
     * `POST /api/create`, structured.
     *
     * Beyond the read-only surface the rest of this interface offers, and here
     * for exactly one caller: `ModelfileService`, which lets a user edit a
     * remote model's system prompt and parameters. Nothing else should use it.
     */
    suspend fun createModel(server: ServerRef, definition: ModelDefinition): AppResult<Unit>
}

/**
 * Resolves a [SecretRef] to the value it stands for.
 *
 * Declared here, in the module that needs it, rather than in `:core-storage`
 * where it will be implemented: `:core-remote` must not depend on the storage
 * layer, and the dependency-inversion is what keeps the client testable with a
 * lambda instead of a Keystore.
 *
 * Suspending because the real implementation reads DataStore and unwraps an
 * Android Keystore key, neither of which may happen on a caller's thread.
 * Credentials are therefore resolved *before* the request is built rather than
 * inside the interceptor — see `AuthInterceptor`.
 */
interface SecretResolver {
    /** The secret, or null when it is not there. A missing token is a 401 to recover from, not a crash. */
    suspend fun resolve(ref: SecretRef): String?
}

/**
 * The binding that makes this module usable before `:core-storage` provides the
 * real one.
 *
 * It resolves nothing, so a server configured with a bearer token behaves
 * exactly as if the token were missing: the request goes out unauthenticated
 * and the server answers 401, which the UI already knows how to explain. The
 * alternative — throwing — would make an un-bound resolver look like a network
 * fault. See `RemoteModule` for how the real implementation displaces this one
 * without either module knowing about the other.
 */
object NoOpSecretResolver : SecretResolver {
    override suspend fun resolve(ref: SecretRef): String? = null
}
