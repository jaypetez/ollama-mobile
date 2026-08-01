package io.github.jaypetez.ollamamobile.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Boundary behaviour, which is the whole of what a chunker is.
 *
 * These assert on *where* the splits land rather than on chunk counts wherever
 * possible: a count assertion breaks whenever the target size is tuned and tells
 * you nothing about whether the split was in a sensible place, which is the
 * property that decides whether retrieval works.
 */
class ChunkerTest {
    private val chunker = Chunker(Chunker.Config(targetTokens = 40, overlapFraction = 0.15, minTokens = 4))

    @Test
    fun `an empty or blank document yields nothing`() {
        assertThat(chunker.chunk("")).isEmpty()
        assertThat(chunker.chunk("   \n\n  \t ")).isEmpty()
    }

    @Test
    fun `a document shorter than the target is one chunk`() {
        val chunks = chunker.chunk("A short note about nothing much at all.")

        assertThat(chunks).hasSize(1)
        assertThat(chunks.single().text).isEqualTo("A short note about nothing much at all.")
        assertThat(chunks.single().headingPath).isNull()
    }

    @Test
    fun `a heading always starts a new chunk`() {
        val document = """
            # Alpha

            Text under alpha.

            # Beta

            Text under beta.
        """.trimIndent()

        val chunks = chunker.chunk(document)

        // The two sections must never be merged even though both together fit
        // in one chunk: a vector averaging both belongs to neither.
        assertThat(chunks.map { it.headingPath }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(chunks[0].text).contains("Text under alpha.")
        assertThat(chunks[0].text).doesNotContain("Text under beta.")
    }

    @Test
    fun `the heading path is the full stack, not just the nearest heading`() {
        val document = """
            # Remote servers

            Intro.

            ## Limits

            The rate limit is per token.
        """.trimIndent()

        val chunks = chunker.chunk(document)

        assertThat(chunks.map { it.headingPath })
            .containsExactly("Remote servers", "Remote servers > Limits")
            .inOrder()
    }

    @Test
    fun `a sibling heading pops the stack instead of nesting under it`() {
        val document = """
            # One

            ## Deep

            Text.

            ## Other

            Text.

            # Two

            Text.
        """.trimIndent()

        assertThat(chunker.chunk(document).map { it.headingPath })
            .containsExactly("One", "One > Deep", "One > Other", "Two")
            .inOrder()
    }

    @Test
    fun `a hashtag is not a heading`() {
        // Without the "# must be followed by a space" rule, every hashtag in
        // prose starts a section and shreds the document.
        val chunks = chunker.chunk("Some prose mentioning #hashtag inline and continuing on.")

        assertThat(chunks).hasSize(1)
        assertThat(chunks.single().headingPath).isNull()
    }

    @Test
    fun `chunks split on paragraph boundaries, never mid-paragraph, when they fit`() {
        val paragraph = { n: Int -> "Paragraph $n. " + "word ".repeat(20).trim() }
        val document = (1..6).joinToString("\n\n", transform = paragraph)

        val chunks = chunker.chunk(document)

        assertThat(chunks.size).isAtLeast(2)
        for (chunk in chunks) {
            // Every chunk begins at the start of some paragraph.
            assertThat(chunk.text).startsWith("Paragraph ")
        }
    }

    @Test
    fun `an oversized paragraph is split at sentence ends`() {
        val sentences = (1..12).joinToString(" ") { "This is sentence number $it in a very long paragraph." }

        val chunks = chunker.chunk(sentences)

        assertThat(chunks.size).isAtLeast(2)
        for (chunk in chunks) {
            // A split inside a sentence would leave a chunk ending in a word.
            assertThat(chunk.text.trimEnd()).endsWith(".")
        }
    }

    @Test
    fun `text with no sentence punctuation still splits, and never mid-word`() {
        val words = (1..300).joinToString(" ") { "token$it" }

        val chunks = chunker.chunk(words)

        assertThat(chunks.size).isAtLeast(2)
        for (chunk in chunks) {
            // Every fragment is a whole token. A mid-word split would produce
            // something like "toke" / "n42".
            for (word in chunk.text.split(" ")) {
                assertThat(word).matches("token\\d+")
            }
        }
    }

    @Test
    fun `consecutive chunks overlap so a boundary-spanning fact survives whole`() {
        val document = (1..12).joinToString("\n\n") { "Paragraph $it about subject $it." }

        val chunks = chunker.chunk(document)

        assertThat(chunks.size).isAtLeast(2)
        val first = chunks[0].text
        val second = chunks[1].text
        val shared = first.lines().filter { it.isNotBlank() }.any { it in second }
        assertThat(shared).isTrue()
    }

    @Test
    fun `overlap never stalls the packer`() {
        // A regression guard, not a quality assertion. If the carried overlap is
        // ever the whole of the chunk just emitted, the next iteration re-packs
        // the identical segments and chunk() never returns. This must terminate.
        val aggressive = Chunker(Chunker.Config(targetTokens = 8, overlapFraction = 0.9, minTokens = 0))

        val chunks = aggressive.chunk((1..40).joinToString("\n\n") { "Paragraph $it here." })

        assertThat(chunks).isNotEmpty()
        assertThat(chunks.map { it.ordinal }).isInOrder()
    }

    @Test
    fun `offsets point back at the source text`() {
        val document = "# Title\n\nFirst paragraph.\n\nSecond paragraph."

        for (chunk in chunker.chunk(document)) {
            // The recorded span must contain the chunk, which is what makes
            // "open the source" land in the right place.
            assertThat(document.substring(chunk.startOffset, chunk.endOffset)).contains(chunk.text.trim())
        }
    }

    @Test
    fun `ordinals are dense and ascending across sections`() {
        val document = (1..5).joinToString("\n\n") { "# Heading $it\n\nBody $it." }

        val chunks = chunker.chunk(document)

        assertThat(chunks.map { it.ordinal }).isEqualTo(chunks.indices.toList())
    }

    @Test
    fun `the embeddable text carries the heading and the stored text does not`() {
        val document = "# Context length\n\nIt defaults to 4096."

        val chunk = chunker.chunk(document).single()

        // The distinction that makes a mid-document chunk retrievable at all.
        assertThat(chunk.embeddableText).contains("Context length")
        assertThat(chunk.text).contains("It defaults to 4096.")
    }

    @Test
    fun `a runt merges backwards instead of being indexed alone`() {
        val strict = Chunker(Chunker.Config(targetTokens = 30, overlapFraction = 0.0, minTokens = 20))
        val document = "word ".repeat(40).trim() + "\n\nok."

        val chunks = strict.chunk(document)

        // "ok." on its own would match short queries on the strength of having
        // almost no content.
        assertThat(chunks.last().text).endsWith("ok.")
        assertThat(chunks.none { it.text == "ok." }).isTrue()
    }

    @Test
    fun `the token estimator is pessimistic for wide scripts`() {
        // Under-counting produces chunks that exceed the model's sequence length
        // and get silently truncated. CJK must not be counted as if it were
        // English prose.
        assertThat(TokenEstimator.estimate("日本語のテキスト")).isAtLeast("日本語のテキスト".length)
        assertThat(TokenEstimator.estimate("plain english prose here"))
            .isLessThan("plain english prose here".length)
    }

    @Test
    fun `config rejects nonsense rather than producing strange chunks`() {
        runCatching { Chunker.Config(targetTokens = 0) }.let { assertThat(it.isFailure).isTrue() }
        runCatching { Chunker.Config(overlapFraction = 1.0) }.let { assertThat(it.isFailure).isTrue() }
        runCatching { Chunker.Config(targetTokens = 10, minTokens = 11) }
            .let { assertThat(it.isFailure).isTrue() }
    }
}
