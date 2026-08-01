package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.ToolInvocation
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionChoice
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionChunk
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionChunkChoice
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionRequest
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionResponse
import io.github.jaypetez.ollamamobile.remote.dto.EmbedInput
import io.github.jaypetez.ollamamobile.remote.dto.ModelsResponse
import io.github.jaypetez.ollamamobile.remote.dto.OllamaMessage
import io.github.jaypetez.ollamamobile.remote.dto.OllamaOptions
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiChatMessage
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiEmbeddingData
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiEmbeddingsRequest
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiEmbeddingsResponse
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiFunctionCall
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiToolCall
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.github.jaypetez.ollamamobile.remote.dto.Usage
import io.github.jaypetez.ollamamobile.remote.dto.toNative
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.utils.io.ByteWriteChannel
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * The OpenAI-compatible surface.
 *
 * The acceptance test for this file is that `openai.OpenAI(base_url=".../v1")`
 * works unmodified, and that is a stricter bar than "the JSON looks right":
 * the SDK raises on a stream that ends without `data: [DONE]`, on a chunk
 * missing `object`, and on an `id` that is not a string. So every field the
 * SDK's models declare non-optional is populated here even when it carries no
 * information.
 */

private const val CHAT_OBJECT = "chat.completion"
private const val CHAT_CHUNK_OBJECT = "chat.completion.chunk"
private const val COMPLETION_OBJECT = "text_completion"

