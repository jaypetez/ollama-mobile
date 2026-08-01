package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.model.AppError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/** A parsed `Content-Range: bytes <start>-<end>/<total>`. */
internal data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    /** Null when the server sent `*`, meaning it will not say how long the whole thing is. */
    val total: Long?,
) {
    companion object {
        private val PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

        fun parse(header: String?): ContentRange? {
            val match = header?.let { PATTERN.find(it) } ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
            return ContentRange(start = start, endInclusive = end, total = total)
        }
    }
}

/**
 * Turns a Hugging Face (or generic) error response into a [DownloadError].
 *
 * The Hub states the *reason* for a 401 or 403 in the `X-Error-Code` header,
 * not in the body, and the distinction is the whole product difference between
 * "sign in" and "go and accept a licence on a web page". A gated repository
 * returns 401 with `X-Error-Code: GatedRepo` even for a perfectly valid token,
 * so treating every 401 as an authentication failure sends the user round a
 * sign-in loop that can never succeed.
 */
internal object DownloadHttpErrors {
    const val HEADER_ERROR_CODE: String = "X-Error-Code"
    private const val GATED_REPO = "GatedRepo"
    private const val REPO_NOT_FOUND = "RepoNotFound"
    private const val REVISION_NOT_FOUND = "RevisionNotFound"
    private const val ENTRY_NOT_FOUND = "EntryNotFound"

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404

    private const val MAX_BODY_CHARS = 1_000

    fun licenceUrl(repo: String): String = "https://huggingface.co/$repo"

    fun fromResponse(response: Response, repo: String, what: String, body: String? = null): DownloadError {
        val code = response.code
        val errorCode = response.header(HEADER_ERROR_CODE)
        return when {
            errorCode.equals(GATED_REPO, ignoreCase = true) -> {
                DownloadError.GatedRepo(
                    repo = repo,
                    licenceUrl = licenceUrl(repo),
                )
            }

            // The Hub answers RepoNotFound for a private repo the caller cannot
            // see, deliberately, so repository names cannot be enumerated. It
            // is an authentication problem, not a typo.
            errorCode.equals(REPO_NOT_FOUND, ignoreCase = true) &&
                code in setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN) -> {
                DownloadError.AuthenticationRequired(repo = repo)
            }

            errorCode.equals(REVISION_NOT_FOUND, ignoreCase = true) ||
                errorCode.equals(ENTRY_NOT_FOUND, ignoreCase = true) -> {
                DownloadError.NotFound(what = what)
            }

            code == HTTP_UNAUTHORIZED -> {
                DownloadError.AuthenticationRequired(repo = repo)
            }

            code == HTTP_FORBIDDEN -> {
                DownloadError.AuthenticationRequired(
                    repo = repo,
                    message = "Access to $repo was refused. It may be gated or private.",
                )
            }

            code == HTTP_NOT_FOUND -> {
                DownloadError.NotFound(what = what)
            }

            else -> {
                DownloadError.Transport(
                    AppError.Network.Http(code = code, body = body?.take(MAX_BODY_CHARS)),
                )
            }
        }
    }

    /**
     * Maps a thrown exception onto the transport arm.
     *
     * Ordering is not alphabetical and must not be sorted: every SSL failure and
     * every timeout is also an [IOException], so the specific arms have to come
     * first or a read timeout is reported as "the server could not be reached".
     */
    fun fromThrowable(throwable: Throwable): DownloadError = DownloadError.Transport(
        when (throwable) {
            is SSLException -> AppError.Network.Tls(cause = throwable)

            is SocketTimeoutException -> AppError.Network.Timeout(cause = throwable)

            is InterruptedIOException -> AppError.Network.Timeout(cause = throwable)

            is UnknownHostException -> AppError.Network.Unreachable(
                message = "No server answers to that address.",
                cause = throwable,
            )

            is ConnectException, is NoRouteToHostException -> AppError.Network.Unreachable(
                message = throwable.message ?: "The server refused the connection.",
                cause = throwable,
            )

            is IOException -> AppError.Network.Unreachable(
                message = throwable.message ?: "The connection failed.",
                cause = throwable,
            )

            else -> AppError.Unexpected(
                message = throwable.message ?: throwable::class.java.simpleName,
                cause = throwable,
            )
        },
    )
}

/**
 * Runs a [Call] synchronously while still honouring coroutine cancellation.
 *
 * `execute()` rather than `enqueue()` for the same reason `:core-remote` does
 * it: `LanOnlyGuard` signals a policy violation by throwing a typed exception
 * from inside the interceptor chain, and OkHttp's async path rethrows a
 * non-`IOException` on its own dispatcher thread, where an uncaught throwable
 * kills the process.
 *
 * The watcher coroutine is what makes cancellation prompt. `execute()` blocks
 * its thread, so cancelling the job cannot interrupt it; a child suspended in
 * [awaitCancellation] is resumed immediately and closes the socket from there.
 * The flag stops it cancelling a call whose response is already being streamed.
 */
internal suspend fun Call.awaitResponse(): Response {
    val call = this
    return coroutineScope {
        val answered = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!answered.get()) call.cancel()
            }
        }
        try {
            call.execute().also { answered.set(true) }
        } finally {
            watcher.cancel()
        }
    }
}
