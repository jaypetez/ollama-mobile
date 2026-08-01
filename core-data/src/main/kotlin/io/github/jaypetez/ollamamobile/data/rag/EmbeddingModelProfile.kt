package io.github.jaypetez.ollamamobile.data.rag

import io.github.jaypetez.ollamamobile.model.ModelRef

/**
 * Everything retrieval needs to know about one embedding model.
 *
 * ## Why this exists instead of a handful of constants
 *
 * Every one of these properties varies per model and every one of them is
 * silent when wrong. Dimensions wrong: the vector store rejects the write, which
 * is at least loud. Token budget wrong: chunks get truncated inside the engine
 * and the tail of every chunk is simply absent from the index. Prefixes wrong:
 * *nothing* happens — no exception, no log, no shape mismatch, just worse
 * answers. See [TaskPrefixer].
 *
 * Attaching them to the model rather than to the pipeline is the whole point. A
 * constant somewhere in the indexer is correct until the day somebody switches
 * models in a settings screen, at which point it is confidently wrong and there
 * is no test that can notice, because both models embed both texts and return
 * finite vectors either way.
 *
 * [ModelRef] cannot carry these — `:core-model` knows nothing about retrieval,
 * and widening it would push RAG vocabulary into every module in the graph. So
 * the profile is resolved from the model here, in the layer that owns RAG.
 */
public data class EmbeddingModelProfile(
    /**
     * The model this profile describes, as stored on `rag_documents`.
     *
     * Vectors from two models are not comparable. Recording which model produced
     * an index is what lets the dense scan refuse to mix them instead of
     * returning confident nonsense.
     */
    public val modelId: String,
    /** Vector length. Checked on write; a mismatch is a corrupt index, not a warning. */
    public val dimensions: Int,
    /**
     * The longest sequence the model pools correctly.
     *
     * The chunker targets comfortably under this. Note that the *engine* must
     * also be loaded with a micro-batch at least this large or a full-length
     * chunk fails inside `llama_decode` — see the embedding branch in
     * `llama_jni.cpp`.
     */
    public val maxSequenceTokens: Int,
    /** Prepended to a search query. Empty when the model was trained without one. */
    public val queryPrefix: String,
    /** Prepended to an indexed passage. Empty when the model was trained without one. */
    public val documentPrefix: String,
) {
    /** True when this model is prefix-trained, i.e. omitting them degrades retrieval. */
    public val usesTaskPrefixes: Boolean
        get() = queryPrefix.isNotEmpty() || documentPrefix.isNotEmpty()

    public companion object {
        /**
         * nomic-embed-text v1/v1.5.
         *
         * The prefixes are part of the model's training recipe, not a
         * convention: the same encoder is asked to place a question and the
         * passage that answers it in the same region, and the instruction is the
         * only thing telling it which of the two it is looking at.
         */
        public val NOMIC_EMBED_TEXT: EmbeddingModelProfile = EmbeddingModelProfile(
            modelId = "nomic-embed-text",
            dimensions = NOMIC_DIMENSIONS,
            maxSequenceTokens = NOMIC_MAX_TOKENS,
            queryPrefix = "search_query: ",
            documentPrefix = "search_document: ",
        )

        /**
         * EmbeddingGemma.
         *
         * Different phrasing for the same idea — which is exactly why this is a
         * per-model property. Copying nomic's strings here would produce a model
         * that has never seen this instruction and treats it as ordinary text to
         * average into the pooled vector.
         */
        public val EMBEDDING_GEMMA: EmbeddingModelProfile = EmbeddingModelProfile(
            modelId = "embeddinggemma",
            dimensions = GEMMA_DIMENSIONS,
            maxSequenceTokens = GEMMA_MAX_TOKENS,
            queryPrefix = "task: search result | query: ",
            documentPrefix = "title: none | text: ",
        )

        /**
         * A model we have no recipe for.
         *
         * Empty prefixes rather than a guess. Feeding nomic's instruction to a
         * model that never saw it is worse than feeding none: it is unfamiliar
         * text that lands in the pooled average of every single vector.
         */
        public fun unknown(modelId: String, dimensions: Int): EmbeddingModelProfile =
            EmbeddingModelProfile(
                modelId = modelId,
                dimensions = dimensions,
                maxSequenceTokens = DEFAULT_MAX_TOKENS,
                queryPrefix = "",
                documentPrefix = "",
            )

        private const val NOMIC_DIMENSIONS = 768
        private const val NOMIC_MAX_TOKENS = 2048
        private const val GEMMA_DIMENSIONS = 768
        private const val GEMMA_MAX_TOKENS = 2048
        private const val DEFAULT_MAX_TOKENS = 512
    }
}

/**
 * Maps a [ModelRef] to its [EmbeddingModelProfile].
 *
 * An interface so the app can eventually back this with a user-editable table —
 * new embedding models appear faster than releases do, and a user who knows
 * their model's recipe should be able to enter it rather than wait for us.
 */
public interface EmbeddingModelRegistry {
    public fun profileFor(model: ModelRef): EmbeddingModelProfile

    public fun profileFor(modelId: String, dimensions: Int): EmbeddingModelProfile
}

/**
 * Recognises models by substring of their name.
 *
 * Substring rather than exact match because the same weights ship under many
 * names — `nomic-embed-text`, `nomic-embed-text:v1.5`,
 * `nomic-ai/nomic-embed-text-v1.5-GGUF/…Q4_K_M.gguf`. Exact matching would
 * silently fall through to [EmbeddingModelProfile.unknown] for most real
 * installs, which is the degradation this whole file exists to prevent.
 */
public class DefaultEmbeddingModelRegistry : EmbeddingModelRegistry {
    override fun profileFor(model: ModelRef): EmbeddingModelProfile {
        val haystack = "${model.name} ${model.displayName} ${model.id.value}".lowercase()
        KNOWN
            .firstOrNull { (needles, _) -> needles.any { it in haystack } }
            ?.let { (_, profile) -> return profile }
        return EmbeddingModelProfile.unknown(
            modelId = model.id.value,
            dimensions = FALLBACK_DIMENSIONS,
        )
    }

    override fun profileFor(modelId: String, dimensions: Int): EmbeddingModelProfile {
        val haystack = modelId.lowercase()
        KNOWN
            .firstOrNull { (needles, _) -> needles.any { it in haystack } }
            ?.let { (_, profile) -> return profile.copy(modelId = modelId, dimensions = dimensions) }
        return EmbeddingModelProfile.unknown(modelId, dimensions)
    }

    private companion object {
        val KNOWN: List<Pair<List<String>, EmbeddingModelProfile>> = listOf(
            listOf("nomic-embed") to EmbeddingModelProfile.NOMIC_EMBED_TEXT,
            listOf("embeddinggemma", "embedding-gemma") to EmbeddingModelProfile.EMBEDDING_GEMMA,
        )

        /**
         * Only ever used for a model we did not recognise, and only until the
         * first real vector arrives — [VectorStore] adopts the dimension it is
         * actually handed rather than trusting this.
         */
        const val FALLBACK_DIMENSIONS = 768
    }
}
