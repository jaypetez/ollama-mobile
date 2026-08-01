package io.github.jaypetez.ollamamobile.llm

import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams

/**
 * One turn to generate, in the shape every backend accepts.
 *
 * This is not `:core-remote`'s `ChatTurn` and not a list of
 * [ChatMessage][io.github.jaypetez.ollamamobile.model.ChatMessage]: the first
 * is a wire type belonging to one transport, and the second carries database
 * identity, a status and attachment URIs that only the app process can
 * resolve. A request has to be constructible by `:server` from an inbound HTTP
 * body, where none of that exists.
 */
public data class InferenceRequest(
    /**
     * Which model to run.
     *
     * [ModelRef.origin] is a *hint* recording where the user picked it from,
     * not an instruction. A router honouring [RoutingPolicy] may send the
     * request to a different server that serves the same [ModelRef.name].
     */
    public val model: ModelRef,
    /**
     * The history, oldest first, already trimmed to fit the context window.
     *
     * Trimming is the caller's job because only the caller knows what it is
     * willing to lose — a summary, the oldest turns, or the attachments.
     */
    public val messages: List<InferenceMessage>,
    public val sampling: SamplingParams = SamplingParams.Default,
    /**
     * Prepended as a [Role.SYSTEM] turn by the backend.
     *
     * Null and `""` are different requests: null sends no system message at
     * all, an empty string sends an empty one, and some templates render those
     * differently.
     */
    public val systemPrompt: String? = null,
    public val tools: List<ToolSpec> = emptyList(),
    /**
     * Whether the caller wants [InferenceEvent.Reasoning] at all.
     *
     * False does not merely hide the `<think>` block — it asks the backend not
     * to produce one, which on a reasoning model is the difference between a
     * two-second answer and a twenty-second one. A backend that cannot express
     * the request ignores this and the consumer simply receives no
     * [InferenceEvent.Reasoning] events it wants to render.
     */
    public val wantReasoning: Boolean = false,
    /**
     * The conversation this turn belongs to, when there is one.
     *
     * A gateway that persists transcripts writes the assistant turn here; one
     * that does not — `:server`, which has no conversation store and must not
     * grow one — leaves it null and the events are the only output.
     */
    public val conversationId: ConversationId? = null,
) {
    /** The tag that goes on the wire. See [model]. */
    public val modelName: String
        get() = model.name

    /** Convenience for a consumer wiring up a [StopSequenceFilter]. */
    public val stopHoldBackChars: Int
        get() = sampling.stopHoldBackChars
}

/**
 * One message in the history.
 *
 * Attachments are already resolved: [imagesBase64] holds bytes, not URIs,
 * because nothing below this type can read a `content://` URI.
 */
public data class InferenceMessage(
    public val role: Role,
    public val content: String,
    /** Base64 with **no** `data:image/png;base64,` prefix; servers do not strip one. */
    public val imagesBase64: List<String> = emptyList(),
    /** Present on an assistant turn that called tools; replayed as context. */
    public val toolCalls: List<ToolInvocation> = emptyList(),
    /** The tool whose result this message carries. Only meaningful for [Role.TOOL]. */
    public val toolName: String? = null,
    /**
     * The id of the call this message answers. Only meaningful for [Role.TOOL].
     *
     * OpenAI-compatible servers pair a result with its call by this id and
     * cannot pair it by name, so it has to survive from [ToolInvocation.id] on
     * the assistant turn to here. Null against a backend that issues no ids.
     */
    public val toolCallId: String? = null,
) {
    public companion object {
        public fun system(content: String): InferenceMessage = InferenceMessage(Role.SYSTEM, content)

        public fun user(content: String, imagesBase64: List<String> = emptyList()): InferenceMessage =
            InferenceMessage(Role.USER, content, imagesBase64 = imagesBase64)

        public fun assistant(
            content: String,
            toolCalls: List<ToolInvocation> = emptyList(),
        ): InferenceMessage = InferenceMessage(Role.ASSISTANT, content, toolCalls = toolCalls)

        public fun toolResult(name: String, content: String, callId: String? = null): InferenceMessage =
            InferenceMessage(Role.TOOL, content, toolName = name, toolCallId = callId)

        /**
         * Projects a stored turn onto the wire shape.
         *
         * [ChatMessage.reasoning] is dropped on purpose. Replaying a model's
         * own chain-of-thought as context makes it condition on it, which
         * compounds an early mistake across every following turn — and it
         * costs the tokens twice. [ChatMessage.attachments] are dropped too,
         * because this function cannot read a URI; a caller that needs images
         * resolves them and passes [imagesBase64].
         */
        public fun of(message: ChatMessage, imagesBase64: List<String> = emptyList()): InferenceMessage =
            InferenceMessage(
                role = message.role,
                content = message.content,
                imagesBase64 = imagesBase64,
            )
    }
}

/**
 * A tool the model may call.
 *
 * [parametersSchemaJson] is a JSON Schema document held as **text**, not as a
 * parsed object. This module has no serialization runtime on its classpath and
 * is not getting one: it is the contract `:server` depends on, and every
 * dependency added here is a dependency a host of the server inherits. The
 * transport parses the string once, at the boundary where it is about to be
 * written to the wire anyway.
 */
public data class ToolSpec(
    public val name: String,
    public val description: String,
    public val parametersSchemaJson: String,
)

/**
 * A tool call the model asked for.
 *
 * [argumentsJson] is text for the same reason as [ToolSpec.parametersSchemaJson],
 * and because the two protocol surfaces disagree about the shape anyway — one
 * sends an object, the other a string containing JSON. Text is the lowest
 * common denominator that loses nothing.
 */
public data class ToolInvocation(
    /** Present when the backend issues correlation ids; null when it matches on name. */
    public val id: String? = null,
    public val name: String,
    public val argumentsJson: String,
)
