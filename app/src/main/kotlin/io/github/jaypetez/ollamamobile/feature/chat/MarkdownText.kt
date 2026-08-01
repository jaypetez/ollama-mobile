package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders [text] as Markdown, parsing it on a background dispatcher.
 *
 * Until the parse lands the raw text is drawn as a single paragraph, so there
 * is no empty frame; a previously parsed string comes back from the cache and
 * is used as the initial value, so scrolling a long transcript re-parses
 * nothing.
 */
@Composable
@Suppress("InjectDispatcher")
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onCopyCode: (String) -> Unit = {},
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val fallback = remember(text, linkColor) {
        MarkdownCache.peek(text, linkColor) ?: persistentListOf(MarkdownBlock.Paragraph(AnnotatedString(text)))
    }
    // Dispatchers.Default rather than an injected one: injection does not reach
    // into composition, and threading a CoroutineDispatcher down as a parameter
    // would make every composable on the way here unskippable.
    val blocks by produceState(fallback, text, linkColor) {
        value = withContext(Dispatchers.Default) { MarkdownCache.parse(text, linkColor) }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING),
    ) {
        blocks.forEach { block -> MarkdownBlockContent(block = block, onCopyCode = onCopyCode) }
    }
}

@Composable
private fun MarkdownBlockContent(block: MarkdownBlock, onCopyCode: (String) -> Unit) {
    when (block) {
        is MarkdownBlock.Paragraph -> Text(text = block.text, style = MaterialTheme.typography.bodyLarge)

        is MarkdownBlock.Heading -> Text(text = block.text, style = headingStyle(block.level))

        is MarkdownBlock.ListItem -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LIST_INDENT_STEP * block.depth),
        ) {
            Text(
                text = block.marker,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(MARKER_WIDTH),
            )
            Text(text = block.text, style = MaterialTheme.typography.bodyLarge)
        }

        is MarkdownBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(QUOTE_BAR_WIDTH)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(QUOTE_BAR_WIDTH))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = QUOTE_GAP),
            )
        }

        is MarkdownBlock.Code -> CodeBlock(
            code = block.code,
            language = block.language,
            onCopy = onCopyCode,
        )

        is MarkdownBlock.Rule -> HorizontalDivider()
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    HEADING_1 -> MaterialTheme.typography.headlineSmall
    HEADING_2 -> MaterialTheme.typography.titleLarge
    HEADING_3 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

// ---------------------------------------------------------------------------
// Streaming
// ---------------------------------------------------------------------------

/**
 * Splits a partially received answer into the part worth parsing and the tail.
 *
 * The boundary is the last blank line outside a fence, or the line after the
 * last closing fence. Everything before it is a finished block and can be
 * parsed and syntax-highlighted once; everything after is still growing and is
 * drawn as plain text.
 *
 * The fence rule is the whole point. Highlighting an *unterminated* fence means
 * re-lexing a code block on every token, and the cost grows with the block, so
 * the longer the snippet the model writes the slower the app gets while writing
 * it. Leaving the open fence in the tail makes that impossible by construction.
 */
internal fun splitStreamingText(text: String): Pair<String, String> {
    var offset = 0
    var boundary = 0
    var openFence: String? = null
    for (line in text.lineSequence()) {
        val next = minOf(offset + line.length + 1, text.length)
        val trimmed = line.trimStart()
        val fence = openFence
        when {
            fence != null -> if (trimmed.startsWith(fence)) {
                openFence = null
                boundary = next
            }

            trimmed.startsWith(BACKTICK_FENCE) -> openFence = BACKTICK_FENCE

            trimmed.startsWith(TILDE_FENCE) -> openFence = TILDE_FENCE

            line.isBlank() -> boundary = next
        }
        offset = next
    }
    return text.substring(0, boundary) to text.substring(boundary)
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

/** Parsed trees, keyed by exactly what produced them. */
private object MarkdownCache {
    private const val MAX_ENTRIES = 96

    private data class Key(
        val text: String,
        val linkColorBits: ULong,
    )

    private val entries = LinkedHashMap<Key, ImmutableList<MarkdownBlock>>()

    fun peek(text: String, linkColor: Color): ImmutableList<MarkdownBlock>? =
        synchronized(entries) { entries[Key(text, linkColor.value)] }

    fun parse(text: String, linkColor: Color): ImmutableList<MarkdownBlock> {
        val key = Key(text, linkColor.value)
        synchronized(entries) { entries[key] }?.let { return it }
        val parsed = parseMarkdown(text, linkColor)
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) entries.keys.firstOrNull()?.let(entries::remove)
            entries[key] = parsed
        }
        return parsed
    }
}

