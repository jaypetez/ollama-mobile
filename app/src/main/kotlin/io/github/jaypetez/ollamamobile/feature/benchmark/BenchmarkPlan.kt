package io.github.jaypetez.ollamamobile.feature.benchmark

import io.github.jaypetez.ollamamobile.model.Quantization
import java.io.File

/** A GGUF file on the device that the harness can measure. */
public data class BenchmarkModel(
    public val file: File,
    public val quantization: Quantization? = Quantization.fromFileName(file.name),
) {
    public val displayName: String get() = file.name
}

/**
 * One measurable cell: a model plus every knob set to a definite value.
 *
 * There are no implicit defaults at this level. A cell that did not state its
 * batch size produces a result nobody can reproduce, and the sweeps below are
 * the only place defaults are allowed to live.
 */
public data class BenchmarkCell(
    public val label: String,
    public val model: BenchmarkModel,
    public val contextTokens: Int,
    public val batchTokens: Int,
    public val ubatchTokens: Int,
    public val threads: Int,
    public val kvCacheTypeK: String,
    public val kvCacheTypeV: String,
    public val promptTokens: Int,
    public val generateTokens: Int,
    public val warmupIterations: Int,
    public val repetitions: Int,
    public val seed: Long,
) {
    public fun toConfiguration(modelId: String): BenchmarkConfiguration = BenchmarkConfiguration(
        label = label,
        modelId = modelId,
        modelFileName = model.file.name,
        modelFileSizeBytes = model.file.length().takeIf { it > 0L },
        quantization = model.quantization?.label,
        bitsPerWeight = model.quantization?.bitsPerWeight,
        kleidiAiAccelerated = model.quantization?.kleidiAiAccelerated,
        contextTokens = contextTokens,
        batchTokens = batchTokens,
        ubatchTokens = ubatchTokens,
        threads = threads,
        kvCacheTypeK = kvCacheTypeK,
        kvCacheTypeV = kvCacheTypeV,
        promptTokens = promptTokens,
        generateTokens = generateTokens,
        warmupIterations = warmupIterations,
        repetitions = repetitions,
        seed = seed,
    )
}

/**
 * The sweeps, modelled on `llama-bench`.
 *
 * Three rules carried over from it, and one added:
 *
 * 1. **Prompt processing and token generation are measured separately.** They
 *    are different regimes — the first is compute-bound and scales with cores
 *    and batch size, the second is memory-bandwidth-bound at batch 1 and
 *    saturates early — and a single combined "tokens per second" conflates two
 *    numbers that respond oppositely to nearly every change.
 * 2. **Warm up, then measure.** JIT, page faults and ggml's weight repacking all
 *    make the first iterations unrepresentative.
 * 3. **Change one thing at a time.** A sweep that varies quantisation and thread
 *    count together says nothing about either, which is why each generator below
 *    varies exactly one axis.
 * 4. Added here: **record the thermal state around every repetition**, because a
 *    phone is not a workstation and llama-bench does not have to care.
 */
public object BenchmarkPlan {
    /** Prompt length for the prompt-processing measurement. */
    public const val DEFAULT_PROMPT_TOKENS: Int = 512

    /** Tokens generated per repetition for the generation measurement. */
    public const val DEFAULT_GENERATE_TOKENS: Int = 128

    public const val DEFAULT_BATCH_TOKENS: Int = 512
    public const val DEFAULT_UBATCH_TOKENS: Int = 512
    public const val DEFAULT_WARMUP_ITERATIONS: Int = 1
    public const val DEFAULT_REPETITIONS: Int = 6
    public const val DEFAULT_CONTEXT_TOKENS: Int = 4096

    /**
     * A fixed seed, so two runs of the same cell process the same prompt and
     * sample the same continuation. Without it, run-to-run variation includes
     * "the model said something longer this time", which is not a performance
     * difference.
     */
    public const val DEFAULT_SEED: Long = 20_260_801L

    /** f16 for both halves of the KV cache: llama.cpp's default, recorded explicitly. */
    public const val DEFAULT_KV_TYPE: String = "f16"

