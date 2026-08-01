package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.common.result.map
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.dto.ChatRequest
import io.github.jaypetez.ollamamobile.remote.dto.ChatResponse
import io.github.jaypetez.ollamamobile.remote.dto.CopyRequest
import io.github.jaypetez.ollamamobile.remote.dto.DeleteRequest
import io.github.jaypetez.ollamamobile.remote.dto.EmbedInput
import io.github.jaypetez.ollamamobile.remote.dto.EmbedRequest
import io.github.jaypetez.ollamamobile.remote.dto.EmbedResponse
import io.github.jaypetez.ollamamobile.remote.dto.EmbeddingsRequest
import io.github.jaypetez.ollamamobile.remote.dto.EmbeddingsResponse
import io.github.jaypetez.ollamamobile.remote.dto.GenerateRequest
import io.github.jaypetez.ollamamobile.remote.dto.GenerateResponse
import io.github.jaypetez.ollamamobile.remote.dto.OllamaMessage
import io.github.jaypetez.ollamamobile.remote.dto.OllamaModel
import io.github.jaypetez.ollamamobile.remote.dto.OllamaTool
import io.github.jaypetez.ollamamobile.remote.dto.OllamaToolCall
import io.github.jaypetez.ollamamobile.remote.dto.OllamaToolCallFunction
import io.github.jaypetez.ollamamobile.remote.dto.OllamaToolFunction
import io.github.jaypetez.ollamamobile.remote.dto.PsResponse
import io.github.jaypetez.ollamamobile.remote.dto.PullProgress as PullProgressDto
import io.github.jaypetez.ollamamobile.remote.dto.PullRequest
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.github.jaypetez.ollamamobile.remote.dto.ShowRequest
import io.github.jaypetez.ollamamobile.remote.dto.ShowResponse
import io.github.jaypetez.ollamamobile.remote.dto.TagsResponse
import io.github.jaypetez.ollamamobile.remote.dto.VersionResponse
import io.github.jaypetez.ollamamobile.remote.dto.toGenerationStats
import io.github.jaypetez.ollamamobile.remote.dto.toOllamaOptions
import io.github.jaypetez.ollamamobile.remote.modelfile.ModelParameters
import io.github.jaypetez.ollamamobile.remote.stream.asNdjsonFlow
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * The Ollama native API over OkHttp.
 *
 * Every HTTP concern — deriving the per-call client from the shared one,
 * `readTimeout = 0` on streams, the credential, the pin, retries, the request
 * history — lives in [RemoteHttp], which the `/v1` client shares. What is left
 * here is the part that is genuinely about *this* protocol: which endpoint,
 * which DTO, and how a chunk becomes a [StreamEvent].
 */
