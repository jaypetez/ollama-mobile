package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.EmptyState
import io.github.jaypetez.ollamamobile.designsystem.component.ErrorBanner
import io.github.jaypetez.ollamamobile.designsystem.component.LoadingIndicator
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.model.SamplingParams

private enum class ChatSheet { NONE, TARGETS, SYSTEM_PROMPT, SAMPLER }

/**
 * The chat surface, with no knowledge of where its state came from.
 *
 * The in-flight answer arrives as [frames] rather than inside [state]. If it
 * were in the state this composable would recompose at the streaming rate, and
 * the transcript would recompose with it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ChatScreen(
    state: ChatUiState,
    frames: StreamingFrames,
    targets: TargetPickerState,
    sampling: SamplingParams,
    systemPrompt: String?,
    actions: ChatScreenActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var sheet by rememberSaveable { mutableStateOf(ChatSheet.NONE) }
    val open = state as? ChatUiState.Open

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { ChatTopBar(header = open?.header, actions = actions, onOpenSheet = { sheet = it }) },
        bottomBar = {
            Column {
                (state as? ChatUiState.Failed)?.let {
                    ChatFailureBanner(
                        failure = it.failure,
                        onDismiss = actions.onDismissFailure,
                        onRetry = actions.onRegenerate,
                        modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                    )
                }
                open?.let {
                    ChatInputBar(
                        composer = it.composer,
                        isStreaming = state is ChatUiState.Streaming,
                        onSend = actions.onSend,
                        onStop = actions.onStop,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        ChatBody(
            state = state,
            frames = frames,
            actions = actions,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    ChatSheets(
        sheet = sheet,
        targets = targets,
        sampling = sampling,
        systemPrompt = systemPrompt,
        actions = actions,
        onClose = { sheet = ChatSheet.NONE },
    )
}

@Composable
private fun ChatSheets(
    sheet: ChatSheet,
    targets: TargetPickerState,
    sampling: SamplingParams,
    systemPrompt: String?,
    actions: ChatScreenActions,
    onClose: () -> Unit,
) {
    when (sheet) {
        ChatSheet.NONE -> Unit

        ChatSheet.TARGETS -> TargetSwitcher(
            state = targets,
            onSelect = {
                actions.onSelectModel(it)
                onClose()
            },
            onDismiss = onClose,
        )

        ChatSheet.SYSTEM_PROMPT -> SystemPromptSheet(
            prompt = systemPrompt,
            onSave = {
                actions.onSetSystemPrompt(it)
                onClose()
            },
            onDismiss = onClose,
        )

        ChatSheet.SAMPLER -> SamplerSheet(
            sampling = sampling,
            onSave = {
                actions.onSetSampling(it)
                onClose()
            },
            onDismiss = onClose,
        )
    }
}

@Composable
private fun ChatBody(
    state: ChatUiState,
    frames: StreamingFrames,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
) {
    val messageActions = remember(actions) {
        MessageActions(
            onCopy = actions.onCopy,
            onCopyCode = actions.onCopy,
            onRegenerate = actions.onRegenerate,
        )
    }
    val listState = rememberLazyListState()
    val stickToBottom = rememberStickToBottom(listState)

    Box(modifier = modifier) {
        when (state) {
            is ChatUiState.Loading -> Centred { LoadingIndicator(label = stringResource(R.string.chat_loading)) }

            is ChatUiState.Empty -> Centred {
                EmptyState(
                    icon = Icons.Rounded.Forum,
                    title = stringResource(R.string.chat_empty_title),
                    description = stringResource(R.string.chat_empty_body),
                )
            }

            is ChatUiState.Open -> MessageList(
                messages = state.messages,
                listState = listState,
                stickToBottom = stickToBottom,
                modifier = Modifier.fillMaxSize(),
                streamingItem = if (state is ChatUiState.Streaming) {
                    { StreamingMessage(frames = frames, onCopyCode = messageActions.onCopyCode) }
                } else {
                    null
                },
            ) { message ->
                MessageBubble(message = message, actions = messageActions)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    header: ChatHeader?,
    actions: ChatScreenActions,
    onOpenSheet: (ChatSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = header?.title ?: stringResource(R.string.chat_title_new),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                header?.serverLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = actions.onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.chat_back),
                )
            }
        },
        actions = {
            TextButton(onClick = { onOpenSheet(ChatSheet.TARGETS) }) {
                Text(
                    text = header?.modelName ?: stringResource(R.string.chat_no_model_selected),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OverflowMenu(canExport = header?.canExport == true, actions = actions, onOpenSheet = onOpenSheet)
        },
    )
}

@Composable
private fun OverflowMenu(
    canExport: Boolean,
    actions: ChatScreenActions,
    onOpenSheet: (ChatSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.chat_more_actions),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.chat_edit_system_prompt)) },
                onClick = {
                    open = false
                    onOpenSheet(ChatSheet.SYSTEM_PROMPT)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.chat_open_sampler)) },
                onClick = {
                    open = false
                    onOpenSheet(ChatSheet.SAMPLER)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.chat_export_markdown)) },
                enabled = canExport,
                onClick = {
                    open = false
                    actions.onExport(ExportFormat.MARKDOWN)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.chat_export_json)) },
                enabled = canExport,
                onClick = {
                    open = false
                    actions.onExport(ExportFormat.JSON)
                },
            )
        }
    }
}

/**
 * The failure strip.
 *
 * It names the specific failure — unreachable, blocked by LAN-only mode, model
 * not loaded — because each needs a different action from the user, and one
 * "something went wrong" would hide which of them happened. The sentences come
 * from resources keyed on the error *type*: `AppError.message` is documented as
 * developer-facing and is never shown.
 */
@Composable
private fun ChatFailureBanner(
    failure: ChatFailure,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = listOfNotNull(
        stringResource(failure.titleRes),
        stringResource(failure.bodyRes),
        failure.detail,
    ).joinToString(separator = System.lineSeparator())
    ErrorBanner(
        message = message,
        modifier = modifier,
        onDismiss = onDismiss,
        onRetry = onRetry.takeIf { failure.retryable },
        retryLabel = stringResource(R.string.chat_error_retry).takeIf { failure.retryable },
    )
}

@Composable
private fun Centred(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(GUTTER),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private val GUTTER = Spacing.Xxl
