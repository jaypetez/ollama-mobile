package io.github.jaypetez.ollamamobile.ml

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * `PowerManager`'s thermal status, as a type instead of an int.
 *
 * The ordinal order is the platform's severity order, so `>=` comparisons are
 * meaningful and `THROTTLING_FLOOR` below is a single comparison.
 */
public enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,

    /** API < 29, or `getSystemService` returned nothing. Not a temperature. */
    UNKNOWN,
    ;

    /**
     * True at [LIGHT] or worse.
     *
     * `LIGHT` is deliberately the floor rather than `MODERATE`. The platform
     * defines `LIGHT` as the point at which the device has begun shedding
     * performance, and a benchmark repetition that ran through it is not
     * comparable to one that did not — even though a user would not notice.
     */
    public val isThrottling: Boolean get() = this != UNKNOWN && this >= LIGHT

    public companion object {
        /**
         * Maps `PowerManager.THERMAL_STATUS_*`.
         *
         * Unrecognised values map to [UNKNOWN] rather than to [NONE]: a future
         * platform level could add a status, and defaulting an unknown severity
         * to "cool" is the wrong direction to guess in.
         */
        public fun fromPlatform(value: Int): ThermalStatus = when (value) {
            PowerManager.THERMAL_STATUS_NONE -> NONE
            PowerManager.THERMAL_STATUS_LIGHT -> LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> SHUTDOWN
            else -> UNKNOWN
        }
    }
}

/**
 * A thermal reading at a point in time.
 *
 * [headroom] is `getThermalHeadroom`'s forecast: a normalised value where 1.0 is
 * the throttling threshold, so 0.8 means "20% of the budget left". It is null on
 * API < 30, on devices whose HAL does not implement it, and when it was polled
 * faster than the platform allows — all three are ordinary, and null must be
 * rendered as "not available" rather than as zero.
 */
public data class ThermalSnapshot(
    public val status: ThermalStatus,
    public val headroom: Float?,
    public val atMillis: Long,
)

/**
 * Turns thermal state into a thread count.
 *
 * ## Why fewer threads is the right response
 *
 * Sustained token generation heats a phone. The SoC's response is to drop
 * clocks, and once it does, the same thread count does less work while drawing
 * the same power. Backing off voluntarily keeps the cores that remain at a
 * higher clock, keeps the interactive responsiveness the user notices, and — the
 * part that matters — produces a *steady* token rate instead of one that stalls.
 * A visible stutter is worse than a uniformly slower stream.
 *
 * The reductions below are a policy, not a measurement. Nobody in this project
 * has measured the crossover point on real hardware, because there is no arm64
 * device here. They are chosen to be monotonic and conservative; treat the
 * specific fractions as a starting position for the benchmark harness to
 * replace, not as a tuned result.
 */
public object ThermalPolicy {
    /**
     * The thread count to run at given [baseThreads] and the current [status].
     *
     * Never returns less than 1. [baseThreads] should be the performance-core
     * count from [CpuTopology], not `availableProcessors()`.
     */
    public fun recommendedThreads(baseThreads: Int, status: ThermalStatus): Int {
        val base = baseThreads.coerceAtLeast(1)
        val scaled = when (status) {
            ThermalStatus.UNKNOWN, ThermalStatus.NONE -> base

            ThermalStatus.LIGHT -> base

            ThermalStatus.MODERATE -> base - 1

            ThermalStatus.SEVERE -> base / 2

            ThermalStatus.CRITICAL,
            ThermalStatus.EMERGENCY,
            ThermalStatus.SHUTDOWN,
            -> 1
        }
        return scaled.coerceIn(1, base)
    }

    /**
     * Whether generation should be paused outright.
     *
     * At `CRITICAL` the platform is already shutting subsystems down; continuing
     * to hold every core busy is how an app becomes the reason a phone powers
     * off. One thread is still allowed at `CRITICAL` so an in-flight response can
     * finish; from `EMERGENCY` upwards nothing new should start.
     */
    public fun shouldStopGenerating(status: ThermalStatus): Boolean =
        status >= ThermalStatus.EMERGENCY && status != ThermalStatus.UNKNOWN
}

