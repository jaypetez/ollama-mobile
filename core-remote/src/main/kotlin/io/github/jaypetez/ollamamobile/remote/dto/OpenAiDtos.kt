package io.github.jaypetez.ollamamobile.remote.dto

import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.Role
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Wire types for the OpenAI-compatible surface — the `/v1` endpoints.
 *
 * Ollama serves both APIs from the same process, and a user's server may be
 * something else entirely that only speaks `/v1`. The app must therefore know
 * both dialects — but only at this boundary. Everything above uses the native
 * vocabulary, and the converters at the bottom of this file are the single
 * place the two ever meet.
 *
 * The difference that actually bites is tool calls: `arguments` is a JSON
 * OBJECT on `/api/chat` and a JSON STRING containing an object on `/v1`. Code
 * that handles both shapes at the call site handles one of them wrong.
 */

// ---------------------------------------------------------------------------
// /v1/chat/completions
// ---------------------------------------------------------------------------

@Serializable
data class OpenAiChatMessage(
    /**
     * Nullable, and this is the `/v1` spelling of trap 3.
     *
     * A streaming `delta` carries `role` only on the first chunk of a turn, and
     * the final chunk before `[DONE]` is frequently the empty object `{}` plus
     * a `finish_reason`. Declaring `role` required makes every chunk after the
     * first fail to decode — which is to say, the entire answer.
     *
     * A request message must always set it; [OllamaMessage.toOpenAi] does.
     */
    val role: String? = null,
    /**
     * Nullable on purpose: an assistant turn that only calls tools carries
     * `"content": null`, and a `delta` carries no content at all between
     * tool-call fragments.
     */
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    /** Present on a `role: "tool"` turn; echoes [OpenAiToolCall.id]. */
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/** The `/v1` tool call. See the note on [OpenAiFunctionCall.arguments]. */
@Serializable
data class OpenAiToolCall(
    val id: String? = null,
    val type: String = "function",
    val function: OpenAiFunctionCall,
    /**
     * Only present in streaming deltas, where it says which call a fragment
     * belongs to. Nullable because a non-streaming response omits it and 0
     * would be a real index.
     */
    val index: Int? = null,
)

@Serializable
data class OpenAiFunctionCall(
    val name: String? = null,
    /**
     * A JSON object **encoded as a string**, e.g. `"{\"city\":\"Oslo\"}"`.
     *
     * While streaming it arrives in fragments that are not individually valid
     * JSON, which is why [decodeToolArguments] never throws.
     */
    val arguments: String = "",
)

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunctionDefinition,
)

@Serializable
data class OpenAiFunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    /** Deprecated upstream in favour of [maxCompletionTokens]; Ollama reads this one. */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val stop: List<String>? = null,
    val seed: Long? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    val tools: List<OpenAiTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: JsonElement? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String = "",
    @SerialName("object") val objectType: String? = null,
    /** Epoch **seconds**, unlike the native API's RFC 3339 `created_at`. */
    val created: Long? = null,
    val model: String = "",
    val choices: List<ChatCompletionChoice> = emptyList(),
    val usage: Usage? = null,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
)

