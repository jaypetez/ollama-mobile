package io.github.jaypetez.ollamamobile.data.routing

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The state machine directly, on a clock the test moves.
 *
 * What is asserted throughout is the *behaviour* the breaker exists for —
 * whether a request is allowed — rather than the internal state name, because
 * the state is only interesting insofar as it gates traffic.
 */
@RunWith(JUnit4::class)
class CircuitBreakerTest {
    private class FakeClock(
        var now: Long = 0L,
    ) : WallClock {
        override fun nowMillis(): Long = now
    }

    private val clock = FakeClock()

    private val config = CircuitBreaker.Config(
        failureThreshold = 3,
        baseCooldownMillis = 30_000L,
        maxCooldownMillis = 120_000L,
        failureDecayMillis = 60_000L,
    )

    private val breaker = CircuitBreaker(clock, config)

    private val pi = ServerId("pi")
    private val nuc = ServerId("nuc")

    private val timeout = AppError.Network.Timeout()

    @Test
    fun `an unknown server is closed and allows traffic`() {
        assertThat(breaker.state(pi)).isEqualTo(BreakerState.CLOSED)
        assertThat(breaker.allows(pi)).isTrue()
    }

    @Test
    fun `stays closed below the threshold`() {
        repeat(config.failureThreshold - 1) { breaker.onFailure(pi, timeout) }

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.CLOSED)
        assertThat(breaker.allows(pi)).isTrue()
    }

    @Test
    fun `opens on the threshold failure and refuses traffic`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.OPEN)
        assertThat(breaker.allows(pi)).isFalse()
    }

    @Test
    fun `one dead server does not open another`() {
        // The whole point: a Pi that is unplugged must not slow down the NUC.
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }

        assertThat(breaker.allows(pi)).isFalse()
        assertThat(breaker.allows(nuc)).isTrue()
    }

    @Test
    fun `becomes half-open once the cooldown elapses, and lets a probe through`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }

        clock.now += config.baseCooldownMillis

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.HALF_OPEN)
        assertThat(breaker.allows(pi)).isTrue()
    }

    @Test
    fun `a success while half-open closes the breaker`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }
        clock.now += config.baseCooldownMillis

        breaker.onSuccess(pi)

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.CLOSED)
        assertThat(breaker.status(pi).consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun `a failure while half-open re-opens immediately, without re-reaching the threshold`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }
        clock.now += config.baseCooldownMillis
        assertThat(breaker.allows(pi)).isTrue()

        breaker.onFailure(pi, timeout)

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.OPEN)
    }

    @Test
    fun `the cooldown doubles each time a probe fails`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }

        clock.now += config.baseCooldownMillis
        breaker.onFailure(pi, timeout)

        // The old cooldown is no longer enough to reach half-open...
        clock.now += config.baseCooldownMillis
        assertThat(breaker.state(pi)).isEqualTo(BreakerState.OPEN)

        // ...but twice it is.
        clock.now += config.baseCooldownMillis
        assertThat(breaker.state(pi)).isEqualTo(BreakerState.HALF_OPEN)
    }

    @Test
    fun `the escalating cooldown is capped`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }
        // Enough re-opens that an uncapped doubling would run to hours.
        repeat(6) {
            clock.now += config.maxCooldownMillis
            breaker.onFailure(pi, timeout)
        }

        clock.now += config.maxCooldownMillis

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.HALF_OPEN)
    }

    @Test
    fun `recovers fully after a success, restarting from the base cooldown`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }
        clock.now += config.baseCooldownMillis
        breaker.onSuccess(pi)

        // Down again later: the wait must be the base one, not the escalated one.
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }
        clock.now += config.baseCooldownMillis

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.HALF_OPEN)
    }

    @Test
    fun `the failure count decays, so occasional failures never open the breaker`() {
        // Two failures a long way apart are not "consecutive" in any sense the
        // user would recognise, and must not add up.
        breaker.onFailure(pi, timeout)
        clock.now += config.failureDecayMillis
        breaker.onFailure(pi, timeout)
        clock.now += config.failureDecayMillis
        breaker.onFailure(pi, timeout)

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.CLOSED)
        assertThat(breaker.status(pi).consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `failures inside the decay window do accumulate`() {
        repeat(config.failureThreshold) {
            clock.now += config.failureDecayMillis / 2
            breaker.onFailure(pi, timeout)
        }

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.OPEN)
    }

    @Test
    fun `a request-level failure never trips the breaker`() {
        // A 404 for a model that does not exist says nothing about the server.
        // Counting it would let one bad prompt sideline a healthy machine.
        val notFound = AppError.Network.Http(code = 404, body = "model not found")

        repeat(config.failureThreshold * 2) {
            assertThat(breaker.onFailure(pi, notFound)).isFalse()
        }

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.CLOSED)
    }

    @Test
    fun `a server error does trip it`() {
        repeat(config.failureThreshold) {
            assertThat(breaker.onFailure(pi, AppError.Network.Http(code = 502))).isTrue()
        }

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.OPEN)
    }

    @Test
    fun `a saturated queue trips it, because retrying deepens the queue`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, AppError.Network.QueueFull()) }

        assertThat(breaker.state(pi)).isEqualTo(BreakerState.OPEN)
    }

    @Test
    fun `status reports when the breaker will next admit a probe`() {
        clock.now = 1_000L
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }

        assertThat(breaker.status(pi).retryAtMillis).isEqualTo(1_000L + config.baseCooldownMillis)
    }

    @Test
    fun `forgetting a server clears its breaker`() {
        repeat(config.failureThreshold) { breaker.onFailure(pi, timeout) }

        breaker.forget(pi)

        assertThat(breaker.allows(pi)).isTrue()
        assertThat(breaker.snapshot()).doesNotContainKey(pi)
    }
}
