package io.github.jaypetez.ollamamobile.data.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The `keep_alive` grammar.
 *
 * Worth its own test because the two interesting rules are both silent when
 * wrong: a bare number read as milliseconds instead of seconds is off by a
 * factor of a thousand and simply looks like a model that never unloads, and a
 * negative value read as a duration schedules an unload in the past.
 */
@RunWith(JUnit4::class)
class KeepAliveTest {
    @Test
    fun `a bare number is seconds`() {
        assertThat(KeepAlive.parse("300")).isEqualTo(300_000L)
    }

    @Test
    fun `suffixed durations are seconds, minutes and hours`() {
        assertThat(KeepAlive.parse("45s")).isEqualTo(45_000L)
        assertThat(KeepAlive.parse("10m")).isEqualTo(600_000L)
        assertThat(KeepAlive.parse("2h")).isEqualTo(7_200_000L)
    }

    @Test
    fun `any negative value means never unload`() {
        assertThat(KeepAlive.parse("-1")).isEqualTo(KeepAlive.INDEFINITE)
        assertThat(KeepAlive.parse("-30m")).isEqualTo(KeepAlive.INDEFINITE)
    }

    @Test
    fun `zero means unload as soon as the turn ends`() {
        assertThat(KeepAlive.parse("0")).isEqualTo(0L)
    }

    @Test
    fun `absent and blank fall back to the default`() {
        assertThat(KeepAlive.parse(null)).isEqualTo(KeepAlive.DEFAULT_MILLIS)
        assertThat(KeepAlive.parse("   ")).isEqualTo(KeepAlive.DEFAULT_MILLIS)
    }

    @Test
    fun `a typo falls back to the default rather than breaking inference`() {
        // A text box the user can put anything in must not be able to stop the
        // engine working.
        assertThat(KeepAlive.parse("ten minutes")).isEqualTo(KeepAlive.DEFAULT_MILLIS)
        assertThat(KeepAlive.parse("5x")).isEqualTo(KeepAlive.DEFAULT_MILLIS)
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertThat(KeepAlive.parse("  10M ")).isEqualTo(600_000L)
    }
}
