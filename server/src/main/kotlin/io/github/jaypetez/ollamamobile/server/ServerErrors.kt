package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.remote.dto.OllamaErrorResponse
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/**
 * The error strings this server emits, quoted from Ollama where a client is
 * known to match on them.
 *
 * Real clients do string-match: the Python client surfaces `error` verbatim and
 * several UIs branch on "server busy". Paraphrasing an upstream message is
 * therefore a behaviour change, not a wording change.
 */
object ServerErrors {
    /**
     * Ollama's own overload message, byte for byte.
     *
     * The double space after `again.` is upstream's, not a typo here. It is
     * reproduced deliberately so a client comparing against the literal string
     * sees a match.
     */
    const val MAX_QUEUE: String = "server busy, please try again.  maximum pending requests exceeded"

    const val UNAUTHORIZED: String = "unauthorized"

    const val METHOD_NOT_ALLOWED: String = "method not allowed"

    /** Sent when the `Host` header is not a private address. See [HostGuard]. */
    const val FORBIDDEN_HOST: String = "forbidden: host header is not a private address"

    /** Sent when a request arrives carrying this server's own [VIA_TOKEN]. */
    const val PROXY_LOOP: String = "forbidden: request loop detected"

    /** Ollama's shape for an unknown tag; clients branch on the "not found" prefix. */
    fun modelNotFound(model: String): String = "model \"$model\" not found, try pulling it first"

    fun invalidRequest(detail: String): String = "invalid request: $detail"
}

/**
 * Marks this server on every response and every hop it can see.
 *
 * Two phones pointed at each other would otherwise proxy a request back and
 * forth until one of them runs out of sockets. A `Via` token is the cheap,
 * standard way to notice; [LoopGuard] rejects an inbound request that already
 * carries it.
 */
const val VIA_TOKEN: String = "ollamamobile"

/**
 * Writes Ollama's error envelope: `{"error": "..."}` and nothing else.
 *
 * Written as text rather than through content negotiation so the body is
 * byte-predictable and so an error can still be produced after negotiation
 * itself has failed.
 */
suspend fun ApplicationCall.respondOllamaError(status: HttpStatusCode, message: String) {
    respondText(
        text = RemoteJson.encodeToString(OllamaErrorResponse.serializer(), OllamaErrorResponse(message)),
        contentType = ContentType.Application.Json,
        status = status,
    )
}
