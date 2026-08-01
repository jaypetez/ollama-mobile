package io.github.jaypetez.ollamamobile.model

import java.util.Locale

/**
 * The answer to "can this device load this model right now?".
 *
 * The thresholds that separate the three cases are policy and live in
 * `:core-ml`; this type only carries the outcome and the numbers behind it.
 * [explain] exists because a refusal with a number and a fix is a good
 * experience and a bare "cannot load this model" is not.
 */
public sealed interface MemoryVerdict {
    /** Human-readable, already unit-formatted, safe to show in the UI. */
    public fun explain(): String

    /** Whether a load should be attempted at all. */
    public val allowsLoad: Boolean
        get() = this !is Refuse

    /** Comfortably below available memory. Load without comment. */
    public data class Fits(
        public val headroomBytes: Long,
    ) : MemoryVerdict {
        public override fun explain(): String = "Fits with ${formatBytes(headroomBytes)} to spare."
    }

    /**
     * Below available memory but inside the margin where a background app
     * waking up can tip it over. Load, but say what to change — [reason] must
     * be specific enough to act on.
     */
    public data class Tight(
        public val headroomBytes: Long,
        public val reason: String,
    ) : MemoryVerdict {
        public override fun explain(): String =
            "Tight: only ${formatBytes(headroomBytes)} of headroom. $reason"
    }

    /** Does not fit. Do not attempt the load; an OOM kill teaches the user nothing. */
    public data class Refuse(
        public val requiredBytes: Long,
        public val availableBytes: Long,
        public val reason: String,
    ) : MemoryVerdict {
        /** How much more memory would be needed; never negative. */
        public val shortfallBytes: Long
            get() = (requiredBytes - availableBytes).coerceAtLeast(0L)

        public override fun explain(): String =
            "Needs ${formatBytes(requiredBytes)} but only ${formatBytes(availableBytes)} is available " +
                "(${formatBytes(shortfallBytes)} short). $reason"
    }
}

/**
 * IEC units, because this is memory and the platform reports it in KiB.
 *
 * [Locale.ROOT] is not optional: on a French device the default locale renders
 * "1,5 GiB", which then fails every string assertion and reads oddly next to
 * the app's other numbers.
 */
internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) {
        "$bytes B"
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
