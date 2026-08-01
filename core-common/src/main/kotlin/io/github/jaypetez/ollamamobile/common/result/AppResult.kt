package io.github.jaypetez.ollamamobile.common.result

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import kotlin.coroutines.cancellation.CancellationException

/**
 * A result type over [AppError].
 *
 * The stdlib `kotlin.Result` was evaluated first and is genuinely insufficient
 * here, for two reasons that are not stylistic:
 *
 *  1. `Result.failure` takes a `Throwable`. [AppError] is deliberately *not* a
 *     `Throwable` (see its KDoc), so every failure would have to be boxed into
 *     an [AppErrorException] on the way in and unboxed with a cast on the way
 *     out. A cast on every error path is the thing the typed hierarchy exists
 *     to remove.
 *  2. `kotlin.Result` cannot be used as a return type at all without the
 *     `-Xallow-result-return-type` compiler flag, which we do not set. It was
 *     designed for `runCatching`, not as a domain result.
 *
 * So this type carries an [AppError] directly and a `when` over
 * `AppResult<T>` stays exhaustive down to the concrete error case.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : AppResult<T>

    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing>
}

/** The value, or null when this is a [AppResult.Failure]. */
fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

/** The error, or null when this is a [AppResult.Success]. */
fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error

val AppResult<*>.isSuccess: Boolean get() = this is AppResult.Success

val AppResult<*>.isFailure: Boolean get() = this is AppResult.Failure

/** The value, or [fallback] when this is a [AppResult.Failure]. */
fun <T> AppResult<T>.getOrElse(fallback: (AppError) -> @UnsafeVariance T): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> fallback(error)
}

/** Throws [AppErrorException] on failure. Use only at a boundary that can only signal by throwing. */
fun <T> AppResult<T>.getOrThrow(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw AppErrorException(error)
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Success) action(value)
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Failure) action(error)
}

/**
 * Runs [block], mapping a thrown [AppErrorException] back to its [AppError] and
 * anything else to [AppError.Unexpected].
 *
 * [CancellationException] is rethrown rather than captured: swallowing it turns
 * a cancelled coroutine into a zombie that keeps running, which is the classic
 * way `runCatching` breaks structured concurrency.
 */
inline fun <T> appRunCatching(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: AppErrorException) {
    AppResult.Failure(error.error)
} catch (
    @Suppress("TooGenericExceptionCaught") throwable: Throwable,
) {
    AppResult.Failure(
        AppError.Unexpected(
            message = throwable.message ?: throwable::class.java.simpleName,
            cause = throwable,
        ),
    )
}
