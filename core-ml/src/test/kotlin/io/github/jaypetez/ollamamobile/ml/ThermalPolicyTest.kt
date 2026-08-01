package io.github.jaypetez.ollamamobile.ml

import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ThermalPolicyTest {
    @Test
    fun `platform constants map onto the enum`() {
        assertThat(ThermalStatus.fromPlatform(PowerManager.THERMAL_STATUS_NONE))
            .isEqualTo(ThermalStatus.NONE)
        assertThat(ThermalStatus.fromPlatform(PowerManager.THERMAL_STATUS_SEVERE))
            .isEqualTo(ThermalStatus.SEVERE)
        assertThat(ThermalStatus.fromPlatform(PowerManager.THERMAL_STATUS_SHUTDOWN))
            .isEqualTo(ThermalStatus.SHUTDOWN)
    }

    @Test
    fun `an unrecognised status is unknown rather than none`() {
        // Guessing "cool" for a severity a future platform added is the wrong
        // direction to guess in.
        assertThat(ThermalStatus.fromPlatform(Int.MAX_VALUE)).isEqualTo(ThermalStatus.UNKNOWN)
        assertThat(ThermalStatus.fromPlatform(-1)).isEqualTo(ThermalStatus.UNKNOWN)
    }

    @Test
    fun `throttling starts at light`() {
        assertThat(ThermalStatus.NONE.isThrottling).isFalse()
        assertThat(ThermalStatus.UNKNOWN.isThrottling).isFalse()
        assertThat(ThermalStatus.LIGHT.isThrottling).isTrue()
        assertThat(ThermalStatus.MODERATE.isThrottling).isTrue()
    }

    @Test
    fun `thread recommendation decreases monotonically with heat`() {
        val base = 4
        val recommendations = listOf(
            ThermalStatus.NONE,
            ThermalStatus.LIGHT,
            ThermalStatus.MODERATE,
            ThermalStatus.SEVERE,
            ThermalStatus.CRITICAL,
        ).map { ThermalPolicy.recommendedThreads(base, it) }

        assertThat(recommendations).isEqualTo(listOf(4, 4, 3, 2, 1))
        recommendations.zipWithNext().forEach { (hotter, hottest) ->
            assertThat(hottest).isAtMost(hotter)
        }
    }

    @Test
    fun `the recommendation never drops below one thread`() {
        ThermalStatus.entries.forEach { status ->
            assertThat(ThermalPolicy.recommendedThreads(baseThreads = 1, status = status))
                .isAtLeast(1)
            assertThat(ThermalPolicy.recommendedThreads(baseThreads = 0, status = status))
                .isEqualTo(1)
        }
    }

    @Test
    fun `the recommendation never exceeds the base`() {
        ThermalStatus.entries.forEach { status ->
            assertThat(ThermalPolicy.recommendedThreads(baseThreads = 6, status = status))
                .isAtMost(6)
        }
    }

    @Test
    fun `generation stops only at emergency and above`() {
        assertThat(ThermalPolicy.shouldStopGenerating(ThermalStatus.SEVERE)).isFalse()
        // CRITICAL still allows one thread so an in-flight answer can finish.
        assertThat(ThermalPolicy.shouldStopGenerating(ThermalStatus.CRITICAL)).isFalse()
        assertThat(ThermalPolicy.shouldStopGenerating(ThermalStatus.EMERGENCY)).isTrue()
        assertThat(ThermalPolicy.shouldStopGenerating(ThermalStatus.SHUTDOWN)).isTrue()
        assertThat(ThermalPolicy.shouldStopGenerating(ThermalStatus.UNKNOWN)).isFalse()
    }

    @Test
    fun `the monitor reads the platform status`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)

        val monitor = ThermalMonitor(context) { FIXED_CLOCK }

        assertThat(monitor.currentStatus()).isEqualTo(ThermalStatus.MODERATE)
    }

    @Test
    fun `a snapshot carries the status and the capture time`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_LIGHT)

        val snapshot = ThermalMonitor(context) { FIXED_CLOCK }.snapshot()

        assertThat(snapshot.status).isEqualTo(ThermalStatus.LIGHT)
        assertThat(snapshot.atMillis).isEqualTo(FIXED_CLOCK)
        assertThat(snapshot.status.isThrottling).isTrue()
    }

    @Test
    fun `headroom is polled no faster than once a second`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var now = 0L
        val monitor = ThermalMonitor(context) { now }

        // Robolectric's PowerManager returns a NaN-free default or NaN depending
        // on the shadow; either way the contract under test is that a second
        // reading inside the same second reuses the first, and that whatever
        // comes back is never NaN.
        val first = monitor.snapshot()
        now = 100L
        val second = monitor.snapshot()

        assertThat(second.headroom).isEqualTo(first.headroom)
        assertThat(first.headroom?.isNaN() ?: false).isFalse()
    }

    @Test
    fun `the recommended thread count uses performance cores not total cores`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
        val capabilities = DeviceCapabilities.Unknown.copy(
            topology = CpuTopology(totalCores = 8, performanceCores = 3),
        )

        val threads = ThermalMonitor(context) { FIXED_CLOCK }.recommendedThreads(capabilities)

        assertThat(threads).isEqualTo(3)
    }

    private companion object {
        const val FIXED_CLOCK = 1_700_000_000_000L
    }
}
