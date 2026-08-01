package io.github.jaypetez.ollamamobile.remote.health

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.ChatTurn
import io.github.jaypetez.ollamamobile.remote.CompletionTurn
import io.github.jaypetez.ollamamobile.remote.EmbeddingResult
import io.github.jaypetez.ollamamobile.remote.ModelDefinition
import io.github.jaypetez.ollamamobile.remote.ModelDetails
import io.github.jaypetez.ollamamobile.remote.OllamaClient
import io.github.jaypetez.ollamamobile.remote.PullProgress
import io.github.jaypetez.ollamamobile.remote.RunningModel
import io.github.jaypetez.ollamamobile.remote.ServerVersion
import io.github.jaypetez.ollamamobile.remote.StreamEvent
import io.github.jaypetez.ollamamobile.remote.testServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The circuit breaker, on virtual time.
 *
 * What is being asserted is not "the flag flips" but the behaviour the flag
 * exists for: after the threshold, the monitor stops making requests, and it
 * starts again once the cooldown has passed.
 */
@RunWith(JUnit4::class)
class ServerHealthMonitorTest {
    private val server = testServer("http://192.168.1.40:11434")

    private val config = ServerHealthMonitor.Config(
        foregroundIntervalMillis = 1_000,
        backgroundIntervalMillis = 10_000,
        failureThreshold = 3,
        cooldownMillis = 30_000,
        maxCooldownMillis = 120_000,
    )

    @Test
    fun `a healthy server is reported with its version and loaded models`() = runTest {
        val client = FakeOllamaClient()
        val monitor = ServerHealthMonitor(client, backgroundScope) { currentTime }

        monitor.setServers(listOf(server), config)
        advanceTimeBy(1)

        val health = monitor.health.value.getValue(server.id)
        assertThat(health.reachable).isTrue()
        assertThat(health.version).isEqualTo("0.12.3")
        assertThat(health.loadedModels).containsExactly("qwen3:4b")
        assertThat(health.breaker).isEqualTo(BreakerState.CLOSED)

        monitor.stop()
    }

    @Test
    fun `the breaker opens after repeated failures and stops polling`() = runTest {
        val client = FakeOllamaClient(failing = true)
        val monitor = ServerHealthMonitor(client, backgroundScope) { currentTime }

        monitor.setServers(listOf(server), config)
        // Three intervals: three failures, which is the threshold.
        advanceTimeBy(3 * config.foregroundIntervalMillis)

        val opened = monitor.health.value.getValue(server.id)
        assertThat(opened.breaker).isEqualTo(BreakerState.OPEN)
        assertThat(opened.consecutiveFailures).isAtLeast(config.failureThreshold)
        assertThat(opened.reachable).isFalse()

        val callsWhenOpened = client.versionCalls
        // Ten more ticks while the breaker is open must cost nothing: the whole
        // point is that a dead server stops adding a connect timeout to
        // everything the app does.
        advanceTimeBy(10 * config.foregroundIntervalMillis)
        assertThat(client.versionCalls).isEqualTo(callsWhenOpened)

        monitor.stop()
    }

    @Test
    fun `the breaker half-opens after the cooldown and closes on success`() = runTest {
        val client = FakeOllamaClient(failing = true)
        val monitor = ServerHealthMonitor(client, backgroundScope) { currentTime }

        monitor.setServers(listOf(server), config)
        advanceTimeBy(3 * config.foregroundIntervalMillis)
        assertThat(
            monitor.health.value
                .getValue(server.id)
                .breaker,
        ).isEqualTo(BreakerState.OPEN)

        // The server comes back while the breaker is open.
        client.failing = false
        advanceTimeBy(config.cooldownMillis + config.foregroundIntervalMillis)

        val recovered = monitor.health.value.getValue(server.id)
        assertThat(recovered.reachable).isTrue()
        assertThat(recovered.breaker).isEqualTo(BreakerState.CLOSED)
        assertThat(recovered.consecutiveFailures).isEqualTo(0)

        monitor.stop()
    }

    @Test
    fun `a rejected request does not trip the breaker`() = runTest {
        // A 404 is a problem with the request, not with the server. Counting it
        // would let one bad model name disable a healthy machine.
        val client = FakeOllamaClient(failing = true, error = AppError.Network.Http(code = 404))
        val monitor = ServerHealthMonitor(client, backgroundScope) { currentTime }

        monitor.setServers(listOf(server), config)
        advanceTimeBy(5 * config.foregroundIntervalMillis)

        assertThat(
            monitor.health.value
                .getValue(server.id)
                .breaker,
        ).isEqualTo(BreakerState.CLOSED)

        monitor.stop()
    }

    @Test
    fun `backgrounding stretches the polling interval`() = runTest {
        val client = FakeOllamaClient()
        val monitor = ServerHealthMonitor(client, backgroundScope) { currentTime }

        monitor.setServers(listOf(server), config)
        advanceTimeBy(1)
        val foregroundCalls = client.versionCalls
        monitor.setForegrounded(false)

        // Two foreground intervals would have been two more probes; at the
        // background interval it is none.
        advanceTimeBy(2 * config.foregroundIntervalMillis)
        assertThat(client.versionCalls).isEqualTo(foregroundCalls)

        advanceTimeBy(config.backgroundIntervalMillis)
        assertThat(client.versionCalls).isGreaterThan(foregroundCalls)

        monitor.stop()
    }

    @Test
    fun `a disabled server is dropped rather than polled`() = runTest {
        val client = FakeOllamaClient()
        val monitor = ServerHealthMonitor(client, backgroundScope) { currentTime }

        monitor.setServers(listOf(server), config)
        advanceTimeBy(1)
        assertThat(monitor.health.value).containsKey(server.id)

        monitor.setServers(listOf(server.copy(enabled = false)), config)
        advanceTimeBy(1)
        assertThat(monitor.health.value).doesNotContainKey(server.id)

        monitor.stop()
    }
}

/** A client that answers instantly, so the test controls time rather than the network. */
private class FakeOllamaClient(
    var failing: Boolean = false,
    private val error: AppError = AppError.Network.Unreachable(),
) : OllamaClient {
    var versionCalls = 0
        private set

    override suspend fun version(server: ServerRef): AppResult<ServerVersion> {
        versionCalls++
        return if (failing) AppResult.Failure(error) else AppResult.Success(ServerVersion("0.12.3"))
    }

    override suspend fun listModels(server: ServerRef): AppResult<List<ModelRef>> = AppResult.Success(emptyList())

    override suspend fun showModel(server: ServerRef, model: String): AppResult<ModelDetails> =
        AppResult.Success(ModelDetails(model = model))

    override fun chat(server: ServerRef, request: ChatTurn): Flow<StreamEvent> = emptyFlow()

    override fun generate(server: ServerRef, request: CompletionTurn): Flow<StreamEvent> = emptyFlow()

    override suspend fun embed(
        server: ServerRef,
        model: String,
        inputs: List<String>,
    ): AppResult<EmbeddingResult> = AppResult.Success(EmbeddingResult(emptyList()))

    override fun pullModel(server: ServerRef, model: String, allowInsecure: Boolean): Flow<PullProgress> = emptyFlow()

    override suspend fun deleteModel(server: ServerRef, model: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun copyModel(server: ServerRef, source: String, destination: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun runningModels(server: ServerRef): AppResult<List<RunningModel>> =
        if (failing) AppResult.Failure(error) else AppResult.Success(listOf(RunningModel(name = "qwen3:4b")))

    override suspend fun createModel(server: ServerRef, definition: ModelDefinition): AppResult<Unit> =
        AppResult.Success(Unit)
}
