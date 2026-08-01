package io.github.jaypetez.ollamamobile.remote.dto

import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.SamplingParams
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Wire types for Ollama's native API — the `/api` endpoints.
 *
 * (Endpoint globs are spelled out rather than written with a star: Kotlin block
 * comments nest, so a slash-star inside a KDoc opens a comment that the closing
 * delimiter then fails to close. It costs a whole file.)
 *
 * Two rules hold everywhere in this file and are not negotiable:
 *
 *  * Every field the server may omit is nullable with a `null` default. Ollama
 *    marshals its Go structs with `omitempty`, so a cache hit ships no
 *    `prompt_eval_duration` and a load-only call ships no `eval_*` at all. A
 *    non-null default would turn "not reported" into `0`, which renders as a
 *    perfectly plausible and completely fabricated measurement.
 *  * Every duration is an int64 count of NANOSECONDS. Reading one as
 *    milliseconds is wrong by a factor of a million and still looks sane in a
 *    log, so the names here all end in `Nanos` and the only conversion lives in
 *    [GenerationStats].
 */

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/**
 * Sampling and runtime knobs sent under `"options"`.
 *
 * Null means "server default", never "zero" — see [SamplingParams] for why the
 * distinction matters. [explicitNulls][RemoteJson] being off is what makes the
 * nulls disappear from the encoded object rather than being sent.
 */
@Serializable
data class OllamaOptions(
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("min_p") val minP: Double? = null,
    @SerialName("repeat_penalty") val repeatPenalty: Double? = null,
    @SerialName("repeat_last_n") val repeatLastN: Int? = null,
    val seed: Long? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    @SerialName("num_ctx") val numCtx: Int? = null,
    val stop: List<String>? = null,
    @SerialName("num_thread") val numThread: Int? = null,
    @SerialName("num_gpu") val numGpu: Int? = null,
) {
    /** True when nothing is set, i.e. when `"options"` should be omitted entirely. */
    val isEmpty: Boolean
        get() = this == OllamaOptions()
}

/**
 * Projects the domain's [SamplingParams] onto the wire shape.
 *
 * Returns `null` when the user has configured nothing, so the caller can leave
 * `"options"` out of the request rather than sending an empty object — some
 * proxies in front of Ollama treat `{}` as "reset every default".
 */
fun SamplingParams.toOllamaOptions(): OllamaOptions? {
    val options = OllamaOptions(
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        seed = seed,
        numPredict = numPredict,
        numCtx = numCtx,
        stop = stop.takeIf { it.isNotEmpty() },
    )
    return options.takeUnless { it.isEmpty }
}

/**
 * One entry of `"tool_calls"` on the native API.
 *
 * Note the shape of [OllamaToolCallFunction.arguments]: a JSON **object**. The
 * OpenAI-compatible surface carries the same data as a JSON **string**. The
 * conversion is in `OpenAiDtos.kt`; nothing above this layer should ever see
 * both shapes.
 */
@Serializable
data class OllamaToolCall(
    val function: OllamaToolCallFunction,
)

