package io.github.jaypetez.ollamamobile.server

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionResponse
import io.github.jaypetez.ollamamobile.remote.dto.ChatResponse
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiEmbeddingsResponse
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The non-streaming paths and the browser path.
 *
 * A client that works over a stream and fails on `stream: false` is a client
 * that fails on the first tool-calling turn, and one that works from curl and
 * fails from a browser is one that fails for every Open WebUI user.
 */
class ServerCompatibilityTest {
    @Test
    fun `a non-streaming chat is one JSON object carrying the whole answer`() = runTest {
        withServer { http ->
            val response = http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3:1.7b","messages":[],"stream":false}""")
            }

            assertThat(response.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
            val chat = RemoteJson.decodeFromString(ChatResponse.serializer(), response.bodyAsText())
            assertThat(chat.message?.content).isEqualTo("Hello world")
            assertThat(chat.message?.role).isEqualTo("assistant")
            assertThat(chat.done).isTrue()
        }
    }

    @Test
    fun `a non-streaming v1 completion carries usage when the backend measured it`() = runTest {
        val gateway = FakeGateway(
            events = listOf(
                InferenceEvent.Token("hi"),
                InferenceEvent.Stats(GenerationStats(promptTokens = 4, completionTokens = 1)),
                InferenceEvent.Completed(FinishReason.STOP),
            ),
        )
        withServer(environment(gateway = gateway)) { http ->
            val body = http
                .post("/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","messages":[{"role":"user","content":"hi"}]}""")
                }.bodyAsText()

            val completion = RemoteJson.decodeFromString(ChatCompletionResponse.serializer(), body)
            assertThat(completion.objectType).isEqualTo("chat.completion")
            assertThat(completion.id).startsWith("chatcmpl-")
            assertThat(
                completion.choices
                    .single()
                    .message
                    ?.content,
            ).isEqualTo("hi")
            assertThat(completion.usage?.totalTokens).isEqualTo(5)
        }
    }

    @Test
    fun `usage is absent rather than zeroed when nothing was measured`() = runTest {
        withServer { http ->
            val body = http
                .post("/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","messages":[]}""")
                }.bodyAsText()

            // A caller billing on total_tokens has to be able to tell "nothing
            // reported" from "no tokens".
            assertThat(body).doesNotContain("\"usage\"")
        }
    }

    @Test
    fun `v1 embeddings answers the list-of-objects shape the SDK expects`() = runTest {
        withServer { http ->
            val body = http
                .post("/v1/embeddings") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","input":["a","b"]}""")
                }.bodyAsText()

            val response = RemoteJson.decodeFromString(OpenAiEmbeddingsResponse.serializer(), body)
            assertThat(response.objectType).isEqualTo("list")
            assertThat(response.data.map { it.index }).containsExactly(0, 1).inOrder()
            assertThat(response.data.first().objectType).isEqualTo("embedding")
            // Required by the SDK's model, so it must be present even though
            // this server counts no embedding tokens.
            assertThat(response.usage?.totalTokens).isEqualTo(0)
        }
    }

    @Test
    fun `a browser preflight is answered so Open WebUI can reach the API`() = runTest {
        withServer(environment(config = ServerConfig(bindPolicy = BindPolicy.LAN, bearerToken = "t"))) { http ->
            val response = http.options("/api/chat") {
                header(HttpHeaders.Origin, "http://192.168.1.9:3000")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type")
            }

            // A preflight carries no Authorization by definition; 401-ing it
            // would break every authenticated browser client before its first
            // real request.
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isNotNull()
        }
    }

    @Test
    fun `a leading system turn becomes the system prompt, not a message`() = runTest {
        val gateway = FakeGateway()
        withServer(environment(gateway = gateway)) { http ->
            http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"model":"qwen3:1.7b","stream":false,"messages":[
                      {"role":"system","content":"be terse"},
                      {"role":"user","content":"hi"}
                    ]}
                    """.trimIndent(),
                )
            }

            val request = requireNotNull(gateway.lastRequest)
            assertThat(request.systemPrompt).isEqualTo("be terse")
            assertThat(request.messages.map { it.role }).containsExactly(Role.USER)
            // The server keeps no transcript, so nothing lands in chat history.
            assertThat(request.conversationId).isNull()
        }
    }

    @Test
    fun `sampling options survive the trip and unset ones stay unset`() = runTest {
        val gateway = FakeGateway()
        withServer(environment(gateway = gateway)) { http ->
            http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"qwen3:1.7b","stream":false,"messages":[],""" +
                        """"options":{"temperature":0.2,"stop":["END"]}}""",
                )
            }

            val sampling = requireNotNull(gateway.lastRequest).sampling
            assertThat(sampling.temperature).isEqualTo(0.2)
            assertThat(sampling.stop).containsExactly("END")
            // Absent stays absent: a zeroed top_p is a different request.
            assertThat(sampling.topP).isNull()
            assertThat(sampling.numPredict).isNull()
        }
    }

    @Test
    fun `the request counter advances for served requests`() = runTest {
        val env = environment()
        withServer(env) { http ->
            http.get("/")
            http.get("/api/tags")

            assertThat(env.requestCount.value).isEqualTo(2L)
        }
    }
}
