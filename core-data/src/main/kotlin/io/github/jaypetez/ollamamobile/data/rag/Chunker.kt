package io.github.jaypetez.ollamamobile.data.rag

/**
 * One piece of a document, ready to embed.
 *
 * [text] and [embeddableText] are separate on purpose. What gets stored and
 * shown to the user as a citation is the passage as written; what gets embedded
 * additionally carries the heading path, because a chunk from the middle of a
 * long document is frequently unintelligible on its own — "It defaults to 4096."
 * matches nothing, while "Configuration > Context length\n\nIt defaults to
 * 4096." matches a question about context length. Embedding the heading and
 * displaying the bare text gives both.
 */
public data class Chunk(
    public val ordinal: Int,
    /** The passage verbatim, for display and for the lexical index. */
    public val text: String,
    /** `Heading > Subheading`, or null outside any heading. */
    public val headingPath: String?,
    /** Character offsets into the source document, for "open the source span". */
    public val startOffset: Int,
    public val endOffset: Int,
    public val tokenCount: Int,
) {
    /** What [EmbeddingService] sends, before the task prefix is applied. */
    public val embeddableText: String
        get() = if (headingPath.isNullOrEmpty()) text else "$headingPath\n\n$text"
}

/**
 * Splits a document into overlapping, structure-aware chunks.
 *
 * ## The shape of the problem
 *
 * A chunk is the unit of retrieval, so its boundaries decide what can ever be
 * found. Two failure modes bracket the design: chunks that are too large dilute
 * the embedding (one vector averaging six unrelated paragraphs matches nothing
 * strongly), and chunks that split mid-thought strand the answer across a
 * boundary so neither half scores well enough to be retrieved.
 *
 * So: a target size, a preference for splitting where the author already split,
 * and an overlap so a fact landing near a boundary appears whole in at least one
 * chunk.
 *
 * ## Boundary preference, strongest first
 *
 * 1. **Markdown headings** — a hard break. Text under a different heading is
 *    about a different thing, and merging across one produces a chunk whose
 *    embedding belongs to neither section.
 * 2. **Blank lines (paragraphs)** — the author's own unit of thought.
 * 3. **Sentence ends** — used only when a single paragraph exceeds the budget.
 * 4. **Word boundaries** — the last resort, for text with no sentence
 *    punctuation at all (minified data, some CJK, a wall of URLs). Never
 *    mid-word: splitting a word produces subword fragments that tokenise into
 *    garbage in both chunks.
 *
 * ## Why tokens are estimated rather than counted
 *
 * The real count comes from the model's tokenizer, behind a suspend call into
 * the engine. Calling it per candidate boundary would make chunking a few
 * thousand JNI round trips per document. Instead the chunker works to a
 * conservative estimate and the *budget* absorbs the error: the target sits well
 * under the model's real sequence limit, so an estimate that is 25% low still
 * produces a chunk that embeds. See [TokenEstimator].
 */