@Serializable
data class OllamaToolCallFunction(
    val name: String,
    /**
     * Ollama's own index for the call within the turn. Present on newer servers
     * only, which is why it is nullable rather than defaulted to 0 — a
     * fabricated index would collide with a real one.
     */
    val index: Int? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

/** A tool offered to the model, as sent in `"tools"`. */
@Serializable
data class OllamaTool(
    val type: String = "function",
    val function: OllamaToolFunction,
)

@Serializable
data class OllamaToolFunction(
    val name: String,
    val description: String? = null,
    /** A JSON Schema object. Kept as raw JSON: the app never inspects it. */
    val parameters: JsonObject? = null,
)

/**
 * One chat turn.
 *
 * [thinking] is the reasoning channel emitted by models run with `think: true`;
 * it is a sibling of [content], not a prefix of it, and belongs in
 * `ChatMessage.reasoning`.
 *
 * [images] are base64-encoded, with no `data:` URI prefix.
 */
@Serializable
data class OllamaMessage(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
    val images: List<String>? = null,
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    /** Set on a `role: "tool"` turn to say which call this result answers. */
    @SerialName("tool_name") val toolName: String? = null,
)

// ---------------------------------------------------------------------------
// /api/generate
// ---------------------------------------------------------------------------

@Serializable
data class GenerateRequest(
    val model: String,
    val prompt: String,
    /** Text after the cursor, for fill-in-the-middle models. */
    val suffix: String? = null,
    val system: String? = null,
    val template: String? = null,
    /** Base64 images for a vision model. */
    val images: List<String>? = null,
    /**
     * `"json"`, or a JSON Schema object for structured output. Raw JSON because
     * the two forms are a string and an object and callers supply either.
     */
    val format: JsonElement? = null,
    val options: OllamaOptions? = null,
    /** Opaque conversation state from a previous [GenerateResponse.context]. */
    val context: List<Int>? = null,
    /** Bypasses the model's prompt template. Mutually exclusive with [system]. */
    val raw: Boolean? = null,
    val think: Boolean? = null,
    /** e.g. `"5m"`, or `0` to unload immediately. String or number on the wire. */
    @SerialName("keep_alive") val keepAlive: JsonElement? = null,
    val stream: Boolean = true,
)

@Serializable
data class GenerateResponse(
    val model: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    /** The delta while streaming, the whole completion when `stream: false`. */
    val response: String = "",
    val thinking: String? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    val context: List<Int>? = null,
    @SerialName("total_duration") val totalDurationNanos: Long? = null,
    @SerialName("load_duration") val loadDurationNanos: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration") val promptEvalDurationNanos: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDurationNanos: Long? = null,
)

// ---------------------------------------------------------------------------
// /api/chat
// ---------------------------------------------------------------------------

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val tools: List<OllamaTool>? = null,
    val format: JsonElement? = null,
    val options: OllamaOptions? = null,
    val think: Boolean? = null,
    @SerialName("keep_alive") val keepAlive: JsonElement? = null,
    val stream: Boolean = true,
)

/**
 * One chunk of a `/api/chat` stream, or the whole reply when `stream: false`.
 *
 * **[message] is nullable and must stay that way.** The terminal chunk of a
 * streaming chat carries `done: true` plus the timing block and frequently
 * carries no `message` key at all. Declaring it non-null makes the last chunk —
 * the only one that has the statistics — fail to decode, which reads in the UI
 * as a response that simply stopped.
 */
@Serializable
data class ChatResponse(
    val model: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("total_duration") val totalDurationNanos: Long? = null,
    @SerialName("load_duration") val loadDurationNanos: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration") val promptEvalDurationNanos: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDurationNanos: Long? = null,
)

// ---------------------------------------------------------------------------
// /api/tags, /api/show, /api/ps, /api/version
// ---------------------------------------------------------------------------

@Serializable
data class TagsResponse(
    val models: List<OllamaModel> = emptyList(),
)

@Serializable
data class OllamaModel(
    /** The tag, e.g. `qwen3:1.7b`. */
    val name: String = "",
    /** Same value as [name] on current servers; absent on older ones. */
    val model: String? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: ModelDetails? = null,
)

@Serializable
data class ModelDetails(
    @SerialName("parent_model") val parentModel: String? = null,
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    /** Human-readable, e.g. `"1.7B"` — not a number. */
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
)

@Serializable
data class ShowRequest(
    val model: String,
    /** Returns the full tokeniser tables in `model_info`. Large; off by default. */
    val verbose: Boolean? = null,
)

@Serializable
data class ShowResponse(
    val license: String? = null,
    val modelfile: String? = null,
    val parameters: String? = null,
    val template: String? = null,
    val system: String? = null,
    val details: ModelDetails? = null,
    /**
     * Architecture facts keyed by GGUF metadata name
     * (`"llama.context_length"`, `"general.parameter_count"`, ...). Values are
     * mixed types, so this stays raw JSON rather than pretending to be typed.
     */
    @SerialName("model_info") val modelInfo: JsonObject? = null,
    @SerialName("projector_info") val projectorInfo: JsonObject? = null,
    /** e.g. `["completion", "tools", "vision", "thinking", "embedding"]`. */
    val capabilities: List<String>? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
)

