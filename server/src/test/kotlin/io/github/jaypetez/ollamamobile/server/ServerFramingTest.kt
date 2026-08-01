package io.github.jaypetez.ollamamobile.server

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.ToolInvocation
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionChunk
import io.github.jaypetez.ollamamobile.remote.dto.ChatResponse
import io.github.jaypetez.ollamamobile.remote.dto.GenerateResponse
import io.github.jaypetez.ollamamobile.remote.stream.asNdjsonFlow
import io.github.jaypetez.ollamamobile.remote.stream.asSseFlow
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.junit.Test

/**
 * Framing, asserted on the bytes.
 *
 * Two levels, and both are needed. The literal-string assertions pin the
 * delimiters — a missing `\n` or a stray blank line is invisible to a
 * structural assertion and fatal to a line-oriented client. The replay
 * assertions then feed those same bytes to `:core-remote`'s own parsers, which
 * is what makes "the client and the server cannot drift" a checked fact: if
 * either side changes its mind about the shape, this fails.
 */
class ServerFramingTest {
    private val streamingChatBody =
        """{"model":"qwen3:1.7b","messages":[{"role":"user","content":"hi"}],"stream":true}"""

    @Test
    fun `chat NDJSON is one JSON object per line, newline-terminated, no blank lines`() = runTest {
        withServer { http ->
            val body = http
                .post("/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(streamingChatBody)
                }.bodyAsText()

            assertThat(body).isEqualTo(
                """
                {"model":"qwen3:1.7b","created_at":"2023-11-14T22:13:20Z","message":{"role":"assistant","content":"Hello"},"done":false}
                {"model":"qwen3:1.7b","created_at":"2023-11-14T22:13:20Z","message":{"role":"assistant","content":" world"},"done":false}
                {"model":"qwen3:1.7b","created_at":"2023-11-14T22:13:20Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                """.trimIndent() + "\n",
            )
        }
    }

