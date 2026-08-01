package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jaypetez.ollamamobile.R

/**
 * Edits the instructions sent ahead of every message in this thread.
 *
 * Empty is saved as *no system message*, not as an empty one. The distinction
 * is real on the wire — `SamplingParams` and `InferenceRequest` both preserve
 * it — and an empty string is a different request from an absent field.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SystemPromptSheet(
    prompt: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(prompt) { mutableStateOf(prompt.orEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = SHEET_GUTTER),
            verticalArrangement = Arrangement.spacedBy(SHEET_GAP),
        ) {
            Text(
                text = stringResource(R.string.chat_system_prompt_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.chat_system_prompt_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(R.string.chat_system_prompt_hint)) },
                minLines = MIN_LINES,
                maxLines = MAX_LINES,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SHEET_GUTTER),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        draft = ""
                        onSave(null)
                    },
                ) {
                    Text(text = stringResource(R.string.chat_system_prompt_clear))
                }
                Button(onClick = { onSave(draft.takeIf { it.isNotBlank() }) }) {
                    Text(text = stringResource(R.string.chat_system_prompt_save))
                }
            }
        }
    }
}

private const val MIN_LINES = 3
private const val MAX_LINES = 10