@Singleton
class OllamaClientImpl
    @Inject
    internal constructor(
        private val http: RemoteHttp,
    ) : OllamaClient {
        override suspend fun version(server: ServerRef): AppResult<ServerVersion> =
            get(server, "api/version", VersionResponse.serializer()).map { ServerVersion(it.version) }

        override suspend fun listModels(server: ServerRef): AppResult<List<ModelRef>> =
            get(server, "api/tags", TagsResponse.serializer()).map { tags ->
                tags.models.map { it.toModelRef(server) }
            }

        override suspend fun showModel(server: ServerRef, model: String): AppResult<ModelDetails> = post(
            server = server,
            path = "api/show",
            // A read, despite the verb: the model is not modified, so retrying
            // it is safe and the policy is told so.
            kind = RequestKind.IDEMPOTENT,
            body = ShowRequest(model = model),
            serializer = ShowRequest.serializer(),
            responseSerializer = ShowResponse.serializer(),
        ).map { it.toModelDetails(model) }

        override fun chat(server: ServerRef, request: ChatTurn): Flow<StreamEvent> = streamEvents(
            server = server,
            path = "api/chat",
            body = request.toWire(),
            bodySerializer = ChatRequest.serializer(),
            responseSerializer = ChatResponse.serializer(),
            toEvents = ::chatEvents,
        )

        override fun generate(server: ServerRef, request: CompletionTurn): Flow<StreamEvent> = streamEvents(
            server = server,
            path = "api/generate",
            body = request.toWire(),
            bodySerializer = GenerateRequest.serializer(),
            responseSerializer = GenerateResponse.serializer(),
            toEvents = ::generateEvents,
        )

        override suspend fun embed(
            server: ServerRef,
            model: String,
            inputs: List<String>,
        ): AppResult<EmbeddingResult> {
            val modern = post(
                server = server,
                path = "api/embed",
                kind = RequestKind.IDEMPOTENT,
                body = EmbedRequest(model = model, input = EmbedInput.of(inputs)),
                serializer = EmbedRequest.serializer(),
                responseSerializer = EmbedResponse.serializer(),
            ).map { response ->
                EmbeddingResult(
                    embeddings = response.embeddings,
                    stats = GenerationStats(
                        promptTokens = response.promptEvalCount,
                        loadNanos = response.loadDurationNanos,
                        totalNanos = response.totalDurationNanos,
                    ),
                )
            }

            // Servers older than 0.1.39, and several third-party
            // implementations of this protocol, have only the legacy singular
            // endpoint. A 404 here means "this route does not exist", not "the
            // model does not exist" — the model name is in the body.
            val notFound = (modern as? AppResult.Failure)
                ?.error
                .let { it is AppError.Network.Http && it.code == HTTP_NOT_FOUND }
            if (!notFound) return modern
            return legacyEmbed(server, model, inputs)
        }

        override fun pullModel(server: ServerRef, model: String, allowInsecure: Boolean): Flow<PullProgress> = flow {
            var succeeded = false
            stream(
                server = server,
                path = "api/pull",
                body = PullRequest(model = model, insecure = allowInsecure.takeIf { it }),
                bodySerializer = PullRequest.serializer(),
                responseSerializer = PullProgressDto.serializer(),
            ).collect { line ->
                val progress = PullProgress(
                    status = line.status,
                    digest = line.digest,
                    totalBytes = line.total,
                    completedBytes = line.completed,
                    // The stream ends with `{"status":"success"}`; there is no
                    // `done` flag on this endpoint the way there is on /api/chat.
                    done = line.status.equals("success", ignoreCase = true),
                )
                if (progress.done) succeeded = true
                emit(progress)
            }
            if (!succeeded) emit(truncatedPull())
        }.catch { failure ->
            currentCoroutineContext().ensureActive()
            emit(PullProgress(status = "error", error = RemoteError.fromThrowable(failure)))
        }

        override suspend fun deleteModel(server: ServerRef, model: String): AppResult<Unit> = http.request(
            server = server,
            method = "DELETE",
            path = "api/delete",
            // Deleting twice leaves the same world as deleting once; the second
            // call answers 404 and the caller treats that as already gone.
            kind = RequestKind.IDEMPOTENT,
            body = RemoteJson.encodeToString(DeleteRequest.serializer(), DeleteRequest(model)).asJsonBody(),
        ) { }

        override suspend fun copyModel(server: ServerRef, source: String, destination: String): AppResult<Unit> =
            http.request(
                server = server,
                method = "POST",
                path = "api/copy",
                kind = RequestKind.NON_IDEMPOTENT,
                body = RemoteJson
                    .encodeToString(CopyRequest.serializer(), CopyRequest(source, destination))
                    .asJsonBody(),
            ) { }

        override suspend fun runningModels(server: ServerRef): AppResult<List<RunningModel>> =
            get(server, "api/ps", PsResponse.serializer()).map { ps ->
                ps.models.map { running ->
                    RunningModel(
                        name = running.name,
                        sizeBytes = running.size,
                        sizeVramBytes = running.sizeVram,
                        expiresAtMillis = running.expiresAt?.toEpochMillisOrNull(),
                        digest = running.digest,
                    )
                }
            }

        override suspend fun createModel(server: ServerRef, definition: ModelDefinition): AppResult<Unit> = http
            .request(
                server = server,
                method = "POST",
                path = "api/create",
                kind = RequestKind.NON_IDEMPOTENT,
                body = definition.toCreateBody().asJsonBody(),
            ) { }

        // ------------------------------------------------------------ requests

        private suspend fun <T> get(
            server: ServerRef,
            path: String,
            serializer: DeserializationStrategy<T>,
        ): AppResult<T> = http.request(server, "GET", path, RequestKind.IDEMPOTENT) { text ->
            RemoteJson.decodeFromString(serializer, text)
        }

        private suspend fun <B, T> post(
            server: ServerRef,
            path: String,
            kind: RequestKind,
            body: B,
            serializer: SerializationStrategy<B>,
            responseSerializer: DeserializationStrategy<T>,
        ): AppResult<T> = http.request(
            server = server,
            method = "POST",
            path = path,
            kind = kind,
            body = RemoteJson.encodeToString(serializer, body).asJsonBody(),
        ) { text ->
            RemoteJson.decodeFromString(responseSerializer, text)
        }

        // ----------------------------------------------------------- streaming

        private fun <B, D> stream(
            server: ServerRef,
            path: String,
            body: B,
            bodySerializer: SerializationStrategy<B>,
            responseSerializer: DeserializationStrategy<D>,
        ): Flow<D> = http.stream(
            server = server,
            path = path,
            body = RemoteJson.encodeToString(bodySerializer, body).asJsonBody(),
        ) { responseBody ->
            responseBody.asNdjsonFlow(responseSerializer)
        }

        private fun <B, D> streamEvents(
            server: ServerRef,
            path: String,
            body: B,
            bodySerializer: SerializationStrategy<B>,
            responseSerializer: DeserializationStrategy<D>,
            toEvents: (D, MutableList<StreamEvent>) -> Boolean,
        ): Flow<StreamEvent> = flow {
            var sawTerminal = false
            val buffer = mutableListOf<StreamEvent>()
            stream(server, path, body, bodySerializer, responseSerializer).collect { chunk ->
                buffer.clear()
                if (toEvents(chunk, buffer)) sawTerminal = true
                buffer.forEach { emit(it) }
            }
            if (!sawTerminal) emit(truncatedStream())
        }.catch { failure ->
            // A failed generation is an emitted event, not an exception: see the
            // KDoc on StreamEvent. Cancellation is not a failure and must keep
            // propagating, so the context is checked first.
            currentCoroutineContext().ensureActive()
            emit(StreamEvent.Failed(RemoteError.fromThrowable(failure)))
        }

        private suspend fun legacyEmbed(
            server: ServerRef,
            model: String,
            inputs: List<String>,
        ): AppResult<EmbeddingResult> {
            val vectors = mutableListOf<List<Float>>()
            inputs.forEach { input ->
                val result = post(
                    server = server,
                    path = "api/embeddings",
                    kind = RequestKind.IDEMPOTENT,
                    body = EmbeddingsRequest(model = model, prompt = input),
                    serializer = EmbeddingsRequest.serializer(),
                    responseSerializer = EmbeddingsResponse.serializer(),
                )
                when (result) {
                    is AppResult.Success -> vectors += result.value.embedding
                    is AppResult.Failure -> return result
                }
            }
            return AppResult.Success(EmbeddingResult(embeddings = vectors))
        }

        private companion object {
            const val HTTP_NOT_FOUND = 404
        }
    }

