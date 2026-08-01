package io.github.jaypetez.ollamamobile.llm.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The escalation policy.
 *
 * This is the state machine whose bugs are, by construction, only reproducible
 * by crashing a phone twice — so it is written as a pure function and tested
 * exhaustively here instead.
 */
class BackendSelectionTest {
    @Test
    fun `no sentinel means a clean previous run, so scan everything`() {
        val plan = BackendSelection.plan(null)

        assertThat(plan).isInstanceOf(BackendPlan.Load::class.java)
        assertThat((plan as BackendPlan.Load).mode).isEqualTo(BackendMode.FULL_SCAN)
        assertThat(plan.attempt).isEqualTo(1)
    }

    @Test
    fun `a sentinel from a full scan drops to the baseline variant`() {
        val plan = BackendSelection.plan(
            SentinelRecord(attempt = 1, mode = BackendMode.FULL_SCAN, backend = "CPU (i8mm)"),
        )

        assertThat(plan).isInstanceOf(BackendPlan.Load::class.java)
        assertThat((plan as BackendPlan.Load).mode).isEqualTo(BackendMode.SAFE_BASELINE)
        assertThat(plan.attempt).isEqualTo(2)
    }

    @Test
    fun `a sentinel from a baseline run disables native inference entirely`() {
        val plan = BackendSelection.plan(
            SentinelRecord(attempt = 2, mode = BackendMode.SAFE_BASELINE, backend = "CPU"),
        )

        assertThat(plan).isInstanceOf(BackendPlan.Disabled::class.java)
    }

    @Test
    fun `the disabled reason names the backend that died and says how to recover`() {
        val plan = BackendSelection.plan(
            SentinelRecord(attempt = 2, mode = BackendMode.SAFE_BASELINE, backend = "CPU (armv8)"),
        ) as BackendPlan.Disabled

        assertThat(plan.reason).contains("CPU (armv8)")
        assertThat(plan.reason).contains("clear the app's data")
    }

    @Test
    fun `an attempt count beyond the ceiling stays disabled rather than wrapping`() {
        // A sentinel that somehow survived several launches must not come back
        // round to "try the full scan again" and resume the crash loop.
        val plan = BackendSelection.plan(
            SentinelRecord(attempt = 97, mode = BackendMode.SAFE_BASELINE, backend = "CPU"),
        )

        assertThat(plan).isInstanceOf(BackendPlan.Disabled::class.java)
    }

    @Test
    fun `baseline variant names match the tags llama cpp builds for Android`() {
        // These strings are filenames produced by ggml_add_cpu_backend_variant
        // in the pinned submodule. Getting one wrong means safe mode silently
        // falls back to a full scan, which is the thing it exists to avoid.
        assertThat(BackendSelection.baselineLibraryFor("arm64-v8a"))
            .isEqualTo("libggml-cpu-android_armv8.0_1.so")
        assertThat(BackendSelection.baselineLibraryFor("x86_64"))
            .isEqualTo("libggml-cpu-x64.so")
    }

    @Test
    fun `an ABI with no known baseline returns null rather than guessing a name`() {
        assertThat(BackendSelection.baselineLibraryFor("armeabi-v7a")).isNull()
        assertThat(BackendSelection.baselineLibraryFor("")).isNull()
    }
}