    @Test
    fun `chat NDJSON is declared as application x-ndjson`() = runTest {
        withServer { http ->
            val response = http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(streamingChatBody)
            }

            assertThat(response.contentType()?.withoutParameters()).isEqualTo(NdjsonContentType)
        }
    }

    @Test
    fun `the client's own NDJSON parser reads back exactly what the server wrote`() = runTest {
        withServer { http ->
            val body = http
                .post("/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(streamingChatBody)
                }.bodyAsText()

            val chunks = body.asNdjson().asNdjsonFlow(ChatResponse.serializer()).toList()

            assertThat(chunks.map { it.message?.content }).containsExactly("Hello", " world", "").inOrder()
            assertThat(chunks.last().done).isTrue()
            assertThat(chunks.last().doneReason).isEqualTo("stop")
        }
    }

    @Test
    fun `generate streams the response field, not a message object`() = runTest {
        withServer { http ->
            val body = http
                .post("/api/generate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","prompt":"hi","stream":true}""")
                }.bodyAsText()

            val chunks = body.asNdjson().asNdjsonFlow(GenerateResponse.serializer()).toList()
            assertThat(chunks.map { it.response }).containsExactly("Hello", " world", "").inOrder()
            assertThat(body).doesNotContain("\"message\"")
        }
    }

    @Test
    fun `a failure after the headers is one more NDJSON line carrying error`() = runTest {
        val gateway = FakeGateway(
            events = listOf(
                InferenceEvent.Started(InferenceTarget.Local(LOCAL_MODEL.id)),
                InferenceEvent.Token("partial"),
                InferenceEvent.Failed(AppError.Network.Timeout()),
            ),
        )
        withServer(environment(gateway = gateway)) { http ->
            val body = http
                .post("/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(streamingChatBody)
                }.bodyAsText()

            // The partial answer stands, and the error is a line rather than a
            // truncated stream — which is the only way to say so after a 200.
            assertThat(body.trim().lines()).hasSize(2)
            assertThat(body.trim().lines().last()).isEqualTo("""{"error":"The server did not respond in time."}""")
        }
    }

    @Test
    fun `stats reach the terminal chunk as nanosecond counters`() = runTest {
        val gateway = FakeGateway(
            events = listOf(
                InferenceEvent.Token("x"),
                InferenceEvent.Stats(
                    GenerationStats(promptTokens = 7, completionTokens = 3, evalNanos = 2_000_000_000L),
                ),
                InferenceEvent.Completed(FinishReason.LENGTH),
            ),
        )
        withServer(environment(gateway = gateway)) { http ->
            val body = http
                .post("/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(streamingChatBody)
                }.bodyAsText()

            val last = body
                .asNdjson()
                .asNdjsonFlow(ChatResponse.serializer())
                .toList()
                .last()
            assertThat(last.evalDurationNanos).isEqualTo(2_000_000_000L)
            assertThat(last.promptEvalCount).isEqualTo(7)
            // A truncated answer must not be reported as a finished one.
            assertThat(last.doneReason).isEqualTo("length")
            // Nothing was measured for load, so nothing is reported.
            assertThat(last.loadDurationNanos).isNull()
        }
    }

    // -----------------------------------------------------------------------
    // SSE
    // -----------------------------------------------------------------------

    @Test
    fun `v1 chat frames are literal data lines separated by a blank line`() = runTest {
        withServer { http ->
            val body = http
                .post("/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","messages":[{"role":"user","content":"hi"}],"stream":true}""")
                }.bodyAsText()

            val frames = body.split("\n\n").filter { it.isNotEmpty() }
            assertThat(frames).hasSize(5)
            assertThat(frames.all { it.startsWith("data: ") }).isTrue()
            assertThat(body).endsWith(SSE_DONE_FRAME)
        }
    }

    @Test
    fun `the v1 stream always terminates with data DONE`() = runTest {
        withServer { http ->
            val body = http
                .post("/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","messages":[],"stream":true}""")
                }.bodyAsText()

            // The OpenAI SDKs treat a stream without this as truncated and
            // raise, so its absence turns every success into a client error.
            assertThat(body).endsWith("data: [DONE]\n\n")
        }
    }

    @Test
    fun `the first v1 delta carries the role and the last carries finish_reason`() = runTest {
        withServer { http ->
            val body = http
                .post("/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","messages":[],"stream":true}""")
                }.bodyAsText()

            val chunks = body.asSse().asSseFlow(ChatCompletionChunk.serializer()).toList()

            assertThat(
                chunks
                    .first()
                    .choices
                    .single()
                    .delta
                    ?.role,
            ).isEqualTo("assistant")
            assertThat(chunks.map { it.objectType }.toSet()).containsExactly("chat.completion.chunk")
            assertThat(
                chunks
                    .last()
                    .choices
                    .single()
                    .finishReason,
            ).isEqualTo("stop")
        }
    }

    @Test
    fun `tool calls end a v1 stream with finish_reason tool_calls`() = runTest {
        val gateway = FakeGateway(
            events = listOf(
                InferenceEvent.ToolCall(ToolInvocation(name = "get_weather", argumentsJson = """{"city":"Oslo"}""")),
                InferenceEvent.Completed(FinishReason.TOOL_CALLS),
            ),
        )
        withServer(environment(gateway = gateway)) { http ->
            val body = http
                .post("/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","messages":[],"stream":true}""")
                }.bodyAsText()

            val chunks = body.asSse().asSseFlow(ChatCompletionChunk.serializer()).toList()
            val toolFrame = chunks.first {
                it.choices
                    .single()
                    .delta
                    ?.toolCalls != null
            }
            val call = toolFrame.choices
                .single()
                .delta
                ?.toolCalls
                ?.single()
            // `/v1` carries arguments as a STRING containing JSON, not an object.
            assertThat(call?.function?.arguments).isEqualTo("""{"city":"Oslo"}""")
            assertThat(
                chunks
                    .last()
                    .choices
                    .single()
                    .finishReason,
            ).isEqualTo("tool_calls")
        }
    }

    @Test
    fun `v1 completions streams text choices and terminates`() = runTest {
        withServer { http ->
            val body = http
                .post("/v1/completions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","prompt":"hi","stream":true}""")
                }.bodyAsText()

            assertThat(body).contains("\"text\":\"Hello\"")
            assertThat(body).contains("\"object\":\"text_completion\"")
            assertThat(body).endsWith(SSE_DONE_FRAME)
        }
    }
}

/** Wraps a captured body so `:core-remote`'s parsers can be pointed at it. */
private fun String.asNdjson(): ResponseBody = asBody("application/x-ndjson")

private fun String.asSse(): ResponseBody = asBody("text/event-stream")

private fun String.asBody(mediaType: String): ResponseBody =
    Buffer().writeUtf8(this).asResponseBody(mediaType.toMediaType(), toByteArray().size.toLong())
