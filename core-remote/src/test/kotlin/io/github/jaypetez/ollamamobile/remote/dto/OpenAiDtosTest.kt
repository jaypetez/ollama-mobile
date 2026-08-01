package io.github.jaypetez.ollamamobile.remote.dto

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Trap 6 lives here.
 *
 * `arguments` is a JSON object on the native API and a JSON string containing
 * an object on `/v1`. The round trip is asserted in both directions, against
 * literal wire bodies, because the failure mode is not an exception — it is a
 * tool being invoked with the string `"{\"city\":\"Oslo\"}"` as its only
 * argument, which the model then apologises for.
 */
@RunWith(JUnit4::class)
class OpenAiDtosTest {
    private val arguments: JsonObject = buildJsonObject {
        put("city", "Oslo")
        put("days", 3)
        put("metric", true)
    }

    @Test
    fun `tool call arguments round-trip from the object form to the string form and back`() {
        val native = OllamaToolCall(
            function = OllamaToolCallFunction(name = "get_forecast", arguments = arguments),
        )

        val openAi = native.toOpenAi()

        // On the wire it must be a *string*, not an object.
        assertThat(openAi.function.arguments).isInstanceOf(String::class.java)
        assertThat(openAi.function.arguments).isEqualTo("""{"city":"Oslo","days":3,"metric":true}""")

        val roundTripped = openAi.toNative()
        assertThat(roundTripped.function.name).isEqualTo("get_forecast")
        assertThat(roundTripped.function.arguments).isEqualTo(arguments)
    }

