package io.github.jaypetez.ollamamobile.model

/**
 * A model the app knows about, wherever it lives.
 *
 * One type covers local files, models on a remote server and catalogue entries
 * that have not been downloaded, because the picker, the router and the memory
 * estimate all need to reason about them side by side. [origin] is what
 * distinguishes them.
 */
public data class ModelRef(
    public val id: ModelId,
    /** What the user sees, e.g. "Qwen3 1.7B Instruct". */
    public val displayName: String,
    /** The raw name or tag: `qwen3:1.7b-instruct-q4_K_M`, or the GGUF filename. */
    public val name: String,
    public val origin: ModelOrigin,
    public val parameterCount: Long? = null,
    public val quantization: Quantization? = null,
    /** On-disk or reported file size. Null for a catalogue entry with no size yet. */
    public val sizeBytes: Long? = null,
    /** Training context length from the GGUF header, not the configured `n_ctx`. */
    public val contextLength: Int? = null,
    public val capabilities: Set<ModelCapability> = setOf(ModelCapability.CHAT),
    /** The Jinja chat template from the GGUF header, when it carried one. */
    public val chatTemplate: String? = null,
) {
    public fun supports(capability: ModelCapability): Boolean = capability in capabilities

    public val isLocal: Boolean
        get() = origin is ModelOrigin.Local

    /**
     * Best available estimate of the weight bytes, for the memory verdict.
     *
     * Prefers the real file size; falls back to parameters × bits-per-weight
     * for a catalogue entry that has not been downloaded, which is the case
     * where the estimate has to be made *before* committing to a multi-gigabyte
     * transfer.
     */
    public val estimatedWeightBytes: Long?
        get() = sizeBytes ?: parameterCount?.let { params -> quantization?.estimateWeightBytes(params) }
}

/** Where a [ModelRef] comes from. */
public sealed interface ModelOrigin {
    /** A GGUF file on this device. [path] is absolute. */
    public data class Local(
        public val path: String,
    ) : ModelOrigin

    /** A model served by a configured remote server. */
    public data class Remote(
        public val serverId: ServerId,
    ) : ModelOrigin

    /**
     * A downloadable entry. [repo] is the Hugging Face repository id
     * (`owner/name`) and [file] the GGUF filename within it — both are needed,
     * because one repo publishes every quantisation of the same model.
     */
    public data class Catalog(
        public val repo: String,
        public val file: String,
    ) : ModelOrigin
}

/** What a model can be used for. Drives which UI affordances are offered. */
public enum class ModelCapability {
    CHAT,
    EMBEDDING,
    VISION,
    TOOLS,

    /** Emits `<think>` blocks that belong in [ChatMessage.reasoning]. */
    REASONING,
}
