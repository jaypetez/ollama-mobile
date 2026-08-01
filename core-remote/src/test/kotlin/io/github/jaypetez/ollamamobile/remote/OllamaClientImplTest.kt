package io.github.jaypetez.ollamamobile.remote

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Bytes of the first NDJSON line, so the server delivers exactly one token before going quiet. */
private const val FIRST_LINE_BYTES = 74L

/** How long the mid-generation silence lasts. Bounded so the fixture shuts down promptly. */
private const val STREAM_PAUSE_MILLIS = 3_000L

/**
 * The wire-format traps, each one exercised against a real socket.
 *
 * Every test in this class fails if the corresponding handling is removed from
 * the client or the parsers, which is the only way these stay fixed: all of
 * them are invisible in a happy-path integration test, and most of them present
 * as "the model stopped early" rather than as an error.
 *
 * `runBlocking` rather than `runTest`: these exercise real I/O against
 * MockWebServer, so virtual time would only make the delays lie.
 */
@RunWith(JUnit4::class)
class OllamaClientImplTest {
    private lateinit var server: MockWebServer
    private lateinit var history: RequestHistory
    private lateinit var client: OllamaClientImpl

    private val ndjson = Headers.headersOf("Content-Type", "application/x-ndjson")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        history = RequestHistory(MutableClock())
        client = OllamaClientImpl(testHttp(history = history))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ref() = server.serverRef()

    private fun chatTurn() = ChatTurn(
        model = "qwen3:4b",
        messages = listOf(RemoteMessage(role = Role.USER, content = "hi")),
    )

    // ------------------------------------------------------------------ traps