@Serializable
data class PsResponse(
    val models: List<RunningModel> = emptyList(),
)

@Serializable
data class RunningModel(
    val name: String = "",
    val model: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: ModelDetails? = null,
    /** RFC 3339. When the model will be evicted unless it is used again. */
    @SerialName("expires_at") val expiresAt: String? = null,
    /** Bytes resident in VRAM; 0 on a CPU-only server. */
    @SerialName("size_vram") val sizeVram: Long? = null,
)

@Serializable
data class VersionResponse(
    val version: String = "",
)

// ---------------------------------------------------------------------------
// /api/pull, /api/delete, /api/copy, /api/create
// ---------------------------------------------------------------------------

@Serializable
data class PullRequest(
    val model: String,
    /** Only for a registry served over plain HTTP. Development use only. */
    val insecure: Boolean? = null,
    val stream: Boolean = true,
)

/**
 * One NDJSON line of a `/api/pull` (or `/api/push`) stream.
 *
 * [total] and [completed] are absent for the manifest and verification phases
 * and present only while layer bytes move, so a progress bar must treat "no
 * numbers yet" as indeterminate rather than as 0 %.
 */
@Serializable
data class PullProgress(
    val status: String = "",
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
) {
    /** Fraction in `0.0..1.0`, or null while the phase reports no byte counts. */
    val fraction: Double?
        get() {
            val totalBytes = total ?: return null
            val done = completed ?: return null
            if (totalBytes <= 0L) return null
            return (done.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        }
}

@Serializable
data class DeleteRequest(
    val model: String,
)

@Serializable
data class CopyRequest(
    val source: String,
    val destination: String,
)

@Serializable
data class CreateRequest(
    val model: String,
    /** An existing model to derive from. Alternative to [files]. */
    val from: String? = null,
    /** Filename to blob-digest map for a create-from-GGUF. */
    val files: Map<String, String>? = null,
    val adapters: Map<String, String>? = null,
    val template: String? = null,
    val license: JsonElement? = null,
    val system: String? = null,
    val parameters: OllamaOptions? = null,
    val messages: List<OllamaMessage>? = null,
    /** e.g. `"q4_K_M"`. Only valid when creating from an f16/f32 source. */
    val quantize: String? = null,
    val stream: Boolean = true,
)

// ---------------------------------------------------------------------------
// /api/embed and the legacy /api/embeddings
// ---------------------------------------------------------------------------

/**
 * The `"input"` of `/api/embed`, which the server accepts as either a single
 * string or an array of strings.
 *
 * Modelled as one field with a custom serializer rather than as two nullable
 * fields, because two fields make an illegal state representable: both set, or
 * neither. The serializer is in [EmbedInputSerializer].
 */
@Serializable(with = EmbedInputSerializer::class)
sealed interface EmbedInput {
    /** A single document: encodes as `"input": "text"`. */
    data class Text(
        val value: String,
    ) : EmbedInput

    /** A batch: encodes as `"input": ["a", "b"]`. */
    data class Batch(
        val values: List<String>,
    ) : EmbedInput

    companion object {
        /** One string stays a string; anything else becomes an array. */
        fun of(texts: List<String>): EmbedInput =
            if (texts.size == 1) Text(texts.single()) else Batch(texts)
    }
}

/**
 * Reads and writes [EmbedInput] as a bare string or a bare array.
 *
 * The descriptor claims [PrimitiveKind.STRING] because a serializer must
 * declare exactly one kind and there is no "string or array" kind. That is
 * harmless for JSON — the format never validates the encoded shape against the
 * descriptor — but it does mean this serializer is JSON-only, which is why both
 * halves check for a JSON encoder/decoder and fail loudly otherwise rather than
 * producing something subtly wrong in another format.
 */
object EmbedInputSerializer : KSerializer<EmbedInput> {
    private val stringList = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.jaypetez.ollamamobile.remote.dto.EmbedInput", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: EmbedInput) {
        require(encoder is JsonEncoder) { "EmbedInput can only be encoded as JSON." }
        when (value) {
            is EmbedInput.Text -> encoder.encodeString(value.value)
            is EmbedInput.Batch -> encoder.encodeSerializableValue(stringList, value.values)
        }
    }

    override fun deserialize(decoder: Decoder): EmbedInput {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("EmbedInput can only be decoded from JSON.")
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> EmbedInput.Batch(element.map { it.embedInputString() })
            is JsonPrimitive -> EmbedInput.Text(element.embedInputString())
            else -> throw SerializationException("Expected a string or an array of strings for \"input\".")
        }
    }

    private fun JsonElement.embedInputString(): String {
        val primitive = this as? JsonPrimitive
            ?: throw SerializationException("Expected a string inside \"input\", found $this.")
        if (!primitive.isString) {
            throw SerializationException("Expected a quoted string inside \"input\", found ${primitive.content}.")
        }
        return primitive.content
    }
}

