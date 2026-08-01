package io.github.jaypetez.ollamamobile.remote.stream

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionChunk
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
 * The SSE parser. Same failure modes as NDJSON plus framing.
 *
 * The interesting property throughout is *exactly one* logical event per frame,
 * whatever the byte boundaries were: an SSE parser that emits per line rather
 * than per frame produces fragments of JSON, and one that emits per read
 * produces fragments of fragments.
 */
@RunWith(JUnit4::class)
class SseFlowTest {
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
    fun `a frame split across two writes yields exactly one event and DONE ends the stream`() = runBlocking<Unit> {
        val frame = """{"id":"chatcmpl-1","model":"qwen3","choices":""" +
            """[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}"""
        // A frame far longer than the drip size, so it is guaranteed to be
        // reassembled from several reads rather than arriving whole.
        val body = "data: $frame\n\ndata: [DONE]\n\n"

        val events = DripSource(body).asBody(SSE_MEDIA_TYPE).asSseFlow<ChatCompletionChunk>().toList()

        assertThat(events).hasSize(1)
        assertThat(
            events
                .single()
                .choices
                .single()
                .delta
                ?.content,
        ).isEqualTo("Hello")
    }

    @Test
    fun `the same frame split by the transport also yields exactly one event`() = runBlocking<Unit> {
        val body = buildString {
            append("data: ")
            append("""{"id":"1","model":"qwen3","choices":[{"index":0,"delta":{"content":"Hel"}}]}""")
            append("\n\n")
            append("data: ")
            append("""{"id":"1","model":"qwen3","choices":[{"index":0,"delta":{"content":"lo"},""")
            append(""""finish_reason":"stop"}]}""")
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(chunkedStreamingResponse(body, SSE_MEDIA_TYPE, chunkSize = 11))

        val events = client
            .streamBody(server, path = "/v1/chat/completions")
            .asSseFlow<ChatCompletionChunk>()
            .toList()

        assertThat(events).hasSize(2)
        assertThat(
            events.joinToString("") {
                it.choices
                    .single()
                    .delta
                    ?.content
                    .orEmpty()
            },
        ).isEqualTo("Hello")
        assertThat(
            events
                .last()
                .choices
                .single()
                .finishReason,
        ).isEqualTo("stop")
    }

    @Test
    fun `nothing after DONE is emitted`() = runBlocking<Unit> {
        val body = buildString {
            append("""data: {"id":"1","choices":[{"index":0,"delta":{"content":"a"}}]}""")
            append("\n\n")
            append("data: [DONE]\n\n")
            // A server that keeps writing after the terminator — or a proxy
            // that replays — must not produce another event.
            append("""data: {"id":"1","choices":[{"index":0,"delta":{"content":"ghost"}}]}""")
            append("\n\n")
        }
        server.enqueue(wholeResponse(body, SSE_MEDIA_TYPE))

        client.streamBody(server, path = "/v1/chat/completions").asSseFlow<ChatCompletionChunk>().test {
            assertThat(
                awaitItem()
                    .choices
                    .single()
                    .delta
                    ?.content,
            ).isEqualTo("a")
            awaitComplete()
        }
    }

    @Test
    fun `CRLF line endings do not leak a carriage return into the payload`() = runBlocking<Unit> {
        // "\r" left on the end of a data line makes the payload invalid JSON,
        // and the failure looks like a corrupt response rather than a framing
        // bug.
        val body = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"crlf\"}}]}\r\n" +
            "\r\n" +
            "data: [DONE]\r\n\r\n"
        server.enqueue(wholeResponse(body, SSE_MEDIA_TYPE))

        val events = client
            .streamBody(server, path = "/v1/chat/completions")
            .asSseFlow<ChatCompletionChunk>()
            .toList()

        assertThat(
            events
                .single()
                .choices
                .single()
                .delta
                ?.content,
        ).isEqualTo("crlf")
    }

    @Test
    fun `comment lines and keep-alives are ignored`() = runBlocking<Unit> {
        val body = buildString {
            append(": ping\n\n")
            append(":\n")
            append("""data: {"id":"1","choices":[{"index":0,"delta":{"content":"x"}}]}""")
            append("\n\n")
            append(": another comment\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(wholeResponse(body, SSE_MEDIA_TYPE))

        val events = client
            .streamBody(server, path = "/v1/chat/completions")
            .asSseFlow<ChatCompletionChunk>()
            .toList()

        assertThat(events).hasSize(1)
        assertThat(
            events
                .single()
                .choices
                .single()
                .delta
                ?.content,
        ).isEqualTo("x")
    }

    @Test
    fun `a payload spread over several data lines is one event`() = runBlocking<Unit> {
        // The spec joins consecutive data lines with newlines. Emitting each
        // one separately would hand the JSON parser two halves of an object.
        val body = buildString {
            append("event: message\n")
            append("id: 7\n")
            append("""data: {"id":"1","model":"qwen3",""")
            append("\n")
            append("""data: "choices":[{"index":0,"delta":{"content":"multi"}}]}""")
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(wholeResponse(body, SSE_MEDIA_TYPE))

        val events = client
            .streamBody(server, path = "/v1/chat/completions")
            .asSseFlow<ChatCompletionChunk>()
            .toList()

        assertThat(
            events
                .single()
                .choices
                .single()
                .delta
                ?.content,
        ).isEqualTo("multi")
    }

    @Test
    fun `a final frame with no trailing blank line is still emitted`() = runBlocking<Unit> {
        val body = """data: {"id":"1","choices":[{"index":0,"delta":{"content":"last"}}]}"""

        val events = DripSource(body).asBody(SSE_MEDIA_TYPE).asSseFlow<ChatCompletionChunk>().toList()

        assertThat(
            events
                .single()
                .choices
                .single()
                .delta
                ?.content,
        ).isEqualTo("last")
    }

    @Test
    fun `an error payload inside a 200 SSE stream is a typed failure`() = runBlocking<Unit> {
        val body = buildString {
            append("""data: {"id":"1","choices":[{"index":0,"delta":{"content":"a"}}]}""")
            append("\n\n")
            append("""data: {"error":{"message":"context length exceeded","type":"invalid_request_error"}}""")
            append("\n\n")
        }
        server.enqueue(wholeResponse(body, SSE_MEDIA_TYPE))

        client.streamBody(server, path = "/v1/chat/completions").asSseFlow<ChatCompletionChunk>().test {
            assertThat(
                awaitItem()
                    .choices
                    .single()
                    .delta
                    ?.content,
            ).isEqualTo("a")

            val failure = awaitError()
            assertThat(failure).isInstanceOf(AppErrorException::class.java)
            val error = (failure as AppErrorException).error
            assertThat(error).isInstanceOf(AppError.Network.Http::class.java)
            assertThat(error.message).isEqualTo("context length exceeded")
        }
    }

    @Test
    fun `data with no space after the colon carries the same payload`() = runBlocking<Unit> {
        val body = """data:{"id":"1","choices":[{"index":0,"delta":{"content":"tight"}}]}""" + "\n\n"

        val events = DripSource(body).asBody(SSE_MEDIA_TYPE).asSseFlow<ChatCompletionChunk>().toList()

        assertThat(
            events
                .single()
                .choices
                .single()
                .delta
                ?.content,
        ).isEqualTo("tight")
    }
}
