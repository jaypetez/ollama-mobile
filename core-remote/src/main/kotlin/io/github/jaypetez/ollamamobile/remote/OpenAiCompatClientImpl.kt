package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.common.result.map
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionChunk
import io.github.jaypetez.ollamamobile.remote.dto.EmbedInput
import io.github.jaypetez.ollamamobile.remote.dto.ModelsResponse
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiEmbeddingsRequest
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiEmbeddingsResponse
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiToolCall
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.github.jaypetez.ollamamobile.remote.dto.Usage
import io.github.jaypetez.ollamamobile.remote.dto.decodeToolArguments
import io.github.jaypetez.ollamamobile.remote.dto.toGenerationStats
import io.github.jaypetez.ollamamobile.remote.dto.toOpenAi
import io.github.jaypetez.ollamamobile.remote.stream.asSseFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * The `/v1` client.
 *
 * ## One vocabulary, two wires
 *
 * Nothing here invents a second set of domain types. The request is built as a
 * native [io.github.jaypetez.ollamamobile.remote.dto.ChatRequest] and put
 * through the `toOpenAi()` converters that live beside the DTOs, and the reply
 * is fed back through `toNative()`. So a caller sees the same [StreamEvent]s,
 * the same [ModelRef]s and the same [RemoteToolCall]s whichever protocol
 * answered, and the divergences are handled in exactly one place — the
 * converter file — rather than at every call site.
 *
 * ## What `/v1` cannot do, and is not faked
 *
 *  * **No timing statistics.** `usage` carries token counts and no durations at
 *    all, so [GenerationStats] comes back with null nanos and
 *    `tokensPerSecond` is null. Inventing a duration to fill the UI would be
 *    inventing a measurement.
 *  * **No `keep_alive`, `num_ctx`, `top_k`, `min_p` or `repeat_penalty`.** The
 *    converters drop them rather than mapping them onto the nearest-looking
 *    OpenAI field; `frequency_penalty` is not `repeat_penalty`, and pretending
 *    otherwise silently changes the sampling the user configured.
 *
 * ## Streaming tool calls
 *
 * Over `/v1` a tool call's `arguments` arrive as string *fragments* spread
 * across chunks and keyed by `index` — `{"ci`, then `ty":"Nai`, and so on. No
 * fragment is valid JSON on its own, so they are accumulated per index here and
 * parsed once, when `finish_reason` arrives. Parsing each fragment produces a
 * stream of exceptions that look exactly like a malformed server.
 */
@Singleton
class OpenAiCompatClientImpl
    @Inject
    internal constructor(
        private val http: RemoteHttp,
    ) : OpenAiCompatClient {
        override suspend fun listModels(server: ServerRef): AppResult<List<ModelRef>> = http
            .request(server, "GET", "v1/models", RequestKind.IDEMPOTENT) { text ->
                RemoteJson.decodeFromString(ModelsResponse.serializer(), text)
            }.map { response ->
                response.data.map { model ->
                    ModelRef(
                        id = ModelId("${server.id.value}/${model.id}"),
                        displayName = model.id.substringBefore(':').ifEmpty { model.id },
                        name = model.id,
                        origin = ModelOrigin.Remote(server.id),
                        quantization = Quantization.fromFileName(model.id),
                    )
                }
            }

        override fun chat(server: ServerRef, request: ChatTurn): Flow<StreamEvent> = flow {
            // Built straight from the domain turn, not via toWire().toOpenAi():
            // the native DTO cannot carry a tool-call id, and losing it breaks
            // tool loops in a way the server accepts. See ChatTurn.toOpenAiWire.
            val wire = request.toOpenAiWire(stream = true)
            val body = RemoteJson
                .encodeToString(
                    io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionRequest
                        .serializer(),
                    wire,
                ).asJsonBody()

            val pendingCalls = linkedMapOf<Int, ToolCallFragment>()
            var sawTerminal = false
            // Carried across frames rather than read off the terminal one: the
            // spec does not say which frame `usage` rides on, and a server that
            // attaches it to the last *content* chunk would otherwise have its
            // counts thrown away.
            var usage: Usage? = null

            http
                .stream(server, "v1/chat/completions", body) { responseBody ->
                    responseBody.asSseFlow(ChatCompletionChunk.serializer())
                }.collect { chunk ->
                    chunk.usage?.let { usage = it }
                    val choice = chunk.choices.firstOrNull()
                    choice?.delta?.toolCalls?.forEach { call -> pendingCalls.accumulate(call) }
                    choice
                        ?.delta
                        ?.content
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(StreamEvent.Text(it)) }

                    val finish = choice?.finishReason
                    if (finish != null) {
                        pendingCalls.values.forEach { emit(StreamEvent.ToolCall(it.build())) }
                        pendingCalls.clear()
                        emit(
                            StreamEvent.Completed(
                                doneReason = DoneReason.fromWire(finish),
                                // `usage` is only present when the request asked
                                // for it, and a frame carrying nothing but usage
                                // may still follow this one — that one is lost,
                                // because Completed is terminal and emitting it
                                // late would leave the UI spinning on a server
                                // that never sends [DONE]. Absent counts stay
                                // absent rather than becoming zero.
                                stats = usage?.toGenerationStats() ?: GenerationStats.Empty,
                            ),
                        )
                        sawTerminal = true
                    }
                }

            if (!sawTerminal) emit(truncatedStream())
        }.catch { failure ->
            currentCoroutineContext().ensureActive()
            emit(StreamEvent.Failed(RemoteError.fromThrowable(failure)))
        }

        override suspend fun embed(
            server: ServerRef,
            model: String,
            inputs: List<String>,
        ): AppResult<EmbeddingResult> = http
            .request(
                server = server,
                method = "POST",
                path = "v1/embeddings",
                kind = RequestKind.IDEMPOTENT,
                body = RemoteJson
                    .encodeToString(
                        OpenAiEmbeddingsRequest.serializer(),
                        OpenAiEmbeddingsRequest(model = model, input = EmbedInput.of(inputs)),
                    ).asJsonBody(),
            ) { text ->
                RemoteJson.decodeFromString(OpenAiEmbeddingsResponse.serializer(), text)
            }.map { response ->
                EmbeddingResult(
                    // Ordered by the server's own index rather than by arrival:
                    // the contract is "vectors in input order" and the spec does
                    // not promise the array is sorted.
                    embeddings = response.data.sortedBy { it.index }.map { it.embedding },
                    stats = response.usage?.toGenerationStats() ?: GenerationStats.Empty,
                )
            }
    }

/** One tool call under construction, keyed by the `index` its fragments carry. */
private class ToolCallFragment(
    val id: String?,
    var name: String?,
) {
    val arguments = StringBuilder()

    fun build(): RemoteToolCall = RemoteToolCall(
        id = id,
        name = name.orEmpty(),
        // Parsed once, now that every fragment has arrived.
        arguments = decodeToolArguments(arguments.toString()),
    )
}

private fun MutableMap<Int, ToolCallFragment>.accumulate(call: OpenAiToolCall) {
    // A server that omits `index` is sending one call at a time; slot 0 is then
    // the only slot, which is exactly the non-streaming shape.
    val fragment = getOrPut(call.index ?: 0) { ToolCallFragment(call.id, call.function.name) }
    call.function.name?.let { fragment.name = it }
    fragment.arguments.append(call.function.arguments)
}