public class Chunker(
    private val config: Config = Config(),
) {
    /**
     * Chunking parameters.
     *
     * @property targetTokens the size to aim for. 384 sits below the 512 that
     *   several small embedding models are trained to and leaves room for the
     *   heading path and the task prefix, both added *after* chunking and both
     *   counting against the model's sequence limit.
     * @property overlapFraction how much of the previous chunk to repeat. 15% is
     *   the usual compromise: enough that a sentence spanning a boundary is
     *   whole somewhere, small enough that the index does not grow by a third.
     * @property minTokens chunks below this merge backwards. A four-word
     *   fragment left over from a heading break is noise in the index — it
     *   matches short queries on the strength of having almost no content.
     */
    public data class Config(
        public val targetTokens: Int = DEFAULT_TARGET_TOKENS,
        public val overlapFraction: Double = DEFAULT_OVERLAP_FRACTION,
        public val minTokens: Int = DEFAULT_MIN_TOKENS,
    ) {
        init {
            require(targetTokens > 0) { "targetTokens must be positive" }
            require(overlapFraction >= 0.0 && overlapFraction < 1.0) {
                "overlapFraction must be in [0, 1): was $overlapFraction"
            }
            require(minTokens in 0..targetTokens) { "minTokens must be in 0..targetTokens" }
        }

        public val overlapTokens: Int
            get() = (targetTokens * overlapFraction).toInt()
    }

    public fun chunk(document: String): List<Chunk> {
        if (document.isBlank()) return emptyList()
        val chunks = mutableListOf<Chunk>()
        for (section in splitIntoSections(document)) {
            packSection(document, section, chunks)
        }
        return chunks.mapIndexed { index, chunk -> chunk.copy(ordinal = index) }
    }

    // --- structure -------------------------------------------------------

    private data class Section(
        val headingPath: String?,
        val start: Int,
        val end: Int,
    )

    /**
     * Splits on ATX headings, carrying a heading *stack* rather than only the
     * last heading.
     *
     * The stack is what makes the path meaningful. Under `## Limits` inside
     * `# Remote servers`, a chunk labelled only "Limits" is barely better than
     * unlabelled; "Remote servers > Limits" disambiguates when the same
     * subheading appears under three different parents, which in technical
     * documents it always does.
     */
    private fun splitIntoSections(document: String): List<Section> {
        val sections = mutableListOf<Section>()
        val stack = ArrayDeque<Pair<Int, String>>()
        var cursor = 0
        var sectionStart = 0
        var currentPath: String? = null

        for (line in document.lineSequence()) {
            val lineStart = cursor
            cursor += line.length + 1 // the newline lineSequence consumed

            val heading = parseHeading(line)
            if (heading == null) continue
            if (lineStart > sectionStart) {
                sections += Section(currentPath, sectionStart, lineStart)
            }
            while (stack.isNotEmpty() && stack.last().first >= heading.level) stack.removeLast()
            stack.addLast(heading.level to heading.title)
            currentPath = stack.joinToString(HEADING_SEPARATOR) { it.second }
            // The heading line starts the new section: it is the most
            // descriptive sentence in it, and dropping it loses the only text
            // that names what follows.
            sectionStart = lineStart
        }
        if (sectionStart < document.length) {
            sections += Section(currentPath, sectionStart, document.length)
        }
        return sections.filter { document.substring(it.start, it.end).isNotBlank() }
    }

    private data class Heading(
        val level: Int,
        val title: String,
    )

    private fun parseHeading(line: String): Heading? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return null
        val level = trimmed.takeWhile { it == '#' }.length
        if (level > MAX_HEADING_LEVEL) return null
        val rest = trimmed.drop(level)
        // `#tag` is not a heading. ATX requires a space, and without this check
        // every hashtag in prose would start a section.
        if (rest.isNotEmpty() && !rest.first().isWhitespace()) return null
        val title = rest.trim().trimEnd('#').trim()
        return if (title.isEmpty()) null else Heading(level, title)
    }

    // --- packing ---------------------------------------------------------

    /** A candidate boundary: an indivisible span that may start or end a chunk. */
    private data class Segment(
        val start: Int,
        val end: Int,
        val tokens: Int,
    )

    private fun packSection(document: String, section: Section, into: MutableList<Chunk>) {
        val segments = splitIntoSegments(document, section.start, section.end)
        if (segments.isEmpty()) return

        var index = 0
        var carried = emptyList<Segment>()
        while (index < segments.size) {
            val packed = carried.toMutableList()
            var tokens = packed.sumOf { it.tokens }
            // `packed.isEmpty()` forces progress: a single segment larger than
            // the whole budget still has to be emitted, or the loop stalls.
            while (index < segments.size &&
                (packed.isEmpty() || tokens + segments[index].tokens <= config.targetTokens)
            ) {
                packed += segments[index]
                tokens += segments[index].tokens
                index++
            }
            emit(document, packed, section.headingPath, into)
            carried = if (index < segments.size) overlapOf(packed) else emptyList()
            // The overlap must never be the whole chunk, or the next iteration
            // re-packs exactly the same segments and never terminates.
            if (carried.size == packed.size) carried = emptyList()
        }
    }

    private fun emit(
        document: String,
        segments: List<Segment>,
        headingPath: String?,
        into: MutableList<Chunk>,
    ) {
        if (segments.isEmpty()) return
        val start = segments.first().start
        val end = segments.last().end
        val text = document.substring(start, end).trim()
        if (text.isEmpty()) return
        val tokens = segments.sumOf { it.tokens }

        // Merge a runt backwards when it shares a heading, rather than indexing
        // a fragment. A trailing three-word line under the same heading belongs
        // with the paragraph above it.
        val previous = into.lastOrNull()
        if (tokens < config.minTokens && previous != null && previous.headingPath == headingPath) {
            into[into.lastIndex] = previous.copy(
                text = document.substring(previous.startOffset, end).trim(),
                endOffset = end,
                tokenCount = previous.tokenCount + tokens,
            )
            return
        }
        into += Chunk(
            ordinal = into.size,
            text = text,
            headingPath = headingPath,
            startOffset = start,
            endOffset = end,
            tokenCount = tokens,
        )
    }

    /**
     * The tail of [packed] to repeat at the head of the next chunk.
     *
     * Whole segments only — a partial paragraph is exactly the mid-thought split
     * the overlap exists to prevent. But note the fallback: if not even the last
     * segment fits the budget, one is carried anyway. Without it, a document
     * whose paragraphs are individually larger than 15% of the target gets *no
     * overlap at all* while every configuration value still looks correct, which
     * is the silent-degradation shape this pipeline is full of. Overlapping a
     * little more than asked is strictly better than the feature quietly not
     * existing.
     *
     * Carrying the last segment is always safe for termination because the
     * caller drops the overlap whenever it would be the whole chunk.
     */
    private fun overlapOf(packed: List<Segment>): List<Segment> {
        if (config.overlapTokens <= 0 || packed.size < 2) return emptyList()
        val carried = ArrayDeque<Segment>()
        var tokens = 0
        for (segment in packed.asReversed()) {
            val budgetSpent = tokens >= config.overlapTokens
            val wouldOverflow = tokens + segment.tokens > config.overlapTokens
            // The first segment is taken unconditionally; see the note above on
            // why an empty overlap is worse than a slightly oversized one.
            if (carried.isNotEmpty() && (budgetSpent || wouldOverflow)) break
            carried.addFirst(segment)
            tokens += segment.tokens
        }
        return carried.toList()
    }

    // --- splitting -------------------------------------------------------

    private fun splitIntoSegments(document: String, start: Int, end: Int): List<Segment> {
        val segments = mutableListOf<Segment>()
        for (paragraph in splitParagraphs(document, start, end)) {
            if (paragraph.tokens <= config.targetTokens) {
                segments += paragraph
            } else {
                segments += splitSentences(document, paragraph)
            }
        }
        return segments
    }

    private fun splitParagraphs(document: String, start: Int, end: Int): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = start
        val body = document.substring(start, end)
        for (match in BLANK_LINE.findAll(body)) {
            addSegment(document, segments, cursor, start + match.range.first + 1)
            cursor = start + match.range.last + 1
        }
        addSegment(document, segments, cursor, end)
        return segments
    }

    private fun splitSentences(document: String, paragraph: Segment): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = paragraph.start
        val body = document.substring(paragraph.start, paragraph.end)
        for (match in SENTENCE_END.findAll(body)) {
            addSegment(document, segments, cursor, paragraph.start + match.range.last + 1)
            cursor = paragraph.start + match.range.last + 1
        }
        addSegment(document, segments, cursor, paragraph.end)

        // A single "sentence" can still blow the budget — a table row, a base64
        // blob, a script this regex has no punctuation for. Split those on
        // words, the last boundary that does not corrupt a token.
        return segments.flatMap {
            if (it.tokens > config.targetTokens) splitWords(document, it) else listOf(it)
        }
    }

    private fun splitWords(document: String, segment: Segment): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = segment.start
        val body = document.substring(segment.start, segment.end)
        for (match in WHITESPACE.findAll(body)) {
            val boundary = segment.start + match.range.last + 1
            if (TokenEstimator.estimate(document.substring(cursor, boundary)) >= config.targetTokens) {
                addSegment(document, segments, cursor, boundary)
                cursor = boundary
            }
        }
        addSegment(document, segments, cursor, segment.end)
        return segments
    }

    private fun addSegment(document: String, into: MutableList<Segment>, start: Int, end: Int) {
        if (end <= start) return
        val text = document.substring(start, end)
        if (text.isBlank()) return
        into += Segment(start, end, TokenEstimator.estimate(text))
    }

    public companion object {
        public const val DEFAULT_TARGET_TOKENS: Int = 384
        public const val DEFAULT_OVERLAP_FRACTION: Double = 0.15
        public const val DEFAULT_MIN_TOKENS: Int = 24

        public const val HEADING_SEPARATOR: String = " > "
        private const val MAX_HEADING_LEVEL = 6

        private val BLANK_LINE = Regex("\\n[ \\t]*\\n")

        /**
         * A sentence terminator followed by whitespace.
         *
         * Requiring the whitespace keeps "1.5" and most of "e.g." from being
         * boundaries. It is not a sentence tokenizer and does not try to be —
         * the cost of an occasional wrong split *inside a paragraph that already
         * exceeds the budget* is small, and a real one is a dependency plus a
         * locale table.
         */
        private val SENTENCE_END = Regex("[.!?。！？]['\")\\]]?(?=\\s)")
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * A cheap, deliberately pessimistic token estimate.
 *
 * The chunker needs a count at every candidate boundary and the only exact
 * source is the model's tokenizer behind a suspend JNI call. Estimating trades
 * accuracy for three orders of magnitude in speed, and the design absorbs the
 * inaccuracy by aiming well under the model's real limit.
 *
 * Pessimistic on purpose: characters-over-four is roughly right for English
 * prose and badly optimistic for everything else — CJK is closer to one token
 * per character, and code and non-Latin scripts fall in between. Under-counting
 * produces chunks that exceed the sequence length and get silently truncated at
 * the far end, so where the estimate is wrong it should be wrong in the
 * direction that costs a little index size rather than a little content.
 */
public object TokenEstimator {
    public fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        for (character in text) {
            if (character.code >= WIDE_SCRIPT_FLOOR) wide++ else narrow++
        }
        // One token per CJK-and-beyond character, one per ~3.6 characters
        // elsewhere. 3.6 rather than 4 is the pessimism.
        val estimate = wide + Math.round(narrow / NARROW_CHARS_PER_TOKEN)
        return estimate.coerceAtLeast(1)
    }

    /** Above U+2E80 is CJK, Kana, Hangul and friends. */
    private const val WIDE_SCRIPT_FLOOR = 0x2E80
    private const val NARROW_CHARS_PER_TOKEN = 3.6f
}
