package io.github.jaypetez.ollamamobile.server

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.remote.dto.OllamaErrorResponse
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The queue, and the exact body a full one returns.
 *
 * The bound is on *waiters*, not just on concurrency: a plain semaphore parks
 * every excess request, and each parked request holds its body and its socket
 * for as long as the client cares to keep it open. That is the leak.
 */
class RequestAdmissionTest {
    @Test
    fun `a full queue is refused rather than parked`() = runTest {
        val admission = RequestAdmission(maxConcurrent = 1, maxQueued = 1)
        val release = CompletableDeferred<Unit>()

        coroutineScope {
            val running = async { admission.withPermit { release.await() } }
            val waiting = async { admission.withPermit { release.await() } }
            yieldUntil { admission.inFlight == 2 }

            val refused = admission.withPermit { Unit }

            assertThat(refused).isNull()
            release.complete(Unit)
            running.await()
            waiting.await()
        }
        assertThat(admission.inFlight).isEqualTo(0)
    }

    @Test
    fun `the peak high-water mark is recorded for the diagnostics screen`() = runTest {
        val admission = RequestAdmission(maxConcurrent = 1, maxQueued = 4)
        val release = CompletableDeferred<Unit>()

        coroutineScope {
            val jobs = List(3) { async { admission.withPermit { release.await() } } }
            yieldUntil { admission.inFlight == 3 }
            release.complete(Unit)
            jobs.forEach { it.await() }
        }

        assertThat(admission.peak).isEqualTo(3)
    }

    @Test
    fun `a permit is released even when the body throws`() = runTest {
        val admission = RequestAdmission(maxConcurrent = 1, maxQueued = 0)

        runCatching { admission.withPermit { error("boom") } }

        assertThat(admission.inFlight).isEqualTo(0)
        assertThat(admission.withPermit { "ok" }).isEqualTo("ok")
    }

    @Test
    fun `an overloaded server answers 503 with Ollama's exact message`() = runTest {
        // maxQueued = 0 with a gateway that never returns means the second
        // request has nowhere to wait, which is the condition under test.
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            onChat = {
                started.complete(Unit)
                gate.await()
            },
        )
        val env = ServerEnvironment(
            config = ServerConfig(maxConcurrentRequests = 1, maxQueuedRequests = 0),
            gateway = gateway,
            clock = FakeClock(),
        )

        withServer(env) { http ->
            coroutineScope {
                val first = async {
                    http.post("/api/chat") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"qwen3:1.7b","messages":[],"stream":false}""")
                    }
                }
                started.await()

                val refused = http.post("/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","messages":[],"stream":false}""")
                }

                assertThat(refused.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
                val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), refused.bodyAsText())
                // Byte for byte, double space included. Clients string-match it.
                assertThat(error.error)
                    .isEqualTo("server busy, please try again.  maximum pending requests exceeded")
                assertThat(error.error).isEqualTo(ServerErrors.MAX_QUEUE)

                gate.complete(Unit)
                first.await()
            }
        }
    }
}

/** Spins the dispatcher until [condition] holds. Cheaper and more honest than a delay. */
private suspend fun yieldUntil(condition: () -> Boolean) {
    repeat(YIELD_ATTEMPTS) {
        if (condition()) return
        yield()
    }
    check(condition()) { "condition never became true" }
}

private const val YIELD_ATTEMPTS = 1_000
