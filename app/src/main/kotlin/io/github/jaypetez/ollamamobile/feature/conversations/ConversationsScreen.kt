package io.github.jaypetez.ollamamobile.feature.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.EmptyState
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaTextField
import io.github.jaypetez.ollamamobile.designsystem.component.SectionHeader
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.ui.relativeTime
import kotlinx.collections.immutable.persistentListOf

@Composable
fun ConversationsRoute(
    onOpenConversation: (ConversationId) -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenConversation, onNewConversation) {
        ConversationsActions(
            onQueryChange = viewModel::onQueryChange,
            onOpen = { id -> onOpenConversation(ConversationId(id)) },
            onNew = onNewConversation,
            onDelete = viewModel::onDelete,
            onUndoDelete = viewModel::onUndoDelete,
            onUndoExpire = viewModel::onUndoWindowExpired,
            onStartRename = viewModel::onStartRename,
            onRenameTextChange = viewModel::onRenameTextChange,
            onConfirmRename = viewModel::onConfirmRename,
            onCancelRename = viewModel::onCancelRename,
            onTogglePinned = viewModel::onTogglePinned,
        )
    }
    ConversationsScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    actions: ConversationsActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UndoDeleteSnackbar(
        title = state.undoDeleteTitle,
        hostState = snackbarHostState,
        onUndo = actions.onUndoDelete,
        onExpire = actions.onUndoExpire,
    )

    // One root emitter: the dialog is a sibling of the scaffold, and a screen
    // that emits two things at its top level is both a lint failure and a hint
    // that the caller cannot position it.
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(title = { Text(text = stringResource(R.string.conversations_title)) })
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                FloatingActionButton(onClick = actions.onNew) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.conversations_new),
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // The search field is a text entry point, so the content has
                    // to rise above the keyboard rather than being covered by it.
                    .imePadding(),
            ) {
                SearchField(
                    query = state.query,
                    onQueryChange = actions.onQueryChange,
                    modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                )
                ConversationsBody(state = state, actions = actions, modifier = Modifier.fillMaxSize())
            }
        }

        val rename = state.renameTarget
        if (rename != null) {
            RenameDialog(
                text = rename.text,
                onTextChange = actions.onRenameTextChange,
                onConfirm = actions.onConfirmRename,
                onDismiss = actions.onCancelRename,
            )
        }
    }
}

@Composable
private fun ConversationsBody(
    state: ConversationsUiState,
    actions: ConversationsActions,
    modifier: Modifier = Modifier,
) {
    when {
        state.showFirstRunEmptyState -> EmptyState(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = stringResource(R.string.conversations_empty_title),
            description = stringResource(R.string.conversations_empty_description),
            modifier = modifier,
            action = {
                OllamaButton(
                    text = stringResource(R.string.conversations_empty_action),
                    onClick = actions.onNew,
                )
            },
        )

        state.showNoSearchResults -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.conversations_no_results_title),
            description = stringResource(R.string.conversations_no_results_description),
            modifier = modifier,
        )

        state.isSearching -> SearchResults(
            state = state,
            onOpen = actions.onOpen,
            modifier = modifier,
        )

        else -> ConversationList(
            state = state,
            actions = actions,
            modifier = modifier,
        )
    }
}

@Composable
private fun SearchResults(
    state: ConversationsUiState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = Spacing.Lg,
            end = Spacing.Lg,
            bottom = Spacing.Huge,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        item {
            SectionHeader(title = stringResource(R.string.conversations_search_results_title))
        }
        items(items = state.searchHits, key = { hit -> hit.messageId }) { hit ->
            OllamaCard(
                onClick = { onOpen(hit.conversationId) },
                clickLabel = stringResource(R.string.conversations_open_click_label),
            ) {
                Text(text = hit.conversationTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = SNIPPET_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = relativeTime(hit.createdAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationList(
    state: ConversationsUiState,
    actions: ConversationsActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = Spacing.Lg,
            end = Spacing.Lg,
            bottom = Spacing.Huge,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        items(items = state.conversations, key = { item -> item.id }) { item ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        actions.onDelete(item.id)
                        true
                    } else {
                        false
                    }
                },
            )
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = { SwipeDeleteBackground() },
                enableDismissFromStartToEnd = false,
            ) {
                ConversationRow(item = item, actions = actions)
            }
        }
    }
}

