package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.model.Role

/**
 * One finalised turn.
 *
 * Every parameter is stable, so this composable skips while another message
 * streams — which is the only reason a long conversation stays at frame rate.
 */
@Composable
internal fun MessageBubble(
    message: ChatMessageUi,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Role.USER -> UserBubble(message = message, actions = actions, modifier = modifier)
        Role.ASSISTANT -> AssistantBubble(message = message, actions = actions, modifier = modifier)
        Role.SYSTEM, Role.TOOL -> AsideBubble(message = message, modifier = modifier)
    }
}

@Composable
private fun UserBubble(message: ChatMessageUi, actions: MessageActions, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(BUBBLE_CORNER, BUBBLE_CORNER, TAIL_CORNER, BUBBLE_CORNER),
            modifier = Modifier.widthIn(max = USER_BUBBLE_MAX_WIDTH),
        ) {
            Column(modifier = Modifier.padding(BUBBLE_PADDING)) {
                Text(text = message.text, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    CopyButton(onClick = { actions.onCopy(message.text) })
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: ChatMessageUi, actions: MessageActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        message.reasoning?.let { ReasoningSection(reasoning = it, messageKey = message.id.value) }

        MarkdownText(text = message.text, onCopyCode = actions.onCopyCode)

        if (message.outcome == MessageOutcome.INTERRUPTED) InterruptedNote()

        message.stats?.let { MessageStatsRow(stats = it) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            CopyButton(onClick = { actions.onCopy(message.text) })
            if (message.isLastAssistant) {
                IconButton(onClick = actions.onRegenerate) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.chat_regenerate),
                        modifier = Modifier.size(ACTION_ICON),
                    )
                }
            }
        }
    }
}

@Composable
private fun AsideBubble(message: ChatMessageUi, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(vertical = ROW_GAP)) {
            Text(
                text = stringResource(
                    if (message.role == Role.SYSTEM) R.string.chat_role_system else R.string.chat_role_tool,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ReasoningSection(reasoning: String, messageKey: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(key = "reasoning-$messageKey") { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                modifier = Modifier.size(ACTION_ICON),
            )
            Text(
                text = stringResource(
                    if (expanded) R.string.chat_reasoning_hide else R.string.chat_reasoning_show,
                ),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = ROW_GAP),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = BUBBLE_PADDING, bottom = ROW_GAP),
            )
        }
    }
}

/**
 * The marker on a turn that stopped early.
 *
 * Deliberately not the persisted error text: `AppError.message` is
 * developer-facing, and after a restart the failure type is not recoverable
 * from the row anyway, so a specific sentence here would be invented.
 */
@Composable
private fun InterruptedNote(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(ACTION_ICON),
        )
        Text(
            text = stringResource(R.string.chat_interrupted),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CopyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.chat_copy_message),
            modifier = Modifier.size(ACTION_ICON),
        )
    }
}

internal val BUBBLE_CORNER = 18.dp
internal val TAIL_CORNER = Spacing.Xs
internal val BUBBLE_PADDING = Spacing.Md
internal val ROW_GAP = Spacing.Sm
internal val ACTION_ICON = 18.dp

private val USER_BUBBLE_MAX_WIDTH = 320.dp
