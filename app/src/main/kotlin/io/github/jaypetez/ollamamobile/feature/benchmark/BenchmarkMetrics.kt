package io.github.jaypetez.ollamamobile.feature.benchmark

/**
 * The arithmetic behind every number in a [BenchmarkDocument].
 *
 * Pure functions, in one place, because this is the part where a plausible
 * result and a correct result look identical. A unit error here does not throw;
 * it publishes a number that is wrong by a factor of a thousand and stays in
 * the history until somebody notices the trend line makes no sense.
 *
 * Rules that hold throughout:
 *
 * * A missing input produces `null`, never `0.0`. A zero is eventually averaged
 *   into something and treated as data.
 * * A non-positive duration produces `null`. It means the clock did not move,
 *   not that the work was infinitely fast.
 */
public object BenchmarkMetrics {
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val NANOS_PER_MILLI = 1_000_000.0

    /**
     * Joules per watt-hour. Used to convert the battery counters, which are all
     * expressed in charge or energy *per hour*.
     */
    private const val JOULES_PER_WATT_HOUR = 3_600.0
    private const val MILLI = 1_000.0
    private const val MICRO = 1_000_000.0
    private const val NANO = 1_000_000_000.0

    /** Tokens per second, or null when either input is unusable. */
    public fun tokensPerSecond(tokens: Int?, durationNanos: Long?): Double? {
        if (tokens == null || tokens <= 0) return null
        if (durationNanos == null || durationNanos <= 0L) return null
        return tokens.toDouble() * NANOS_PER_SECOND / durationNanos.toDouble()
    }

    /** Nanoseconds as milliseconds, for the fields humans read. */
    public fun nanosToMillis(nanos: Long?): Double? =
        nanos?.takeIf { it >= 0L }?.let { it.toDouble() / NANOS_PER_MILLI }

    /**
     * Median. Even-sized inputs average the two middle values.
     *
     * Median rather than mean throughout, and reported next to min and max. On a
     * thermally constrained device the distribution is the interesting part, and
     * a mean hides the repetition that fell off a cliff.
     */
    public fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /**
     * Energy over a window, in millijoules, from `BATTERY_PROPERTY_ENERGY_COUNTER`.
     *
     * The counter is *remaining* energy in nanowatt-hours, so it decreases as
     * the device discharges and the consumed energy is `before - after`. A
     * negative result means the device gained charge — it was plugged in — and
     * is returned as null rather than as a negative energy, because a charging
     * device's power draw describes the charger.
     */
    public fun energyMilliJoulesFromNanoWattHours(
        beforeNanoWattHours: Long?,
        afterNanoWattHours: Long?,
    ): Double? {
        if (beforeNanoWattHours == null || afterNanoWattHours == null) return null
        val deltaNanoWattHours = beforeNanoWattHours - afterNanoWattHours
        if (deltaNanoWattHours <= 0L) return null
        // nWh -> Wh -> J -> mJ
        return deltaNanoWattHours.toDouble() / NANO * JOULES_PER_WATT_HOUR * MILLI
    }

    /**
     * Energy over a window, in millijoules, from `BATTERY_PROPERTY_CHARGE_COUNTER`.
     *
     * The coarser fallback for devices without an energy counter.
     * `charge (µAh) × voltage (mV)` is `1e-6 Ah × 1e-3 V = 1e-9 Wh`, hence the
     * scaling below. Voltage is sampled once and treated as constant across the
     * window, which is an approximation — a phone's terminal voltage sags under
     * load — so this is systematically a slight *under*-estimate of energy under
     * heavy draw. Coarse and honest beats absent.
     */
    public fun energyMilliJoulesFromChargeCounter(
        beforeMicroAmpHours: Long?,
        afterMicroAmpHours: Long?,
        voltageMilliVolts: Int?,
    ): Double? {
        if (beforeMicroAmpHours == null || afterMicroAmpHours == null) return null
        if (voltageMilliVolts == null || voltageMilliVolts <= 0) return null
        val deltaMicroAmpHours = beforeMicroAmpHours - afterMicroAmpHours
        if (deltaMicroAmpHours <= 0L) return null
        val wattHours = deltaMicroAmpHours.toDouble() * voltageMilliVolts.toDouble() / (MICRO * MILLI)
        return wattHours * JOULES_PER_WATT_HOUR * MILLI
    }

