package io.github.jaypetez.ollamamobile.remote

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The `/v1` surface, and specifically the three places it disagrees with the
 * native API: SSE framing instead of NDJSON, `[DONE]` instead of `done: true`,
 * and tool arguments as a fragmented string instead of an object.
 */
@RunWith(JUnit4::class)
class OpenAiCompatClientImplTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCompatClientImpl

    private val sse = Headers.headersOf("Content-Type", "text/event-stream")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiCompatClientImpl(testHttp(history = RequestHistory(MutableClock())))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun turn() = ChatTurn(
        model = "qwen3:4b",
        messages = listOf(RemoteMessage(role = Role.USER, content = "hi")),
    )

    @Test
    fun `an SSE stream produces the same events a native stream would`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(sse)
                .body(
                    """
                data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant"}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{"content":"Hel"}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{"content":"lo"}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":2}}

                data: [DONE]

                    """.trimIndent() + "\n",
                ).build(),
        )

        val events = client.chat(server.serverRef(), turn()).toList()

        assertThat(events.filterIsInstance<StreamEvent.Text>().map { it.delta }).containsExactly("Hel", "lo").inOrder()
        val completed = events.filterIsInstance<StreamEvent.Completed>().single()
        assertThat(completed.doneReason).isEqualTo(DoneReason.STOP)
        assertThat(completed.stats.promptTokens).isEqualTo(9)
        // /v1 reports no durations at all, so a rate would be an invention.
        assertThat(completed.stats.tokensPerSecond).isNull()
        assertThat(server.takeRequest().target).isEqualTo("/v1/chat/completions")
    }

    @Test
    fun `frames split across reads and keep-alive comments are handled`() = runBlocking {
        val body = buildString {
            append(": keep-alive\n\n")
            repeat(10) { index ->
                append("""data: {"id":"1","choices":[{"index":0,"delta":{"content":"t$index"}}]}""").append("\n\n")
            }
            append("""data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""").append("\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse
                .Builder()
                .headers(sse)
                .chunkedBody(body, 9)
                .build(),
        )

        val events = client.chat(server.serverRef(), turn()).toList()

        assertThat(events.filterIsInstance<StreamEvent.Text>()).hasSize(10)
        assertThat(events.last()).isInstanceOf(StreamEvent.Completed::class.java)
    }

    @Test
    fun `trap 6 - fragmented string arguments are reassembled into one object`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(sse)
                .body(
                    """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"Nairobi\"}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                    """.trimIndent() + "\n",
                ).build(),
        )

        val events = client.chat(server.serverRef(), turn()).toList()

        val call = events.filterIsInstance<StreamEvent.ToolCall>().single().call
        // Neither fragment is valid JSON on its own; parsing per chunk produces
        // a stream of exceptions that look like a broken server.
        assertThat(call.name).isEqualTo("get_weather")
        assertThat(call.id).isEqualTo("call_1")
        assertThat(call.arguments["city"].toString()).isEqualTo("\"Nairobi\"")
        assertThat(events.last()).isInstanceOf(StreamEvent.Completed::class.java)
    }

    @Test
    fun `a stream that stops without a finish reason is a truncation`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .headers(sse)
                .body(
                    """data: {"choices":[{"index":0,"delta":{"content":"half"}}]}""" + "\n\n",
                ).build(),
        )

        val events = client.chat(server.serverRef(), turn()).toList()

        assertThat(events.last()).isInstanceOf(StreamEvent.Failed::class.java)
    }

    @Test
    fun `models are listed in the OpenAI shape and mapped to the same domain type`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .body("""{"object":"list","data":[{"id":"qwen3:4b","object":"model"},{"id":"llama3.2:3b"}]}""")
                .build(),
        )

        val models = (client.listModels(server.serverRef()) as AppResult.Success).value

        assertThat(models.map { it.name }).containsExactly("qwen3:4b", "llama3.2:3b").inOrder()
        assertThat(server.takeRequest().target).isEqualTo("/v1/models")
    }

    @Test
    fun `embeddings come back in input order`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .body(
                    """{"data":[{"index":1,"embedding":[0.3,0.4]},{"index":0,"embedding":[0.1,0.2]}],"model":"e"}""",
                ).build(),
        )

        val result = (client.embed(server.serverRef(), "e", listOf("a", "b")) as AppResult.Success).value

        assertThat(result.embeddings).hasSize(2)
        assertThat(result.embeddings.first()).containsExactly(0.1f, 0.2f).inOrder()
    }
}