fun Route.openAiRoutes(env: ServerEnvironment) {
    endpoint("/v1/models", HttpMethod.Get, HttpMethod.Head) {
        val models = env.gateway
            .listAvailableModels()
            .map { it.toOpenAiModel(env.clock.nowEpochSeconds()) }
            .sortedBy { it.id }
        call.respondJson(ModelsResponse.serializer(), ModelsResponse(objectType = "list", data = models))
    }

    endpoint("/v1/chat/completions", HttpMethod.Post) {
        val body = call.receiveJson(ChatCompletionRequest.serializer())
        val model = env.requireModel(call, body.model) ?: return@endpoint
        val native = body.toNative()
        val request = buildInferenceRequest(model, native.messages, native.options, native.tools, think = null)
        env.runGuarded(call, model, keepAliveElement = null) {
            if (body.stream) {
                call.streamChatCompletion(env, model, request)
            } else {
                call.completeChatCompletion(env, model, request)
            }
        }
    }

    endpoint("/v1/completions", HttpMethod.Post) {
        val body = call.receiveJson(CompletionRequest.serializer())
        val model = env.requireModel(call, body.model) ?: return@endpoint
        val request = body.toInferenceRequest(model)
        env.runGuarded(call, model, keepAliveElement = null) {
            if (body.stream) {
                call.streamCompletion(env, model, request)
            } else {
                call.completeCompletion(env, model, request)
            }
        }
    }

    endpoint("/v1/embeddings", HttpMethod.Post) {
        val body = call.receiveJson(OpenAiEmbeddingsRequest.serializer())
        val model = env.requireModel(call, body.model) ?: return@endpoint
        val texts = when (val input = body.input) {
            is EmbedInput.Text -> listOf(input.value)
            is EmbedInput.Batch -> input.values
        }
        env.runGuarded(call, model, keepAliveElement = null) {
            val vectors = env.embedAll(texts)
            call.respondJson(
                OpenAiEmbeddingsResponse.serializer(),
                OpenAiEmbeddingsResponse(
                    objectType = "list",
                    model = model.name,
                    data = vectors.mapIndexed { index, vector ->
                        OpenAiEmbeddingData(objectType = "embedding", embedding = vector, index = index)
                    },
                    // Zeroes, and this is the one place this server reports a
                    // number it did not measure. The OpenAI SDK declares
                    // `usage` REQUIRED on an embeddings response with two
                    // non-optional ints, so omitting it makes
                    // `client.embeddings.create(...)` raise a validation error
                    // before the caller ever sees the vectors. A zero that is
                    // obviously a placeholder beats an endpoint the SDK cannot
                    // call at all; the embedding path has no tokeniser to ask.
                    usage = Usage(promptTokens = 0, totalTokens = 0),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// /v1/chat/completions
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.streamChatCompletion(
    env: ServerEnvironment,
    model: ModelRef,
    request: InferenceRequest,
) {
    val id = newCompletionId("chatcmpl")
    val created = env.clock.nowEpochSeconds()
    respondBytesWriter(contentType = SseContentType) {
        val state = StreamState()
        // The SDK reads `role` from the first delta and nowhere else, so an
        // opening frame carrying only the role is not decorative — without it
        // the assembled message has no author.
        writeChatFrame(id, created, model, OpenAiChatMessage(role = "assistant", content = ""), finish = null)
        env.gateway.chat(request).collect { event ->
            when (event) {
                is InferenceEvent.Token -> {
                    writeChatFrame(id, created, model, OpenAiChatMessage(content = event.text), finish = null)
                }

                is InferenceEvent.ToolCall -> {
                    state.toolCalls += event.call
                }

                is InferenceEvent.Stats -> {
                    state.stats = event.stats
                }

                is InferenceEvent.Completed -> {
                    state.finish = event.reason
                }

                is InferenceEvent.Failed -> {
                    state.failure = event.error.message
                }

                // `/v1` has no reasoning channel. Folding it into content would
                // feed the model's scratchpad back as the answer.
                is InferenceEvent.Reasoning, is InferenceEvent.Started -> {
                    Unit
                }
            }
        }
        writeChatTerminalFrames(id, created, model, state)
        writeSseDone()
    }
}

private suspend fun ByteWriteChannel.writeChatTerminalFrames(
    id: String,
    created: Long,
    model: ModelRef,
    state: StreamState,
) {
    if (state.failure != null) {
        // Mid-stream failure after a 200. The SDKs surface an `error` key on a
        // frame, so this is the only way to say so without a silent truncation.
        writeSseData(RemoteJson.encodeToString(SseErrorFrame.serializer(), SseErrorFrame(state.failure.orEmpty())))
        return
    }
    val toolFrame = state.toolCalls.takeIf { it.isNotEmpty() }?.let { calls ->
        OpenAiChatMessage(toolCalls = calls.mapIndexed { index, call -> call.toOpenAiToolCall(index) })
    }
    if (toolFrame != null) writeChatFrame(id, created, model, toolFrame, finish = null)
    writeChatFrame(
        id = id,
        created = created,
        model = model,
        delta = OpenAiChatMessage(),
        finish = state.effectiveFinish().toOpenAiFinishReason(),
        usage = state.usage(),
    )
}

private suspend fun ByteWriteChannel.writeChatFrame(
    id: String,
    created: Long,
    model: ModelRef,
    delta: OpenAiChatMessage,
    finish: String?,
    usage: Usage? = null,
) {
    val chunk = ChatCompletionChunk(
        id = id,
        objectType = CHAT_CHUNK_OBJECT,
        created = created,
        model = model.name,
        choices = listOf(ChatCompletionChunkChoice(index = 0, delta = delta, finishReason = finish)),
        usage = usage,
    )
    writeSseData(RemoteJson.encodeToString(ChatCompletionChunk.serializer(), chunk))
}

private suspend fun ApplicationCall.completeChatCompletion(
    env: ServerEnvironment,
    model: ModelRef,
    request: InferenceRequest,
) {
    val state = env.collect(request)
    val failure = state.failure
    if (failure != null) {
        respondOllamaError(HttpStatusCode.InternalServerError, failure)
        return
    }
    val response = ChatCompletionResponse(
        id = newCompletionId("chatcmpl"),
        objectType = CHAT_OBJECT,
        created = env.clock.nowEpochSeconds(),
        model = model.name,
        choices = listOf(
            ChatCompletionChoice(
                index = 0,
                message = OpenAiChatMessage(
                    role = "assistant",
                    content = state.text.toString(),
                    toolCalls = state.toolCalls
                        .takeIf { it.isNotEmpty() }
                        ?.mapIndexed { index, call -> call.toOpenAiToolCall(index) },
                ),
                finishReason = state.effectiveFinish().toOpenAiFinishReason(),
            ),
        ),
        usage = state.usage(),
    )
    respondJson(ChatCompletionResponse.serializer(), response)
}

// ---------------------------------------------------------------------------
// /v1/completions
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.streamCompletion(
    env: ServerEnvironment,
    model: ModelRef,
    request: InferenceRequest,
) {
    val id = newCompletionId("cmpl")
    val created = env.clock.nowEpochSeconds()
    respondBytesWriter(contentType = SseContentType) {
        val state = StreamState()
        env.gateway.chat(request).collect { event ->
            when (event) {
                is InferenceEvent.Token -> writeCompletionFrame(id, created, model, event.text, finish = null)
                is InferenceEvent.Stats -> state.stats = event.stats
                is InferenceEvent.Completed -> state.finish = event.reason
                is InferenceEvent.Failed -> state.failure = event.error.message
                else -> Unit
            }
        }
        val failure = state.failure
        if (failure != null) {
            writeSseData(RemoteJson.encodeToString(SseErrorFrame.serializer(), SseErrorFrame(failure)))
        } else {
            writeCompletionFrame(id, created, model, "", state.effectiveFinish().toOpenAiFinishReason())
        }
        writeSseDone()
    }
}

private suspend fun ByteWriteChannel.writeCompletionFrame(
    id: String,
    created: Long,
    model: ModelRef,
    text: String,
    finish: String?,
) {
    val chunk = CompletionResponse(
        id = id,
        objectType = COMPLETION_OBJECT,
        created = created,
        model = model.name,
        choices = listOf(CompletionChoice(index = 0, text = text, finishReason = finish)),
    )
    writeSseData(RemoteJson.encodeToString(CompletionResponse.serializer(), chunk))
}

private suspend fun ApplicationCall.completeCompletion(
    env: ServerEnvironment,
    model: ModelRef,
    request: InferenceRequest,
) {
    val state = env.collect(request)
    val failure = state.failure
    if (failure != null) {
        respondOllamaError(HttpStatusCode.InternalServerError, failure)
        return
    }
    val response = CompletionResponse(
        id = newCompletionId("cmpl"),
        objectType = COMPLETION_OBJECT,
        created = env.clock.nowEpochSeconds(),
        model = model.name,
        choices = listOf(
            CompletionChoice(
                index = 0,
                text = state.text.toString(),
                finishReason = state.effectiveFinish().toOpenAiFinishReason(),
            ),
        ),
        usage = state.usage(),
    )
    respondJson(CompletionResponse.serializer(), response)
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

/**
 * The error shape an SDK recognises inside a stream.
 *
 * Declared here rather than reusing Ollama's envelope because the `/v1` clients
 * look for a nested `error.message`, not a top-level `error` string.
 */
@Serializable
internal data class SseErrorFrame(
    val error: SseErrorBody,
) {
    constructor(message: String) : this(SseErrorBody(message))
}

@Serializable
internal data class SseErrorBody(
    val message: String,
    val type: String = "server_error",
)

/**
 * Usage, or null when nothing was measured.
 *
 * Null rather than zeroes: the SDK exposes `usage` verbatim, and a caller
 * billing on `total_tokens` must be able to tell "nothing reported" from "no
 * tokens".
 */
internal fun StreamState.usage(): Usage? {
    val measured = stats ?: return null
    if (measured.promptTokens == null && measured.completionTokens == null) return null
    return Usage(
        promptTokens = measured.promptTokens,
        completionTokens = measured.completionTokens,
        totalTokens = listOfNotNull(measured.promptTokens, measured.completionTokens)
            .takeIf { it.isNotEmpty() }
            ?.sum(),
    )
}

internal fun ToolInvocation.toOpenAiToolCall(index: Int): OpenAiToolCall =
    OpenAiToolCall(
        id = id ?: "call_${UUID.randomUUID().toString().replace("-", "").take(TOOL_ID_LENGTH)}",
        function = OpenAiFunctionCall(
            name = name,
            arguments = argumentsJson.ifEmpty { "{}" },
        ),
        index = index,
    )

private const val TOOL_ID_LENGTH = 24

/** `chatcmpl-…`. The SDK only requires a string, but the prefix aids debugging. */
private fun newCompletionId(prefix: String): String = "$prefix-" + UUID.randomUUID().toString().replace("-", "")

/**
 * `/v1/completions` -> the chat-shaped request the gateway takes.
 *
 * `prompt` may be a string or an array of strings; only the first element of an
 * array is used, because the domain has no place for a batch and silently
 * concatenating them would answer a different question than was asked.
 */
private fun CompletionRequest.toInferenceRequest(model: ModelRef): InferenceRequest = buildInferenceRequest(
    model = model,
    messages = listOf(
        OllamaMessage(role = "user", content = prompt.firstText()),
    ),
    options = OllamaOptions(
        temperature = temperature,
        topP = topP,
        seed = seed,
        numPredict = maxTokens,
        stop = stop.stringList(),
    ).takeUnless { it.isEmpty },
    tools = null,
    think = null,
)

private fun JsonElement?.firstText(): String = when (this) {
    is JsonPrimitive -> content
    is JsonArray -> firstOrNull()?.let { (it as? JsonPrimitive)?.content }.orEmpty()
    else -> ""
}

private fun JsonElement?.stringList(): List<String>? = when (this) {
    is JsonPrimitive -> listOf(content)
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }.takeIf { it.isNotEmpty() }
    else -> null
}
