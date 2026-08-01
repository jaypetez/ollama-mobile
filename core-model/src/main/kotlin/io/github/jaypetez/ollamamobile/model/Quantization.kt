package io.github.jaypetez.ollamamobile.model

/**
 * A GGUF quantisation format.
 *
 * [bitsPerWeight] is the effective average across a whole model, including the
 * per-block scale and min metadata that k-quants carry, so it is a little
 * higher than the name suggests: `Q4_K_M` averages ~4.85 bpw, not 4.0. It is
 * what a size estimate should multiply parameter count by.
 *
 * The ordering of this enum is meaningful: entries run from smallest/lowest
 * quality to largest/highest, so `compareTo` and `sorted()` behave sensibly in
 * the model picker.
 */
public enum class Quantization(
    public val label: String,
    public val bitsPerWeight: Double,
) {
    Q2_K("Q2_K", 3.35),
    Q3_K_S("Q3_K_S", 3.50),
    Q3_K_M("Q3_K_M", 3.89),
    Q3_K_L("Q3_K_L", 4.27),
    Q4_0("Q4_0", 4.55),
    Q4_K_S("Q4_K_S", 4.58),
    Q4_K_M("Q4_K_M", 4.85),
    Q5_0("Q5_0", 5.54),
    Q5_K_S("Q5_K_S", 5.52),
    Q5_K_M("Q5_K_M", 5.69),
    Q6_K("Q6_K", 6.56),
    Q8_0("Q8_0", 8.50),
    F16("F16", 16.0),
    F32("F32", 32.0),
    ;

    /**
     * Whether ARM KleidiAI kernels can accelerate this format.
     *
     * Only the legacy linear quants and the float formats are covered. K-quants
     * — including the common default `Q4_K_M` — are NOT accelerated by
     * KleidiAI; their speed-up comes from ggml's own runtime repacking
     * (`GGML_CPU_REPACK`) instead. Surfacing this honestly in the model picker
     * is the difference between a user choosing Q4_0 for a real reason and
     * choosing it because of a vague claim.
     */
    public val kleidiAiAccelerated: Boolean
        get() = this == Q4_0 || this == Q8_0 || this == F16 || this == F32

    /** Estimated weight bytes for a model with [parameterCount] parameters. */
    public fun estimateWeightBytes(parameterCount: Long): Long =
        (parameterCount.toDouble() * bitsPerWeight / 8.0).toLong()

    public companion object {
        /**
         * Parses the quantisation out of a GGUF filename or an Ollama tag, e.g.
         * `qwen3-1.7b-instruct-q4_k_m.gguf` or `llama3.2:3b-instruct-q8_0`.
         *
         * Matching is longest-first so `Q4_K_M` wins over `Q4_K_S` and neither
         * is mistaken for `Q4_0`.
         */
        public fun fromFileName(name: String): Quantization? {
            val haystack = name.uppercase()
            return entries
                .sortedByDescending { it.label.length }
                .firstOrNull { haystack.contains(it.label) }
        }
    }
}
