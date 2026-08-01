package io.github.jaypetez.ollamamobile.remote

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.github.jaypetez.ollamamobile.remote.dto.toOpenAi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * Tool-call identity must survive the trip to `/v1`.
 *
 * The failure this guards against is not a crash. A request whose `tool_calls`
 * have no `id`, answered by a turn with no `tool_call_id`, is accepted by the
 * server and produces a plausible answer — one that pairs the wrong result with
 * the wrong call as soon as a model issues two calls at once. Nothing downstream
 * can detect it.
 */
class OpenAiToolLoopTest {
    private val callId = "call_9RhVtqPzQm"

    private val assistantTurn = RemoteMessage(
        role = Role.ASSISTANT,
        content = "",
        toolCalls = listOf(
            RemoteToolCall(
                id = callId,
                name = "get_weather",
                arguments = JsonObject(mapOf("city" to JsonPrimitive("Oslo"))),
            ),
        ),
    )

    private val toolResultTurn = RemoteMessage(
        role = Role.TOOL,
        content = """{"tempC":7}""",
        toolName = "get_weather",
        toolCallId = callId,
    )

    private fun turn() = ChatTurn(
        model = "qwen3:4b",
        messages = listOf(
            RemoteMessage(Role.USER, "weather in Oslo?"),
            assistantTurn,
            toolResultTurn,
        ),
    )

    @Test
    fun `assistant tool call keeps its id on the v1 wire`() {
        val wire = turn().toOpenAiWire(stream = true)

        val assistant = wire.messages.single { it.role == Role.ASSISTANT.wireName }
        assertThat(assistant.toolCalls).hasSize(1)
        assertThat(assistant.toolCalls!!.single().id).isEqualTo(callId)
    }

    @Test
    fun `tool result echoes tool_call_id`() {
        val wire = turn().toOpenAiWire(stream = true)

        val tool = wire.messages.single { it.role == Role.TOOL.wireName }
        assertThat(tool.toolCallId).isEqualTo(callId)
        assertThat(tool.name).isEqualTo("get_weather")
    }

    @Test
    fun `serialised request carries both id fields`() {
        val json = RemoteJson.encodeToString(
            io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionRequest
                .serializer(),
            turn().toOpenAiWire(stream = true),
        )

        // Asserting on the encoded form as well as the object graph, because a
        // @SerialName regression would leave the object correct and the wire
        // wrong — which is exactly the bug this file exists for.
        assertThat(json).contains(""""id":"$callId"""")
        assertThat(json).contains(""""tool_call_id":"$callId"""")
    }

    @Test
    fun `arguments are encoded as a JSON string not an object`() {
        // The one shape difference between the two protocols: `/api/chat` sends
        // `arguments` as an object, `/v1` as a string containing JSON.
        val wire = turn().toOpenAiWire(stream = true)
        val args = wire.messages
            .single { it.role == Role.ASSISTANT.wireName }
            .toolCalls!!
            .single()
            .function.arguments

        assertThat(args).isEqualTo("""{"city":"Oslo"}""")
    }

    @Test
    fun `routing through the native DTO would lose the id`() {
        // Pins the reason toOpenAiWire exists. If someone "simplifies" the
        // client back to toWire().toOpenAi(), the assertions above start
        // failing and this one explains why: the native shape has nowhere to
        // put an id, so the loss happens here, not in the encoder.
        val viaNative = turn().toWire().messages.map { it.toOpenAi() }

        val assistant = viaNative.single { it.role == Role.ASSISTANT.wireName }
        assertThat(assistant.toolCalls!!.single().id).isNull()
        assertThat(viaNative.single { it.role == Role.TOOL.wireName }.toolCallId).isNull()
    }

    @Test
    fun `native wire is unaffected and still pairs by name`() {
        // The native API has no ids and must not grow fabricated ones.
        val native = turn().toWire()

        val tool = native.messages.single { it.role == Role.TOOL.wireName }
        assertThat(tool.toolName).isEqualTo("get_weather")
    }
}
