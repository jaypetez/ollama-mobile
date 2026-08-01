package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.ToolInvocation
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.remote.dto.ChatRequest
import io.github.jaypetez.ollamamobile.remote.dto.ChatResponse
import io.github.jaypetez.ollamamobile.remote.dto.EmbedInput
import io.github.jaypetez.ollamamobile.remote.dto.EmbedRequest
import io.github.jaypetez.ollamamobile.remote.dto.EmbedResponse
import io.github.jaypetez.ollamamobile.remote.dto.EmbeddingsRequest
import io.github.jaypetez.ollamamobile.remote.dto.EmbeddingsResponse
import io.github.jaypetez.ollamamobile.remote.dto.GenerateRequest
import io.github.jaypetez.ollamamobile.remote.dto.GenerateResponse
import io.github.jaypetez.ollamamobile.remote.dto.OllamaErrorResponse
import io.github.jaypetez.ollamamobile.remote.dto.OllamaMessage
import io.github.jaypetez.ollamamobile.remote.dto.PsResponse
import io.github.jaypetez.ollamamobile.remote.dto.PullProgress
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.github.jaypetez.ollamamobile.remote.dto.ShowResponse
import io.github.jaypetez.ollamamobile.remote.dto.TagsResponse
import io.github.jaypetez.ollamamobile.remote.dto.VersionResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.utils.io.ByteWriteChannel

/**
 * The version this server reports from `/api/version`.
 *
 * Clients gate features on it — the Python client refuses `think=True` against
 * anything older than 0.9, and several UIs hide tool support below 0.4 — so
 * reporting a plausible modern version is what makes those features reachable.
 * It is the API level this surface implements, not the app's own version.
 */
const val REPORTED_OLLAMA_VERSION: String = "0.12.3"

/** `GET|HEAD /` plus the whole `/api` surface. */
fun Route.ollamaApiRoutes(env: ServerEnvironment) {
    livenessRoute()
    metadataRoutes(env)
    inferenceRoutes(env)
    managementRoutes(env)
}

/**
 * The liveness probe, and the single most load-bearing route here.
 *
 * Every Ollama client hits `GET /` before anything else and treats a
 * non-200 or a different body as "this is not an Ollama server". The subnet
 * scanner this project ships does the same. `HEAD` is registered too because
 * that is what a cheap reachability check uses.
 */
private fun Route.livenessRoute() {
    endpoint("/", HttpMethod.Get, HttpMethod.Head) {
        call.respondText(LIVENESS_BODY, ContentType.Text.Plain)
    }
}

private fun Route.metadataRoutes(env: ServerEnvironment) {
    endpoint("/api/version", HttpMethod.Get, HttpMethod.Head) {
        call.respondJson(VersionResponse.serializer(), VersionResponse(REPORTED_OLLAMA_VERSION))
    }

    endpoint("/api/tags", HttpMethod.Get, HttpMethod.Head) {
        // listAvailableModels() is already the union of every enabled backend —
        // on-device files and every reachable remote — which is exactly what a
        // client pointed at this phone should see as "the models you have".
        val models = env.gateway.listAvailableModels().map { it.toOllamaModel() }
        call.respondJson(TagsResponse.serializer(), TagsResponse(models.sortedBy { it.name }))
    }

    endpoint("/api/ps", HttpMethod.Get, HttpMethod.Head) {
        call.respondJson(PsResponse.serializer(), PsResponse(env.residency.running()))
    }

    endpoint("/api/show", HttpMethod.Post) {
        val body = call.receiveJson(ServerModelRequest.serializer())
        val requested = body.resolvedModel
        if (requested == null) {
            call.respondOllamaError(HttpStatusCode.BadRequest, ServerErrors.invalidRequest("model is required"))
            return@endpoint
        }
        val model = env.resolveModel(requested)
        if (model == null) {
            call.respondOllamaError(HttpStatusCode.NotFound, ServerErrors.modelNotFound(requested))
            return@endpoint
        }
        call.respondJson(ShowResponse.serializer(), model.toShowResponse())
    }
}

