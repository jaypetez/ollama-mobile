package io.github.jaypetez.ollamamobile.remote.stream

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.remote.dto.ChatResponse
import io.github.jaypetez.ollamamobile.remote.dto.PullProgress
import io.github.jaypetez.ollamamobile.remote.dto.toGenerationStats
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The NDJSON parser against real sockets and real partial reads.
 *
 * The bodies are lifted from `/api/chat` and `/api/pull`. Every test here
 * corresponds to a way this integration is normally wrong, and each would pass
 * against a naive `readLine`-per-chunk parser only until it met a network.
 */
@RunWith(JUnit4::class)
class NdjsonFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = streamingClient()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `a chat stream whose final chunk omits message still parses`() = runBlocking<Unit> {
        // Trap 3. The chunk with the statistics is exactly the one with no
        // `message` key.
        val body = buildString {
            appendLine("""{"model":"qwen3","message":{"role":"assistant","content":"Hel"},"done":false}""")
            appendLine("""{"model":"qwen3","message":{"role":"assistant","content":"lo"},"done":false}""")
            appendLine(
                """{"model":"qwen3","done":true,"done_reason":"stop",""" +
                    """"eval_count":100,"eval_duration":2000000000}""",
            )
        }
        server.enqueue(wholeResponse(body, NDJSON_MEDIA_TYPE))

        val chunks = client.streamBody(server).asNdjsonFlow<ChatResponse>().toList()

        assertThat(chunks).hasSize(3)
        assertThat(chunks.map { it.message?.content }).containsExactly("Hel", "lo", null).inOrder()