// ----------------------------------------------------------------- conversions

internal fun String.asJsonBody(): RequestBody = toRequestBody(JSON_MEDIA_TYPE)

/**
 * The stream stopped without saying it was finished.
 *
 * Trap 4's quieter sibling: the socket closed with no `done: true` line and no
 * error line. The answer is incomplete, and saying so is the only honest
 * option — the alternative renders half a sentence as a finished reply.
 */
internal fun truncatedStream(): StreamEvent.Failed = StreamEvent.Failed(
    AppError.Engine.GenerationFailed(
        message = "The server closed the connection before the response was complete.",
    ),
)

/**
 * The same hole on the pull path.
 *
 * `/api/pull` has no `done` flag — the only thing that says the model arrived
 * is a final `{"status":"success"}`. A stream that stops before it therefore
 * looks, to a collector that just watches the flow complete, exactly like a
 * finished download of a model that is not on the server. The error is a
 * transport one because that is what happened: the connection ended early.
 */
internal fun truncatedPull(): PullProgress = PullProgress(
    status = "error",
    error = AppError.Network.Unreachable(
        message = "The server closed the connection before the download was complete.",
    ),
)

/**
 * Chat chunk to events. Returns true when this chunk was the terminal one.
 *
 * Everything about this function is trap 3: [ChatResponse.message] is nullable
 * because the final chunk routinely omits it while carrying `done: true` and
 * the statistics, so content is read only when there is a message and the
 * terminal chunk is recognised by `done`, never by the absence of content.
 */
internal fun chatEvents(chunk: ChatResponse, into: MutableList<StreamEvent>): Boolean {
    chunk.message?.let { message ->
        message.thinking?.takeIf { it.isNotEmpty() }?.let { into += StreamEvent.Reasoning(it) }
        message.content.takeIf { it.isNotEmpty() }?.let { into += StreamEvent.Text(it) }
        message.toolCalls?.forEach { call ->
            into += StreamEvent.ToolCall(
                RemoteToolCall(name = call.function.name, arguments = call.function.arguments),
            )
        }
    }
    if (chunk.done) {
        into += StreamEvent.Completed(DoneReason.fromWire(chunk.doneReason), chunk.toGenerationStats())
    }
    return chunk.done
}

/** As [chatEvents], for `/api/generate`, whose text field is `response`. */
private fun generateEvents(chunk: GenerateResponse, into: MutableList<StreamEvent>): Boolean {
    chunk.thinking?.takeIf { it.isNotEmpty() }?.let { into += StreamEvent.Reasoning(it) }
    chunk.response.takeIf { it.isNotEmpty() }?.let { into += StreamEvent.Text(it) }
    if (chunk.done) {
        into += StreamEvent.Completed(DoneReason.fromWire(chunk.doneReason), chunk.toGenerationStats())
    }
    return chunk.done
}

