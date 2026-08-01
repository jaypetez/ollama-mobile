package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceGateway
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.ServerId
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/*
 * Shared fixtures. Deliberately tiny: a gateway that replays a scripted event
 * list is enough to pin every framing rule, and anything richer would test the
 * fake instead of the server.
 */

/** A model that exists on this device. */
val LOCAL_MODEL: ModelRef = ModelRef(
    id = ModelId("local-qwen"),
    displayName = "Qwen3 1.7B",
    name = "qwen3:1.7b",
    origin = ModelOrigin.Local(path = "/data/models/qwen3-1.7b.gguf"),
    parameterCount = 1_700_000_000L,
    sizeBytes = 1_200_000_000L,
    contextLength = 32_768,
    capabilities = setOf(ModelCapability.CHAT, ModelCapability.TOOLS),
)

/** A model served by a configured remote. `/api/tags` must show both. */
val REMOTE_MODEL: ModelRef = ModelRef(
    id = ModelId("remote-llama"),
    displayName = "Llama 3.2 3B",
    name = "llama3.2:3b",
    origin = ModelOrigin.Remote(ServerId("pi-in-the-hallway")),
    sizeBytes = 2_000_000_000L,
    capabilities = setOf(ModelCapability.CHAT),
)

/**
 * Replays a fixed event list for every request.
 *
 * [lastRequest] is captured so a test can assert the HTTP body was translated
 * correctly without also asserting on the response.
 */
class FakeGateway(
    private val events: List<InferenceEvent> = listOf(
        InferenceEvent.Started(InferenceTarget.Local(LOCAL_MODEL.id)),
        InferenceEvent.Token("Hello"),
        InferenceEvent.Token(" world"),
        InferenceEvent.Completed(io.github.jaypetez.ollamamobile.llm.FinishReason.STOP),
    ),
    private val models: List<ModelRef> = listOf(LOCAL_MODEL, REMOTE_MODEL),
    private val onChat: suspend () -> Unit = {},
) : InferenceGateway {
    var lastRequest: InferenceRequest? = null
        private set

    override fun chat(request: InferenceRequest): Flow<InferenceEvent> = flow {
        lastRequest = request
        onChat()
        events.forEach { emit(it) }
    }

    override suspend fun listAvailableModels(): List<ModelRef> = models

    override val reachableTargets: StateFlow<List<InferenceTarget>> =
        MutableStateFlow(listOf(InferenceTarget.Local(LOCAL_MODEL.id)))
}

/** A clock that does not move unless a test moves it. */
class FakeClock(
    var millis: Long = 1_700_000_000_000L,
) : ServerClock {
    override fun nowMillis(): Long = millis
}

fun environment(
    gateway: InferenceGateway = FakeGateway(),
    config: ServerConfig = ServerConfig(),
    admin: ModelAdmin = UnsupportedModelAdmin,
    embeddings: EmbeddingProvider = EmbeddingProvider { listOf(0.5f, -0.25f) },
    clock: ServerClock = FakeClock(),
): ServerEnvironment = ServerEnvironment(
    config = config,
    gateway = gateway,
    admin = admin,
    embeddings = embeddings,
    clock = clock,
)

/** Stands the whole HTTP surface up in-process, with no Android and no socket. */
fun withServer(
    env: ServerEnvironment = environment(),
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) = testApplication {
    application { ollamaServerModule(env) }
    val http = createClient { expectSuccess = false }
    block(http)
}