private fun Route.inferenceRoutes(env: ServerEnvironment) {
    endpoint("/api/chat", HttpMethod.Post) {
        val body = call.receiveJson(ChatRequest.serializer())
        val model = env.requireModel(call, body.model) ?: return@endpoint
        val request = buildInferenceRequest(model, body.messages, body.options, body.tools, body.think)
        env.runGuarded(call, model, body.keepAlive) {
            if (body.stream) call.streamChat(env, model, request) else call.completeChat(env, model, request)
        }
    }

    endpoint("/api/generate", HttpMethod.Post) {
        val body = call.receiveJson(GenerateRequest.serializer())
        val model = env.requireModel(call, body.model) ?: return@endpoint
        val request = body.toInferenceRequest(model)
        env.runGuarded(call, model, body.keepAlive) {
            if (body.stream) {
                call.streamGenerate(env, model, request)
            } else {
                call.completeGenerate(env, model, request)
            }
        }
    }

    endpoint("/api/embed", HttpMethod.Post) {
        val body = call.receiveJson(EmbedRequest.serializer())
        val model = env.requireModel(call, body.model) ?: return@endpoint
        val texts = when (val input = body.input) {
            is EmbedInput.Text -> listOf(input.value)
            is EmbedInput.Batch -> input.values
        }
        env.runGuarded(call, model, body.keepAlive) {
            call.respondJson(
                EmbedResponse.serializer(),
                EmbedResponse(model = model.name, embeddings = env.embedAll(texts)),
            )
        }
    }

    // The legacy singular. Older clients and several third-party
    // implementations speak only this one, and it answers with `embedding`
    // (flat array), not `embeddings` (list of vectors) — decoding one with the
    // other's shape yields an empty result rather than an error.
    endpoint("/api/embeddings", HttpMethod.Post) {
        val body = call.receiveJson(EmbeddingsRequest.serializer())
        val model = env.requireModel(call, body.model) ?: return@endpoint
        env.runGuarded(call, model, body.keepAlive) {
            val vectors = env.embedAll(listOf(body.prompt))
            call.respondJson(EmbeddingsResponse.serializer(), EmbeddingsResponse(vectors.firstOrNull().orEmpty()))
        }
    }
}

private fun Route.managementRoutes(env: ServerEnvironment) {
    endpoint("/api/pull", HttpMethod.Post) {
        val body = call.receiveJson(ServerPullRequest.serializer())
        val requested = body.resolvedModel
        if (requested == null) {
            call.respondOllamaError(HttpStatusCode.BadRequest, ServerErrors.invalidRequest("model is required"))
            return@endpoint
        }
        if (body.stream) {
            call.streamPull(env, requested, body.insecure == true)
        } else {
            val last = env.admin.pull(requested, body.insecure == true).lastOrDefault()
            call.respondJson(StatusResponse.serializer(), StatusResponse(last?.status ?: PULL_SUCCESS))
        }
    }

    endpoint("/api/delete", HttpMethod.Post, HttpMethod.Delete) {
        val body = call.receiveJson(ServerModelRequest.serializer())
        val requested = body.resolvedModel
        if (requested == null) {
            call.respondOllamaError(HttpStatusCode.BadRequest, ServerErrors.invalidRequest("model is required"))
            return@endpoint
        }
        val failure = env.admin.delete(requested)
        if (failure == null) call.respondEmptyOk() else call.respondOllamaError(HttpStatusCode.NotFound, failure)
    }

    endpoint("/api/copy", HttpMethod.Post) {
        val body = call.receiveJson(ServerCopyRequest.serializer())
        val source = body.source
        val destination = body.destination
        if (source.isNullOrBlank() || destination.isNullOrBlank()) {
            call.respondOllamaError(
                HttpStatusCode.BadRequest,
                ServerErrors.invalidRequest("source and destination are required"),
            )
            return@endpoint
        }
        val failure = env.admin.copy(source, destination)
        if (failure == null) call.respondEmptyOk() else call.respondOllamaError(HttpStatusCode.NotFound, failure)
    }
}

