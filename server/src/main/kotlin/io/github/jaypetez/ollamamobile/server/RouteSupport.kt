package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.remote.dto.GenerateRequest
import io.github.jaypetez.ollamamobile.remote.dto.OllamaMessage
import io.github.jaypetez.ollamamobile.remote.dto.PullProgress
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.serialization.json.JsonElement

/*
 * The four things every inference route does: resolve the model, admit the
 * request, honour keep_alive, and drain the event stream. Written once so a
 * new endpoint cannot silently skip the admission gate.
 */

/**
 * Resolves the requested tag or answers 404 and returns null.
 *
 * Ollama's own behaviour for an unknown tag, and clients depend on it: a 404
 * with `not found` makes the CLI offer to pull, while a 500 makes it retry.
 */
suspend fun ServerEnvironment.requireModel(call: ApplicationCall, requested: String): ModelRef? {
    val model = resolveModel(requested)
    if (model == null) {
        call.respondOllamaError(HttpStatusCode.NotFound, ServerErrors.modelNotFound(requested))
    }
    return model
}

/**
 * Runs [block] behind the admission gate, recording residency around it.
 *
 * The model is marked resident *before* the work so a concurrent `/api/ps`
 * reports what is actually running, and again afterwards so the `keep_alive`
 * countdown starts when the request ends rather than when it began — which is
 * what a client polling `expires_at` assumes.
 */
suspend fun ServerEnvironment.runGuarded(
    call: ApplicationCall,
    model: ModelRef,
    keepAliveElement: JsonElement?,
    block: suspend () -> Unit,
) {
    val keepAlive = KeepAlive.parse(keepAliveElement, config.defaultKeepAlive)
    val admitted = admission.withPermit {
        residency.touch(model, keepAlive)
        try {
            block()
        } finally {
            residency.touch(model, keepAlive)
        }
    }
    if (admitted == null) {
        call.respondOllamaError(HttpStatusCode.ServiceUnavailable, ServerErrors.MAX_QUEUE)
    }
}

/** Drains a generation into a [StreamState] without writing anything. */
internal suspend fun ServerEnvironment.collect(request: InferenceRequest): StreamState {
    val state = StreamState()
    gateway.chat(request).collect { event ->
        when (event) {
            is InferenceEvent.Token -> state.text.append(event.text)
            is InferenceEvent.Reasoning -> state.reasoning.append(event.text)
            is InferenceEvent.ToolCall -> state.toolCalls += event.call
            is InferenceEvent.Stats -> state.stats = event.stats
            is InferenceEvent.Completed -> state.finish = event.reason
            is InferenceEvent.Failed -> state.failure = event.error.message
            is InferenceEvent.Started -> Unit
        }
    }
    return state
}

/** Embeds every input, in order. */
suspend fun ServerEnvironment.embedAll(texts: List<String>): List<List<Float>> =
    texts.map { embeddings.embed(it) }

/** The last progress line, or null for a provider that emitted nothing. */
suspend fun Flow<PullProgress>.lastOrDefault(): PullProgress? = lastOrNull()

/**
 * Turns a `GenerateRequest` into the chat-shaped request the gateway takes.
 *
 * `/api/generate` is a completion endpoint with no roles, so the prompt becomes
 * a single user turn. `raw: true` is honoured by *not* passing a system prompt,
 * which is the closest this layer can get to "do not apply the template" — the
 * gateway owns templating and there is no flag to reach it with, so pretending
 * otherwise would be worse than being predictable.
 */
fun GenerateRequest.toInferenceRequest(model: ModelRef): InferenceRequest =
    buildInferenceRequest(
        model = model,
        messages = listOf(OllamaMessage(role = "user", content = prompt, images = images)),
        options = options,
        tools = null,
        think = think,
    ).copy(systemPrompt = system?.takeUnless { raw == true })
