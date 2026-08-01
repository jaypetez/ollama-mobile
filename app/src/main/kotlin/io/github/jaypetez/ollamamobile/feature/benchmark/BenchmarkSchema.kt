package io.github.jaypetez.ollamamobile.feature.benchmark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The result-document schema version.
 *
 * ## Why this number is load-bearing
 *
 * Nightly runs accumulate history, and history is only comparable if the shape
 * of the document is stable. Adding an optional field is compatible and does not
 * bump this. Renaming, removing, or changing the unit or meaning of a field is
 * not, and must: a silently changed unit turns a regression detector into a
 * random-number generator, and nobody notices for weeks.
 *
 * Every field below carries its unit in its name for the same reason.
 */
public const val BENCHMARK_SCHEMA_VERSION: Int = 1

/**
 * One benchmark run, self-contained.
 *
 * Self-contained is the requirement: six months later a result file has to be
 * interpretable without the CI run, the git checkout or the person who produced
 * it. Everything needed to say "what was this, on what, built how" is in
 * [environment], and everything needed to say "under what settings" is in each
 * result's [BenchmarkResult.configuration].
 */
@Serializable
public data class BenchmarkDocument(
    @SerialName("schema_version")
    val schemaVersion: Int = BENCHMARK_SCHEMA_VERSION,
    val environment: BenchmarkEnvironment,
    val results: List<BenchmarkResult>,
)

/**
 * What produced the numbers.
 *
 * [caveat] is a field and not a comment on purpose. Someone will copy a result
 * out of this file into a chart, and the caveat has to travel inside the data
 * rather than alongside it.
 *
 * Nothing here may contain a server URL, a hostname or a token. A benchmark
 * document uploaded by an automated job is a genuine way to leak a credential.
 */
@Serializable
public data class BenchmarkEnvironment(
    @SerialName("started_at_epoch_millis") val startedAtEpochMillis: Long,
    @SerialName("device_manufacturer") val deviceManufacturer: String,
    @SerialName("device_model") val deviceModel: String,
    /** `Build.SOC_MODEL` on API 31+, otherwise `Build.HARDWARE`. */
    val soc: String,
    val abi: String,
    @SerialName("android_release") val androidRelease: String,
    @SerialName("sdk_int") val sdkInt: Int,
    /** True when the build fingerprint says emulator. Decides how to read everything else. */
    @SerialName("is_emulator") val isEmulator: Boolean,
    @SerialName("app_version_name") val appVersionName: String,
    @SerialName("app_version_code") val appVersionCode: Int,
    @SerialName("app_build_type") val appBuildType: String,
    /** `-Pollama.nativeSource`: `build`, `prebuilt` or `none`. */
    @SerialName("native_source") val nativeSource: String,
    /**
     * False means `StubLlamaEngine` answered.
     *
     * A document with `native_enabled: false` contains no inference measurement
     * at all, however plausible the numbers look. The consumer must check this
     * before reading a throughput field.
     */
    @SerialName("native_enabled") val nativeEnabled: Boolean,
    /** llama.cpp submodule SHA, or null when the submodule is not vendored. */
    @SerialName("llama_cpp_revision") val llamaCppRevision: String? = null,
    @SerialName("cpu_features") val cpuFeatures: List<String>,
    @SerialName("ggml_cpu_variant") val ggmlCpuVariant: String?,
    @SerialName("total_cores") val totalCores: Int,
    @SerialName("performance_cores") val performanceCores: Int,
    @SerialName("total_ram_bytes") val totalRamBytes: Long,
    @SerialName("mem_available_bytes_at_start") val memAvailableBytesAtStart: Long?,
    @SerialName("is_low_ram_device") val isLowRamDevice: Boolean,
    val caveat: String,
)

/**
 * The complete configuration of one measured cell.
 *
 * A throughput number without its configuration is not comparable to anything,
 * and the defaults will not be remembered. Every knob a sweep can vary is here,
 * including the ones a given run did not vary.
 */
@Serializable
public data class BenchmarkConfiguration(
    /** Human label for the sweep cell, e.g. `threads=4`. Never used as a key. */
    val label: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_file_name") val modelFileName: String,
    @SerialName("model_file_size_bytes") val modelFileSizeBytes: Long?,
    val quantization: String?,
    @SerialName("bits_per_weight") val bitsPerWeight: Double?,
    /** From `Quantization.kleidiAiAccelerated`. The matrix exists partly to test it. */
    @SerialName("kleidiai_accelerated") val kleidiAiAccelerated: Boolean?,
    @SerialName("context_tokens") val contextTokens: Int,
    @SerialName("batch_tokens") val batchTokens: Int,
    @SerialName("ubatch_tokens") val ubatchTokens: Int,
    val threads: Int,
    @SerialName("kv_cache_type_k") val kvCacheTypeK: String,
    @SerialName("kv_cache_type_v") val kvCacheTypeV: String,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("generate_tokens") val generateTokens: Int,
    @SerialName("warmup_iterations") val warmupIterations: Int,
    val repetitions: Int,
    val seed: Long,
) {
    /**
     * The identity a consumer compares on.
     *
     * Keyed comparison, never array position: configurations get added and
     * reordered, and a positional diff invents regressions when they do. The
     * label is excluded deliberately — renaming a label must not orphan its
     * history.
     */
    public fun key(): String = listOf(
        modelFileName,
        quantization.orEmpty(),
        contextTokens,
        batchTokens,
        ubatchTokens,
        threads,
        kvCacheTypeK,
        kvCacheTypeV,
        promptTokens,
        generateTokens,
    ).joinToString(separator = "/")
}

