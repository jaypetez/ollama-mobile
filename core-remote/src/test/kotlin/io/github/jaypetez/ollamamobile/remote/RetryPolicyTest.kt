package io.github.jaypetez.ollamamobile.remote

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The two rules in [RetryPolicy]'s KDoc, as executable assertions.
 *
 * Deleting either rule from the implementation has to fail a test here, because
 * both failures are invisible in manual use: a retried stream looks like the
 * model changing its mind, and a retried [AppError.Network.QueueFull] looks
 * like a server that is merely slow.
 */
@RunWith(JUnit4::class)
class RetryPolicyTest {
    private val policy = RetryPolicy(Random(seed = 7))
    private val fastConfig = RetryPolicy.Config(maxAttempts = 3, initialDelayMillis = 10, maxDelayMillis = 40)

    @Test
    fun `an idempotent GET is retried until it succeeds`() = runTest {
        var attempts = 0

        val outcome = policy.withRetry(RequestKind.IDEMPOTENT, fastConfig) { attempt ->
            attempts = attempt
            if (attempt < 3) {
                RetryPolicy.Attempt.Failure(AppError.Network.Timeout())
            } else {
                RetryPolicy.Attempt.Success("tags")
            }
        }

        assertThat(attempts).isEqualTo(3)
        assertThat(outcome).isEqualTo(RetryPolicy.Attempt.Success("tags"))
    }

    @Test
    fun `retries stop at the configured ceiling`() = runTest {
        var attempts = 0

        val outcome = policy.withRetry(RequestKind.IDEMPOTENT, fastConfig) { attempt ->
            attempts = attempt
            RetryPolicy.Attempt.Failure(AppError.Network.Unreachable())
        }

        assertThat(attempts).isEqualTo(fastConfig.maxAttempts)
        assertThat(outcome).isInstanceOf(RetryPolicy.Attempt.Failure::class.java)
    }

    @Test
    fun `a non-idempotent request is never retried`() = runTest {
        var attempts = 0

        policy.withRetry(RequestKind.NON_IDEMPOTENT, fastConfig) { attempt ->
            attempts = attempt
            RetryPolicy.Attempt.Failure(AppError.Network.Timeout())
        }

        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `a stream that has already emitted a token is not retried`() {
        val error = AppError.Network.Timeout()

        // Nothing has reached the user yet: starting over is invisible.
        assertThat(policy.canRetryStream(error, tokensEmitted = 0)).isTrue()

        // One token is on screen. A retry restarts the generation from the
        // beginning, the user watches the answer rewrite itself, and the server
        // pays for the prompt twice.
        assertThat(policy.canRetryStream(error, tokensEmitted = 1)).isFalse()
        assertThat(policy.canRetryStream(error, tokensEmitted = 500)).isFalse()
    }

    @Test
    fun `QueueFull is not treated as cheap-transient`() = runTest {
        assertThat(policy.isRetryable(AppError.Network.QueueFull(), RequestKind.IDEMPOTENT)).isFalse()

        var attempts = 0
        policy.withRetry(RequestKind.IDEMPOTENT, fastConfig) { attempt ->
            attempts = attempt
            RetryPolicy.Attempt.Failure(AppError.Network.QueueFull())
        }

        // One attempt, not three: the queue that refused the first request is
        // only made longer by the second.
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `a queue-full stream is not retried either`() {
        assertThat(policy.canRetryStream(AppError.Network.QueueFull(), tokensEmitted = 0)).isFalse()
    }

    @Test
    fun `server errors retry and client errors do not`() {
        assertThat(policy.isRetryable(AppError.Network.Http(code = 503), RequestKind.IDEMPOTENT)).isTrue()
        assertThat(policy.isRetryable(AppError.Network.Http(code = 500), RequestKind.IDEMPOTENT)).isTrue()
        assertThat(policy.isRetryable(AppError.Network.Http(code = 404), RequestKind.IDEMPOTENT)).isFalse()
        assertThat(policy.isRetryable(AppError.Network.Http(code = 401), RequestKind.IDEMPOTENT)).isFalse()
    }

    @Test
    fun `cancellation policy violations and TLS failures are never retried`() {
        listOf(
            AppError.Network.Cancelled(),
            AppError.Network.Tls(),
            AppError.Policy.OfflineMode(),
            AppError.Policy.LanOnlyViolation(host = "192.168.1.40"),
        ).forEach { error ->
            assertThat(policy.isRetryable(error, RequestKind.IDEMPOTENT)).isFalse()
        }
    }

    @Test
    fun `backoff grows, is capped, and is jittered`() {
        val config = RetryPolicy.Config(initialDelayMillis = 100, maxDelayMillis = 400, multiplier = 2.0)

        // Full jitter means the value is a sample in [0, cap], so the property
        // to assert is the ceiling per attempt, not an exact number.
        repeat(50) {
            assertThat(policy.delayMillisFor(1, config)).isAtMost(100L)
            assertThat(policy.delayMillisFor(2, config)).isAtMost(200L)
            assertThat(policy.delayMillisFor(3, config)).isAtMost(400L)
            // Capped: 100 * 2^4 would be 1600.
            assertThat(policy.delayMillisFor(5, config)).isAtMost(400L)
        }

        val samples = (1..50).map { policy.delayMillisFor(3, config) }.toSet()
        assertThat(samples.size).isGreaterThan(1)
    }
}
