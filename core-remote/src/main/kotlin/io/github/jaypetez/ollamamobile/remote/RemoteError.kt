package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one place an HTTP status, a server body or a thrown exception becomes an
 * [AppError].
 *
 * Centralised because the classification is the product decision, not the
 * plumbing: "the queue is full, wait" and "your token was rejected, re-enter
 * it" are different screens, and a call site that maps its own errors will pick
 * a different answer than the one next to it.
 *
 * Ollama puts the useful sentence in the response *body* — `{"error":"model
 * \"qwen3\" not found, try pulling it first"}` — and nothing about the status
 * code says that. So the body text is extracted and carried through on every
 * path that has one.
 */
object RemoteError {
    /** Longest body text kept. Enough for any real Ollama message; a runaway HTML error page is cut. */
    const val MAX_BODY_CHARS: Int = 2_000

    private const val HTTP_OK = 200
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_SERVICE_UNAVAILABLE = 503

    /**
     * How Ollama says its scheduler queue is saturated.
     *
     * This is *not* a 429. The server is not rate-limiting the client; it has
     * `OLLAMA_MAX_QUEUE` requests already waiting and refused to accept another.
     * The recovery is "finish or cancel something", not "back off and retry" —
     * a retry loop against a full queue just deepens it. Hence its own
     * [AppError.Network.QueueFull] case rather than a generic HTTP error the UI
     * would have to pattern-match on.
     */
    private val QUEUE_FULL_BODY = Regex("pending requests|server busy|queue is full", RegexOption.IGNORE_CASE)

    /**
     * Maps a non-2xx response. [body] is the raw response text, if it was read.
     *
     * 401 and 403 land on the same [AppError.Network.Http] type — the hierarchy
     * deliberately distinguishes them by code, see its KDoc — but they get
     * different messages, because "the credential was rejected" and "the
     * credential is fine and this is still not allowed" send the user to
     * different places.
     */
    fun fromHttp(code: Int, body: String? = null): AppError.Network {
        val text = truncate(body)
        val fromServer = serverMessage(text)
        return when {
            code == HTTP_SERVICE_UNAVAILABLE && isQueueFull(text) -> {
                AppError.Network.QueueFull(message = fromServer ?: "The server has too many requests queued already.")
            }

            code == HTTP_UNAUTHORIZED -> {
                AppError.Network.Http(
                    code = code,
                    body = text,
                    message = fromServer ?: "The server rejected the credential for this connection.",
                )
            }

            code == HTTP_FORBIDDEN -> {
                AppError.Network.Http(
                    code = code,
                    body = text,
                    message = fromServer ?: "The server refused this request.",
                )
            }

            code == HTTP_NOT_FOUND -> {
                AppError.Network.Http(
                    code = code,
                    body = text,
                    message = fromServer ?: "The server has no such endpoint or model.",
                )
            }

            else -> {
                AppError.Network.Http(
                    code = code,
                    body = text,
                    message = fromServer ?: "The server returned HTTP $code.",
                )
            }
        }
    }

    /**
     * Maps an error the server reported *inside* a successful response — the
     * `{"error": "..."}` line that arrives mid-NDJSON-stream at HTTP 200.
     *
     * It stays an [AppError.Network.Http] carrying code 200 rather than becoming
     * a generation failure, because the code is genuinely what the server sent
     * and hiding that makes the transcript in a bug report impossible to follow.
     * The queue-full case is lifted out here too: a stream can be refused after
     * the headers are already on the wire.
     */
    fun fromStreamPayload(error: String): AppError.Network {
        val text = truncate(error).orEmpty()
        val fromServer = serverMessage(text) ?: text
        return if (isQueueFull(text)) {
            AppError.Network.QueueFull(message = fromServer)
        } else {
            AppError.Network.Http(code = HTTP_OK, body = text, message = fromServer)
        }
    }

    /**
     * Maps a thrown exception.
     *
     * Ordering matters and is not alphabetical: [SocketTimeoutException] is an
     * [InterruptedIOException] is an [IOException], and every SSL failure is an
     * [IOException] too, so the specific arms must precede the general one or
     * every timeout becomes "unreachable".
     */
    @Suppress("ReturnCount") // One early return per failure family reads better than a nested when.
    fun fromThrowable(throwable: Throwable): AppError {
        // Already classified upstream — re-deriving it would lose the detail.
        if (throwable is AppErrorException) return throwable.error

        // Cancellation is a normal outcome, never a fault. Callers that catch
        // broadly must still rethrow it rather than reporting this value; it is
        // mapped here only so an exhaustive `when` has somewhere to send it.
        if (throwable is CancellationException) return AppError.Network.Cancelled(cause = throwable)

        return when (throwable) {
            is SSLPeerUnverifiedException -> AppError.Network.Tls(
                message = "The server's certificate did not match its address.",
                cause = throwable,
            )

            is SSLException -> AppError.Network.Tls(
                message = throwable.message ?: "The TLS handshake with the server failed.",
                cause = throwable,
            )

            is SocketTimeoutException -> AppError.Network.Timeout(cause = throwable)

            // OkHttp raises a plain InterruptedIOException for its own call and
            // write deadlines, so the socket-level type alone does not cover it.
            is InterruptedIOException -> AppError.Network.Timeout(cause = throwable)

            is UnknownHostException -> AppError.Network.Unreachable(
                message = "No device answers to that address.",
                cause = throwable,
            )

            is ConnectException,
            is NoRouteToHostException,
            is PortUnreachableException,
            -> AppError.Network.Unreachable(
                message = throwable.message ?: "The server refused the connection.",
                cause = throwable,
            )

            // Everything left that is an IOException came off the socket: a
            // reset, a broken pipe, a truncated body. Unreachable is the honest
            // summary and the recovery — check the server — is the same.
            is IOException -> AppError.Network.Unreachable(
                message = throwable.message ?: "The connection to the server failed.",
                cause = throwable,
            )

            else -> AppError.Unexpected(
                message = throwable.message ?: throwable::class.java.simpleName,
                cause = throwable,
            )
        }
    }

    /**
     * A line or frame that is not decodable.
     *
     * Never silently skipped: a stream that drops what it cannot read presents
     * a truncated answer as a complete one, and the user has no way to tell.
     */
    fun malformedPayload(raw: String, cause: Throwable? = null): AppError = AppError.Unexpected(
        message = "The server sent a response this client could not parse: ${truncate(raw)}",
        cause = cause,
    )

    /** True when [body] is the server saying its request queue is saturated. */
    fun isQueueFull(body: String?): Boolean = body != null && QUEUE_FULL_BODY.containsMatchIn(body)

    /**
     * Pulls the human sentence out of an error body.
     *
     * Handles both dialects: the native API's `{"error": "text"}` and the `/v1`
     * surface's `{"error": {"message": "text", ...}}`. A body that is not JSON
     * at all — an nginx 502 page, a bare string — is returned as-is, because a
     * short unhelpful sentence still beats discarding the only evidence.
     */
    fun serverMessage(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null

        val errorField = runCatching { RemoteJson.parseToJsonElement(text) }
            .getOrNull()
            ?.let { it as? JsonObject }
            ?.get("error")

        val message = when (errorField) {
            null -> text
            is JsonPrimitive -> errorField.content
            is JsonObject -> (errorField["message"] as? JsonPrimitive)?.content ?: errorField.toString()
            else -> errorField.toString()
        }
        return message.trim().takeIf { it.isNotEmpty() }
    }

    private fun truncate(body: String?): String? {
        val text = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (text.length <= MAX_BODY_CHARS) text else text.take(MAX_BODY_CHARS) + "…"
    }
}
