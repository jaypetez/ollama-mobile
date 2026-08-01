package io.github.jaypetez.ollamamobile.feature.servers

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import io.github.jaypetez.ollamamobile.designsystem.component.ConfirmDialog
import io.github.jaypetez.ollamamobile.designsystem.component.EmptyState
import io.github.jaypetez.ollamamobile.designsystem.component.ErrorBanner
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaTextField
import io.github.jaypetez.ollamamobile.designsystem.component.SectionHeader
import io.github.jaypetez.ollamamobile.designsystem.component.StatusKind
import io.github.jaypetez.ollamamobile.designsystem.component.StatusLabel
import io.github.jaypetez.ollamamobile.designsystem.theme.MonospaceTextStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.ui.formatBytes
import io.github.jaypetez.ollamamobile.ui.relativeTime
import kotlinx.collections.immutable.persistentListOf

@Composable
fun ServerDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack) {
        ServerDetailActions(
            onBack = onBack,
            onProbe = viewModel::onProbe,
            onRefreshModels = viewModel::onRefreshModels,
            onPullNameChange = viewModel::onPullModelNameChange,
            onStartPull = viewModel::onStartPull,
            onDismissPull = viewModel::onDismissPull,
            onRequestDeleteModel = viewModel::onRequestDeleteModel,
            onConfirmDeleteModel = viewModel::onConfirmDeleteModel,
            onCancelDeleteModel = viewModel::onCancelDeleteModel,
            onDismissMessage = viewModel::onDismissMessage,
        )
    }
    ServerDetailScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    state: ServerDetailUiState,
    actions: ServerDetailActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.messageRes?.let { stringResource(it) }
    val currentDismiss by rememberUpdatedState(actions.onDismissMessage)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        currentDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(text = state.label.ifEmpty { stringResource(R.string.server_detail_title) }) },
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { padding ->
            if (state.missing) {
                EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.server_detail_missing_title),
                    description = stringResource(R.string.server_detail_missing_description),
                    modifier = Modifier.padding(padding),
                    action = {
                        OllamaButton(text = stringResource(R.string.action_back), onClick = actions.onBack)
                    },
                )
            } else {
                ServerDetailBody(
                    state = state,
                    actions = actions,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding(),
                )
            }
        }

        val target = state.deleteTargetModel
        if (target != null) {
            ConfirmDialog(
                title = stringResource(R.string.server_model_delete_title),
                message = stringResource(R.string.server_model_delete_message, target),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = actions.onConfirmDeleteModel,
                onDismiss = actions.onCancelDeleteModel,
                destructive = true,
            )
        }
    }
}

@Composable
private fun ServerDetailBody(
    state: ServerDetailUiState,
    actions: ServerDetailActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = Spacing.Lg, end = Spacing.Lg, bottom = Spacing.Huge),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        item { OverviewCard(state = state, onProbe = actions.onProbe) }

        item {
            SectionHeader(
                title = stringResource(R.string.server_models_title),
                description = stringResource(R.string.server_models_description),
                trailing = {
                    OllamaButton(
                        text = stringResource(R.string.action_refresh),
                        onClick = actions.onRefreshModels,
                        style = OllamaButtonStyle.Text,
                        loading = state.isRefreshing,
                    )
                },
            )
        }

        item { PullCard(state = state, actions = actions) }

        if (state.models.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = stringResource(R.string.server_models_empty_title),
                    description = stringResource(R.string.server_models_empty_description),
                )
            }
        } else {
            items(items = state.models, key = { model -> model.id }) { model ->
                ModelRow(
                    model = model,
                    loaded = model.name in state.loadedModels,
                    onDelete = actions.onRequestDeleteModel,
                )
            }
        }

        item {
            SectionHeader(
                // NEVER "Server logs". Ollama has no log endpoint; a screen with
                // that title guarantees the bug report "why are the logs empty".
                title = stringResource(R.string.server_history_title),
                description = stringResource(R.string.server_history_description),
            )
        }

        if (state.history.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.server_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(items = state.history, key = { record -> record.key }) { record ->
                RequestRow(record = record)
            }
        }
    }
}