    @Test
    fun `the two dialects encode the same call differently and both decode to one shape`() {
        val nativeBody = """
            {"model":"m","done":true,"message":{"role":"assistant","content":"",
             "tool_calls":[{"function":{"name":"get_forecast","arguments":{"city":"Oslo"}}}]}}
        """.trimIndent()
        val openAiBody = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1780000000,"model":"m",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
             "tool_calls":[{"id":"call_1","type":"function",
             "function":{"name":"get_forecast","arguments":"{\"city\":\"Oslo\"}"}}]}}]}
        """.trimIndent()

        val fromNative = RemoteJson.decodeFromString(ChatResponse.serializer(), nativeBody)
        val fromOpenAi = RemoteJson.decodeFromString(ChatCompletionResponse.serializer(), openAiBody).toNative()

        val expected = JsonObject(mapOf("city" to JsonPrimitive("Oslo")))
        assertThat(
            fromNative.message
                ?.toolCalls
                ?.single()
                ?.function
                ?.arguments,
        ).isEqualTo(expected)
        assertThat(
            fromOpenAi.message
                ?.toolCalls
                ?.single()
                ?.function
                ?.arguments,
        ).isEqualTo(expected)
        assertThat(fromOpenAi.doneReason).isEqualTo("tool_calls")
    }

    @Test
    fun `a partial arguments fragment yields an empty object instead of failing the stream`() {
        // Streaming /v1 delivers `arguments` a few characters at a time, so an
        // individual delta is routinely not valid JSON on its own.
        assertThat(decodeToolArguments("""{"cit""")).isEqualTo(JsonObject(emptyMap()))
        assertThat(decodeToolArguments("")).isEqualTo(JsonObject(emptyMap()))
        assertThat(decodeToolArguments("   ")).isEqualTo(JsonObject(emptyMap()))
    }

    @Test
    fun `a streaming chunk becomes a native chunk and DONE is not part of it`() {
        val body = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1780000000,"model":"qwen3",
             "choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}
        """.trimIndent()

        val native = RemoteJson.decodeFromString(ChatCompletionChunk.serializer(), body).toNative()

        assertThat(native.message?.content).isEqualTo("Hel")
        assertThat(native.message?.role).isEqualTo("assistant")
        // No finish_reason yet, so this is not the terminal chunk.
        assertThat(native.done).isFalse()
        // Epoch seconds became the RFC 3339 spelling the rest of the app uses.
        assertThat(native.createdAt).isEqualTo("2026-05-28T20:26:40Z")
    }

    @Test
    fun `the terminal chunk carries finish_reason and an empty delta`() {
        val body = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"qwen3",
             "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":9,"completion_tokens":100,"total_tokens":109}}
        """.trimIndent()

        val chunk = RemoteJson.decodeFromString(ChatCompletionChunk.serializer(), body)
        val native = chunk.toNative()

        assertThat(native.done).isTrue()
        assertThat(native.doneReason).isEqualTo("stop")
        assertThat(native.promptEvalCount).isEqualTo(9)
        assertThat(native.evalCount).isEqualTo(100)

        // /v1 reports no durations at all, so a rate cannot be computed and
        // must not be invented.
        val stats = requireNotNull(chunk.usage).toGenerationStats()
        assertThat(stats.completionTokens).isEqualTo(100)
        assertThat(stats.evalNanos).isNull()
        assertThat(stats.tokensPerSecond).isNull()
    }

    @Test
    fun `a native request converts to a v1 request and back without inventing options`() {
        val request = ChatRequest(
            model = "qwen3:1.7b",
            messages = listOf(
                OllamaMessage(role = "system", content = "be brief"),
                OllamaMessage(role = "user", content = "hi"),
            ),
            tools = listOf(
                OllamaTool(
                    function = OllamaToolFunction(name = "get_forecast", description = "weather", parameters = null),
                ),
            ),
            options = OllamaOptions(temperature = 0.7, topP = 0.9, topK = 40, numPredict = 128, seed = 42L),
            stream = true,
        )

        val openAi = request.toOpenAi()

        assertThat(openAi.temperature).isEqualTo(0.7)
        assertThat(openAi.topP).isEqualTo(0.9)
        assertThat(openAi.maxTokens).isEqualTo(128)
        assertThat(
            openAi.tools
                ?.single()
                ?.function
                ?.name,
        ).isEqualTo("get_forecast")
        assertThat(openAi.stream).isTrue()

        val back = openAi.toNative()

        assertThat(back.model).isEqualTo("qwen3:1.7b")
        assertThat(back.messages.map { it.role }).containsExactly("system", "user").inOrder()
        // top_k has no /v1 counterpart. It is dropped, not approximated onto a
        // penalty that means something else.
        assertThat(back.options?.topK).isNull()
        assertThat(back.options?.temperature).isEqualTo(0.7)
        assertThat(back.options?.numPredict).isEqualTo(128)
    }

    @Test
    fun `an assistant turn with no text encodes content as null on v1 and empty natively`() {
        val toolOnly = OllamaMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(OllamaToolCall(OllamaToolCallFunction(name = "f", arguments = arguments))),
        )

        val encoded = RemoteJson.encodeToString(OpenAiChatMessage.serializer(), toolOnly.toOpenAi())

        // explicitNulls = false drops the key rather than sending "content":null.
        assertThat(encoded).doesNotContain("\"content\"")
        assertThat(toolOnly.toOpenAi().toNative().content).isEmpty()
    }

    @Test
    fun `the models and embeddings surfaces decode`() {
        val models = RemoteJson.decodeFromString(
            ModelsResponse.serializer(),
            """{"object":"list","data":[{"id":"qwen3:1.7b","object":"model","created":1780000000,
               "owned_by":"library"}]}""",
        )
        val embeddings = RemoteJson.decodeFromString(
            OpenAiEmbeddingsResponse.serializer(),
            """{"object":"list","model":"nomic","data":[{"object":"embedding","embedding":[0.1,0.2],"index":0}],
               "usage":{"prompt_tokens":4,"total_tokens":4}}""",
        )

        assertThat(models.data.single().id).isEqualTo("qwen3:1.7b")
        assertThat(embeddings.data.single().embedding).hasSize(2)

        val request = OpenAiEmbeddingsRequest(model = "nomic", input = EmbedInput.of(listOf("a", "b")))
        assertThat(RemoteJson.encodeToString(OpenAiEmbeddingsRequest.serializer(), request))
            .contains(""""input":["a","b"]""")
    }

    @Test
    fun `an unknown field on the v1 surface does not throw either`() {
        val body = """
            {"id":"chatcmpl-1","model":"m","service_tier":"scale","choices":[{"index":0,
             "delta":{"content":"x","refusal":null},"logprobs":null,"finish_reason":null}]}
        """.trimIndent()

        val chunk = RemoteJson.decodeFromString(ChatCompletionChunk.serializer(), body)

        assertThat(
            chunk.choices
                .single()
                .delta
                ?.content,
        ).isEqualTo("x")
    }
}
