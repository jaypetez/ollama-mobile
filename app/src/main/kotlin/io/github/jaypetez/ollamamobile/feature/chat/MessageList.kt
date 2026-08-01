package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The transcript.
 *
 * Two things here are load-bearing rather than idiomatic:
 *
 *  * **`key` and `contentType` per item.** Without a key, appending a turn
 *    re-binds every row below it; without a content type the list cannot reuse
 *    a user bubble's nodes for the next user bubble.
 *  * **[itemContent] as a slot.** The list owns keying, content types and
 *    scrolling; what a row looks like is the caller's business. It also gives a
 *    test somewhere to count recompositions of a real row inside the real list.
 */
@Composable
internal fun MessageList(
    messages: ImmutableList<ChatMessageUi>,
    listState: LazyListState,
    stickToBottom: StickToBottomState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(GUTTER),
    streamingItem: (@Composable () -> Unit)? = null,
    itemContent: @Composable (ChatMessageUi) -> Unit,
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(ITEM_GAP),
        ) {
            items(
                items = messages,
                key = { it.id.value },
                contentType = { it.contentType },
            ) { message ->
                itemContent(message)
            }
            if (streamingItem != null) {
                item(key = STREAMING_ITEM_KEY, contentType = STREAMING_CONTENT_TYPE) {
                    streamingItem()
                }
            }
        }
        ScrollToBottomButton(
            state = stickToBottom,
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(GUTTER),
        )
    }
}

/**
 * Whether the list is following new content.
 *
 * A separate object rather than a boolean in the screen state so that flipping
 * it recomposes the one button that reads it, not the list that owns it.
 */
@Stable
internal class StickToBottomState {
    var pinned: Boolean by mutableStateOf(true)
        internal set
}

/**
 * Keeps the newest content in view without fighting the reader.
 *
 * The rule is intent, not geometry. "Am I at the bottom?" flips to false the
 * instant a token makes the content taller, so a purely geometric test stops
 * following after the first frame. Instead a *drag* unpins — an explicit act by
 * the user — and scrolling back to the bottom pins again. Programmatic scrolls
 * do not appear as drag interactions, so the loop cannot unpin itself.
 *
 * Everything below reads scroll state through `snapshotFlow` and
 * `derivedStateOf`, never in the composition body: `layoutInfo` changes on
 * every frame of a scroll, and reading it while composing would recompose the
 * whole transcript at exactly the moment it must not.
 */
@Composable
internal fun rememberStickToBottom(listState: LazyListState): StickToBottomState {
    val state = remember(listState) { StickToBottomState() }
    val nearBottom = remember(listState) { derivedStateOf { listState.isNearBottom() } }

    LaunchedEffect(listState, state) {
        launch {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) state.pinned = false
            }
        }
        launch {
            snapshotFlow { nearBottom.value }
                .distinctUntilChanged()
                .collect { atBottom -> if (atBottom) state.pinned = true }
        }
        snapshotFlow { listState.layoutInfo.contentExtent() }
            .distinctUntilChanged()
            .collect { if (state.pinned) listState.scrollToEnd() }
    }
    return state
}

@Composable
private fun ScrollToBottomButton(
    state: StickToBottomState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = !state.pinned,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        SmallFloatingActionButton(
            onClick = {
                state.pinned = true
                scope.launch { listState.scrollToEnd() }
            },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(R.string.chat_scroll_to_bottom),
            )
        }
    }
}

/**
 * A number that changes whenever the content below the fold does.
 *
 * The item count alone is not enough: a streaming bubble grows without the list
 * gaining an item, and that is exactly the case that has to keep scrolling.
 */
private fun LazyListLayoutInfo.contentExtent(): Long {
    val last = visibleItemsInfo.lastOrNull() ?: return 0L
    return totalItemsCount.toLong() * EXTENT_SCALE + (last.offset + last.size)
}

private fun LazyListState.isNearBottom(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + STICK_SLACK_PX
}

/**
 * Scrolls so the end of the last item sits at the bottom of the viewport.
 *
 * The offset is computed rather than passed as a large sentinel: a bubble
 * taller than the screen has to be scrolled past its own top, and asking for
 * index-only alignment would park the viewport on the answer's first line.
 */
private suspend fun LazyListState.scrollToEnd() {
    val info = layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0) return
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val lastSize = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }?.size ?: 0
    scrollToItem(lastIndex, (lastSize - viewport).coerceAtLeast(0))
}

internal const val STREAMING_ITEM_KEY = "chat-streaming-turn"
internal const val STREAMING_CONTENT_TYPE = "streaming"

private const val EXTENT_SCALE = 1_000_000L
private const val STICK_SLACK_PX = 24

private val GUTTER = Spacing.Lg
private val ITEM_GAP = Spacing.Md