@Composable
private fun OverviewCard(
    state: ServerDetailUiState,
    onProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when {
        !state.enabled -> StatusKind.Unknown
        state.circuitOpen -> StatusKind.Warning
        state.reachable -> StatusKind.Online
        state.everChecked -> StatusKind.Offline
        else -> StatusKind.Unknown
    }
    val statusText = when {
        !state.enabled -> stringResource(R.string.server_status_disabled)
        state.circuitOpen -> stringResource(R.string.server_status_backing_off)
        state.reachable -> stringResource(R.string.server_status_reachable)
        state.everChecked -> stringResource(R.string.server_status_unreachable)
        else -> stringResource(R.string.server_status_unknown)
    }
    OllamaCard(modifier = modifier) {
        Text(text = state.baseUrl, style = MonospaceTextStyle)
        Row(
            modifier = Modifier.padding(top = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            StatusLabel(status = status, statusText = statusText)
            Text(
                text = state.version?.let { stringResource(R.string.server_version, it) }
                    ?: stringResource(R.string.server_version_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.latencyMillis != null) {
            Text(
                text = stringResource(R.string.server_latency_millis, state.latencyMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.lastErrorRes != null) {
            ErrorBanner(
                message = stringResource(state.lastErrorRes),
                modifier = Modifier.padding(top = Spacing.Sm),
            )
        }
        OllamaButton(
            text = stringResource(R.string.action_check_again),
            onClick = onProbe,
            style = OllamaButtonStyle.Text,
        )
    }
}

@Composable
private fun PullCard(
    state: ServerDetailUiState,
    actions: ServerDetailActions,
    modifier: Modifier = Modifier,
) {
    OllamaCard(modifier = modifier) {
        OllamaTextField(
            value = state.pullModelName,
            onValueChange = actions.onPullNameChange,
            label = stringResource(R.string.server_pull_label),
            placeholder = stringResource(R.string.server_pull_placeholder),
            supportingText = stringResource(R.string.server_pull_supporting),
            monospace = true,
        )
        val pull = state.pull
        if (pull == null) {
            OllamaButton(
                text = stringResource(R.string.server_pull_action),
                onClick = actions.onStartPull,
                style = OllamaButtonStyle.Secondary,
                enabled = state.pullModelName.isNotBlank(),
            )
        } else {
            Text(
                text = stringResource(R.string.server_pull_status, pull.model, pull.status),
                style = MaterialTheme.typography.bodySmall,
            )
            if (pull.fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Sm))
            } else {
                LinearProgressIndicator(
                    progress = { pull.fraction },
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Sm),
                )
            }
            if (pull.errorRes != null) {
                ErrorBanner(message = stringResource(pull.errorRes))
            }
            OllamaButton(
                text = stringResource(
                    if (pull.done) R.string.action_dismiss else R.string.server_pull_stop_watching,
                ),
                onClick = actions.onDismissPull,
                style = OllamaButtonStyle.Text,
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelRowUiState,
    loaded: Boolean,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OllamaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = model.name, style = MonospaceTextStyle)
                Text(
                    text = listOfNotNull(
                        model.sizeBytes?.let(::formatBytes),
                        model.quantization,
                        stringResource(R.string.server_model_loaded).takeIf { loaded },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OllamaButton(
                text = stringResource(R.string.action_delete),
                onClick = { onDelete(model.name) },
                style = OllamaButtonStyle.Destructive,
            )
        }
    }
}

@Composable
private fun RequestRow(
    record: RequestRecordUiState,
    modifier: Modifier = Modifier,
) {
    OllamaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${record.method} ${record.path}", style = MonospaceTextStyle)
                Text(
                    text = stringResource(
                        R.string.server_history_line,
                        relativeTime(record.startedAtMillis),
                        record.durationMillis,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when {
                    record.cancelled -> stringResource(R.string.server_history_cancelled)
                    record.statusCode != null -> record.statusCode.toString()
                    record.errorRes != null -> stringResource(record.errorRes)
                    else -> stringResource(R.string.server_history_unknown_outcome)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (record.success) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Preview
@Composable
private fun ServerDetailScreenPreview() {
    OllamaPreviewTheme {
        ServerDetailScreen(
            state = ServerDetailUiState(
                isLoading = false,
                label = "Living room Pi",
                baseUrl = "http://192.168.1.40:11434",
                reachable = true,
                everChecked = true,
                version = "0.12.3",
                models = persistentListOf(
                    ModelRowUiState(
                        id = "m1",
                        displayName = "Qwen3 1.7B",
                        name = "qwen3:1.7b",
                        sizeBytes = 1_200_000_000L,
                        quantization = "Q4_K_M",
                    ),
                ),
            ),
            actions = ServerDetailActions(
                onBack = {},
                onProbe = {},
                onRefreshModels = {},
                onPullNameChange = {},
                onStartPull = {},
                onDismissPull = {},
                onRequestDeleteModel = {},
                onConfirmDeleteModel = {},
                onCancelDeleteModel = {},
                onDismissMessage = {},
            ),
        )
    }
}
