package io.github.jaypetez.ollamamobile.data

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.ChatTurn
import io.github.jaypetez.ollamamobile.remote.EmbeddingResult
import io.github.jaypetez.ollamamobile.remote.RemoteChatClient
import io.github.jaypetez.ollamamobile.remote.StreamEvent
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A clock the test moves by hand. */
class FakeClock(
    var now: Long = 1_000L,
) : WallClock {
    override fun nowMillis(): Long = now

    /** Advances and returns the new time, for building an ordered transcript. */
    fun tick(millis: Long = 1L): Long {
        now += millis
        return now
    }
}

/**
 * A [RemoteChatClient] that replays a scripted stream.
 *
 * A hand-written fake rather than a mock because the interesting property is
 * the *sequence* of events — deltas split at awkward boundaries, an error
 * arriving mid-stream — and expressing a sequence is what a list does well and
 * a stub chain does badly.
 */
class ScriptedChatClient(
    private val script: List<StreamEvent>,
) : RemoteChatClient {
    /** The turns this client was asked to run, for asserting the request shape. */
    val requests = mutableListOf<ChatTurn>()

    override suspend fun listModels(server: ServerRef): AppResult<List<ModelRef>> = AppResult.Success(emptyList())

    override fun chat(server: ServerRef, request: ChatTurn): Flow<StreamEvent> = flow {
        requests += request
        script.forEach { emit(it) }
    }

    override suspend fun embed(
        server: ServerRef,
        model: String,
        inputs: List<String>,
    ): AppResult<EmbeddingResult> = AppResult.Success(EmbeddingResult(emptyList()))
}

fun testServerRef(
    id: ServerId = ServerId("pi"),
    baseUrl: String = "http://192.168.1.40:11434",
): ServerRef = ServerRef(id = id, label = "Pi", baseUrl = baseUrl)

fun testModelRef(
    serverId: ServerId = ServerId("pi"),
    name: String = "qwen3:1.7b",
): ModelRef = ModelRef(
    id = ModelId("${serverId.value}/$name"),
    displayName = name.substringBefore(':'),
    name = name,
    origin = ModelOrigin.Remote(serverId),
)