internal fun ChatTurn.toWire(): ChatRequest = ChatRequest(
    model = model,
    messages = messages.map { it.toWire() },
    tools = tools.takeIf { it.isNotEmpty() }?.map { tool ->
        OllamaTool(
            function = OllamaToolFunction(
                name = tool.name,
                description = tool.description,
                parameters = tool.parametersSchema,
            ),
        )
    },
    options = sampling.toOllamaOptions(),
    think = think,
    keepAlive = keepAlive?.let(::JsonPrimitive),
    stream = true,
)

internal fun RemoteMessage.toWire(): OllamaMessage = OllamaMessage(
    role = role.wireName,
    content = content,
    images = imagesBase64.takeIf { it.isNotEmpty() },
    toolCalls = toolCalls
        .takeIf { it.isNotEmpty() }
        ?.map { call ->
            OllamaToolCall(
                function = OllamaToolCallFunction(
                    name = call.name,
                    arguments = call.arguments,
                ),
            )
        },
    toolName = toolName,
)

private fun CompletionTurn.toWire(): GenerateRequest = GenerateRequest(
    model = model,
    prompt = prompt,
    system = system,
    template = template,
    images = imagesBase64.takeIf { it.isNotEmpty() },
    options = sampling.toOllamaOptions(),
    raw = raw.takeIf { it },
    keepAlive = keepAlive?.let(::JsonPrimitive),
    stream = true,
)

private fun OllamaModel.toModelRef(server: ServerRef): ModelRef = ModelRef(
    // Derived from the server and the tag, never random: a rescan has to
    // produce the same id or the picker grows a duplicate every refresh.
    id = ModelId("${server.id.value}/$name"),
    displayName = name.substringBefore(':').ifEmpty { name },
    name = name,
    origin = ModelOrigin.Remote(server.id),
    quantization = details?.quantizationLevel?.let(Quantization::fromFileName) ?: Quantization.fromFileName(name),
    sizeBytes = size,
)

private fun ShowResponse.toModelDetails(model: String): ModelDetails = ModelDetails(
    model = model,
    system = system,
    template = template,
    license = license,
    // The blob, structured. See ModelfileService for why this is not a text
    // editor.
    parameters = ModelParameters.parse(parameters),
    modelInfo = modelInfo.orEmpty(),
    capabilities = capabilities.orEmpty().mapNotNull(::toCapability).toSet(),
    parameterCount = modelInfo?.longOrNull("general.parameter_count"),
    quantizationLabel = details?.quantizationLevel,
    contextLength = modelInfo?.contextLength(),
)

private fun toCapability(wire: String): ModelCapability? = when (wire.lowercase()) {
    "completion" -> ModelCapability.CHAT

    "embedding" -> ModelCapability.EMBEDDING

    "vision" -> ModelCapability.VISION

    "tools" -> ModelCapability.TOOLS

    "thinking" -> ModelCapability.REASONING

    // An unrecognised capability is a newer server, not a broken one.
    else -> null
}

private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitiveOrNull()?.content?.toLongOrNull()

/**
 * `model_info` keys are namespaced by architecture (`"llama.context_length"`,
 * `"qwen3.context_length"`), so the architecture has to be read first or the
 * suffix matched. Matching the suffix also covers an architecture we have never
 * heard of.
 */
private fun JsonObject.contextLength(): Int? = entries
    .firstOrNull { it.key.endsWith(".context_length") }
    ?.value
    ?.jsonPrimitiveOrNull()
    ?.content
    ?.toIntOrNull()

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun String.toEpochMillisOrNull(): Long? = try {
    Instant.parse(this).toEpochMilli()
} catch (_: DateTimeParseException) {
    // Servers differ on the offset format here and an unparseable expiry is not
    // worth failing a `/api/ps` over — the caller renders "unknown".
    null
}

/**
 * The structured `/api/create` body.
 *
 * Assembled as JSON rather than through `CreateRequest`, whose `parameters` is
 * an `OllamaOptions`: that type is the *sampling* subset, and a model
 * definition may legitimately carry any key the server understands. Mapping
 * through it would silently drop whatever the user typed that is not one of the
 * dozen fields it declares.
 */
private fun ModelDefinition.toCreateBody(): String = RemoteJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("model", model)
        put("from", from)
        system?.let { put("system", it) }
        template?.let { put("template", it) }
        license?.let { put("license", it) }
        if (!parameters.isEmpty) put("parameters", JsonObject(parameters.toWireMap()))
        put("stream", false)
    },
)