/**
 * Observes thermal state.
 *
 * `addThermalStatusListener` (API 29) is the event source; there is no polling
 * of it, because the platform pushes. [ThermalSnapshot.headroom] comes from
 * `getThermalHeadroom` (API 30), which is null-guarded twice: by API level and
 * by rate. **The platform documents a minimum interval between calls and
 * returns `NaN` when called more often**, so [snapshot] refuses to call it more
 * than once a second and reuses the previous value in between. Calling it in a
 * tight loop is the documented way to get garbage out of it.
 */
@Singleton
public class ThermalMonitor
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * Injected as a property with a secondary constructor rather than as a
         * defaulted constructor parameter: Dagger does not see Kotlin default
         * arguments, so a `() -> Long = ...` in the `@Inject` constructor asks
         * the graph for a `Function0<Long>` binding that does not exist and
         * fails the whole build.
         */
        private var clock: () -> Long = System::currentTimeMillis

        internal constructor(context: Context, clock: () -> Long) : this(context) {
            this.clock = clock
        }

        private val powerManager: PowerManager? = context.getSystemService()

        @Volatile private var lastHeadroomAtMillis: Long = 0L

        @Volatile private var lastHeadroom: Float? = null

        /** The current status, or [ThermalStatus.UNKNOWN] below API 29. */
        public fun currentStatus(): ThermalStatus {
            val manager = powerManager ?: return ThermalStatus.UNKNOWN
            return ThermalStatus.fromPlatform(manager.currentThermalStatus)
        }

        /**
         * A reading now.
         *
         * Safe to call per benchmark repetition; the headroom rate limit is
         * enforced internally rather than by the caller remembering to.
         */
        public fun snapshot(): ThermalSnapshot = ThermalSnapshot(
            status = currentStatus(),
            headroom = headroom(),
            atMillis = clock(),
        )

        /**
         * Pushed thermal status changes, starting with the current value.
         *
         * Emits on the main looper because that is where the platform delivers
         * the callback; collectors that do real work should hop off it.
         */
        public fun statusChanges(): Flow<ThermalStatus> {
            val manager = powerManager ?: return flowOf(ThermalStatus.UNKNOWN)
            return callbackFlow {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    trySend(ThermalStatus.fromPlatform(status))
                }
                trySend(ThermalStatus.fromPlatform(manager.currentThermalStatus))
                manager.addThermalStatusListener(listener)
                awaitClose { manager.removeThermalStatusListener(listener) }
            }.distinctUntilChanged()
        }

        /**
         * The recommended thread count right now, given the device's own core
         * topology.
         */
        public fun recommendedThreads(capabilities: DeviceCapabilities): Int =
            ThermalPolicy.recommendedThreads(
                baseThreads = capabilities.topology.performanceCores,
                status = currentStatus(),
            )

        private fun headroom(): Float? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val manager = powerManager ?: return null
            val now = clock()
            if (now - lastHeadroomAtMillis < HEADROOM_MIN_INTERVAL_MILLIS) return lastHeadroom
            lastHeadroomAtMillis = now
            lastHeadroom = readHeadroom(manager)
            return lastHeadroom
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun readHeadroom(manager: PowerManager): Float? {
            val value = manager.getThermalHeadroom(HEADROOM_FORECAST_SECONDS)
            // NaN is the documented "not available / called too soon" answer, and
            // a NaN that escapes into an average silently poisons every number
            // downstream of it.
            return value.takeUnless { it.isNaN() }
        }

        private companion object {
            /**
             * The platform rate-limits `getThermalHeadroom`; once a second is the
             * documented safe cadence and anything faster returns NaN.
             */
            const val HEADROOM_MIN_INTERVAL_MILLIS = 1_000L

            /**
             * Forecast horizon in seconds. Ten seconds is roughly the length of
             * one generation burst, which is the window worth predicting.
             */
            const val HEADROOM_FORECAST_SECONDS = 10
        }
    }
