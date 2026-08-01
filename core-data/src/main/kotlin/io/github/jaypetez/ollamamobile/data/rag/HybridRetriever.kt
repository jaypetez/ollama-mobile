package io.github.jaypetez.ollamamobile.data.rag

/**
 * A chunk that survived fusion, with the evidence for why.
 *
 * The per-side ranks are kept rather than discarded because they are the only
 * way to debug a bad retrieval after the fact: "this chunk was 1st on bm25 and
 * absent from the dense side" and "this chunk was 40th on both" are completely
 * different problems, and the fused score alone cannot tell them apart.
 */
public data class RetrievedChunk(
    public val chunkUuid: String,
    public val fusedScore: Double,
    /** 1-based rank on the lexical side, or null if it did not appear. */
    public val lexicalRank: Int?,
    /** 1-based rank on the dense side, or null if it did not appear. */
    public val denseRank: Int?,
)

/**
 * Combines two ranked lists with Reciprocal Rank Fusion.
 *
 * ## Why fuse at all
 *
 * The two retrievers fail in opposite directions, which is the entire argument
 * for running both. bm25 cannot match a paraphrase — ask "how do I stop it
 * talking" of a document that says "cancel generation" and it returns nothing.
 * Dense retrieval cannot reliably match a rare literal: a product code, an error
 * number, an API name the embedding model never saw gets averaged into the
 * neighbourhood of whatever it looks like. Each covers the other's blind spot.
 *
 * ## Why RRF and not a weighted score blend
 *
 * The two sides produce numbers that are not comparable and not even stable.
 * bm25 is an unbounded corpus-relative score; cosine is bounded but its useful
 * range drifts with the embedding model and the query length. Any `alpha * dense
 * + (1 - alpha) * lexical` needs both normalised, and every normalisation
 * (min-max over the result set, z-score, sigmoid) is itself sensitive to the
 * result set's shape — one runaway bm25 score compresses everything else to
 * near-zero and silently turns the blend into a pure dense ranking.
 *
 * RRF throws the scores away and keeps only the ranks, which are comparable by
 * construction. It needs no tuning, no normalisation, and no per-model
 * calibration, and it is robust to exactly the pathology above: a document
 * ranked 1st contributes the same whether it won by a nose or by a mile.
 *
 * ## The constant
 *
 * `score = sum over sides of 1 / (k + rank)`, with **k = 60** from Cormack et
 * al.'s original formulation. k damps the top of each list: without it, rank 1
 * scores 1.0 and rank 2 scores 0.5, so a single side's first place would
 * outweigh a strong consensus further down. At 60 the difference between rank 1
 * and rank 2 is about 1.6%, which means agreement between the two retrievers
 * matters more than either one's confidence — which is the property being bought.
 */
public class ReciprocalRankFusion(
    private val k: Int = DEFAULT_K,
) {
    init {
        require(k > 0) { "k must be positive: was $k" }
    }

    /**
     * Fuses two ranked lists of chunk ids, best-first in, best-first out.
     *
     * Ties are broken by the better of the two ranks, then by id. Deterministic
     * tie-breaking is not fussiness: with k = 60 exact ties are common (any two
     * chunks appearing at the same rank on one side and absent from the other),
     * and a non-deterministic order makes the golden fixture test flap and makes
     * a user's answer change between two runs of the same question.
     */
    public fun fuse(
        lexical: List<String>,
        dense: List<String>,
        limit: Int,
    ): List<RetrievedChunk> {
        val lexicalRanks = lexical.toRankMap()
        val denseRanks = dense.toRankMap()

        val fused = (lexicalRanks.keys + denseRanks.keys).map { id ->
            val lexicalRank = lexicalRanks[id]
            val denseRank = denseRanks[id]
            val score = contribution(lexicalRank) + contribution(denseRank)
            RetrievedChunk(id, score, lexicalRank, denseRank)
        }

        return fused
            .sortedWith(
                compareByDescending<RetrievedChunk> { it.fusedScore }
                    .thenBy { minOf(it.lexicalRank ?: Int.MAX_VALUE, it.denseRank ?: Int.MAX_VALUE) }
                    .thenBy { it.chunkUuid },
            ).take(limit)
    }

    private fun contribution(rank: Int?): Double =
        if (rank == null) 0.0 else 1.0 / (k + rank)

    /**
     * Position to 1-based rank, first occurrence winning.
     *
     * Duplicates should not reach here, but a raw FTS query over a table with a
     * stale trigger can emit one, and `associate` would otherwise let the *last*
     * (worse) position win.
     */
    private fun List<String>.toRankMap(): Map<String, Int> {
        val ranks = LinkedHashMap<String, Int>(size)
        forEachIndexed { index, id -> ranks.putIfAbsent(id, index + 1) }
        return ranks
    }

    public companion object {
        /** Cormack et al. 2009. Changing it re-tunes every retrieval in the app. */
        public const val DEFAULT_K: Int = 60
    }
}

/**
 * Retrieval configuration.
 *
 * @property candidatesPerSide how deep each retriever goes before fusion. 50 is
 *   well past where either side is individually useful, on purpose: RRF's value
 *   comes from a chunk that one side ranked 30th and the other ranked 3rd, and
 *   truncating at 10 throws exactly those away.
 * @property topK how many chunks reach the prompt. 8 is a context-budget
 *   decision, not a quality one — eight 384-token chunks is roughly 3k tokens,
 *   which is already a large fraction of a small model's window once the history
 *   and the answer are accounted for.
 */
public data class RetrievalConfig(
    public val candidatesPerSide: Int = DEFAULT_CANDIDATES_PER_SIDE,
    public val topK: Int = DEFAULT_TOP_K,
    public val fusionK: Int = ReciprocalRankFusion.DEFAULT_K,
) {
    public companion object {
        public const val DEFAULT_CANDIDATES_PER_SIDE: Int = 50
        public const val DEFAULT_TOP_K: Int = 8
    }
}