/**
 * A deliberately small block-level Markdown parser.
 *
 * Only what a chat model actually emits: fenced code, ATX headings, bullet and
 * ordered lists, block quotes, thematic breaks and paragraphs. It is here
 * rather than behind a library because the streaming path needs to control
 * *when* parsing happens, and every Compose Markdown renderer available parses
 * inside composition.
 */
internal fun parseMarkdown(source: String, linkColor: Color): ImmutableList<MarkdownBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        index = when {
            line.isBlank() -> {
                index + 1
            }

            trimmed.startsWith(BACKTICK_FENCE) || trimmed.startsWith(TILDE_FENCE) -> {
                readFence(lines, index, blocks)
            }

            THEMATIC_BREAK.matches(trimmed) -> {
                blocks += MarkdownBlock.Rule
                index + 1
            }

            HEADING.matches(trimmed) -> {
                val hashes = trimmed.takeWhile { it == '#' }
                blocks += MarkdownBlock.Heading(
                    level = hashes.length,
                    text = inlineMarkdown(trimmed.removePrefix(hashes).trim(), linkColor),
                )
                index + 1
            }

            trimmed.startsWith(">") -> {
                readQuote(lines, index, linkColor, blocks)
            }

            LIST_ITEM.containsMatchIn(line) -> {
                readListItem(lines, index, linkColor, blocks)
            }

            else -> {
                readParagraph(lines, index, linkColor, blocks)
            }
        }
    }
    return blocks.toImmutableList()
}

private fun readFence(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
    val opener = lines[start].trimStart()
    val marker = if (opener.startsWith(BACKTICK_FENCE)) BACKTICK_FENCE else TILDE_FENCE
    val language = opener
        .removePrefix(marker)
        .trim()
        .substringBefore(' ')
        .takeIf { it.isNotEmpty() }
    var index = start + 1
    val body = StringBuilder()
    while (index < lines.size && !lines[index].trimStart().startsWith(marker)) {
        if (body.isNotEmpty()) body.append('\n')
        body.append(lines[index])
        index++
    }
    blocks += MarkdownBlock.Code(language = language, code = body.toString())
    // Step past the closing fence when there is one; an unterminated fence at
    // the end of a finalised message simply ends the block.
    return if (index < lines.size) index + 1 else index
}

private fun readQuote(
    lines: List<String>,
    start: Int,
    linkColor: Color,
    blocks: MutableList<MarkdownBlock>,
): Int {
    var index = start
    val body = StringBuilder()
    while (index < lines.size && lines[index].trimStart().startsWith(">")) {
        if (body.isNotEmpty()) body.append(' ')
        body.append(lines[index].trimStart().removePrefix(">").trim())
        index++
    }
    blocks += MarkdownBlock.Quote(inlineMarkdown(body.toString(), linkColor))
    return index
}

private fun readListItem(
    lines: List<String>,
    start: Int,
    linkColor: Color,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val line = lines[start]
    val match = LIST_ITEM.find(line) ?: return start + 1
    val indent = line.takeWhile { it == ' ' || it == '\t' }.length
    val marker = match.groupValues[2]
    blocks += MarkdownBlock.ListItem(
        marker = if (marker.first().isDigit()) marker else BULLET,
        text = inlineMarkdown(line.substring(match.range.last + 1).trim(), linkColor),
        depth = indent / LIST_INDENT_SPACES,
    )
    return start + 1
}

private fun readParagraph(
    lines: List<String>,
    start: Int,
    linkColor: Color,
    blocks: MutableList<MarkdownBlock>,
): Int {
    var index = start
    val body = StringBuilder()
    while (index < lines.size && !isBlockStart(lines[index])) {
        if (body.isNotEmpty()) body.append('\n')
        body.append(lines[index])
        index++
    }
    if (body.isNotEmpty()) blocks += MarkdownBlock.Paragraph(inlineMarkdown(body.toString(), linkColor))
    return if (index == start) start + 1 else index
}

