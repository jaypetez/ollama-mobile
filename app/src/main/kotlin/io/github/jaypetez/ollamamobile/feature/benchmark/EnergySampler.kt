package io.github.jaypetez.ollamamobile.feature.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A battery counter reading at a point in time. */
public data class EnergyReading(
    /** `BATTERY_PROPERTY_ENERGY_COUNTER`, remaining energy in nanowatt-hours. */
    public val energyNanoWattHours: Long?,
    /** `BATTERY_PROPERTY_CHARGE_COUNTER`, remaining charge in microamp-hours. */
    public val chargeMicroAmpHours: Long?,
    /** Terminal voltage in millivolts, from the sticky battery broadcast. */
    public val voltageMilliVolts: Int?,
    /**
     * True when the device was plugged in.
     *
     * A measurement taken while charging describes the charger, not the
     * workload, and must be discarded rather than corrected.
     */
    public val isCharging: Boolean,
) {
    /** True when neither counter answered, i.e. energy cannot be measured at all. */
    public val isEmpty: Boolean
        get() = energyNanoWattHours == null && chargeMicroAmpHours == null
}

/**
 * Energy per token, from `BatteryManager` counter deltas.
 *
 * ## Why this is here at all
 *
 * On a battery-powered device, joules per token is arguably the metric that
 * matters most, and it is the one benchmark harnesses almost always omit. The
 * configuration with the highest tokens per second is frequently not the one
 * with the best tokens per joule — running every core flat out wins the first
 * and loses the second — and on a phone the second number is often the one that
 * should decide.
 *
 * ## What it can and cannot see
 *
 * `androidx.benchmark`'s `PowerMetric` reads on-device power rails and is
 * strictly better where it exists, which is API 31+ on hardware that exposes
 * the rails: Pixels do, most others do not. That path belongs to the
 * macrobenchmark module. This is the in-app fallback, and it is coarser:
 *
 * * `ENERGY_COUNTER` is nanowatt-hours remaining and is the good case. Many
 *   devices return `Long.MIN_VALUE` for it, which is the platform's "not
 *   supported" and is filtered out below.
 * * `CHARGE_COUNTER` (microamp-hours) times terminal voltage is the fallback.
 *   Voltage is sampled once per reading and treated as constant, which
 *   under-estimates slightly under heavy load as the terminal voltage sags.
 * * Both counters update at the fuel gauge's own cadence — typically seconds,
 *   sometimes tens of seconds. A window shorter than that reads zero delta,
 *   which is reported as *no measurement*, not as zero energy.
 *
 * **An emulator has neither counter and no rails.** Energy is absent in every
 * nightly document and that is correct.
 */
@Singleton
public class EnergySampler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val batteryManager: BatteryManager? = context.getSystemService()

        /** Reads both counters and the current voltage. Cheap; safe per repetition. */
        public fun read(): EnergyReading {
            val manager = batteryManager
            val battery = batteryStatusIntent()
            return EnergyReading(
                energyNanoWattHours = manager?.property(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
                chargeMicroAmpHours = manager?.property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                voltageMilliVolts = battery
                    ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, INVALID)
                    ?.takeIf { it > 0 },
                isCharging = battery
                    ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    ?.let { it != 0 } == true,
            )
        }

        /**
         * Energy consumed between two readings, in millijoules, or null.
         *
         * Prefers the energy counter and falls back to charge times voltage.
         * Returns null while charging: there is no honest way to separate the
         * workload's draw from the charger's supply.
         */
        public fun energyMilliJoules(before: EnergyReading, after: EnergyReading): Double? {
            if (before.isCharging || after.isCharging) return null
            return BenchmarkMetrics.energyMilliJoulesFromNanoWattHours(
                beforeNanoWattHours = before.energyNanoWattHours,
                afterNanoWattHours = after.energyNanoWattHours,
            ) ?: BenchmarkMetrics.energyMilliJoulesFromChargeCounter(
                beforeMicroAmpHours = before.chargeMicroAmpHours,
                afterMicroAmpHours = after.chargeMicroAmpHours,
                // The later voltage is the one measured under load.
                voltageMilliVolts = after.voltageMilliVolts ?: before.voltageMilliVolts,
            )
        }

        /**
         * `getLongProperty` returns `Long.MIN_VALUE` for an unsupported property
         * rather than throwing, and that sentinel arithmetically overflows into
         * a plausible-looking delta if it is not filtered here.
         */
        private fun BatteryManager.property(id: Int): Long? =
            getLongProperty(id).takeIf { it != Long.MIN_VALUE && it != INVALID.toLong() }

        /**
         * The sticky `ACTION_BATTERY_CHANGED` broadcast. A null receiver returns
         * the last value without registering anything.
         */
        private fun batteryStatusIntent(): Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )

        private companion object {
            const val INVALID = -1
        }
    }