@Serializable
data class EmbedRequest(
    val model: String,
    val input: EmbedInput,
    /** Truncate to the context window instead of erroring. Defaults to true server-side. */
    val truncate: Boolean? = null,
    val options: OllamaOptions? = null,
    @SerialName("keep_alive") val keepAlive: JsonElement? = null,
)

/** Note the plural: `/api/embed` always answers with a list of vectors. */
@Serializable
data class EmbedResponse(
    val model: String = "",
    val embeddings: List<List<Float>> = emptyList(),
    @SerialName("total_duration") val totalDurationNanos: Long? = null,
    @SerialName("load_duration") val loadDurationNanos: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
)

/**
 * The legacy `/api/embeddings` request. One string, never an array.
 *
 * Kept because the compatibility runs both ways: servers older than 0.1.39 do
 * not have `/api/embed`, and third-party implementations of the Ollama API
 * frequently implement only this endpoint. A client that assumes the modern
 * route gets a 404 from both.
 */
@Serializable
data class EmbeddingsRequest(
    val model: String,
    val prompt: String,
    val options: OllamaOptions? = null,
    @SerialName("keep_alive") val keepAlive: JsonElement? = null,
)

/**
 * The legacy response. The key is `"embedding"` — singular, and a flat array of
 * doubles rather than a list of vectors. Decoding one endpoint's body with the
 * other's DTO yields an empty result rather than an error, so the two types
 * must not be merged however similar they look.
 */
@Serializable
data class EmbeddingsResponse(
    val embedding: List<Float> = emptyList(),
)

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Ollama's error envelope.
 *
 * It arrives with a 4xx/5xx status **and** as a lone NDJSON line inside an
 * otherwise successful HTTP 200 stream. `stream/NdjsonFlow.kt` checks every
 * line for the `error` key precisely because of the second case.
 */
@Serializable
data class OllamaErrorResponse(
    val error: String = "",
)

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

/**
 * Lifts the timing block onto the domain type.
 *
 * Absent stays absent: a response with no timings maps to a [GenerationStats]
 * whose every field is null and whose `tokensPerSecond` is therefore null, not
 * zero.
 */
fun ChatResponse.toGenerationStats(): GenerationStats = GenerationStats(
    promptTokens = promptEvalCount,
    completionTokens = evalCount,
    promptEvalNanos = promptEvalDurationNanos,
    evalNanos = evalDurationNanos,
    loadNanos = loadDurationNanos,
    totalNanos = totalDurationNanos,
)

/** As [ChatResponse.toGenerationStats]. */
fun GenerateResponse.toGenerationStats(): GenerationStats = GenerationStats(
    promptTokens = promptEvalCount,
    completionTokens = evalCount,
    promptEvalNanos = promptEvalDurationNanos,
    evalNanos = evalDurationNanos,
    loadNanos = loadDurationNanos,
    totalNanos = totalDurationNanos,
)
