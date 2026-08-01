package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.ToolInvocation
import io.github.jaypetez.ollamamobile.llm.ToolSpec
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.remote.dto.OllamaMessage
import io.github.jaypetez.ollamamobile.remote.dto.OllamaOptions
import io.github.jaypetez.ollamamobile.remote.dto.OllamaTool
import io.github.jaypetez.ollamamobile.remote.dto.OllamaToolCall
import io.github.jaypetez.ollamamobile.remote.dto.OllamaToolCallFunction
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.github.jaypetez.ollamamobile.remote.dto.decodeToolArguments
import kotlinx.serialization.json.JsonObject

/*
 * The translation layer between an inbound HTTP body and InferenceRequest.
 *
 * Kept away from the route table on purpose: routes should read as a list of
 * endpoints, and every line of mapping mixed in there is a line where a future
 * endpoint gets a subtly different conversion.
 */

/** Wire options -> the domain's sampling knobs. Absent stays absent. */
fun OllamaOptions?.toSamplingParams(): SamplingParams {
    if (this == null) return SamplingParams.Default
    return SamplingParams(
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        seed = seed,
        numPredict = numPredict,
        numCtx = numCtx,
        stop = stop.orEmpty(),
    )
}

/**
 * One inbound chat turn -> one [InferenceMessage].
 *
 * `thinking` is dropped rather than folded into content: replaying a model's
 * own scratchpad as context makes it condition on it, and the domain type has
 * nowhere honest to put it.
 */
fun OllamaMessage.toInferenceMessage(): InferenceMessage = InferenceMessage(
    role = Role.fromWire(role, fallback = Role.USER),
    content = content,
    imagesBase64 = images.orEmpty(),
    toolCalls = toolCalls.orEmpty().map { it.toInvocation() },
    toolName = toolName,
)

fun OllamaToolCall.toInvocation(): ToolInvocation = ToolInvocation(
    name = function.name,
    argumentsJson = RemoteJson.encodeToString(JsonObject.serializer(), function.arguments),
)

fun ToolInvocation.toOllamaToolCall(index: Int): OllamaToolCall = OllamaToolCall(
    function = OllamaToolCallFunction(
        name = name,
        index = index,
        arguments = decodeToolArguments(argumentsJson),
    ),
)

fun OllamaTool.toToolSpec(): ToolSpec = ToolSpec(
    name = function.name,
    description = function.description.orEmpty(),
    parametersSchemaJson = function.parameters
        ?.let { RemoteJson.encodeToString(JsonObject.serializer(), it) }
        .orEmpty(),
)

/**
 * Assembles the request the gateway runs.
 *
 * [InferenceRequest.conversationId] is deliberately left null: this server has
 * no transcript store and must not grow one, so a request that arrives over
 * HTTP leaves no trace in the user's chat history.
 */
@Suppress("LongParameterList")
fun buildInferenceRequest(
    model: ModelRef,
    messages: List<OllamaMessage>,
    options: OllamaOptions?,
    tools: List<OllamaTool>?,
    think: Boolean?,
): InferenceRequest {
    val turns = messages.map { it.toInferenceMessage() }
    // A leading system turn becomes systemPrompt rather than a message, because
    // some backends template the two differently and the leading position is
    // the only one where they are equivalent.
    val leadingSystem = turns.firstOrNull()?.takeIf { it.role == Role.SYSTEM }
    return InferenceRequest(
        model = model,
        messages = if (leadingSystem == null) turns else turns.drop(1),
        sampling = options.toSamplingParams(),
        systemPrompt = leadingSystem?.content,
        tools = tools.orEmpty().map { it.toToolSpec() },
        wantReasoning = think == true,
        conversationId = null,
    )
}

/**
 * Ollama's `done_reason` vocabulary.
 *
 * `"stop"` and `"length"` are the two Ollama actually emits; the rest are this
 * server being honest about states Ollama has no spelling for, which is better
 * than reporting `"stop"` for a cancelled generation and having the client
 * persist a truncated answer as complete.
 */
fun FinishReason.toOllamaDoneReason(): String = when (this) {
    FinishReason.STOP -> "stop"
    FinishReason.LENGTH -> "length"
    FinishReason.TOOL_CALLS -> "stop"
    FinishReason.CONTENT_FILTER -> "stop"
    FinishReason.CANCELLED -> "cancelled"
    FinishReason.UNKNOWN -> "stop"
}

/** The OpenAI `finish_reason` vocabulary, which is a different set of strings. */
fun FinishReason.toOpenAiFinishReason(): String = when (this) {
    FinishReason.STOP -> "stop"

    FinishReason.LENGTH -> "length"

    FinishReason.TOOL_CALLS -> "tool_calls"

    FinishReason.CONTENT_FILTER -> "content_filter"

    // The SDKs' enums have no "cancelled"; "stop" is the only value they parse
    // without raising, and the truncation is already visible in the text.
    FinishReason.CANCELLED -> "stop"

    FinishReason.UNKNOWN -> "stop"
}
