package io.github.jaypetez.ollamamobile.feature.benchmark

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Serialises a [BenchmarkDocument] and derives the flat file CI consumes.
 *
 * Two outputs, deliberately:
 *
 * * `benchmark-result.json` — the self-describing document. This is the
 *   artefact. It is interpretable six months later without the run that produced
 *   it.
 * * `benchmark-results.json` — the flat `[{name, unit, value}]` array that
 *   `benchmark-action/github-action-benchmark` reads in `customSmallerIsBetter`
 *   mode. Derived from the first, never measured separately, because two
 *   independently produced views of the same run eventually disagree.
 *
 * The flat file's name matches the `RESULT_JSON` path in
 * `.github/workflows/nightly-benchmark.yml`.
 */
@Singleton
public class BenchmarkResultStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * `prettyPrint` because a human reads this in an artifact browser, and
         * `encodeDefaults` because a consumer diffing two documents must see the
         * schema version and every explicitly-null measurement rather than
         * inferring absence from a missing key.
         */
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
        }

        public fun encode(document: BenchmarkDocument): String = json.encodeToString(document)

        public fun decode(text: String): BenchmarkDocument = json.decodeFromString(text)

        public fun encodeFlat(metrics: List<FlatBenchmarkMetric>): String = json.encodeToString(metrics)

        /** Writes both files under `filesDir/benchmark` and returns the rich one. */
        public fun write(document: BenchmarkDocument): File? = try {
            val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            val rich = File(directory, DOCUMENT_FILE_NAME)
            rich.writeText(encode(document))
            File(directory, FLAT_FILE_NAME).writeText(encodeFlat(toFlatMetrics(document)))
            rich
        } catch (_: IOException) {
            null
        }

        public companion object {
            public const val DIRECTORY: String = "benchmark"
            public const val DOCUMENT_FILE_NAME: String = "benchmark-result.json"

            /** Matches `RESULT_JSON` in `.github/workflows/nightly-benchmark.yml`. */
            public const val FLAT_FILE_NAME: String = "benchmark-results.json"

            /**
             * Projects the document onto the flat smaller-is-better schema.
             *
             * Every emitted value is one where **lower is better**. Throughput is
             * therefore inverted to milliseconds per token before it is emitted:
             * putting tokens-per-second into a smaller-is-better tracker reports
             * every speed-up as a regression and lets every real regression pass.
             *
             * Absent measurements are omitted, never emitted as zero. A zero in a
             * trend series becomes the new baseline and quietly destroys the
             * history it lands in.
             */
            public fun toFlatMetrics(document: BenchmarkDocument): List<FlatBenchmarkMetric> =
                document.results.flatMap { result ->
                    val key = result.configuration.key()
                    val extra = document.environment.caveat
                    buildList {
                        BenchmarkMetrics
                            .millisPerToken(result.summary.promptProcessingTokensPerSecondMedian)
                            ?.let { add(FlatBenchmarkMetric("$key prompt_processing", "ms/token", it, extra)) }
                        BenchmarkMetrics
                            .millisPerToken(result.summary.tokenGenerationTokensPerSecondMedian)
                            ?.let { add(FlatBenchmarkMetric("$key token_generation", "ms/token", it, extra)) }
                        result.summary.timeToFirstTokenMillisMedian
                            ?.let { add(FlatBenchmarkMetric("$key time_to_first_token", "ms", it, extra)) }
                        result.loadColdNanos
                            ?.let { BenchmarkMetrics.nanosToMillis(it) }
                            ?.let { add(FlatBenchmarkMetric("$key load_cold", "ms", it, extra)) }
                        result.loadWarmNanos
                            ?.let { BenchmarkMetrics.nanosToMillis(it) }
                            ?.let { add(FlatBenchmarkMetric("$key load_warm", "ms", it, extra)) }
                        result.summary.peakRssBytes
                            ?.let { add(FlatBenchmarkMetric("$key peak_rss", "bytes", it.toDouble(), extra)) }
                        result.summary.energyMilliJoulesPerToken
                            ?.let { add(FlatBenchmarkMetric("$key energy_per_token", "mJ/token", it, extra)) }
                    }
                }
        }
    }
