package io.github.jaypetez.ollamamobile.data.rag

import io.github.jaypetez.ollamamobile.storage.entity.MessageCitationEntity

/**
 * The prompt fragment and the citation rows that go with it.
 *
 * They are produced together and must stay together: the `[n]` markers in
 * [contextBlock] are the *only* thing connecting the model's "[2]" to a chunk,
 * and a citation list built separately — or renumbered later — turns every
 * marker in the answer into a link to the wrong passage. That is worse than no
 * citations, because it looks authoritative.
 */
public data class InjectedContext(
    /** Prepended to the user turn. Empty when nothing was retrieved. */
    public val contextBlock: String,
    /** One row per marker, `rank` matching the marker number. */
    public val citations: List<MessageCitationEntity>,
    /** Rough token cost of [contextBlock], for the history-trimming budget. */
    public val estimatedTokens: Int,
)

/**
 * Builds the retrieved-context block and the citation rows.
 *
 * ## Why a `<context>` block and not a system-prompt rewrite
 *
 * Small instruct models follow an explicit, delimited block far more reliably
 * than prose folded into the system prompt, and a delimiter the user cannot
 * accidentally produce keeps a document's own text from being read as
 * instructions. It also keeps the system prompt the user configured intact —
 * silently replacing it to make RAG work would break every conversation the
 * moment retrieval is toggled on.
 *
 * ## Why the markers are `[1]`-based and dense
 *
 * The model is being asked to cite, and it will only reliably emit a marker it
 * can see. Numbering from 1 with no gaps matches how citations appear in every
 * document these models were trained on. Gaps — from filtering a chunk out after
 * numbering — measurably increase the rate at which a model invents `[9]`.
 */
public class RagContextInjector(
    private val config: Config = Config(),
) {
    /**
     * @property maxContextTokens the ceiling on the whole block. Retrieval can
     *   return eight chunks that together exceed what a 4k-context model has
     *   left after the history; chunks are dropped from the *end* (worst-ranked
     *   first) until it fits, and the numbering is assigned after that so it
     *   stays dense.
     * @property includeHeadings whether the heading path is shown to the model.
     *   It is: a passage labelled with its section is easier for the model to
     *   attribute correctly, and it costs a handful of tokens.
     */
    public data class Config(
        public val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
        public val includeHeadings: Boolean = true,
    )

    /**
     * @param messageUuid the *assistant* message the citations attach to. The
     *   citation belongs to the answer, not to the question: it records what the
     *   model was shown when it produced that text, which is what a user tapping
     *   a chip wants to see.
     */
    public fun inject(hits: List<RetrievalHit>, messageUuid: String): InjectedContext {
        if (hits.isEmpty()) return InjectedContext("", emptyList(), 0)

        val kept = mutableListOf<RetrievalHit>()
        var tokens = TokenEstimator.estimate(HEADER) + TokenEstimator.estimate(FOOTER)
        for (hit in hits) {
            val cost = TokenEstimator.estimate(hit.render(kept.size + 1, config.includeHeadings))
            if (kept.isNotEmpty() && tokens + cost > config.maxContextTokens) break
            kept += hit
            tokens += cost
        }
        if (kept.isEmpty()) return InjectedContext("", emptyList(), 0)

        val block = buildString {
            append(HEADER)
            kept.forEachIndexed { index, hit ->
                append(hit.render(index + 1, config.includeHeadings))
            }
            append(FOOTER)
        }

        val citations = kept.mapIndexed { index, hit ->
            MessageCitationEntity(
                messageUuid = messageUuid,
                chunkUuid = hit.chunkUuid,
                documentId = hit.documentId,
                // 1-based, matching the marker exactly. An off-by-one here is
                // invisible in code review and sends every chip one passage
                // adrift.
                rank = index + 1,
                score = hit.fusedScore,
                // The chunk text is snapshotted onto the citation so a chip
                // still resolves after the document is deleted or reindexed.
                // Reindexing mints new chunk uuids, so a citation that only held
                // a foreign key would dangle the first time a user re-imports a
                // file they edited.
                quotedText = hit.text.take(MAX_QUOTED_CHARS),
            )
        }
        return InjectedContext(block, citations, tokens)
    }

    private fun RetrievalHit.render(marker: Int, includeHeadings: Boolean): String {
        val label = if (includeHeadings && !headingPath.isNullOrEmpty()) {
            "$documentTitle — $headingPath"
        } else {
            documentTitle
        }
        return "[$marker] $label\n$text\n\n"
    }

    public companion object {
        private const val HEADER =
            "<context>\nUse the sources below to answer. Cite them inline as [1], [2] and so on. " +
                "If they do not contain the answer, say so instead of guessing.\n\n"
        private const val FOOTER = "</context>\n\n"

        /** Generous, because the caller's history trimmer has the real budget. */
        public const val DEFAULT_MAX_CONTEXT_TOKENS: Int = 3072

        /** Enough to show a chip's preview without duplicating the corpus in the messages table. */
        private const val MAX_QUOTED_CHARS = 600
    }
}
