package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R

/**
 * The answer being generated, and the only thing on screen that recomposes
 * while it is.
 *
 * It subscribes to its own state rather than receiving the text as a parameter.
 * A parameter would mean the caller recomposes too, and the caller owns the
 * list — so twenty-five times a second the whole transcript would be
 * re-evaluated to draw one growing paragraph.
 *
 * The text is split: everything up to the last completed block is handed to
 * [MarkdownText], which parses and highlights it once, and only the trailing
 * fragment is redrawn as plain text behind the caret.
 */
@Composable
internal fun StreamingMessage(
    frames: StreamingFrames,
    modifier: Modifier = Modifier,
    onCopyCode: (String) -> Unit = {},
) {
    val frame = frames.state.collectAsStateWithLifecycle().value ?: return
    val split = remember(frame.text) { splitStreamingText(frame.text) }
    val generating = frame.phase != StreamPhase.SETTLING

    // Announced once, when the answer is finished. A live region carrying the
    // partial text would make TalkBack read the answer again on every frame.
    val announcement = when (frame.phase) {
        StreamPhase.WAITING -> stringResource(R.string.chat_waiting)
        StreamPhase.GENERATING -> stringResource(R.string.chat_generating)
        StreamPhase.SETTLING -> frame.text
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        if (frame.reasoning.isNotEmpty()) ThinkingStrip()

        if (split.first.isNotEmpty()) MarkdownText(text = split.first, onCopyCode = onCopyCode)

        TailText(
            tail = split.second,
            generating = generating,
            announcement = announcement,
        )
    }
}

/**
 * The still-growing fragment.
 *
 * `clearAndSetSemantics` is what keeps TalkBack quiet: without it every
 * published frame is a content change on a text node, and a screen reader
 * re-reads a paragraph that is still being written.
 */
@Composable
private fun TailText(
    tail: String,
    generating: Boolean,
    announcement: String,
    modifier: Modifier = Modifier,
) {
    val semantics = modifier.clearAndSetSemantics {
        contentDescription = announcement
        liveRegion = LiveRegionMode.Polite
    }
    if (tail.isEmpty() && generating) {
        Box(modifier = semantics) { Caret() }
        return
    }
    Row(modifier = semantics, verticalAlignment = Alignment.Bottom) {
        Text(
            text = if (generating) tail + CARET_GLYPH else tail,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** A blinking block, shown while the first token is still on its way. */
@Composable
private fun Caret(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha = transition.animateFloat(
        initialValue = CARET_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CARET_PERIOD_MILLIS), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Box(
        modifier = modifier
            .width(CARET_WIDTH)
            .height(CARET_HEIGHT)
            // Read inside the layer block so the blink costs a redraw of one
            // box rather than a recomposition of the bubble.
            .graphicsLayer { this.alpha = alpha.value }
            .clip(RoundedCornerShape(CARET_WIDTH))
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun ThinkingStrip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Icon(
            imageVector = Icons.Rounded.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ACTION_ICON),
        )
        Text(
            text = stringResource(R.string.chat_thinking),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val CARET_GLYPH = "▌"
private const val CARET_PERIOD_MILLIS = 600
private const val CARET_MIN_ALPHA = 0.15f

private val CARET_WIDTH = 3.dp
private val CARET_HEIGHT = 18.dp