    /**
     * The quantisations the comparison matrix covers.
     *
     * `Q4_0` and `Q8_0` are KleidiAI-accelerated and the k-quants are not; the
     * matrix exists partly to find out how much that is worth in practice, since
     * k-quants get their own speed-up from ggml's runtime repacking. Nobody in
     * this project has measured which wins.
     */
    public val QUANTISATION_MATRIX: List<Quantization> = listOf(
        Quantization.Q2_K,
        Quantization.Q4_0,
        Quantization.Q4_K_M,
        Quantization.Q5_K_M,
        Quantization.Q8_0,
    )

    /**
     * Fixed thread counts every sweep includes, so two devices' sweeps overlap
     * somewhere even when their core counts do not.
     */
    private val FIXED_THREAD_COUNTS = listOf(1, 2, 4)

    /**
     * Context depths every sweep tries, in tokens. Powers of two from half a
     * kilotoken to sixteen, which spans "one question" to "a RAG context".
     */
    private val CONTEXT_DEPTHS = listOf(512, 2_048, 4_096, 8_192, 16_384)

    /** Thread counts to sweep, clamped to what the device actually has. */
    public fun threadSweep(performanceCores: Int, totalCores: Int): List<Int> =
        (FIXED_THREAD_COUNTS + performanceCores + totalCores)
            .filter { it in 1..totalCores.coerceAtLeast(1) }
            .distinct()
            .sorted()

    /**
     * Context depths to sweep.
     *
     * Depth matters because attention cost grows with the KV cache, so a rate
     * measured at an empty context is not the rate a user sees ten turns in.
     * Depths above the model's own trained context are dropped rather than
     * clamped — silently measuring 4096 while the label says 16384 is worse than
     * not measuring it.
     */
    public fun contextSweep(maxContextTokens: Int): List<Int> =
        CONTEXT_DEPTHS.filter { it <= maxContextTokens }

    /** One cell per thread count, everything else fixed. */
    public fun threadCells(
        model: BenchmarkModel,
        performanceCores: Int,
        totalCores: Int,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    ): List<BenchmarkCell> = threadSweep(performanceCores, totalCores).map { threads ->
        cell(label = "threads=$threads", model = model, threads = threads, contextTokens = contextTokens)
    }

    /** One cell per context depth, everything else fixed. */
    public fun contextCells(
        model: BenchmarkModel,
        threads: Int,
        maxContextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    ): List<BenchmarkCell> = contextSweep(maxContextTokens).map { context ->
        cell(label = "context=$context", model = model, threads = threads, contextTokens = context)
    }

    /**
     * One cell per quantisation of the same model.
     *
     * [available] is filtered by [QUANTISATION_MATRIX] rather than the other way
     * round: a matrix cell with no file on the device is omitted, not faked.
     */
    public fun quantisationCells(
        available: List<BenchmarkModel>,
        threads: Int,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    ): List<BenchmarkCell> = QUANTISATION_MATRIX.mapNotNull { quantization ->
        available
            .firstOrNull { it.quantization == quantization }
            ?.let { model ->
                cell(
                    label = "quant=${quantization.label}",
                    model = model,
                    threads = threads,
                    contextTokens = contextTokens,
                )
            }
    }

    private fun cell(
        label: String,
        model: BenchmarkModel,
        threads: Int,
        contextTokens: Int,
    ): BenchmarkCell = BenchmarkCell(
        label = label,
        model = model,
        contextTokens = contextTokens,
        batchTokens = DEFAULT_BATCH_TOKENS,
        ubatchTokens = DEFAULT_UBATCH_TOKENS,
        threads = threads,
        kvCacheTypeK = DEFAULT_KV_TYPE,
        kvCacheTypeV = DEFAULT_KV_TYPE,
        promptTokens = DEFAULT_PROMPT_TOKENS,
        generateTokens = DEFAULT_GENERATE_TOKENS,
        warmupIterations = DEFAULT_WARMUP_ITERATIONS,
        repetitions = DEFAULT_REPETITIONS,
        seed = DEFAULT_SEED,
    )
}
