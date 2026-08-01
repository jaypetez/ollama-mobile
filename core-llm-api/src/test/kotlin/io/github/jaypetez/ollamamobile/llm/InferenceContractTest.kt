package io.github.jaypetez.ollamamobile.llm

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.model.ServerId
import org.junit.jupiter.api.Test

class InferenceTargetTest {
    @Test
    fun `a remote target is not local and says so without a when`() {
        val target = InferenceTarget.Remote(ServerId("pi"), "qwen3:1.7b")

        assertThat(target.isLocal).isFalse()
        assertThat(target.isRemote).isTrue()
    }

    @Test
    fun `a local target is local`() {
        val target = InferenceTarget.Local(ModelId("file:/models/qwen3.gguf"))

        assertThat(target.isLocal).isTrue()
        assertThat(target.isRemote).isFalse()
    }

    @Test
    fun `the same model on two servers is two different targets`() {
        // Equality has to include the server, or a router that de-duplicates
        // its candidate list silently collapses a two-server LAN into one.
        val a = InferenceTarget.Remote(ServerId("pi"), "qwen3:1.7b")
        val b = InferenceTarget.Remote(ServerId("nuc"), "qwen3:1.7b")

        assertThat(a).isNotEqualTo(b)
    }
}

class InferenceRequestTest {
    private val model = ModelRef(
        id = ModelId("pi/qwen3:1.7b"),
        displayName = "Qwen3 1.7B",
        name = "qwen3:1.7b",
        origin = ModelOrigin.Remote(ServerId("pi")),
    )

    @Test
    fun `the wire name comes from the model tag, not from the display name`() {
        val request = InferenceRequest(model = model, messages = emptyList())

        assertThat(request.modelName).isEqualTo("qwen3:1.7b")
    }

    @Test
    fun `the hold-back is carried through from the sampling parameters`() {
        val request = InferenceRequest(
            model = model,
            messages = emptyList(),
            sampling = SamplingParams(stop = listOf("<|im_end|>")),
        )

        assertThat(request.stopHoldBackChars).isEqualTo(9)
    }

    @Test
    fun `defaults send no system prompt, no tools and no reasoning`() {
        val request = InferenceRequest(model = model, messages = emptyList())

        // Null and "" are different requests; the default must be "send none".
        assertThat(request.systemPrompt).isNull()
        assertThat(request.tools).isEmpty()
        assertThat(request.wantReasoning).isFalse()
        assertThat(request.conversationId).isNull()
    }
}

class InferenceMessageTest {
    @Test
    fun `role factories set the role they are named for`() {
        assertThat(InferenceMessage.system("s").role).isEqualTo(Role.SYSTEM)
        assertThat(InferenceMessage.user("u").role).isEqualTo(Role.USER)
        assertThat(InferenceMessage.assistant("a").role).isEqualTo(Role.ASSISTANT)
        assertThat(InferenceMessage.toolResult("clock", "12:00").role).isEqualTo(Role.TOOL)
    }

    @Test
    fun `a tool result carries the name and the correlation id`() {
        val message = InferenceMessage.toolResult("clock", "12:00", callId = "call_1")

        assertThat(message.toolName).isEqualTo("clock")
        assertThat(message.toolCallId).isEqualTo("call_1")
    }

    @Test
    fun `projecting a stored turn drops its reasoning`() {
        // Replaying a model's own chain-of-thought makes it condition on it,
        // which compounds an early mistake across every following turn.
        val stored = ChatMessage(
            id = MessageId("m1"),
            conversationId = ConversationId("c1"),
            role = Role.ASSISTANT,
            content = "42",
            createdAt = 0L,
            reasoning = "let me think about this at length",
        )

        val projected = InferenceMessage.of(stored)

        assertThat(projected.content).isEqualTo("42")
        assertThat(projected.role).isEqualTo(Role.ASSISTANT)
        assertThat(projected.imagesBase64).isEmpty()
    }
}
