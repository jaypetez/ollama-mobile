package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.model.AppError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * What is being retried.
 *
 * Retrying is a property of the *request*, not of the error, and no error type
 * carries enough information to decide on its own. A timeout on `GET
 * /api/tags` is free to repeat; the identical timeout on `POST /api/chat` may
 * have already run a 40-second generation on the server.
 */
enum class RequestKind {
    /**
     * Repeating it changes nothing: `GET /api/version`, `GET /api/tags`,
     * `POST /api/show` (a read despite the verb), `DELETE /api/delete`.
     */
    IDEMPOTENT,

    /**
     * Repeating it costs the server real work or has a visible effect:
     * `/api/chat`, `/api/generate`, `/api/pull`, `/api/copy`.
     */
    NON_IDEMPOTENT,
}

/**
 * Bounded exponential backoff with full jitter, for idempotent requests only.
 *
 * ## The two rules that are not negotiable
 *
 * **1. A streaming generation that has already emitted a token is never
 * retried.** Not "retried carefully" — never. The tokens are already on the
 * user's screen and in the message being accumulated; a retry starts the
 * generation again from the beginning, so the user watches the answer restart,
 * and the two partial outputs cannot be stitched together because the sampler
 * is stateful and the second run is a different answer. Worse, the server pays
 * for the whole prompt evaluation twice. [canRetryStream] encodes this: once
 * `tokensEmitted > 0`, the answer is the user's to re-ask, not the client's to
 * silently redo.
 *
 * **2. [AppError.Network.QueueFull] is never treated as cheap-transient.** A
 * 503 from Ollama's request queue is not a 429 with a `Retry-After` header — it
 * means the server is already doing more than it can. Backing off for a few
 * hundred milliseconds and trying again adds another entry to the queue that
 * refused the first one, which is how a busy server becomes an unresponsive
 * one. The correct handling is to surface it: the user chooses to wait, or to
 * send the request somewhere else. If a future version ever does retry it, that
 * has to be a long, user-visible wait driven by the UI, not this policy.
 *
 * Jitter is full jitter (`random(0, cap)`) rather than a ±10% wobble because
 * the failure this policy is most likely to see is a server that just came back
 * up, with every screen in the app probing it at once. Equal-and-opposite
 * wobble keeps a thundering herd a herd.
 */
@Singleton
class RetryPolicy internal constructor(
    private val random: Random,
) {
    /** Dagger cannot see Kotlin default arguments, so the injectable constructor takes none. */
    @Inject
    constructor() : this(Random.Default)

    /** Tuning knobs, kept in one place so a test can shrink the waits. */
    data class Config(
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
        val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
        val multiplier: Double = DEFAULT_MULTIPLIER,
    )

    /**
     * Whether [error] on a [kind] request may be attempted again.
     *
     * Exhaustive over the error hierarchy on purpose — no `else` — so that a
     * new [AppError] case fails to compile here and gets classified
     * deliberately rather than inheriting somebody's default.
     */
    @Suppress("CyclomaticComplexMethod") // One arm per error case; a table, not logic.
    fun isRetryable(error: AppError, kind: RequestKind): Boolean {
        if (kind == RequestKind.NON_IDEMPOTENT) return false
        return when (error) {
            is AppError.Network.Timeout -> true

            is AppError.Network.Unreachable -> true

            // Rule 2. See the class KDoc.
            is AppError.Network.QueueFull -> false

            // A 5xx is the server failing at something it agreed to do; a 4xx
            // is the request being wrong, and repeating a wrong request
            // produces the same wrong answer more expensively.
            is AppError.Network.Http -> error.code in SERVER_ERRORS

            // A certificate does not change between two attempts a second
            // apart, and retrying past a pin failure would be a security
            // decision made by a loop.
            is AppError.Network.Tls -> false

            is AppError.Network.Cancelled -> false

            // Offline mode and LAN-only are settings, not weather.
            is AppError.Policy -> false

            is AppError.Storage -> false

            is AppError.Model -> false

            is AppError.Engine -> false

            is AppError.Unexpected -> false
        }
    }

    /**
     * Rule 1. See the class KDoc.
     *
     * @param tokensEmitted how many deltas the collector has already been
     *   given. Zero means the connection failed before the model said
     *   anything, which is the only case where starting over is invisible.
     */
    fun canRetryStream(error: AppError, tokensEmitted: Int): Boolean =
        tokensEmitted == 0 && isRetryable(error, RequestKind.IDEMPOTENT)

    /** The wait before attempt number [attempt] (1-based), with full jitter. */
    fun delayMillisFor(attempt: Int, config: Config = Config()): Long {
        val exponential = config.initialDelayMillis * config.multiplier.pow(attempt - 1)
        val cap = exponential.coerceAtMost(config.maxDelayMillis.toDouble()).toLong()
        return if (cap <= 0L) 0L else random.nextLong(cap + 1)
    }

    /**
     * Runs [block] until it succeeds or the policy gives up.
     *
     * [block] reports failure by returning an [AppError] rather than throwing,
     * so a caller that already speaks `AppResult` does not box and unbox on
     * every attempt.
     */
    suspend fun <T> withRetry(
        kind: RequestKind,
        config: Config = Config(),
        block: suspend (attempt: Int) -> Attempt<T>,
    ): Attempt<T> {
        var attempt = 1
        while (true) {
            val outcome = block(attempt)
            if (outcome is Attempt.Success) return outcome
            val error = (outcome as Attempt.Failure).error
            if (attempt >= config.maxAttempts || !isRetryable(error, kind)) return outcome
            delay(delayMillisFor(attempt, config))
            attempt++
        }
    }

    /** The result of one attempt. */
    sealed interface Attempt<out T> {
        data class Success<out T>(
            val value: T,
        ) : Attempt<T>

        data class Failure(
            val error: AppError,
        ) : Attempt<Nothing>
    }

    companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val DEFAULT_INITIAL_DELAY_MILLIS = 250L
        private const val DEFAULT_MAX_DELAY_MILLIS = 4_000L
        private const val DEFAULT_MULTIPLIER = 2.0

        private val SERVER_ERRORS = 500..599
    }
}
