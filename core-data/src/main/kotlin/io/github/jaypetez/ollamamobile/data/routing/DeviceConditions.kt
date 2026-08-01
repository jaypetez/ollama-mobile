package io.github.jaypetez.ollamamobile.data.routing

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How hot the device says it is.
 *
 * Mapped from `PowerManager.getCurrentThermalStatus()` rather than used raw so
 * that the router does not have to know the platform constants, and so that a
 * status this build has never heard of degrades to [UNKNOWN] instead of being
 * compared numerically against a constant that has since been renumbered.
 */
enum class ThermalState {
    /** Nothing is being throttled. */
    NOMINAL,

    /** Light throttling. Sustained work is still reasonable. */
    FAIR,

    /** The platform is actively shedding clock speed. Local inference gets slower *and* makes it worse. */
    SERIOUS,

    /** Throttling to protect the hardware, or shutting down. Nothing heavy may start. */
    CRITICAL,

    /** No reading available. Never treated as a reason to refuse anything. */
    UNKNOWN,
}

/**
 * The physical state of the phone at the moment a routing decision is made.
 *
 * Every field is nullable-or-unknown-tolerant on purpose: an emulator, a
 * Robolectric test and a device whose vendor HAL does not implement thermal
 * reporting all produce partial answers, and a router that refused to run
 * locally because it could not read the battery would be broken on exactly the
 * devices that are hardest to debug on.
 */
data class DeviceConditions(
    /** 0..100, or null when the platform did not report one. */
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val thermal: ThermalState = ThermalState.UNKNOWN,
) {
    /**
     * True when running a model on this device is a bad idea for the battery.
     *
     * Charging overrides the level entirely: the cost being avoided is the
     * user's remaining runtime, and a phone on a charger has none to lose.
     */
    val batteryConstrained: Boolean
        get() = !charging && batteryPercent != null && batteryPercent <= LOW_BATTERY_PERCENT

    companion object {
        /** Below this, and off the charger, on-device work is deprioritised. */
        const val LOW_BATTERY_PERCENT: Int = 20

        /** What a caller with no reading uses. Costs a candidate nothing. */
        val Unknown: DeviceConditions = DeviceConditions()
    }
}

/**
 * Reads [DeviceConditions] from the platform.
 *
 * Polled at routing time rather than observed with a `BroadcastReceiver`: the
 * value is wanted once per request, a registered receiver for
 * `ACTION_BATTERY_CHANGED` wakes the process on every percentage change, and
 * `BATTERY_PROPERTY_CAPACITY` answers from the fuel gauge without one.
 */
@Singleton
class DeviceStateProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        fun current(): DeviceConditions {
            val battery = context.getSystemService(BatteryManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            return DeviceConditions(
                // The gauge returns Integer.MIN_VALUE — not an exception — when
                // the property is unsupported, so the range check is what turns
                // "unsupported" into "unknown" rather than into -2147483648%.
                batteryPercent = battery
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?.takeIf { it in 0..PERCENT_MAX },
                charging = battery?.isCharging == true,
                thermal = power?.currentThermalStatus.toThermalState(),
            )
        }

        private companion object {
            const val PERCENT_MAX = 100
        }
    }

/**
 * Collapses the platform's seven thermal levels onto the four the router acts
 * on.
 *
 * `LIGHT` and `MODERATE` are one decision ("mild penalty") and `SEVERE`
 * upwards is another ("do not start heavy work"), so distinguishing all seven
 * would only spread one rule across more branches.
 */
internal fun Int?.toThermalState(): ThermalState = when (this) {
    null -> ThermalState.UNKNOWN

    PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL

    PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR

    PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS

    PowerManager.THERMAL_STATUS_CRITICAL,
    PowerManager.THERMAL_STATUS_EMERGENCY,
    PowerManager.THERMAL_STATUS_SHUTDOWN,
    -> ThermalState.CRITICAL

    else -> ThermalState.UNKNOWN
}
