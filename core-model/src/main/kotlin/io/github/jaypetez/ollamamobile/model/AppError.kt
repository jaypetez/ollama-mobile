package io.github.jaypetez.ollamamobile.model

/**
 * The failure vocabulary every module maps its errors onto before they cross a
 * module boundary.
 *
 * The point of this hierarchy is that a caller can exhaustively `when` over it
 * and produce a specific message and a specific recovery action for every arm.
 * That only works if the cases are fine-grained enough to be actionable: "the
 * server rejected your token" and "the server is unreachable" lead to different
 * screens, so they are different types, not one `Http` with a code the UI has
 * to interpret.
 *
 * [Unexpected] is the escape hatch for a `Throwable` nobody has classified yet.
 * Reaching for it twice for the same underlying cause is the signal to add a
 * case here instead.
 */
public sealed interface AppError {
    /** Developer-facing summary. Never contains a token, a password or a pin. */
    public val message: String

    /** The underlying throwable, when the failure came from one. */
    public val cause: Throwable?

    /** Failures of the transport itself. */
    public sealed interface Network : AppError {
        /** Connect, read or write timed out. Retrying is reasonable. */
        public data class Timeout(
            public override val message: String = "The server did not respond in time.",
            public override val cause: Throwable? = null,
        ) : Network

        /** DNS, connection refused, no route — nothing answered at the address. */
        public data class Unreachable(
            public override val message: String = "The server could not be reached.",
            public override val cause: Throwable? = null,
        ) : Network

        /**
         * The TLS handshake failed. [fingerprintSha256] is the SHA-256 of the
         * presented certificate's SubjectPublicKeyInfo when one was captured,
         * so the UI can offer trust-on-first-use pinning; it is null when the
         * failure happened before a chain was seen.
         */
        public data class Tls(
            public override val message: String = "The server's certificate was not trusted.",
            public val fingerprintSha256: String? = null,
            public override val cause: Throwable? = null,
        ) : Network

        /**
         * A non-2xx response. [body] is truncated by the caller and may be
         * null; 401 and 403 are distinguished by [code] rather than by type
         * because the recovery is the same screen with a different sentence.
         */
        public data class Http(
            public val code: Int,
            public val body: String? = null,
            public override val message: String = "The server returned HTTP $code.",
            public override val cause: Throwable? = null,
        ) : Network

        /** The request was rejected before being sent because the queue is full. */
        public data class QueueFull(
            public override val message: String = "Too many requests are already in flight.",
            public override val cause: Throwable? = null,
        ) : Network

        /**
         * The caller cancelled, or the scope did.
         *
         * This is a normal outcome, not a fault: never show it as an error, and
         * never let it be swallowed into a retry. It is in the hierarchy so
         * that a `when` does not need an `else` for it.
         */
        public data class Cancelled(
            public override val message: String = "The request was cancelled.",
            public override val cause: Throwable? = null,
        ) : Network
    }

    /** Failures of the database, the filesystem or the secrets store. */
    public sealed interface Storage : AppError {
        /** A read or write failed. */
        public data class Io(
            public override val message: String,
            public override val cause: Throwable? = null,
        ) : Storage

        /** The row, file or directory is not there. */
        public data class NotFound(
            public val what: String,
            public override val message: String = "Not found: $what",
            public override val cause: Throwable? = null,
        ) : Storage

        /** Not enough free space. Both figures are in bytes. */
        public data class OutOfSpace(
            public val requiredBytes: Long,
            public val availableBytes: Long,
            public override val message: String = "Not enough free space.",
            public override val cause: Throwable? = null,
        ) : Storage

        /** A Room migration failed; the database is not usable at this version. */
        public data class Migration(
            public override val message: String,
            public override val cause: Throwable? = null,
        ) : Storage

        /**
         * A [SecretRef] could not be resolved — the Keystore entry is gone, or
         * the key was invalidated by a lock-screen change. The user has to
         * re-enter the credential; nothing else recovers this.
         */
        public data class SecretUnavailable(
            public val ref: SecretRef,
            public override val message: String = "A stored credential is no longer available.",
            public override val cause: Throwable? = null,
        ) : Storage
    }

    /** Failures about a model file or model record, before or during load. */
    public sealed interface Model : AppError {
        public data class NotFound(
            public val modelId: ModelId,
            public override val message: String = "Model not found: ${modelId.value}",
            public override val cause: Throwable? = null,
        ) : Model

        /**
         * The file parses but this build cannot run it — a removed [GgmlType],
         * an unknown architecture, a GGUF version we do not read. Rejecting it
         * here is the whole point: the alternative is a segfault inside ggml.
         */
        public data class Unsupported(
            public val reason: String,
            public override val message: String = "This model is not supported: $reason",
            public override val cause: Throwable? = null,
        ) : Model

        /** The bytes are not a valid GGUF, or the digest did not match. */
        public data class Corrupt(
            public override val message: String = "The model file is damaged or incomplete.",
            public override val cause: Throwable? = null,
        ) : Model

        /** The memory budget refused the load. [verdict] carries the arithmetic. */
        public data class InsufficientMemory(
            public val verdict: MemoryVerdict,
            public override val message: String = verdict.explain(),
            public override val cause: Throwable? = null,
        ) : Model
    }

    /** Failures of the inference engine itself. */
    public sealed interface Engine : AppError {
        /** No native engine in this build or on this ABI; remote only. */
        public data class NotAvailable(
            public override val message: String = "On-device inference is not available in this build.",
            public override val cause: Throwable? = null,
        ) : Engine

        /** The model failed to load into the engine. */
        public data class LoadFailed(
            public override val message: String,
            public override val cause: Throwable? = null,
        ) : Engine

        /** Generation started and then failed. Partial output may already be shown. */
        public data class GenerationFailed(
            public override val message: String,
            public override val cause: Throwable? = null,
        ) : Engine
    }

    /**
     * The request was refused by the app's own rules, not by anything external.
     *
     * These are not faults to apologise for — each one is a setting the user
     * chose, so the message should name the setting and offer to change it.
     */
    public sealed interface Policy : AppError {
        /** [NetworkPolicy.OFFLINE] is active and this call needed the network. */
        public data class OfflineMode(
            public override val message: String = "Offline mode is on, so this request was not sent.",
            public override val cause: Throwable? = null,
        ) : Policy

        /** [NetworkPolicy.LAN_ONLY] is active and [host] is not a private address. */
        public data class LanOnlyViolation(
            public val host: String,
            public override val message: String = "LAN-only mode blocked a request to $host.",
            public override val cause: Throwable? = null,
        ) : Policy

        /** Android's runtime local-network permission was denied or revoked. */
        public data class LocalNetworkPermissionDenied(
            public override val message: String = "Permission to reach devices on your network was denied.",
            public override val cause: Throwable? = null,
        ) : Policy
    }

    /** An unclassified failure. See the note on [AppError]. */
    public data class Unexpected(
        public override val message: String,
        public override val cause: Throwable? = null,
    ) : AppError
}

/**
 * Carries an [AppError] across an API that can only signal failure by throwing
 * — a `Flow` collector, a `suspend` call, `Result.failure`.
 *
 * Note that [AppError] itself is deliberately not a `Throwable`: making the
 * domain vocabulary throwable invites `catch (e: AppError)` at every layer,
 * which is exactly the untyped error flow this hierarchy exists to replace.
 */
public class AppErrorException(
    public val error: AppError,
) : RuntimeException(error.message, error.cause)

/** Wraps this error so it can be thrown. */
public fun AppError.asException(): AppErrorException = AppErrorException(this)
