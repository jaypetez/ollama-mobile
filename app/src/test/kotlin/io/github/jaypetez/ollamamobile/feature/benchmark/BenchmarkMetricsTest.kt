package io.github.jaypetez.ollamamobile.feature.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BenchmarkMetricsTest {
    @Test
    fun `tokens per second converts from nanoseconds`() {
        // 100 tokens in 2 seconds.
        assertThat(BenchmarkMetrics.tokensPerSecond(100, 2_000_000_000L))
            .isWithin(TOLERANCE)
            .of(50.0)
    }

    @Test
    fun `a missing input yields null rather than zero`() {
        // A zero is eventually averaged into something and treated as data.
        assertThat(BenchmarkMetrics.tokensPerSecond(null, 1_000L)).isNull()
        assertThat(BenchmarkMetrics.tokensPerSecond(10, null)).isNull()
        assertThat(BenchmarkMetrics.tokensPerSecond(0, 1_000L)).isNull()
    }

    @Test
    fun `a non-positive duration yields null`() {
        // The clock did not move; the work was not infinitely fast.
        assertThat(BenchmarkMetrics.tokensPerSecond(10, 0L)).isNull()
        assertThat(BenchmarkMetrics.tokensPerSecond(10, -5L)).isNull()
    }

    @Test
    fun `median of an odd list is the middle value`() {
        assertThat(BenchmarkMetrics.median(listOf(5.0, 1.0, 3.0))).isEqualTo(3.0)
    }

    @Test
    fun `median of an even list averages the two middle values`() {
        assertThat(BenchmarkMetrics.median(listOf(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5)
    }

    @Test
    fun `median of an empty list is null`() {
        assertThat(BenchmarkMetrics.median(emptyList())).isNull()
    }

    @Test
    fun `energy from the nanowatt-hour counter converts to millijoules`() {
        // 1 Wh = 3600 J. A 1e9 nWh (= 1 Wh) drop is 3_600_000 mJ.
        val energy = BenchmarkMetrics.energyMilliJoulesFromNanoWattHours(
            beforeNanoWattHours = 1_000_000_000L,
            afterNanoWattHours = 0L,
        )

        assertThat(energy).isWithin(TOLERANCE).of(3_600_000.0)
    }

    @Test
    fun `energy is null when the counter went up`() {
        // The device gained charge, i.e. it was plugged in. A charging device's
        // power draw describes the charger.
        assertThat(
            BenchmarkMetrics.energyMilliJoulesFromNanoWattHours(
                beforeNanoWattHours = 100L,
                afterNanoWattHours = 200L,
            ),
        ).isNull()
    }

    @Test
    fun `energy from the charge counter uses voltage`() {
        // 1_000_000 µAh (= 1 Ah) at 3600 mV (= 3.6 V) is 3.6 Wh = 12_960 J.
        val energy = BenchmarkMetrics.energyMilliJoulesFromChargeCounter(
            beforeMicroAmpHours = 1_000_000L,
            afterMicroAmpHours = 0L,
            voltageMilliVolts = 3_600,
        )

        assertThat(energy).isWithin(1.0).of(12_960_000.0)
    }

    @Test
    fun `charge-counter energy is null without a voltage`() {
        assertThat(
            BenchmarkMetrics.energyMilliJoulesFromChargeCounter(1_000L, 0L, voltageMilliVolts = null),
        ).isNull()
        assertThat(
            BenchmarkMetrics.energyMilliJoulesFromChargeCounter(1_000L, 0L, voltageMilliVolts = 0),
        ).isNull()
    }

    @Test
    fun `energy per token divides by generated tokens`() {
        assertThat(BenchmarkMetrics.energyPerToken(1_000.0, 250)).isWithin(TOLERANCE).of(4.0)
        assertThat(BenchmarkMetrics.energyPerToken(null, 250)).isNull()
        assertThat(BenchmarkMetrics.energyPerToken(1_000.0, 0)).isNull()
    }

    @Test
    fun `millis per token inverts a rate`() {
        // 20 tok/s is 50 ms/token. The inversion is what keeps a
        // smaller-is-better tracker from reporting speed-ups as regressions.
        assertThat(BenchmarkMetrics.millisPerToken(20.0)).isWithin(TOLERANCE).of(50.0)
        assertThat(BenchmarkMetrics.millisPerToken(0.0)).isNull()
        assertThat(BenchmarkMetrics.millisPerToken(null)).isNull()
    }

    @Test
    fun `kilobytes convert with the binary factor`() {
        assertThat(BenchmarkMetrics.kilobytesToBytes(1L)).isEqualTo(1_024L)
        assertThat(BenchmarkMetrics.kilobytesToBytes(null)).isNull()
    }

    @Test
    fun `sustained over burst compares the first and last thirds`() {
        // Six repetitions decaying from 20 to 10 tok/s.
        val rates = listOf(20.0, 20.0, 16.0, 14.0, 10.0, 10.0)

        val ratio = BenchmarkMetrics.sustainedOverBurstRatio(rates)

        assertThat(ratio).isWithin(TOLERANCE).of(0.5)
    }

    @Test
    fun `sustained over burst is null with too few repetitions`() {
        assertThat(BenchmarkMetrics.sustainedOverBurstRatio(listOf(1.0, 2.0, 3.0))).isNull()
    }

    @Test
    fun `a summary derives every field from the repetitions`() {
        val summary = BenchmarkMetrics.summarise(
            listOf(
                repetition(index = 0, generated = 100, generationNanos = 5_000_000_000L),
                repetition(index = 1, generated = 100, generationNanos = 10_000_000_000L),
            ),
        )

        // 20 tok/s and 10 tok/s.
        assertThat(summary.tokenGenerationTokensPerSecondMedian).isWithin(TOLERANCE).of(15.0)
        assertThat(summary.tokenGenerationTokensPerSecondMin).isWithin(TOLERANCE).of(10.0)
        assertThat(summary.tokenGenerationTokensPerSecondMax).isWithin(TOLERANCE).of(20.0)
        assertThat(summary.failedRepetitions).isEqualTo(0)
        assertThat(summary.throttledRepetitions).isEqualTo(0)
        assertThat(summary.thermalStatesObserved).containsExactly("NONE")
    }

    @Test
    fun `peak rss is the maximum across repetitions`() {
        val summary = BenchmarkMetrics.summarise(
            listOf(
                repetition(index = 0, peakRss = 1_000L),
                repetition(index = 1, peakRss = 4_000L),
                repetition(index = 2, peakRss = 2_000L),
            ),
        )

        // VmHWM never decreases within a process, so the maximum is the peak for
        // the whole configuration.
        assertThat(summary.peakRssBytes).isEqualTo(4_000L)
    }

    @Test
    fun `a failed repetition is counted and excluded from the rates`() {
        val summary = BenchmarkMetrics.summarise(
            listOf(
                repetition(index = 0, generated = 100, generationNanos = 5_000_000_000L),
                repetition(index = 1, failure = "OutOfMemoryError"),
            ),
        )

        assertThat(summary.failedRepetitions).isEqualTo(1)
        assertThat(summary.tokenGenerationTokensPerSecondMedian).isWithin(TOLERANCE).of(20.0)
    }

    @Test
    fun `a throttled repetition is flagged`() {
        val summary = BenchmarkMetrics.summarise(
            listOf(
                repetition(index = 0),
                repetition(index = 1, thermalAfter = "MODERATE", throttled = true),
            ),
        )

        assertThat(summary.throttledRepetitions).isEqualTo(1)
        assertThat(summary.thermalStatesObserved).containsExactly("MODERATE", "NONE")
    }

    @Test
    fun `energy per token sums energy over successful repetitions`() {
        val summary = BenchmarkMetrics.summarise(
            listOf(
                repetition(index = 0, generated = 50, energy = 500.0),
                repetition(index = 1, generated = 50, energy = 500.0),
            ),
        )

        // 1000 mJ over 100 tokens.
        assertThat(summary.energyMilliJoulesPerToken).isWithin(TOLERANCE).of(10.0)
    }

    @Test
    fun `energy per token is null when no repetition measured energy`() {
        val summary = BenchmarkMetrics.summarise(listOf(repetition(index = 0, generated = 50)))

        assertThat(summary.energyMilliJoulesPerToken).isNull()
    }

    @Suppress("LongParameterList")
    private fun repetition(
        index: Int,
        generated: Int? = null,
        generationNanos: Long? = null,
        peakRss: Long? = null,
        energy: Double? = null,
        thermalAfter: String = "NONE",
        throttled: Boolean = false,
        failure: String? = null,
    ) = BenchmarkRepetition(
        index = index,
        promptTokens = null,
        generatedTokens = generated,
        promptProcessingNanos = null,
        generationNanos = generationNanos,
        timeToFirstTokenNanos = null,
        thermalBefore = "NONE",
        thermalAfter = thermalAfter,
        throttled = throttled,
        thermalHeadroomBefore = null,
        thermalHeadroomAfter = null,
        energyMilliJoules = energy,
        peakRssBytes = peakRss,
        failure = failure,
    )

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
