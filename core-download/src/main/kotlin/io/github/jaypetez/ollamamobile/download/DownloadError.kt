package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.MemoryVerdict

/**
 * The failure vocabulary of a model download.
 *
 * `AppError` is the app-wide hierarchy and is `sealed`, so it cannot be
 * extended from this module. That is the right constraint — but the download
 * path has distinctions the UI genuinely has to act on differently and which
 * `AppError` flattens: "the repository is gated, accept its licence on the
 * website" is a link to a browser, and "the bytes did not hash correctly" is a
 * restart. Collapsing both into `AppError.Network.Http(401)` and
 * `AppError.Model.Corrupt` would force every caller to re-derive the difference
 * from a message string.
 *
 * So this is a second, narrower vocabulary that always knows how to become an
 * [AppError] via [toAppError] when it crosses out of `:core-download`.
 */
public sealed interface DownloadError {
    public val message: String
    public val cause: Throwable?

    /**
     * The repository requires the user to accept its terms on huggingface.co.
     *
     * Recognised from a 401 or 403 carrying `X-Error-Code: GatedRepo`. The
     * recovery is not "sign in again" — a valid token still gets this until the
     * account has accepted the licence — so the UI must offer [licenceUrl].
     */
    public data class GatedRepo(
        public val repo: String,
        public val licenceUrl: String,
        public override val message: String =
            "$repo requires you to accept its licence on Hugging Face before it can be downloaded.",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /**
     * The repository is private or the supplied token was rejected.
     *
     * A 401 with no `GatedRepo` marker, or `X-Error-Code: RepoNotFound` — which
     * is what the Hub returns for a private repository when the caller has no
     * access, deliberately, so that repository names cannot be enumerated.
     */
    public data class AuthenticationRequired(
        public val repo: String,
        public override val message: String =
            "$repo is private or your Hugging Face access token was rejected.",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /** The repository, revision or file does not exist. */
    public data class NotFound(
        public val what: String,
        public override val message: String = "Not found on the server: $what",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /** Anything the transport reported that is not one of the cases above. */
    public data class Transport(
        public val error: AppError,
        public override val message: String = error.message,
        public override val cause: Throwable? = error.cause,
    ) : DownloadError

    /**
     * The server's idea of the total length disagrees with the catalogue's.
     *
     * Checked against the `Content-Range` total on every resumed request, not
     * only at the start: a proxy that answers a range request from a stale or
     * different object is the failure this catches, and it would otherwise
     * present as a hash mismatch after gigabytes of transfer.
     */
    public data class SizeMismatch(
        public val fileName: String,
        public val expectedBytes: Long,
        public val serverBytes: Long,
        public override val message: String =
            "$fileName should be $expectedBytes bytes but the server offered $serverBytes.",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /**
     * The finished bytes did not hash to the expected SHA-256.
     *
     * [expectedSha256] always comes from the Hub's LFS metadata. It is never a
     * CDN `ETag`: see the note on [ModelTransfer].
     */
    public data class IntegrityMismatch(
        public val fileName: String,
        public val expectedSha256: String,
        public val actualSha256: String,
        public override val message: String =
            "$fileName was downloaded but is damaged, so it has been deleted. The download will restart.",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /**
     * The bytes are not a GGUF.
     *
     * Nearly free to check and it catches the common real-world case of a CDN
     * or captive portal serving an HTML error page with a 200 status.
     */
    public data class NotAGguf(
        public val fileName: String,
        public override val message: String =
            "$fileName is not a GGUF file. The server may have returned an error page instead of the model.",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /** Pre-flight refused the transfer; nothing was downloaded. */
    public data class InsufficientStorage(
        public val requiredBytes: Long,
        public val allocatableBytes: Long,
        public override val message: String =
            "This model needs $requiredBytes bytes and only $allocatableBytes can be freed up.",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /** The GGUF parses but this build cannot run it — a removed ggml type, say. */
    public data class Incompatible(
        public val reason: String,
        public override val message: String = "This model cannot run on this device: $reason",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /**
     * The memory estimate refused the model.
     *
     * Only [MemoryVerdict.Refuse] reaches here. A [MemoryVerdict.Tight] is a
     * warning the user is allowed to override and is therefore never an error.
     */
    public data class MemoryRefused(
        public val verdict: MemoryVerdict,
        public override val message: String = verdict.explain(),
        public override val cause: Throwable? = null,
    ) : DownloadError

    /**
     * A sharded model is missing parts.
     *
     * Two of three shards is not a partially usable model, it is an unusable
     * one, so this is an error rather than a degraded success.
     */
    public data class IncompleteShardSet(
        public val missing: List<String>,
        public override val message: String =
            "This model is split into parts and ${missing.size} of them are missing.",
        public override val cause: Throwable? = null,
    ) : DownloadError

    /** Maps onto the app-wide vocabulary at the module boundary. */
    public fun toAppError(): AppError = when (this) {
        is GatedRepo -> AppError.Network.Http(code = HTTP_UNAUTHORIZED, message = message)

        is AuthenticationRequired -> AppError.Network.Http(code = HTTP_UNAUTHORIZED, message = message)

        is NotFound -> AppError.Storage.NotFound(what = what, message = message)

        is Transport -> error

        is SizeMismatch -> AppError.Model.Corrupt(message = message)

        is IntegrityMismatch -> AppError.Model.Corrupt(message = message)

        is NotAGguf -> AppError.Model.Corrupt(message = message)

        is InsufficientStorage -> AppError.Storage.OutOfSpace(
            requiredBytes = requiredBytes,
            availableBytes = allocatableBytes,
            message = message,
        )

        is Incompatible -> AppError.Model.Unsupported(reason = reason, message = message)

        is MemoryRefused -> AppError.Model.InsufficientMemory(verdict = verdict, message = message)

        is IncompleteShardSet -> AppError.Model.Corrupt(message = message)
    }

    public companion object {
        internal const val HTTP_UNAUTHORIZED: Int = 401
    }
}

/**
 * Carries a [DownloadError] across `suspend` boundaries that can only signal by
 * throwing — the body of a `CoroutineWorker`, an OkHttp callback.
 *
 * [DownloadError] itself is deliberately not a `Throwable`, for the same reason
 * `AppError` is not: a throwable domain vocabulary invites `catch (e:
 * DownloadError)` everywhere and re-creates the untyped error flow.
 */
public class DownloadException(
    public val error: DownloadError,
) : RuntimeException(error.message, error.cause)

/** Wraps this error so it can be thrown. */
public fun DownloadError.asException(): DownloadException = DownloadException(this)
