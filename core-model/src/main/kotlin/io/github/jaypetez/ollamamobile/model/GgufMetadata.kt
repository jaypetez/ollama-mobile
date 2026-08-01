package io.github.jaypetez.ollamamobile.model

/**
 * The subset of the GGUF header the app actually uses.
 *
 * Only [architecture] is required: it is what decides whether the file can be
 * run at all. Everything else is genuinely optional in the format — plenty of
 * real-world GGUFs converted by third parties omit `general.name`,
 * `*.rope.freq_base` or the chat template — and a parser that insists on them
 * rejects files llama.cpp would happily load.
 */
public data class GgufMetadata(
    /** `general.architecture`, e.g. "llama", "qwen3", "gemma3". */
    public val architecture: String,
    /** `general.name`. */
    public val name: String? = null,
    /** `general.parameter_count`, when the converter wrote it. */
    public val parameterCount: Long? = null,
    /** `general.file_type`: the `llama_ftype` int. See [quantization]. */
    public val fileType: Int? = null,
    /** `<arch>.context_length` — what the model was trained for, not `n_ctx`. */
    public val contextLength: Int? = null,
    /** `<arch>.embedding_length` (n_embd). */
    public val embeddingLength: Int? = null,
    /** `<arch>.block_count` (n_layer). */
    public val blockCount: Int? = null,
    /** `<arch>.attention.head_count`. */
    public val headCount: Int? = null,
    /**
     * `<arch>.attention.head_count_kv`. Smaller than [headCount] under
     * grouped-query attention, and it — not [headCount] — is what the KV cache
     * size is proportional to.
     */
    public val headCountKv: Int? = null,
    /** `<arch>.rope.freq_base`. */
    public val ropeFreqBase: Double? = null,
    /** `tokenizer.chat_template`, raw Jinja. */
    public val chatTemplate: String? = null,
    /** `tokenizer.ggml.model`, e.g. "gpt2", "llama", "bert". */
    public val tokenizerModel: String? = null,
) {
    /** [fileType] resolved to a [Quantization], or null if it maps to none we model. */
    public val quantization: Quantization?
        get() = fileType?.let(::quantizationFromFileType)

    public companion object {
        /**
         * Maps a `llama_ftype` to the [Quantization] the app reasons about.
         *
         * The values are the `LLAMA_FTYPE_MOSTLY_*` constants from `llama.h`
         * and they are NOT the same numbering as [GgmlType] — 1 is F16 as an
         * ftype but F16 is ggml type 1 by coincidence, while ftype 15 is
         * `Q4_K_M` and ggml type 15 is `Q8_K`. Confusing the two enums silently
         * mislabels every model in the picker.
         *
         * Returns null for formats we do not model (the IQ family, BF16, the
         * ternary types): "unknown" is the honest answer, and the caller falls
         * back to [Quantization.fromFileName]. The one approximation is ftype
         * 21, `MOSTLY_Q2_K_S`, reported as `Q2_K` — close enough for a size
         * estimate, where null would leave the picker with no size at all.
         *
         * The bare literals are deliberate: this `when` IS the spec table and
         * should stay diffable against upstream's `llama_ftype` at a glance.
         * Introducing `FTYPE_MOSTLY_Q4_K_M = 15` and eighteen siblings would
         * restate each number without adding meaning, hence the suppression.
         */
        @Suppress("MagicNumber")
        public fun quantizationFromFileType(fileType: Int): Quantization? = when (fileType) {
            0 -> Quantization.F32
            1 -> Quantization.F16
            2 -> Quantization.Q4_0
            7 -> Quantization.Q8_0
            8 -> Quantization.Q5_0
            10 -> Quantization.Q2_K
            11 -> Quantization.Q3_K_S
            12 -> Quantization.Q3_K_M
            13 -> Quantization.Q3_K_L
            14 -> Quantization.Q4_K_S
            15 -> Quantization.Q4_K_M
            16 -> Quantization.Q5_K_S
            17 -> Quantization.Q5_K_M
            18 -> Quantization.Q6_K
            21 -> Quantization.Q2_K
            else -> null
        }
    }
}

/**
 * A `ggml_type` tensor element type.
 *
 * [removed] entries are the reason this enum exists. Their ids were retired
 * from the format — 4/5 in 2023, and the repacked 31/32/33 plus the IQ4_NL
 * variants 36/37/38 when llama.cpp replaced offline repacking with the runtime
 * `GGML_CPU_REPACK` path. Current ggml refuses to load them, and a file
 * carrying one reaches the JNI boundary looking perfectly well-formed. Naming
 * them lets the loader reject the file with an [AppError.Model.Unsupported]
 * that says which type is at fault, instead of handing the bytes to native code
 * and crashing the process where no Kotlin `try` can catch it.
 */
public enum class GgmlType(
    public val id: Int,
    public val removed: Boolean = false,
) {
    F32(0),
    F16(1),
    Q4_0(2),
    Q4_1(3),
    Q4_2(4, removed = true),
    Q4_3(5, removed = true),
    Q5_0(6),
    Q5_1(7),
    Q8_0(8),
    Q8_1(9),
    Q2_K(10),
    Q3_K(11),
    Q4_K(12),
    Q5_K(13),
    Q6_K(14),
    Q8_K(15),
    IQ2_XXS(16),
    IQ2_XS(17),
    IQ3_XXS(18),
    IQ1_S(19),
    IQ4_NL(20),
    IQ3_S(21),
    IQ2_S(22),
    IQ4_XS(23),
    I8(24),
    I16(25),
    I32(26),
    I64(27),
    F64(28),
    IQ1_M(29),
    BF16(30),
    Q4_0_4_4(31, removed = true),
    Q4_0_4_8(32, removed = true),
    Q4_0_8_8(33, removed = true),
    TQ1_0(34),
    TQ2_0(35),
    IQ4_NL_4_4(36, removed = true),
    IQ4_NL_4_8(37, removed = true),
    IQ4_NL_8_8(38, removed = true),
    MXFP4(39),
    ;

    /** Whether current ggml can still read tensors of this type. */
    public val isSupported: Boolean
        get() = !removed

    public companion object {
        private val byId: Map<Int, GgmlType> = entries.associateBy { it.id }

        /** Null for an id this build does not know — treat that as unsupported too. */
        public fun fromId(id: Int): GgmlType? = byId[id]

        /** The types a loader must refuse. */
        public val removedTypes: Set<GgmlType> = entries.filter { it.removed }.toSet()
    }
}
