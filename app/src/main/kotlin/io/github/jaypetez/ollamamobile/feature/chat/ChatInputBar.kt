package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

/**
 * The composer.
 *
 * The draft lives here and nowhere else. Hoisting it into the screen state
 * would put a keystroke's worth of change above the transcript, and every
 * character typed would recompose the message list; the only thing that needs
 * the text is the send button, which is in this file.
 */
@Composable
internal fun ChatInputBar(
    composer: ComposerState,
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = BAR_ELEVATION,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(BAR_PADDING)) {
            composer.blocker?.let { blocker ->
                Text(
                    text = stringResource(blocker.messageRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = HINT_INSET, bottom = HINT_GAP),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(text = stringResource(R.string.chat_input_hint)) },
                    maxLines = MAX_LINES,
                    shape = RoundedCornerShape(FIELD_CORNER),
                )
                if (isStreaming) {
                    FilledIconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            onSend(draft)
                            draft = ""
                        },
                        enabled = composer.canSend && draft.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = stringResource(R.string.chat_send),
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_LINES = 6

private val BAR_ELEVATION = 3.dp
private val BAR_PADDING = Spacing.Md
private val FIELD_GAP = Spacing.Sm
private val FIELD_CORNER = 22.dp
private val HINT_INSET = Spacing.Md
private val HINT_GAP = Spacing.Xs