    /** Millijoules per generated token, or null when either side is missing. */
    public fun energyPerToken(energyMilliJoules: Double?, tokens: Int?): Double? {
        if (energyMilliJoules == null || energyMilliJoules <= 0.0) return null
        if (tokens == null || tokens <= 0) return null
        return energyMilliJoules / tokens.toDouble()
    }

    /**
     * Milliseconds per token, which is what a smaller-is-better tracker needs.
     *
     * Emitting tokens-per-second into `customSmallerIsBetter` inverts the alarm:
     * a genuine speed-up would be flagged as a regression and a genuine
     * regression would pass silently.
     */
    public fun millisPerToken(tokensPerSecond: Double?): Double? =
        tokensPerSecond?.takeIf { it > 0.0 }?.let { MILLI / it }

    /** Kilobytes as bytes. `/proc` reports memory in kB throughout. */
    public fun kilobytesToBytes(kilobytes: Long?): Long? = kilobytes?.times(BYTES_PER_KILOBYTE)

    /**
     * Sustained throughput over burst throughput.
     *
     * The first third of repetitions measures what the SoC can do; the last
     * third measures what it can do continuously. The ratio between them is a
     * device property worth reporting on its own — and it is the number an
     * emulator can never produce, because it does not throttle.
     *
     * Null below [MIN_REPETITIONS_FOR_RATIO] repetitions: with fewer, the two
     * thirds are the same one or two samples and the ratio is noise.
     */
    public fun sustainedOverBurstRatio(rates: List<Double>): Double? {
        if (rates.size < MIN_REPETITIONS_FOR_RATIO) return null
        val third = rates.size / THIRDS
        val burst = median(rates.take(third))?.takeIf { it > 0.0 } ?: return null
        val sustained = median(rates.takeLast(third)) ?: return null
        return sustained / burst
    }

    /** Builds the derived block for one configuration's repetitions. */
    public fun summarise(repetitions: List<BenchmarkRepetition>): BenchmarkSummary {
        val successful = repetitions.filter { it.failure == null }
        val promptRates = successful.mapNotNull {
            tokensPerSecond(it.promptTokens, it.promptProcessingNanos)
        }
        val generationRates = successful.mapNotNull {
            tokensPerSecond(it.generatedTokens, it.generationNanos)
        }
        val firstTokenMillis = successful.mapNotNull { nanosToMillis(it.timeToFirstTokenNanos) }

        val totalEnergy = successful.mapNotNull { it.energyMilliJoules }.takeIf { it.isNotEmpty() }?.sum()
        val totalTokens = successful.sumOf { it.generatedTokens ?: 0 }.takeIf { it > 0 }

        return BenchmarkSummary(
            promptProcessingTokensPerSecondMedian = median(promptRates),
            promptProcessingTokensPerSecondMin = promptRates.minOrNull(),
            promptProcessingTokensPerSecondMax = promptRates.maxOrNull(),
            tokenGenerationTokensPerSecondMedian = median(generationRates),
            tokenGenerationTokensPerSecondMin = generationRates.minOrNull(),
            tokenGenerationTokensPerSecondMax = generationRates.maxOrNull(),
            timeToFirstTokenMillisMedian = median(firstTokenMillis),
            // VmHWM is a high-water mark that never decreases within a process,
            // so the maximum across repetitions is the peak for the whole
            // configuration.
            peakRssBytes = successful.mapNotNull { it.peakRssBytes }.maxOrNull(),
            energyMilliJoulesPerToken = energyPerToken(totalEnergy, totalTokens),
            throttledRepetitions = repetitions.count { it.throttled },
            thermalStatesObserved = repetitions
                .flatMap { listOf(it.thermalBefore, it.thermalAfter) }
                .distinct()
                .sorted(),
            sustainedOverBurstRatio = sustainedOverBurstRatio(generationRates),
            failedRepetitions = repetitions.count { it.failure != null },
        )
    }

    private const val BYTES_PER_KILOBYTE = 1_024L

    /** The burst/sustained split compares the first third against the last. */
    private const val THIRDS = 3

    /** Below this, a burst/sustained split is comparing single samples. */
    public const val MIN_REPETITIONS_FOR_RATIO: Int = 6
}