@Serializable
data class ChatCompletionChoice(
    val index: Int = 0,
    val message: OpenAiChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * One `data:` frame of a `/v1/chat/completions` stream.
 *
 * Structurally a [ChatCompletionResponse] with `delta` where `message` would
 * be, and — like the native API's terminal chunk — the last frame before
 * `[DONE]` may carry an empty `delta` plus the [usage] block and nothing else.
 */
@Serializable
data class ChatCompletionChunk(
    val id: String = "",
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    val model: String = "",
    val choices: List<ChatCompletionChunkChoice> = emptyList(),
    /** Only sent when the request asked for `stream_options.include_usage`. */
    val usage: Usage? = null,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
)

@Serializable
data class ChatCompletionChunkChoice(
    val index: Int = 0,
    val delta: OpenAiChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * Token counts. No durations at all — which is why a `/v1` stream can never
 * report tok/s and [toGenerationStats] leaves the timing fields null rather
 * than inventing them.
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

// ---------------------------------------------------------------------------
// /v1/models and /v1/embeddings
// ---------------------------------------------------------------------------

@Serializable
data class ModelsResponse(
    @SerialName("object") val objectType: String? = null,
    val data: List<OpenAiModel> = emptyList(),
)

@Serializable
data class OpenAiModel(
    val id: String = "",
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
)

/**
 * Named with the `OpenAi` prefix because the native legacy [EmbeddingsRequest]
 * already owns the plain name in this package, and the two are not
 * interchangeable: this one takes a string or an array, that one only a string.
 */
@Serializable
data class OpenAiEmbeddingsRequest(
    val model: String,
    val input: EmbedInput,
    @SerialName("encoding_format") val encodingFormat: String? = null,
    val dimensions: Int? = null,
    val user: String? = null,
)

@Serializable
data class OpenAiEmbeddingsResponse(
    @SerialName("object") val objectType: String? = null,
    val data: List<OpenAiEmbeddingData> = emptyList(),
    val model: String = "",
    val usage: Usage? = null,
)

@Serializable
data class OpenAiEmbeddingData(
    @SerialName("object") val objectType: String? = null,
    val embedding: List<Float> = emptyList(),
    val index: Int = 0,
)

// ---------------------------------------------------------------------------
// Tool-call argument conversion — the whole point of trap 6
// ---------------------------------------------------------------------------

/** Object form -> `/v1` string form. Compact, because the server re-parses it. */
fun encodeToolArguments(arguments: JsonObject): String = RemoteJson.encodeToString(JsonObject.serializer(), arguments)

/**
 * `/v1` string form -> object form.
 *
 * Total by construction. A streaming `/v1` response delivers `arguments` a few
 * characters at a time, so an individual fragment is routinely not valid JSON;
 * throwing here would abort the stream over a partial write that the very next
 * frame completes. An unparsable value yields an empty object and the caller
 * keeps accumulating.
 */
fun decodeToolArguments(arguments: String): JsonObject {
    val trimmed = arguments.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return runCatching { RemoteJson.decodeFromString(JsonObject.serializer(), trimmed) }
        .getOrElse { JsonObject(emptyMap()) }
}

// ---------------------------------------------------------------------------
// Native <-> OpenAI converters
// ---------------------------------------------------------------------------

fun OllamaToolCall.toOpenAi(): OpenAiToolCall = OpenAiToolCall(
    function = OpenAiFunctionCall(
        name = function.name,
        arguments = encodeToolArguments(function.arguments),
    ),
    index = function.index,
)

fun OpenAiToolCall.toNative(): OllamaToolCall = OllamaToolCall(
    function = OllamaToolCallFunction(
        name = function.name.orEmpty(),
        index = index,
        arguments = decodeToolArguments(function.arguments),
    ),
)

fun OllamaTool.toOpenAi(): OpenAiTool = OpenAiTool(
    type = type,
    function = OpenAiFunctionDefinition(
        name = function.name,
        description = function.description,
        parameters = function.parameters,
    ),
)

fun OpenAiTool.toNative(): OllamaTool = OllamaTool(
    type = type,
    function = OllamaToolFunction(
        name = function.name,
        description = function.description,
        parameters = function.parameters,
    ),
)

/**
 * Native turn -> `/v1` turn.
 *
 * `thinking` has no `/v1` equivalent and is deliberately dropped rather than
 * folded into `content`: merging it would feed the model's own scratchpad back
 * as if it were part of the answer.
 */
fun OllamaMessage.toOpenAi(): OpenAiChatMessage = OpenAiChatMessage(
    role = role,
    // An assistant turn that only called tools has empty content, and `/v1`
    // spells that null.
    content = content.takeIf { it.isNotEmpty() },
    // The native API identifies a tool result by function *name*; `/v1` wants a
    // `tool_call_id` echoing the id it issued. The native DTO has nowhere to
    // keep an id, so this conversion cannot produce one and does not pretend
    // to. That is exactly why an outbound `/v1` request is built by
    // `ChatTurn.toOpenAiWire()` straight from the domain type instead of
    // hopping through here — see the note on that function. This direction
    // remains correct for the read paths, where no id is in play.
    name = toolName,
    toolCalls = toolCalls?.map { it.toOpenAi() },
)

fun OpenAiChatMessage.toNative(): OllamaMessage = OllamaMessage(
    // `/v1` omits `role` on every delta after the first, and it omits it only
    // ever for the assistant's own turn — a tool or user message always states
    // its role. So the fallback is not a guess.
    role = role ?: Role.ASSISTANT.wireName,
    content = content.orEmpty(),
    toolCalls = toolCalls?.map { it.toNative() },
    toolName = name,
)

/**
 * Native request -> `/v1` request.
 *
 * Several native options have no `/v1` counterpart — `top_k`, `min_p`,
 * `repeat_last_n`, `num_ctx`, `num_thread`, `num_gpu`. They are dropped rather
 * than approximated: `frequency_penalty` is *not* `repeat_penalty` (one is a
 * per-occurrence logit subtraction, the other a division over a sliding
 * window), and pretending otherwise silently changes the sampling the user
 * configured.
 */
fun ChatRequest.toOpenAi(): ChatCompletionRequest = ChatCompletionRequest(
    model = model,
    messages = messages.map { it.toOpenAi() },
    temperature = options?.temperature,
    topP = options?.topP,
    maxTokens = options?.numPredict,
    stop = options?.stop,
    seed = options?.seed,
    tools = tools?.map { it.toOpenAi() },
    stream = stream,
)

fun ChatCompletionRequest.toNative(): ChatRequest = ChatRequest(
    model = model,
    messages = messages.map { it.toNative() },
    tools = tools?.map { it.toNative() },
    options = OllamaOptions(
        temperature = temperature,
        topP = topP,
        seed = seed,
        numPredict = maxCompletionTokens ?: maxTokens,
        stop = stop,
    ).takeUnless { it.isEmpty },
    stream = stream,
)

/** Token counts only; `/v1` reports no durations, so tok/s stays null. */
fun Usage.toGenerationStats(): GenerationStats = GenerationStats(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
)

/**
 * `/v1` reply -> the native shape the rest of the app consumes.
 *
 * Only the first choice survives: `n > 1` is never requested by this client and
 * the domain has no place to put alternatives.
 */
fun ChatCompletionResponse.toNative(): ChatResponse {
    val choice = choices.firstOrNull()
    return ChatResponse(
        model = model,
        createdAt = created?.toRfc3339(),
        message = choice?.message?.toNative(),
        done = true,
        doneReason = choice?.finishReason,
        promptEvalCount = usage?.promptTokens,
        evalCount = usage?.completionTokens,
    )
}

/**
 * `/v1` stream frame -> the native chunk shape.
 *
 * `done` is derived from `finish_reason`, because `/v1` has no `done` flag: the
 * end of a stream is signalled out of band by the literal `data: [DONE]` line,
 * which `SseFlow` consumes and never emits.
 */
fun ChatCompletionChunk.toNative(): ChatResponse {
    val choice = choices.firstOrNull()
    return ChatResponse(
        model = model,
        createdAt = created?.toRfc3339(),
        message = choice?.delta?.toNative(),
        done = choice?.finishReason != null,
        doneReason = choice?.finishReason,
        promptEvalCount = usage?.promptTokens,
        evalCount = usage?.completionTokens,
    )
}

/** Epoch seconds -> the RFC 3339 spelling the native API uses for `created_at`. */
private fun Long.toRfc3339(): String = Instant.ofEpochSecond(this).toString()
