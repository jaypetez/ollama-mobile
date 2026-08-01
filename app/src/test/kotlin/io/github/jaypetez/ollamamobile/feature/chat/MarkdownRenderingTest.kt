package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Test

/**
 * The rules that decide what gets parsed and highlighted while an answer is
 * still arriving.
 *
 * The expensive mistake is highlighting an unterminated fence: it re-lexes a
 * growing string on every token. [splitStreamingText] is what makes that
 * impossible, so it is tested directly.
 */
class MarkdownRenderingTest {
    @Test
    fun `an open fence stays in the tail and is never parsed`() {
        val partial = "Here is some code:\n\n```kotlin\nfun main() {\n    println(\"hi\")"
        val (stable, tail) = splitStreamingText(partial)

        assertThat(stable).isEqualTo("Here is some code:\n\n")
        assertThat(tail).startsWith("```kotlin")
        assertThat(parseMarkdown(stable, Color.Blue).filterIsInstance<MarkdownBlock.Code>()).isEmpty()
    }

    @Test
    fun `a closed fence moves into the stable half`() {
        val complete = "Intro:\n\n```kotlin\nval x = 1\n```\nAnd more"
        val (stable, tail) = splitStreamingText(complete)

        assertThat(stable).contains("```kotlin")
        assertThat(tail).isEqualTo("And more")

        val code = parseMarkdown(stable, Color.Blue).filterIsInstance<MarkdownBlock.Code>().single()
        assertThat(code.language).isEqualTo("kotlin")
        assertThat(code.code).isEqualTo("val x = 1")
    }

    @Test
    fun `the split of a partial answer plus its tail is the whole answer`() {
        val samples = listOf(
            "",
            "one line",
            "para one\n\npara two",
            "```\nunterminated",
            "```sh\necho hi\n```\ntrailing",
        )
        samples.forEach { sample ->
            val (stable, tail) = splitStreamingText(sample)
            assertThat(stable + tail).isEqualTo(sample)
        }
    }

    @Test
    fun `block level markdown is recognised`() {
        val source = """
            # Title

            Some **bold** text.

            - first
            - second

            > quoted

            ---
        """.trimIndent()

        val blocks = parseMarkdown(source, Color.Blue)

        assertThat(blocks.filterIsInstance<MarkdownBlock.Heading>().single().level).isEqualTo(1)
        assertThat(blocks.filterIsInstance<MarkdownBlock.ListItem>()).hasSize(2)
        assertThat(blocks.filterIsInstance<MarkdownBlock.Quote>()).hasSize(1)
        assertThat(blocks.filterIsInstance<MarkdownBlock.Rule>()).hasSize(1)
    }

    @Test
    fun `inline emphasis does not leak its delimiters`() {
        val paragraph = parseMarkdown("a **bold** and `code` and *italic*", Color.Blue)
            .filterIsInstance<MarkdownBlock.Paragraph>()
            .single()

        assertThat(paragraph.text.text).isEqualTo("a bold and code and italic")
        assertThat(paragraph.text.spanStyles).isNotEmpty()
    }

    @Test
    fun `an unmatched delimiter is left alone rather than swallowing the rest`() {
        val paragraph = parseMarkdown("2 * 3 * 4 = 24, and 5 ** 2", Color.Blue)
            .filterIsInstance<MarkdownBlock.Paragraph>()
            .single()

        assertThat(paragraph.text.text).contains("24")
    }

    /** An unsupported language must not be lexed with somebody else's rules. */
    @Test
    fun `language tags map to a lexer only when one exists`() {
        assertThat(syntaxLanguageOf("kotlin")).isEqualTo(SyntaxLanguage.KOTLIN)
        assertThat(syntaxLanguageOf("kt")).isEqualTo(SyntaxLanguage.KOTLIN)
        assertThat(syntaxLanguageOf("PY")).isEqualTo(SyntaxLanguage.PYTHON)
        assertThat(syntaxLanguageOf("bash")).isEqualTo(SyntaxLanguage.SHELL)
        assertThat(syntaxLanguageOf("c++")).isEqualTo(SyntaxLanguage.CPP)

        assertThat(syntaxLanguageOf(null)).isNull()
        assertThat(syntaxLanguageOf("")).isNull()
        assertThat(syntaxLanguageOf("brainfuck")).isNull()
        // "default" is the library's no-op lexer, not a language a fence names.
        assertThat(syntaxLanguageOf("default")).isNull()
    }
}