    @Test
    fun `trap 1 and 2 - absent counters stay null and nanosecond durations become a real rate`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body(
                    """
                {"model":"m","message":{"role":"assistant","content":"hi"},"done":false}
                {"model":"m","done":true,"done_reason":"stop","total_duration":4883583458,"eval_count":298,"eval_duration":4535599000}
                    """.trimIndent() + "\n",
                ).build(),
        )

        val events = client.chat(ref(), chatTurn()).toList()
        val completed = events.filterIsInstance<StreamEvent.Completed>().single()

        // prompt_eval_count was absent: it must stay null, because rendering it
        // as 0 tok/s is a fabricated measurement.
        assertThat(completed.stats.promptTokens).isNull()
        assertThat(completed.stats.promptEvalNanos).isNull()
        assertThat(completed.stats.loadNanos).isNull()

        // 298 tokens in 4_535_599_000 nanoseconds is ~65.7 tok/s. Reading the
        // duration as milliseconds would give 65_700_000.
        assertThat(completed.stats.completionTokens).isEqualTo(298)
        assertThat(completed.stats.tokensPerSecond!!).isWithin(0.5).of(65.7)
    }

    @Test
    fun `trap 3 - the final chunk may omit message entirely`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body(
                    """
                {"model":"m","message":{"role":"assistant","content":"Hello"},"done":false}
                {"model":"m","done":true,"done_reason":"stop","eval_count":2,"eval_duration":1000000}
                    """.trimIndent() + "\n",
                ).build(),
        )

        val events = client.chat(ref(), chatTurn()).toList()

        // No Failed event: a terminal chunk without `message` is normal, not a
        // parse error.
        assertThat(events.filterIsInstance<StreamEvent.Failed>()).isEmpty()
        assertThat(events.filterIsInstance<StreamEvent.Text>().map { it.delta }).containsExactly("Hello")
        assertThat(events.last()).isInstanceOf(StreamEvent.Completed::class.java)
    }

    @Test
    fun `trap 4 - a mid-stream error at HTTP 200 becomes a failure, not a short answer`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .headers(ndjson)
                .body(
                    """
                {"model":"m","message":{"role":"assistant","content":"Once upon"},"done":false}
                {"error":"an error was encountered while running the model: context canceled"}
                    """.trimIndent() + "\n",
                ).build(),
        )

        val events = client.chat(ref(), chatTurn()).toList()

        assertThat(events.filterIsInstance<StreamEvent.Text>().map { it.delta }).containsExactly("Once upon")
        val failure = events.filterIsInstance<StreamEvent.Failed>().single()
        assertThat(failure.error.message).contains("context canceled")
        // Emphatically not a Completed: the answer is not complete.
        assertThat(events.filterIsInstance<StreamEvent.Completed>()).isEmpty()
    }

    @Test
    fun `a stream that ends without done is reported as truncated`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body(
                    """{"model":"m","message":{"role":"assistant","content":"half a sen"},"done":false}""" + "\n",
                ).build(),
        )

        val events = client.chat(ref(), chatTurn()).toList()

        assertThat(events.last()).isInstanceOf(StreamEvent.Failed::class.java)
        assertThat((events.last() as StreamEvent.Failed).error).isInstanceOf(AppError.Engine::class.java)
    }

    @Test
    fun `trap 5 - a body split across reads at arbitrary offsets still parses`() = runBlocking {
        val body = buildString {
            repeat(20) { index ->
                append("""{"model":"m","message":{"role":"assistant","content":"tok$index "},"done":false}""")
                append('\n')
            }
            append("""{"model":"m","done":true,"done_reason":"stop"}""").append('\n')
        }

        // 7-byte chunks slice every line mid-object, which is exactly what TCP
        // does on a real network and never does on localhost.
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .chunkedBody(body, 7)
                .build(),
        )

        val events = client.chat(ref(), chatTurn()).toList()

        assertThat(events.filterIsInstance<StreamEvent.Text>()).hasSize(20)
        assertThat(events.filterIsInstance<StreamEvent.Text>().first().delta).isEqualTo("tok0 ")
        assertThat(events.last()).isInstanceOf(StreamEvent.Completed::class.java)
    }

    @Test
    fun `trap 6 - native tool arguments arrive as a parsed object`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body(
                    """
                {"model":"m","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"Nairobi","unit":"c"}}}]},"done":false}
                {"model":"m","done":true,"done_reason":"tool_calls"}
                    """.trimIndent() + "\n",
                ).build(),
        )

        val call = client
            .chat(ref(), chatTurn())
            .toList()
            .filterIsInstance<StreamEvent.ToolCall>()
            .single()
            .call

        assertThat(call.name).isEqualTo("get_weather")
        assertThat(call.arguments.keys).containsExactly("city", "unit")
        assertThat(call.arguments["city"].toString()).isEqualTo("\"Nairobi\"")
    }

    @Test
    fun `trap 7 - a 503 about pending requests is QueueFull, not a generic HTTP error`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .body("""{"error":"server busy, please try again. maximum pending requests exceeded"}""")
                .build(),
        )

        val failed = client.version(ref()) as AppResult.Failure

        assertThat(failed.error).isInstanceOf(AppError.Network.QueueFull::class.java)
    }

    @Test
    fun `an ordinary 503 stays an HTTP error`() = runBlocking {
        // Three responses because a 5xx *is* retried — which is the other half
        // of the QueueFull rule: the queue-full case above answers once and is
        // not retried at all.
        repeat(3) {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(503)
                    .body("<html>bad gateway</html>")
                    .build(),
            )
        }

        val failed = client.version(ref()) as AppResult.Failure

        assertThat(failed.error).isInstanceOf(AppError.Network.Http::class.java)
    }

    @Test
    fun `trap 8 - a streaming call survives a pause longer than the read timeout`() = runBlocking {
        // The shared client in this test has a 250 ms read timeout; the server
        // waits 900 ms before the body. A generation that thinks before its
        // first token looks exactly like this, and only readTimeout = 0 on the
        // streaming path survives it.
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .bodyDelay(900, TimeUnit.MILLISECONDS)
                .body("""{"model":"m","message":{"role":"assistant","content":"late"},"done":true}""" + "\n")
                .build(),
        )

        val events = client.chat(ref(), chatTurn()).toList()

        assertThat(events.filterIsInstance<StreamEvent.Text>().map { it.delta }).containsExactly("late")
        assertThat(events.filterIsInstance<StreamEvent.Failed>()).isEmpty()
    }

    @Test
    fun `the same pause does kill a one-shot call, which is why the override is scoped to streams`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .bodyDelay(900, TimeUnit.MILLISECONDS)
                .body("""{"version":"0.12.3"}""")
                .build(),
        )

        val failed = client.version(ref()) as AppResult.Failure

        assertThat(failed.error).isInstanceOf(AppError.Network.Timeout::class.java)
    }

    @Test
    fun `cancelling mid-generation closes the socket instead of leaking the reader`() = runBlocking {
        // The other side of trap 8. readTimeout = 0 means a parser waiting for
        // the next token is parked in a blocking socket read with no deadline,
        // and coroutine cancellation cannot interrupt a blocking read. Unless
        // something closes the call, backing out of a screen mid-answer leaks a
        // thread and a socket, and the server — which stops generating when its
        // client disconnects — never finds out that it should.
        //
        // The server hands over one complete line and then goes quiet, which is
        // what a model thinking mid-sentence looks like. The pause is seconds
        // rather than minutes only so the fixture tears down promptly: the
        // assertion below is two orders of magnitude tighter than it, and
        // closing a socket takes microseconds.
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body(
                    """{"model":"m","message":{"role":"assistant","content":"tok"},"done":false}""" + "\n" +
                        """{"model":"m","done":true}""" + "\n",
                ).throttleBody(FIRST_LINE_BYTES, STREAM_PAUSE_MILLIS, TimeUnit.MILLISECONDS)
                .build(),
        )

        val firstToken = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            client.chat(ref(), chatTurn()).collect { event ->
                if (event is StreamEvent.Text) firstToken.complete(Unit)
            }
        }
        firstToken.await()

        // Without the cancellation watcher this sits here until the server
        // speaks again, because a blocking read cannot be cancelled.
        withTimeout(STREAM_PAUSE_MILLIS / 3) { job.cancelAndJoin() }
    }

    @Test
    fun `trap 9 - unknown response fields are ignored and no nulls are sent`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .body("""{"version":"0.12.3","a_field_from_a_newer_server":{"nested":true}}""")
                .build(),
        )

        val version = client.version(ref()) as AppResult.Success
        assertThat(version.value.version).isEqualTo("0.12.3")

        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body("""{"done":true}""" + "\n")
                .build(),
        )
        client.chat(ref(), chatTurn()).toList()
        server.takeRequest()
        val chatRequest = server
            .takeRequest()
            .body
            ?.utf8()
            .orEmpty()

        // explicitNulls = false: an unset option must be absent, because Ollama
        // rejects an explicit null where it expects a value.
        assertThat(chatRequest).doesNotContain("null")
        assertThat(chatRequest).contains("\"stream\":true")
    }

    // ------------------------------------------------------- other behaviours

    @Test
    fun `a chat request carries the model, the messages and the sampling options`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body("""{"done":true}""" + "\n")
                .build(),
        )

        client
            .chat(
                ref(),
                ChatTurn(
                    model = "qwen3:4b",
                    messages = listOf(RemoteMessage(role = Role.USER, content = "hello")),
                    sampling = SamplingParams(temperature = 0.2, topK = 20),
                    keepAlive = "10m",
                ),
            ).toList()

        val recorded = server.takeRequest()
        assertThat(recorded.target).isEqualTo("/api/chat")
        val body = recorded.body?.utf8().orEmpty()
        assertThat(body).contains("\"model\":\"qwen3:4b\"")
        assertThat(body).contains("\"content\":\"hello\"")
        assertThat(body).contains("\"temperature\":0.2")
        assertThat(body).contains("\"top_k\":20")
        assertThat(body).contains("\"keep_alive\":\"10m\"")
    }

    @Test
    fun `listModels maps tags onto domain models scoped to the server`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .body(
                    """{"models":[{"name":"qwen3:4b-q4_K_M","size":2600000000,""" +
                        """"details":{"quantization_level":"Q4_K_M"}}]}""",
                ).build(),
        )

        val models = (client.listModels(ref()) as AppResult.Success).value

        assertThat(models).hasSize(1)
        assertThat(models.single().name).isEqualTo("qwen3:4b-q4_K_M")
        assertThat(models.single().origin).isInstanceOf(ModelOrigin.Remote::class.java)
        assertThat(models.single().quantization?.label).isEqualTo("Q4_K_M")
    }

    @Test
    fun `showModel structures the unstructured parameters blob`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .body(
                    """
                {"license":"MIT","system":"You are terse.","template":"{{ .Prompt }}",
                 "parameters":"stop                           \"<|im_end|>\"\nstop                           \"<|im_start|>\"\ntemperature                    0.7",
                 "capabilities":["completion","tools"],
                 "model_info":{"general.parameter_count":4000000000,"qwen3.context_length":40960}}
                    """.trimIndent(),
                ).build(),
        )

        val details = (client.showModel(ref(), "qwen3:4b") as AppResult.Success).value

        assertThat(server.takeRequest().target).isEqualTo("/api/show")
        assertThat(details.parameters.stop).containsExactly("<|im_end|>", "<|im_start|>").inOrder()
        assertThat(details.parameters.single("temperature")).isEqualTo("0.7")
        assertThat(details.contextLength).isEqualTo(40960)
        assertThat(details.parameterCount).isEqualTo(4_000_000_000L)
        assertThat(details.capabilities).contains(ModelCapability.TOOLS)
    }

    @Test
    fun `pull progress is emitted as it arrives and a failure is an event, not an exception`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body(
                    """
                {"status":"pulling manifest"}
                {"status":"pulling 1a2b","digest":"sha256:1a2b","total":100,"completed":40}
                {"error":"pull model manifest: file does not exist"}
                    """.trimIndent() + "\n",
                ).build(),
        )

        client.pullModel(ref(), "nope").test {
            assertThat(awaitItem().status).isEqualTo("pulling manifest")
            val layer = awaitItem()
            assertThat(layer.fraction).isWithin(0.001).of(0.4)
            val failure = awaitItem()
            assertThat(failure.error).isNotNull()
            awaitComplete()
        }
    }

    @Test
    fun `a pull that stops before success is a failure, not a finished download`() = runBlocking {
        // /api/pull has no `done` flag; the only thing that says the model
        // arrived is the final `{"status":"success"}`. Without it the flow just
        // completing is indistinguishable from a successful pull, and the model
        // the caller then tries to run is not there.
        server.enqueue(
            MockResponse
                .Builder()
                .headers(ndjson)
                .body("""{"status":"pulling manifest"}""" + "\n")
                .build(),
        )

        val progress = client.pullModel(ref(), "qwen3:4b").toList()

        assertThat(progress.first().error).isNull()
        assertThat(progress.last().error).isNotNull()
        assertThat(progress.none { it.done }).isTrue()
    }

    @Test
    fun `an unreachable server produces Unreachable rather than an exception`() = runBlocking {
        val dead = testServer("http://127.0.0.1:1")

        val failed = client.version(dead) as AppResult.Failure

        assertThat(failed.error).isInstanceOf(AppError.Network.Unreachable::class.java)
    }

    @Test
    fun `a malformed base url fails without a request`() = runBlocking {
        val broken = testServer("ws://nope")

        assertThat(client.version(broken)).isInstanceOf(AppResult.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