/** Ollama answers a successful delete/copy with 200 and an empty body. */
private suspend fun ApplicationCall.respondEmptyOk() {
    respondText("", ContentType.Application.Json, HttpStatusCode.OK)
}

const val PULL_SUCCESS: String = "success"

// ---------------------------------------------------------------------------
// Streaming
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.streamChat(env: ServerEnvironment, model: ModelRef, request: InferenceRequest) {
    respondBytesWriter(contentType = NdjsonContentType) {
        val state = StreamState()
        env.gateway.chat(request).collect { event ->
            when (event) {
                is InferenceEvent.Token -> {
                    writeChatChunk(env, model, OllamaMessage("assistant", event.text))
                }

                is InferenceEvent.Reasoning -> {
                    writeChatChunk(env, model, OllamaMessage("assistant", "", thinking = event.text))
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

                is InferenceEvent.Started -> {
                    Unit
                }
            }
        }
        val failure = state.failure
        if (failure != null) {
            writeErrorLine(failure)
        } else {
            writeNdjsonLine(RemoteJson.encodeToString(ChatResponse.serializer(), state.terminalChat(env, model)))
        }
    }
}

private suspend fun ByteWriteChannel.writeChatChunk(env: ServerEnvironment, model: ModelRef, message: OllamaMessage) {
    val chunk = ChatResponse(model = model.name, createdAt = env.clock.nowRfc3339(), message = message, done = false)
    writeNdjsonLine(RemoteJson.encodeToString(ChatResponse.serializer(), chunk))
}

private suspend fun ApplicationCall.streamGenerate(
    env: ServerEnvironment,
    model: ModelRef,
    request: InferenceRequest,
) {
    respondBytesWriter(contentType = NdjsonContentType) {
        val state = StreamState()
        env.gateway.chat(request).collect { event ->
            when (event) {
                is InferenceEvent.Token -> writeGenerateChunk(env, model, event.text, thinking = null)
                is InferenceEvent.Reasoning -> writeGenerateChunk(env, model, "", thinking = event.text)
                is InferenceEvent.Stats -> state.stats = event.stats
                is InferenceEvent.Completed -> state.finish = event.reason
                is InferenceEvent.Failed -> state.failure = event.error.message
                is InferenceEvent.ToolCall, is InferenceEvent.Started -> Unit
            }
        }
        val failure = state.failure
        if (failure != null) {
            writeErrorLine(failure)
        } else {
            writeNdjsonLine(
                RemoteJson.encodeToString(GenerateResponse.serializer(), state.terminalGenerate(env, model)),
            )
        }
    }
}

private suspend fun ByteWriteChannel.writeGenerateChunk(
    env: ServerEnvironment,
    model: ModelRef,
    text: String,
    thinking: String?,
) {
    val chunk = GenerateResponse(
        model = model.name,
        createdAt = env.clock.nowRfc3339(),
        response = text,
        thinking = thinking,
        done = false,
    )
    writeNdjsonLine(RemoteJson.encodeToString(GenerateResponse.serializer(), chunk))
}

/**
 * A generation that failed after the headers went out.
 *
 * HTTP 200 has already been committed, so the only way to report the failure is
 * one more NDJSON line carrying `error` — which is exactly what Ollama does,
 * and exactly what `:core-remote`'s `NdjsonFlow` looks for on every line.
 */
private suspend fun ByteWriteChannel.writeErrorLine(message: String) {
    writeNdjsonLine(RemoteJson.encodeToString(OllamaErrorResponse.serializer(), OllamaErrorResponse(message)))
}

private suspend fun ApplicationCall.streamPull(env: ServerEnvironment, model: String, insecure: Boolean) {
    respondBytesWriter(contentType = NdjsonContentType) {
        env.admin.pull(model, insecure).collect { progress ->
            writeNdjsonLine(RemoteJson.encodeToString(PullProgress.serializer(), progress))
        }
        // The CLI's progress bar only completes on this exact final status.
        writeNdjsonLine(RemoteJson.encodeToString(PullProgress.serializer(), PullProgress(status = PULL_SUCCESS)))
    }
}

// ---------------------------------------------------------------------------
// Non-streaming
// ---------------------------------------------------------------------------

internal suspend fun ApplicationCall.completeChat(env: ServerEnvironment, model: ModelRef, request: InferenceRequest) {
    val state = env.collect(request)
    val failure = state.failure
    if (failure != null) {
        respondOllamaError(HttpStatusCode.InternalServerError, failure)
        return
    }
    respondJson(ChatResponse.serializer(), state.terminalChat(env, model, includeText = true))
}

private suspend fun ApplicationCall.completeGenerate(
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
    respondJson(GenerateResponse.serializer(), state.terminalGenerate(env, model, includeText = true))
}

// ---------------------------------------------------------------------------
// Shared accumulation
// ---------------------------------------------------------------------------

/** What a stream produced, as it is being produced. */
internal class StreamState {
    val text: StringBuilder = StringBuilder()
    val reasoning: StringBuilder = StringBuilder()
    val toolCalls: MutableList<ToolInvocation> = mutableListOf()
    var stats: GenerationStats? = null
    var finish: FinishReason? = null
    var failure: String? = null
}

/**
 * The terminal `done: true` chunk.
 *
 * [includeText] is false while streaming — the deltas already carried the text
 * — and true for a non-streaming reply, where this object *is* the answer.
 */
internal fun StreamState.terminalChat(
    env: ServerEnvironment,
    model: ModelRef,
    includeText: Boolean = false,
): ChatResponse = ChatResponse(
    model = model.name,
    createdAt = env.clock.nowRfc3339(),
    message = OllamaMessage(
        role = "assistant",
        content = if (includeText) text.toString() else "",
        thinking = reasoning.toString().takeIf { includeText && it.isNotEmpty() },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.mapIndexed { index, call -> call.toOllamaToolCall(index) },
    ),
    done = true,
    doneReason = effectiveFinish().toOllamaDoneReason(),
    totalDurationNanos = stats?.totalNanos,
    loadDurationNanos = stats?.loadNanos,
    promptEvalCount = stats?.promptTokens,
    promptEvalDurationNanos = stats?.promptEvalNanos,
    evalCount = stats?.completionTokens,
    evalDurationNanos = stats?.evalNanos,
)

internal fun StreamState.terminalGenerate(
    env: ServerEnvironment,
    model: ModelRef,
    includeText: Boolean = false,
): GenerateResponse = GenerateResponse(
    model = model.name,
    createdAt = env.clock.nowRfc3339(),
    response = if (includeText) text.toString() else "",
    thinking = reasoning.toString().takeIf { includeText && it.isNotEmpty() },
    done = true,
    doneReason = effectiveFinish().toOllamaDoneReason(),
    totalDurationNanos = stats?.totalNanos,
    loadDurationNanos = stats?.loadNanos,
    promptEvalCount = stats?.promptTokens,
    promptEvalDurationNanos = stats?.promptEvalNanos,
    evalCount = stats?.completionTokens,
    evalDurationNanos = stats?.evalNanos,
)

/** Tool calls outrank the reported reason: `/v1` needs `tool_calls` to be exact. */
internal fun StreamState.effectiveFinish(): FinishReason = when {
    toolCalls.isNotEmpty() -> FinishReason.TOOL_CALLS
    else -> finish ?: FinishReason.UNKNOWN
}
