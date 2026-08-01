package io.github.jaypetez.ollamamobile.feature.benchmark

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The JSON schema round-trip.
 *
 * The schema is a compatibility burden the moment nightly history starts
 * accumulating: history is only comparable if the shape is stable. These tests
 * are what makes a rename fail loudly instead of silently orphaning a trend
 * line.
 */
@RunWith(RobolectricTestRunner::class)
class BenchmarkDocumentTest {
    private val store =
        BenchmarkResultStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a document survives a round trip unchanged`() {
        val document = sampleDocument()

        val decoded = store.decode(store.encode(document))

        assertThat(decoded).isEqualTo(document)
    }

    @Test
    fun `the encoded document carries the schema version`() {
        val encoded = store.encode(sampleDocument())

        assertThat(encoded).contains("\"schema_version\": $BENCHMARK_SCHEMA_VERSION")
    }

    @Test
    fun `the caveat travels inside the document`() {
        // A field and not a comment, because someone will copy a table out of a
        // job summary and the caveat has to travel with it.
        val encoded = store.encode(sampleDocument())

        assertThat(encoded).contains("no arm64 test device")
        assertThat(encoded).contains("native_enabled")
    }

    @Test
    fun `absent measurements are encoded as null rather than omitted`() {
        // A consumer diffing two documents must be able to tell "not measured"
        // from "key added later".
        val encoded = store.encode(sampleDocument())

        assertThat(encoded).contains("\"energy_millijoules\": null")
    }

    @Test
    fun `a configuration key is stable and excludes the label`() {
        val configuration = sampleConfiguration()

        assertThat(configuration.key()).isEqualTo(configuration.copy(label = "renamed").key())
        assertThat(configuration.key()).contains("model-q4_k_m.gguf")
    }

    @Test
    fun `flat metrics invert throughput into milliseconds per token`() {
        val document = sampleDocument(
            summary = summary(generationRate = 20.0, promptRate = 200.0),
        )

        val flat = BenchmarkResultStore.toFlatMetrics(document)

        val generation = flat.single { it.name.endsWith("token_generation") }
        // 20 tok/s -> 50 ms/token. Emitting the rate into a smaller-is-better
        // tracker would report every speed-up as a regression.
        assertThat(generation.value).isWithin(TOLERANCE).of(50.0)
        assertThat(generation.unit).isEqualTo("ms/token")
    }

    @Test
    fun `flat metrics omit absent measurements rather than emitting zero`() {
        val flat = BenchmarkResultStore.toFlatMetrics(sampleDocument())

        // A zero in a trend series becomes the new baseline and quietly
        // destroys the history it lands in.
        assertThat(flat.map { it.name }).doesNotContain("energy_per_token")
        assertThat(flat.none { it.value == 0.0 }).isTrue()
    }

    @Test
    fun `every flat metric carries the caveat`() {
        val flat = BenchmarkResultStore.toFlatMetrics(
            sampleDocument(summary = summary(generationRate = 10.0)),
        )

        assertThat(flat).isNotEmpty()
        flat.forEach { metric -> assertThat(metric.extra).contains("Relative regression signal only") }
    }

    @Test
    fun `the flat projection round-trips through json`() {
        val flat = BenchmarkResultStore.toFlatMetrics(
            sampleDocument(summary = summary(generationRate = 10.0)),
        )

        val encoded = store.encodeFlat(flat)

        // github-action-benchmark's customSmallerIsBetter shape.
        assertThat(encoded).contains("\"name\"")
        assertThat(encoded).contains("\"unit\"")
        assertThat(encoded).contains("\"value\"")
    }

    @Test
    fun `the store writes both files`() {
        val written = store.write(sampleDocument())

        assertThat(written).isNotNull()
        assertThat(written?.name).isEqualTo(BenchmarkResultStore.DOCUMENT_FILE_NAME)
        val flat = written?.parentFile?.resolve(BenchmarkResultStore.FLAT_FILE_NAME)
        assertThat(flat?.exists()).isTrue()
    }

    private fun sampleConfiguration() = BenchmarkConfiguration(
        label = "threads=4",
        modelId = "model-q4_k_m.gguf",
        modelFileName = "model-q4_k_m.gguf",
        modelFileSizeBytes = 1_234_567L,
        quantization = "Q4_K_M",
        bitsPerWeight = 4.85,
        kleidiAiAccelerated = false,
        contextTokens = 4096,
        batchTokens = 512,
        ubatchTokens = 512,
        threads = 4,
        kvCacheTypeK = "f16",
        kvCacheTypeV = "f16",
        promptTokens = 512,
        generateTokens = 128,
        warmupIterations = 1,
        repetitions = 6,
        seed = 20_260_801L,
    )

    private fun summary(
        generationRate: Double? = null,
        promptRate: Double? = null,
    ) = BenchmarkSummary(
        promptProcessingTokensPerSecondMedian = promptRate,
        promptProcessingTokensPerSecondMin = promptRate,
        promptProcessingTokensPerSecondMax = promptRate,
        tokenGenerationTokensPerSecondMedian = generationRate,
        tokenGenerationTokensPerSecondMin = generationRate,
        tokenGenerationTokensPerSecondMax = generationRate,
        timeToFirstTokenMillisMedian = null,
        peakRssBytes = null,
        energyMilliJoulesPerToken = null,
        throttledRepetitions = 0,
        thermalStatesObserved = listOf("NONE"),
        sustainedOverBurstRatio = null,
        failedRepetitions = 0,
    )

    private fun sampleDocument(summary: BenchmarkSummary = summary()) = BenchmarkDocument(
        environment = BenchmarkEnvironment(
            startedAtEpochMillis = 1_700_000_000_000L,
            deviceManufacturer = "unknown",
            deviceModel = "robolectric",
            soc = "unknown",
            abi = "arm64-v8a",
            androidRelease = "14",
            sdkInt = 34,
            isEmulator = true,
            appVersionName = "0.1.0",
            appVersionCode = 100,
            appBuildType = "debug",
            nativeSource = "none",
            nativeEnabled = false,
            llamaCppRevision = null,
            cpuFeatures = listOf("DOTPROD"),
            ggmlCpuVariant = "libggml-cpu-android_armv8.2_1.so",
            totalCores = 8,
            performanceCores = 4,
            totalRamBytes = 8L * 1024 * 1024 * 1024,
            memAvailableBytesAtStart = 4L * 1024 * 1024 * 1024,
            isLowRamDevice = false,
            caveat = BenchmarkRunner.CAVEAT,
        ),
        results = listOf(
            BenchmarkResult(
                configuration = sampleConfiguration(),
                loadColdNanos = 2_000_000_000L,
                loadWarmNanos = 400_000_000L,
                loadCacheState = LoadCacheState.UNKNOWN.name,
                repetitions = listOf(
                    BenchmarkRepetition(
                        index = 0,
                        promptTokens = 512,
                        generatedTokens = 128,
                        promptProcessingNanos = 1_000_000_000L,
                        generationNanos = 6_400_000_000L,
                        timeToFirstTokenNanos = 1_100_000_000L,
                        thermalBefore = "NONE",
                        thermalAfter = "NONE",
                        throttled = false,
                        thermalHeadroomBefore = null,
                        thermalHeadroomAfter = null,
                        energyMilliJoules = null,
                        peakRssBytes = 1_500_000_000L,
                        failure = null,
                    ),
                ),
                summary = summary,
            ),
        ),
    )

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
