package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.SectionHeader
import io.github.jaypetez.ollamamobile.designsystem.component.StatusDot
import io.github.jaypetez.ollamamobile.designsystem.component.StatusKind
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.model.ModelId

/**
 * Picks the model — and therefore the server — this thread runs on.
 *
 * The on-device section is present and explicitly empty. There is no engine in
 * this build, and omitting the section entirely would read as "you have not
 * downloaded anything yet", sending the user to look for a download button that
 * cannot help them.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun TargetSwitcher(
    state: TargetPickerState,
    onSelect: (ModelId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            SheetHeading(text = stringResource(R.string.chat_targets_title))

            SheetSection(text = stringResource(R.string.chat_targets_remote))
            if (state.options.isEmpty()) {
                SheetHint(text = stringResource(R.string.chat_targets_none))
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(items = state.options, key = { it.modelId.value }) { option ->
                        TargetRow(option = option, onSelect = onSelect)
                    }
                }
            }

            SheetSection(text = stringResource(R.string.chat_targets_local))
            SheetHint(text = stringResource(R.string.chat_targets_local_unavailable))
        }
    }
}

@Composable
private fun TargetRow(option: TargetOptionUi, onSelect: (ModelId) -> Unit, modifier: Modifier = Modifier) {
    val unreachable = stringResource(R.string.chat_targets_unreachable)
    val subtitle = listOfNotNull(
        option.modelName.takeIf { it != option.displayName },
        option.serverLabel,
        unreachable.takeUnless { option.reachable },
    ).joinToString(separator = " · ")

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect(option.modelId) },
        headlineContent = { Text(text = option.displayName) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            StatusDot(
                status = if (option.reachable) StatusKind.Online else StatusKind.Offline,
                statusText = if (option.reachable) option.serverLabel.orEmpty() else unreachable,
            )
        },
        trailingContent = {
            if (option.selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.chat_targets_selected),
                )
            }
        },
    )
}

@Composable
internal fun SheetHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(start = SHEET_GUTTER, end = SHEET_GUTTER, bottom = SHEET_GAP),
    )
}

@Composable
private fun SheetSection(text: String, modifier: Modifier = Modifier) {
    SectionHeader(
        title = text,
        modifier = modifier.padding(horizontal = SHEET_GUTTER),
    )
}

@Composable
internal fun SheetHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(SHEET_GUTTER),
    )
}

internal val SHEET_GUTTER = Spacing.Xl
internal val SHEET_GAP = Spacing.Sm