/**
 * One repetition.
 *
 * Thermal state is captured **before and after every repetition**, not once per
 * run. A sustained workload on a phone passes through several states, and the
 * same configuration measured at `NONE` and at `SEVERE` is two different
 * measurements. Without the per-repetition record, "it got slower halfway
 * through" stays a mystery.
 */
@Serializable
public data class BenchmarkRepetition(
    val index: Int,
    @SerialName("prompt_tokens") val promptTokens: Int?,
    @SerialName("generated_tokens") val generatedTokens: Int?,
    @SerialName("prompt_processing_nanos") val promptProcessingNanos: Long?,
    @SerialName("generation_nanos") val generationNanos: Long?,
    @SerialName("time_to_first_token_nanos") val timeToFirstTokenNanos: Long?,
    @SerialName("thermal_before") val thermalBefore: String,
    @SerialName("thermal_after") val thermalAfter: String,
    /** True when either end of the repetition was at LIGHT throttling or worse. */
    val throttled: Boolean,
    @SerialName("thermal_headroom_before") val thermalHeadroomBefore: Float?,
    @SerialName("thermal_headroom_after") val thermalHeadroomAfter: Float?,
    /**
     * Energy drawn over the repetition, in millijoules. Null when no counter
     * was available — **null, never zero**: a zero is eventually averaged into
     * something and treated as data.
     */
    @SerialName("energy_millijoules") val energyMilliJoules: Double?,
    /** Peak RSS from `/proc/self/status` `VmHWM`. Never the native heap counter. */
    @SerialName("peak_rss_bytes") val peakRssBytes: Long?,
    /** Non-null when this repetition failed; its measurements are then absent. */
    val failure: String? = null,
)

/** Derived figures for one configuration. Every field may be null. */
@Serializable
public data class BenchmarkSummary(
    @SerialName("prompt_processing_tokens_per_second_median")
    val promptProcessingTokensPerSecondMedian: Double?,
    @SerialName("prompt_processing_tokens_per_second_min")
    val promptProcessingTokensPerSecondMin: Double?,
    @SerialName("prompt_processing_tokens_per_second_max")
    val promptProcessingTokensPerSecondMax: Double?,
    @SerialName("token_generation_tokens_per_second_median")
    val tokenGenerationTokensPerSecondMedian: Double?,
    @SerialName("token_generation_tokens_per_second_min")
    val tokenGenerationTokensPerSecondMin: Double?,
    @SerialName("token_generation_tokens_per_second_max")
    val tokenGenerationTokensPerSecondMax: Double?,
    @SerialName("time_to_first_token_millis_median")
    val timeToFirstTokenMillisMedian: Double?,
    @SerialName("peak_rss_bytes") val peakRssBytes: Long?,
    /**
     * Energy per generated token, in millijoules.
     *
     * Arguably the metric that matters most on a phone, and the one most often
     * omitted. The configuration with the best tokens per second is frequently
     * not the one with the best tokens per joule.
     */
    @SerialName("energy_millijoules_per_token") val energyMilliJoulesPerToken: Double?,
    @SerialName("throttled_repetitions") val throttledRepetitions: Int,
    @SerialName("thermal_states_observed") val thermalStatesObserved: List<String>,
    /**
     * Median of the first third of repetitions over the median of the last
     * third. Below 1.0 means the device could not sustain its opening rate;
     * null when there are too few repetitions to say.
     */
    @SerialName("sustained_over_burst_ratio") val sustainedOverBurstRatio: Double?,
    @SerialName("failed_repetitions") val failedRepetitions: Int,
)

/** One configuration's load times, repetitions and summary. */
@Serializable
public data class BenchmarkResult(
    val configuration: BenchmarkConfiguration,
    /**
     * First load after the page cache was dropped, or after install.
     *
     * Measuring "cold" that is actually warm is the easiest mistake here and is
     * wrong by a large factor, so [loadCacheState] records how the caller
     * claims to have achieved it. The harness cannot verify the claim.
     */
    @SerialName("load_cold_nanos") val loadColdNanos: Long?,
    @SerialName("load_warm_nanos") val loadWarmNanos: Long?,
    @SerialName("load_cache_state") val loadCacheState: String,
    val repetitions: List<BenchmarkRepetition>,
    val summary: BenchmarkSummary,
)

/**
 * The flat shape `benchmark-action/github-action-benchmark` consumes.
 *
 * `[{ name, unit, value }, ...]` in `customSmallerIsBetter` mode. Flatter than
 * [BenchmarkDocument] by necessity, and derived from it rather than measured
 * separately — the rich document is the artefact, this is a projection for the
 * comparison step.
 *
 * `customSmallerIsBetter` means **every value must be one where lower is
 * better**. Throughput is therefore emitted as its reciprocal, in milliseconds
 * per token, not as tokens per second. Emitting a rate into a smaller-is-better
 * tracker inverts the alarm: a speed-up would be reported as a regression.
 */
@Serializable
public data class FlatBenchmarkMetric(
    val name: String,
    val unit: String,
    val value: Double,
    /** Rendered under the value in the job summary; carries the caveat with it. */
    val extra: String? = null,
)
