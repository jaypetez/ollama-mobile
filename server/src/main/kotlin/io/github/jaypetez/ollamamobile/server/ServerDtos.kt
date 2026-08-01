package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.remote.dto.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The handful of wire types :core-remote does not carry, because the client
 * never sends them and only a server has to answer them.
 *
 * /v1/completions is the legacy OpenAI text-completion surface. The OpenAI
 * Python SDK still exposes it as `client.completions.create(...)`, so a server
 * that only implements /v1/chat/completions fails half of "point the SDK at it
 * and see if it works".
 */

@Serializable
data class CompletionRequest(
    val model: String,
    /** A string or an array of strings; only the string form is honoured. */
    val prompt: JsonElement? = null,
    val suffix: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val seed: Long? = null,
    /** A string or an array of strings, per the OpenAI schema. */
    val stop: JsonElement? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: JsonElement? = null,
    val user: String? = null,
)

@Serializable
data class CompletionResponse(
    val id: String = "",
    @SerialName("object") val objectType: String = "text_completion",
    val created: Long = 0L,
    val model: String = "",
    val choices: List<CompletionChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class CompletionChoice(
    val index: Int = 0,
    val text: String = "",
    val logprobs: JsonElement? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * The `/api/pull` request as *this* server accepts it.
 *
 * Distinct from `:core-remote`'s `PullRequest` only in that `name` is honoured
 * as an alias for `model`: clients older than Ollama 0.3 send `name`, and the
 * modern DTO would reject the body outright for a missing required field.
 */
@Serializable
data class ServerPullRequest(
    val model: String? = null,
    val name: String? = null,
    val insecure: Boolean? = null,
    val stream: Boolean = true,
) {
    val resolvedModel: String?
        get() = model?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}

/** As [ServerPullRequest], for `/api/delete` and `/api/show`. */
@Serializable
data class ServerModelRequest(
    val model: String? = null,
    val name: String? = null,
    val verbose: Boolean? = null,
) {
    val resolvedModel: String?
        get() = model?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}

/** `/api/copy`. Older clients spell the fields `source`/`destination`. */
@Serializable
data class ServerCopyRequest(
    val source: String? = null,
    val destination: String? = null,
)

/** `{"status": "success"}` — what Ollama answers a completed `/api/pull` with. */
@Serializable
data class StatusResponse(
    val status: String,
)
