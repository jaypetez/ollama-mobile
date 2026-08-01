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
 * Picks the model — and therefore where the answer is generated — for this
 * thread.
 *
 * The on-device section is always present, and what it says when it is empty
 * depends on *why* it is empty. With no engine in the build it says so; with an
 * engine and nothing installed it says that instead. Rendering the same blank
 * list for both would send a user of the default build looking for a download
 * button that cannot help them.
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

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                item(key = "local-header") {
                    SheetSection(text = stringResource(R.string.chat_targets_local))
                }
                when {
                    !state.localAvailable -> item(key = "local-no-engine") {
                        SheetHint(text = stringResource(R.string.chat_targets_local_unavailable))
                    }

                    state.localOptions.isEmpty() -> item(key = "local-empty") {
                        SheetHint(text = stringResource(R.string.chat_targets_local_none))
                    }

                    else -> items(items = state.localOptions, key = { it.modelId.value }) { option ->
                        LocalTargetRow(option = option, onSelect = onSelect)
                    }
                }

                item(key = "remote-header") {
                    SheetSection(text = stringResource(R.string.chat_targets_remote))
                }
                if (state.options.isEmpty()) {
                    item(key = "remote-empty") {
                        SheetHint(text = stringResource(R.string.chat_targets_none))
                    }
                } else {
                    items(items = state.options, key = { it.modelId.value }) { option ->
                        TargetRow(option = option, onSelect = onSelect)
                    }
                }
            }
        }
    }
}

/**
 * One on-device model.
 *
 * The warm badge is the point of this row. It is the same fact the router
 * weighs most heavily — a resident model answers immediately, a cold one maps
 * gigabytes first — so hiding it would leave the user picking between two
 * options that look identical and behave nothing alike.
 */
@Composable
private fun LocalTargetRow(
    option: LocalTargetOptionUi,
    onSelect: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val warmLabel = stringResource(R.string.chat_targets_warm)
    val coldLabel = stringResource(R.string.chat_targets_cold)
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            // A refused memory verdict is not clickable. The row stays so the
            // user can see the model is there and read why it will not run.
            .clickable(enabled = option.loadable) { onSelect(option.modelId) },
        headlineContent = { Text(text = option.displayName) },
        supportingContent = {
            Text(
                text = listOf(if (option.warm) warmLabel else coldLabel, option.detail)
                    .joinToString(separator = " · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (option.loadable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        leadingContent = {
            StatusDot(
                status = when {
                    !option.loadable -> StatusKind.Offline
                    option.warm -> StatusKind.Online
                    else -> StatusKind.Unknown
                },
                statusText = if (option.warm) warmLabel else coldLabel,
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
