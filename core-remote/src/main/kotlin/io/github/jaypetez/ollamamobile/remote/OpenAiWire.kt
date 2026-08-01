package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.remote.dto.ChatCompletionRequest
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiChatMessage
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiFunctionCall
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiFunctionDefinition
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiTool
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiToolCall
import io.github.jaypetez.ollamamobile.remote.dto.encodeToolArguments

/**
 * Domain turn -> `/v1` request, without going through the native DTO.
 *
 * The obvious implementation is `toWire().toOpenAi()`, reusing the native
 * mapping that already exists. It is also wrong, and silently so: the native
 * wire format has no field for a tool-call id, because the native API pairs a
 * tool result with its call by function *name*. Converting through it drops
 * [RemoteToolCall.id] and leaves no way to populate `tool_call_id` on the
 * answering turn.
 *
 * A server then receives an assistant turn whose `tool_calls` have no `id` and
 * a tool turn with no `tool_call_id`. Both are accepted. The model simply
 * cannot tell which result belongs to which call, so a single-tool loop appears
 * to work and a parallel one returns confidently mismatched answers — the worst
 * possible failure shape.
 *
 * So `/v1` gets its own mapping straight from the domain type. The two
 * protocols disagree about identity, and a shared intermediate can only
 * represent the intersection.
 */
internal fun ChatTurn.toOpenAiWire(stream: Boolean): ChatCompletionRequest = ChatCompletionRequest(
    model = model,
    messages = messages.map { it.toOpenAiWire() },
    temperature = sampling.temperature,
    topP = sampling.topP,
    maxTokens = sampling.numPredict,
    stop = sampling.stop.takeIf { it.isNotEmpty() },
    seed = sampling.seed,
    tools = tools.takeIf { it.isNotEmpty() }?.map { tool ->
        OpenAiTool(
            function = OpenAiFunctionDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parametersSchema,
            ),
        )
    },
    stream = stream,
)

/**
 * Domain message -> `/v1` message.
 *
 * `topK`, `minP`, `repeatPenalty`, `repeatLastN` and `numCtx` have no `/v1`
 * counterpart and are dropped rather than approximated, for the reason given on
 * [io.github.jaypetez.ollamamobile.remote.dto.toOpenAi]: `frequency_penalty` is
 * not `repeat_penalty`, and quietly substituting one changes the sampling the
 * user asked for.
 */
internal fun RemoteMessage.toOpenAiWire(): OpenAiChatMessage = OpenAiChatMessage(
    role = role.wireName,
    // An assistant turn that only called tools carries no content, and `/v1`
    // spells that null rather than "".
    content = content.takeIf { it.isNotEmpty() },
    name = toolName,
    toolCallId = toolCallId,
    toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
        OpenAiToolCall(
            id = call.id,
            function = OpenAiFunctionCall(
                name = call.name,
                arguments = encodeToolArguments(call.arguments),
            ),
        )
    },
)