private fun isBlockStart(line: String): Boolean {
    if (line.isBlank()) return true
    val trimmed = line.trimStart()
    return trimmed.startsWith(BACKTICK_FENCE) ||
        trimmed.startsWith(TILDE_FENCE) ||
        trimmed.startsWith(">") ||
        HEADING.matches(trimmed) ||
        THEMATIC_BREAK.matches(trimmed) ||
        LIST_ITEM.containsMatchIn(line)
}

// ---------------------------------------------------------------------------
// Inline spans
// ---------------------------------------------------------------------------

private fun inlineMarkdown(source: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    appendInline(source, linkColor)
}

private fun AnnotatedString.Builder.appendInline(source: String, linkColor: Color) {
    var index = 0
    while (index < source.length) {
        val character = source[index]
        index = when {
            character == '\\' && index + 1 < source.length -> {
                append(source[index + 1])
                index + 2
            }

            character == '`' -> {
                appendInlineCode(source, index)
            }

            source.startsWith(STRONG, index) -> {
                appendWrapped(source, index, STRONG, BOLD, linkColor)
            }

            source.startsWith(STRONG_ALT, index) -> {
                appendWrapped(source, index, STRONG_ALT, BOLD, linkColor)
            }

            source.startsWith(STRIKE, index) -> {
                appendWrapped(source, index, STRIKE, STRUCK, linkColor)
            }

            character == '*' -> {
                appendWrapped(source, index, "*", ITALIC, linkColor)
            }

            character == '_' -> {
                appendWrapped(source, index, "_", ITALIC, linkColor)
            }

            character == '[' -> {
                appendLink(source, index, linkColor)
            }

            else -> {
                append(character)
                index + 1
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineCode(source: String, start: Int): Int {
    val close = source.indexOf('`', start + 1)
    if (close < 0) {
        append('`')
        return start + 1
    }
    withStyle(MONOSPACE) { append(source.substring(start + 1, close)) }
    return close + 1
}

private fun AnnotatedString.Builder.appendWrapped(
    source: String,
    start: Int,
    delimiter: String,
    style: SpanStyle,
    linkColor: Color,
): Int {
    val from = start + delimiter.length
    val close = source.indexOf(delimiter, from)
    if (close < 0 || close == from) {
        append(source[start])
        return start + 1
    }
    withStyle(style) { appendInline(source.substring(from, close), linkColor) }
    return close + delimiter.length
}

private fun AnnotatedString.Builder.appendLink(source: String, start: Int, linkColor: Color): Int {
    val labelEnd = source.indexOf(']', start)
    if (labelEnd < 0 || labelEnd + 1 >= source.length || source[labelEnd + 1] != '(') {
        append('[')
        return start + 1
    }
    val urlEnd = source.indexOf(')', labelEnd + 2)
    if (urlEnd < 0) {
        append('[')
        return start + 1
    }
    val label = source.substring(start + 1, labelEnd)
    val url = source.substring(labelEnd + 2, urlEnd).trim()
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    withLink(LinkAnnotation.Url(url = url, styles = styles)) { append(label) }
    return urlEnd + 1
}

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRUCK = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val MONOSPACE = SpanStyle(fontFamily = FontFamily.Monospace)

private val HEADING = Regex("^#{1,6}\\s+.*")
private val THEMATIC_BREAK = Regex("^([-*_])\\s*(\\1\\s*){2,}$")
private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+")

private const val BACKTICK_FENCE = "```"
private const val TILDE_FENCE = "~~~"
private const val STRONG = "**"
private const val STRONG_ALT = "__"
private const val STRIKE = "~~"
private const val BULLET = "•"
private const val LIST_INDENT_SPACES = 2
private const val HEADING_1 = 1
private const val HEADING_2 = 2
private const val HEADING_3 = 3

private val BLOCK_SPACING = Spacing.Sm
private val MARKER_WIDTH = 20.dp
private val QUOTE_BAR_WIDTH = 3.dp
private val QUOTE_GAP = 10.dp
private val LIST_INDENT_STEP = Spacing.Lg