        val last = chunks.last()
        assertThat(last.message).isNull()
        assertThat(last.done).isTrue()
        // And the numbers on that chunk are the ones the UI shows.
        assertThat(last.toGenerationStats().tokensPerSecond).isWithin(1e-9).of(50.0)
    }

    @Test
    fun `a mid-stream error at HTTP 200 surfaces as an error and not as a completion`() = runBlocking<Unit> {
        // Trap 4. The headers already went out with a 200, so the only signal
        // is the payload. A parser that decodes every line into ChatResponse
        // sees an all-defaults object and reports a short answer.
        val body = buildString {
            appendLine("""{"model":"qwen3","message":{"role":"assistant","content":"partial"},"done":false}""")
            appendLine("""{"error":"an error was encountered while running the model: context canceled"}""")
        }
        server.enqueue(wholeResponse(body, NDJSON_MEDIA_TYPE))

        client.streamBody(server).asNdjsonFlow<ChatResponse>().test {
            assertThat(awaitItem().message?.content).isEqualTo("partial")

            val failure = awaitError()
            assertThat(failure).isInstanceOf(AppErrorException::class.java)
            val error = (failure as AppErrorException).error
            // Not a completion, not a silently truncated answer: a typed error
            // carrying the server's own sentence.
            assertThat(error).isInstanceOf(AppError.Network::class.java)
            assertThat(error.message).contains("context canceled")
        }
    }

    @Test
    fun `an NDJSON body split mid-token by the transport still parses`() = runBlocking<Unit> {
        // Trap 5, over a socket: chunked transfer encoding plus throttling, so
        // the reads land in the middle of keys, values and multi-byte
        // characters rather than on line boundaries.
        val body = buildString {
            appendLine("""{"model":"qwen3","message":{"role":"assistant","content":"Hello "},"done":false}""")
            appendLine("""{"model":"qwen3","message":{"role":"assistant","content":"wörld — "},"done":false}""")
            appendLine("""{"model":"qwen3","message":{"role":"assistant","content":"done"},"done":false}""")
            appendLine("""{"model":"qwen3","done":true,"eval_count":3,"eval_duration":1000000000}""")
        }
        server.enqueue(chunkedStreamingResponse(body, NDJSON_MEDIA_TYPE))

        val chunks = client.streamBody(server).asNdjsonFlow<ChatResponse>().toList()

        assertThat(chunks).hasSize(4)
        assertThat(chunks.mapNotNull { it.message?.content }.joinToString("")).isEqualTo("Hello wörld — done")
    }

    @Test
    fun `an NDJSON body delivered seven bytes at a time still parses`() = runBlocking<Unit> {
        // The deterministic half of trap 5. Reads are guaranteed to stop
        // mid-token, so removing the buffering in `consumeLines` fails this.
        val body = """
            {"model":"qwen3","message":{"role":"assistant","content":"one"},"done":false}
            {"model":"qwen3","message":{"role":"assistant","content":"two"},"done":true}
        """.trimIndent()

        val chunks = DripSource(body).asBody(NDJSON_MEDIA_TYPE).asNdjsonFlow<ChatResponse>().toList()

        assertThat(chunks.map { it.message?.content }).containsExactly("one", "two").inOrder()
        // Note the fixture has no trailing newline: the last line must still
        // come out.
        assertThat(chunks.last().done).isTrue()
    }

    @Test
    fun `a multi-byte character split across reads is not replaced with U+FFFD`() = runBlocking<Unit> {
        // The other half of trap 5, one level down. Three bytes per read lands
        // inside the em dash (3 bytes) and inside the astral-plane emoji (4
        // bytes) every time. A parser that decodes each read to a String as it
        // arrives substitutes U+FFFD for the halves, which reaches the user as
        // mojibake in the answer rather than as an error anybody notices.
        //
        // Two bytes (o-umlaut), three (em dash) and four (an astral-plane
        // globe, which is a surrogate pair in the Kotlin string as well).
        val text = "wörld — 🌍 näh"
        val body = """{"model":"m","message":{"role":"assistant","content":"$text"},"done":true}""" + "\n"

        val chunks = DripSource(body, chunkBytes = 3)
            .asBody(NDJSON_MEDIA_TYPE)
            .asNdjsonFlow<ChatResponse>()
            .toList()

        assertThat(chunks.single().message?.content).isEqualTo(text)
    }

    @Test
    fun `blank lines are skipped and unknown fields are ignored`() = runBlocking<Unit> {
        // Trap 9, end to end. Some proxies inject blank keep-alive lines, and
        // Ollama adds response fields between point releases.
        val body = "\n" +
            """{"model":"qwen3","message":{"role":"assistant","content":"hi"},"done":false,"future":{"x":1}}""" +
            "\n\n" +
            """{"model":"qwen3","done":true,"a_brand_new_counter":42}""" +
            "\n\n"
        server.enqueue(wholeResponse(body, NDJSON_MEDIA_TYPE))

        val chunks = client.streamBody(server).asNdjsonFlow<ChatResponse>().toList()

        assertThat(chunks).hasSize(2)
        assertThat(chunks.first().message?.content).isEqualTo("hi")
        assertThat(chunks.last().done).isTrue()
    }

    @Test
    fun `a malformed line fails the flow instead of being dropped`() = runBlocking<Unit> {
        val body = buildString {
            appendLine("""{"model":"qwen3","message":{"role":"assistant","content":"hi"},"done":false}""")
            appendLine("""{"model":"qwen3","message":{"role":"assist""")
        }
        server.enqueue(wholeResponse(body, NDJSON_MEDIA_TYPE))

        client.streamBody(server).asNdjsonFlow<ChatResponse>().test {
            assertThat(awaitItem().message?.content).isEqualTo("hi")

            val failure = awaitError()
            assertThat(failure).isInstanceOf(AppErrorException::class.java)
            // Silently skipping this line would present a truncated answer as
            // a complete one.
            assertThat((failure as AppErrorException).error).isInstanceOf(AppError.Unexpected::class.java)
        }
    }

    @Test
    fun `abandoning the flow closes the response body`() = runBlocking<Unit> {
        val body = (1..50).joinToString("\n") { """{"status":"pulling","completed":$it,"total":50}""" }
        val source = DripSource(body)

        val first = source
            .asBody(NDJSON_MEDIA_TYPE)
            .asNdjsonFlow<PullProgress>()
            .take(1)
            .toList()

        assertThat(first).hasSize(1)
        // The socket must not be left open when the collector walks away.
        assertThat(source.closed).isTrue()
    }

    @Test
    fun `a pull stream decodes its progress phases`() = runBlocking<Unit> {
        val body = buildString {
            appendLine("""{"status":"pulling manifest"}""")
            appendLine("""{"status":"pulling abc","digest":"sha256:abc","total":1000,"completed":400}""")
            appendLine("""{"status":"success"}""")
        }
        server.enqueue(chunkedStreamingResponse(body, NDJSON_MEDIA_TYPE, chunkSize = 9))

        val progress = client.streamBody(server, path = "/api/pull").asNdjsonFlow<PullProgress>().toList()

        assertThat(progress.map { it.status })
            .containsExactly("pulling manifest", "pulling abc", "success")
            .inOrder()
        assertThat(progress.first().fraction).isNull()
        assertThat(progress[1].fraction).isWithin(1e-9).of(0.4)
    }
}
