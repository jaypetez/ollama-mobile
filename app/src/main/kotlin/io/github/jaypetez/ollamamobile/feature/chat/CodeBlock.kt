package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A fenced code block, highlighted off the main thread and cached by content.
 *
 * ## What must not happen here
 *
 * Highlighting is a full lex of the snippet. Doing it while the fence is still
 * open means doing it again on every token, over a string that is getting
 * longer each time — quadratic work, on the UI thread, exactly while the user
 * is watching. This composable is therefore only ever reached for a *closed*
 * fence: [splitStreamingText] keeps an unterminated one out of the parsed tree
 * entirely, and it is drawn as plain text until its closing fence arrives.
 *
 * A language [Highlights] does not know is not approximated with another
 * lexer's rules; it is rendered as plain monospace, and no highlighter runs.
 */
@Composable
@Suppress("InjectDispatcher")
internal fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
) {
    val syntax = remember(language) { syntaxLanguageOf(language) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < LUMINANCE_MIDPOINT
    val plain = remember(code) { AnnotatedString(code) }
    val seed = remember(code, syntax, dark) { HighlightCache.peek(code, syntax, dark) ?: plain }

    // See MarkdownText for why the dispatcher is not injected.
    val rendered by produceState(seed, code, syntax, dark) {
        value = if (syntax == null) {
            plain
        } else {
            withContext(Dispatchers.Default) {
                HighlightCache.highlight(code, syntax, dark)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(CORNER),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = GUTTER, end = TRAILING_GUTTER),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = language?.lowercase() ?: stringResource(R.string.chat_code_plain),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { onCopy(code) }) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_code),
                        modifier = Modifier.size(ICON),
                    )
                }
            }
            Text(
                text = rendered,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = GUTTER, end = GUTTER, bottom = GUTTER),
            )
        }
    }
}

/**
 * Highlighted snippets, keyed by the exact input that produced them.
 *
 * The cache is what makes scrolling a transcript full of code free: the same
 * block is lexed once per process, not once per time it comes back on screen.
 */
private object HighlightCache {
    private const val MAX_ENTRIES = 48

    private data class Key(
        val code: String,
        val language: SyntaxLanguage,
        val dark: Boolean,
    )

    private val entries = LinkedHashMap<Key, AnnotatedString>()

    fun peek(code: String, language: SyntaxLanguage?, dark: Boolean): AnnotatedString? {
        if (language == null) return null
        return synchronized(entries) { entries[Key(code, language, dark)] }
    }

    fun highlight(code: String, language: SyntaxLanguage, dark: Boolean): AnnotatedString {
        val key = Key(code, language, dark)
        synchronized(entries) { entries[key] }?.let { return it }
        val built = build(code, language, dark)
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) entries.keys.firstOrNull()?.let(entries::remove)
            entries[key] = built
        }
        return built
    }

    private fun build(code: String, language: SyntaxLanguage, dark: Boolean): AnnotatedString {
        val spans = Highlights
            .Builder()
            .code(code)
            .language(language)
            .theme(SyntaxThemes.default(darkMode = dark))
            .build()
            .getHighlights()
        return buildAnnotatedString {
            append(code)
            spans.forEach { span ->
                // The lexer reports offsets into the string it was given; clamp
                // anyway, because an out-of-range addStyle throws and a syntax
                // colour is never worth a crash.
                val start = span.location.start.coerceIn(0, code.length)
                val end = span.location.end.coerceIn(start, code.length)
                if (start == end) return@forEach
                if (span is ColorHighlight) {
                    addStyle(SpanStyle(color = Color(OPAQUE_ALPHA or (span.rgb.toLong() and RGB_MASK))), start, end)
                } else if (span is BoldHighlight) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                }
            }
        }
    }
}

/**
 * Maps a fence's language tag onto a lexer, or null when there is not one.
 *
 * Null is a real answer: rendering Python with the C lexer produces confidently
 * wrong colours, which is worse than no colours at all.
 */
internal fun syntaxLanguageOf(tag: String?): SyntaxLanguage? {
    val key = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    ALIASES[key]?.let { return it }
    return SyntaxLanguage.entries.firstOrNull { it != SyntaxLanguage.DEFAULT && it.name.lowercase() == key }
}

private val ALIASES: Map<String, SyntaxLanguage> = mapOf(
    "kt" to SyntaxLanguage.KOTLIN,
    "kts" to SyntaxLanguage.KOTLIN,
    "js" to SyntaxLanguage.JAVASCRIPT,
    "jsx" to SyntaxLanguage.JAVASCRIPT,
    "mjs" to SyntaxLanguage.JAVASCRIPT,
    "ts" to SyntaxLanguage.TYPESCRIPT,
    "tsx" to SyntaxLanguage.TYPESCRIPT,
    "py" to SyntaxLanguage.PYTHON,
    "python3" to SyntaxLanguage.PYTHON,
    "rb" to SyntaxLanguage.RUBY,
    "rs" to SyntaxLanguage.RUST,
    "sh" to SyntaxLanguage.SHELL,
    "bash" to SyntaxLanguage.SHELL,
    "zsh" to SyntaxLanguage.SHELL,
    "console" to SyntaxLanguage.SHELL,
    "c++" to SyntaxLanguage.CPP,
    "cxx" to SyntaxLanguage.CPP,
    "cc" to SyntaxLanguage.CPP,
    "hpp" to SyntaxLanguage.CPP,
    "h" to SyntaxLanguage.C,
    "c#" to SyntaxLanguage.CSHARP,
    "cs" to SyntaxLanguage.CSHARP,
    "golang" to SyntaxLanguage.GO,
    "coffee" to SyntaxLanguage.COFFEESCRIPT,
)

private const val OPAQUE_ALPHA = 0xFF000000L
private const val RGB_MASK = 0x00FFFFFFL
private const val LUMINANCE_MIDPOINT = 0.5f

private val CORNER = 10.dp
private val GUTTER = Spacing.Md
private val TRAILING_GUTTER = Spacing.Xs
private val ICON = 18.dp