@Composable
private fun SwipeDeleteBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Lg),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.conversations_delete),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ConversationRow(
    item: ConversationItemUiState,
    actions: ConversationsActions,
    modifier: Modifier = Modifier,
) {
    OllamaCard(
        modifier = modifier,
        onClick = { actions.onOpen(item.id) },
        clickLabel = stringResource(R.string.conversations_open_click_label),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = relativeTime(item.updatedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { actions.onTogglePinned(item.id, !item.pinned) }) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = stringResource(
                        if (item.pinned) R.string.conversations_unpin else R.string.conversations_pin,
                    ),
                    tint = if (item.pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            OllamaButton(
                text = stringResource(R.string.conversations_rename),
                onClick = { actions.onStartRename(item.id, item.title) },
                style = OllamaButtonStyle.Text,
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OllamaTextField(
        value = query,
        onValueChange = onQueryChange,
        label = stringResource(R.string.conversations_search_label),
        modifier = modifier,
        placeholder = stringResource(R.string.conversations_search_placeholder),
        supportingText = stringResource(R.string.conversations_search_supporting),
        trailing = {
            if (query.isEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                )
            } else {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.conversations_search_clear),
                    )
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.conversations_rename_title)) },
        text = {
            OllamaTextField(
                value = text,
                onValueChange = onTextChange,
                label = stringResource(R.string.conversations_rename_label),
                modifier = Modifier.imePadding(),
            )
        },
        confirmButton = {
            OllamaButton(
                text = stringResource(R.string.action_save),
                onClick = onConfirm,
                style = OllamaButtonStyle.Text,
                enabled = text.isNotBlank(),
            )
        },
        dismissButton = {
            OllamaButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                style = OllamaButtonStyle.Text,
            )
        },
    )
}

/**
 * Shows the undo snackbar for as long as the view model's window is open.
 *
 * The snackbar does not own the deadline — the view model does. If it did, a
 * configuration change would dismiss the snackbar and the delete would never be
 * committed, leaving a row that is invisible now and back after a restart.
 */
@Composable
private fun UndoDeleteSnackbar(
    title: String?,
    hostState: SnackbarHostState,
    onUndo: () -> Unit,
    onExpire: () -> Unit,
) {
    val message = title?.let { stringResource(R.string.conversations_deleted, it) }
    val actionLabel = stringResource(R.string.action_undo)
    val currentUndo by rememberUpdatedState(onUndo)
    val currentExpired by rememberUpdatedState(onExpire)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> currentUndo()
            SnackbarResult.Dismissed -> currentExpired()
        }
    }
}

private const val SNIPPET_MAX_LINES = 3

@Preview
@Composable
private fun ConversationsScreenPreview() {
    OllamaPreviewTheme {
        ConversationsScreen(
            state = ConversationsUiState(
                isLoading = false,
                conversations = persistentListOf(
                    ConversationItemUiState(
                        id = "1",
                        title = "Refactoring the sync layer",
                        updatedAtMillis = 0L,
                        pinned = true,
                    ),
                ),
            ),
            actions = ConversationsActions(
                onQueryChange = {},
                onOpen = {},
                onNew = {},
                onDelete = {},
                onUndoDelete = {},
                onUndoExpire = {},
                onStartRename = { _, _ -> },
                onRenameTextChange = {},
                onConfirmRename = {},
                onCancelRename = {},
                onTogglePinned = { _, _ -> },
            ),
        )
    }
}
