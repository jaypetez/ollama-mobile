package io.github.jaypetez.ollamamobile.ml

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A seam over `PerformanceHintManager` (API 31+), and an explicit statement that
 * nobody here knows whether it helps.
 *
 * ## Read this before using it
 *
 * ADPF's session model is built around a **repeating deadline**: you declare a
 * target duration, you report the actual duration of each cycle, and the
 * governor learns to place and clock your threads so the next cycle lands on
 * time. It was designed for game and UI frames — thousands of short, similar
 * work units per minute against a fixed budget.
 *
 * Token decode does not have that shape. A decode step at single-digit-to-tens
 * of tokens per second is tens to hundreds of milliseconds long, there is no
 * deadline that missing would visibly hurt (a stream is not a frame), and the
 * work per step is uniform enough that there is little for the governor to
 * learn. The plausible outcome is that reporting decode durations as if they
 * were frames tells the governor to hold clocks high, which on a sustained
 * workload means heat, which means throttling — the opposite of what was wanted.
 *
 * **This is an unproven experiment.** No measurement exists: this project has no
 * arm64 device, and ADPF's effect is entirely a property of a specific vendor's
 * governor. The class is here so the benchmark harness can turn it on and off
 * and produce a number. Until it has, do not enable it by default, do not
 * describe it in release notes as an optimisation, and do not assume the sign of
 * its effect.
 *
 * The honest alternative it should be measured against is [ThermalPolicy] —
 * fewer threads when hot — which at least has a mechanism anyone can state.
 */
@Singleton
public class PerfHints
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** True when the platform offers the API at all. Says nothing about benefit. */
        public val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager() != null

        /**
         * Creates a hint session for [threadIds] with an initial [targetDurationNanos].
         *
         * Returns null when unsupported, when the device's power HAL declines,
         * or when [threadIds] is empty. A null is not an error; it is the normal
         * outcome on most devices.
         *
         * [threadIds] must be **native** thread ids (`gettid`), not Java thread
         * ids. Passing the wrong ones is not rejected — the session simply hints
         * about threads that are doing nothing, which is the failure mode most
         * likely to make this look like it "did nothing" when it was never
         * pointed at the right work.
         */
        public fun createSession(threadIds: IntArray, targetDurationNanos: Long): PerfHintSession? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            if (threadIds.isEmpty()) return null
            val manager = manager() ?: return null
            return createSessionApi31(manager, threadIds, targetDurationNanos)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun createSessionApi31(
            manager: PerformanceHintManager,
            threadIds: IntArray,
            targetDurationNanos: Long,
        ): PerfHintSession? = manager
            .createHintSession(threadIds, targetDurationNanos)
            ?.let(::PerfHintSession)

        private fun manager(): PerformanceHintManager? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService()
            } else {
                null
            }
    }

/**
 * A live ADPF session.
 *
 * Wrapped rather than exposed directly so that call sites do not need an
 * `@RequiresApi` of their own and so the "we have not measured this" note has
 * somewhere to live that a reader will actually reach.
 */
@RequiresApi(Build.VERSION_CODES.S)
public class PerfHintSession internal constructor(
    private val delegate: PerformanceHintManager.Session,
) {
    /** Reports how long the last work unit actually took. */
    public fun reportActualWorkDuration(durationNanos: Long) {
        delegate.reportActualWorkDuration(durationNanos)
    }

    /** Changes the declared target. Cheap; call it when the workload changes shape. */
    public fun updateTargetWorkDuration(targetDurationNanos: Long) {
        delegate.updateTargetWorkDuration(targetDurationNanos)
    }

    /** Releases the session. Not releasing it leaks a HAL-side allocation. */
    public fun close() {
        delegate.close()
    }
}
