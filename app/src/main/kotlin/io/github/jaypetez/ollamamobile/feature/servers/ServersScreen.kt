package io.github.jaypetez.ollamamobile.feature.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.component.SectionHeader
import io.github.jaypetez.ollamamobile.designsystem.component.StatusKind
import io.github.jaypetez.ollamamobile.designsystem.component.StatusLabel
import io.github.jaypetez.ollamamobile.designsystem.theme.MonospaceTextStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.model.ServerId
import kotlinx.collections.immutable.persistentListOf

@Immutable
class ServersActions(
    val onAdd: () -> Unit,
    val onEdit: (String) -> Unit,
    val onOpenDetail: (String) -> Unit,
    val onToggleEnabled: (String, Boolean) -> Unit,
    val onRequestDelete: (String) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onCancelDelete: () -> Unit,
    val onProbe: (String) -> Unit,
    val onDismissMessage: () -> Unit,
)

@Immutable
class ScanActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onAdopt: (String) -> Unit,
)

@Composable
fun ServersRoute(
    onOpenServer: (ServerId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenServer) {
        ServersActions(
            onAdd = viewModel::onAddServer,
            onEdit = viewModel::onEditServer,
            onOpenDetail = { id -> onOpenServer(ServerId(id)) },
            onToggleEnabled = viewModel::onToggleEnabled,
            onRequestDelete = viewModel::onRequestDelete,
            onConfirmDelete = viewModel::onConfirmDelete,
            onCancelDelete = viewModel::onCancelDelete,
            onProbe = viewModel::onProbe,
            onDismissMessage = viewModel::onDismissMessage,
        )
    }
    val scanActions = remember(viewModel) {
        ScanActions(
            onStart = viewModel::onStartScan,
            onStop = viewModel::onStopScan,
            onAdopt = viewModel::onAdoptDiscovered,
        )
    }
    val editorActions = remember(viewModel) {
        ServerEditorActions(
            onLabelChange = viewModel::onEditorLabelChange,
            onUrlChange = viewModel::onEditorUrlChange,
            onAuthModeChange = viewModel::onEditorAuthModeChange,
            onUsernameChange = viewModel::onEditorUsernameChange,
            onSecretChange = viewModel::onEditorSecretChange,
            onSave = viewModel::onSaveServer,
            onDismiss = viewModel::onDismissEditor,
        )
    }
    ServersScreen(
        state = state,
        actions = actions,
        scanActions = scanActions,
        editorActions = editorActions,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    state: ServersUiState,
    actions: ServersActions,
    scanActions: ScanActions,
    editorActions: ServerEditorActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbar(
        messageRes = state.messageRes,
        hostState = snackbarHostState,
        onConsume = actions.onDismissMessage,
    )

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text(text = stringResource(R.string.servers_title)) }) },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                FloatingActionButton(onClick = actions.onAdd) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.servers_add),
                    )
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = Spacing.Lg,
                    end = Spacing.Lg,
                    bottom = Spacing.Huge,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                if (state.isEmpty) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Dns,
                            title = stringResource(R.string.servers_empty_title),
                            description = stringResource(R.string.servers_empty_description),
                            action = {
                                OllamaButton(
                                    text = stringResource(R.string.servers_add),
                                    onClick = actions.onAdd,
                                )
                            },
                        )
                    }
                } else {
                    item { SectionHeader(title = stringResource(R.string.servers_configured_title)) }
                    items(items = state.servers, key = { server -> server.id }) { server ->
                        ServerRow(server = server, actions = actions)
                    }
                }
                item { DiscoverySection(scan = state.scan, actions = scanActions) }
            }
        }

        val editor = state.editor
        if (editor != null) {
            ServerEditSheet(state = editor, actions = editorActions)
        }

        val target = state.deleteTarget
        if (target != null) {
            ConfirmDialog(
                title = stringResource(R.string.server_delete_title),
                message = stringResource(R.string.server_delete_message, target.label),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = actions.onConfirmDelete,
                onDismiss = actions.onCancelDelete,
                destructive = true,
            )
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerRowUiState,
    actions: ServersActions,
    modifier: Modifier = Modifier,
) {
    val status = when {
        !server.enabled -> StatusKind.Unknown
        server.circuitOpen -> StatusKind.Warning
        server.reachable -> StatusKind.Online
        server.everChecked -> StatusKind.Offline
        else -> StatusKind.Unknown
    }
    val statusText = when {
        !server.enabled -> stringResource(R.string.server_status_disabled)
        server.circuitOpen -> stringResource(R.string.server_status_backing_off)
        server.reachable -> stringResource(R.string.server_status_reachable)
        server.everChecked -> stringResource(R.string.server_status_unreachable)
        else -> stringResource(R.string.server_status_unknown)
    }
    OllamaCard(
        modifier = modifier,
        onClick = { actions.onOpenDetail(server.id) },
        clickLabel = stringResource(R.string.servers_open_click_label),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.baseUrl,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = server.enabled,
                onCheckedChange = { enabled -> actions.onToggleEnabled(server.id, enabled) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            StatusLabel(status = status, statusText = statusText)
            if (server.version != null) {
                Text(
                    text = stringResource(R.string.server_version_short, server.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (server.latencyMillis != null) {
                Text(
                    text = stringResource(R.string.server_latency_millis, server.latencyMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
            OllamaButton(
                text = stringResource(R.string.action_check_again),
                onClick = { actions.onProbe(server.id) },
                style = OllamaButtonStyle.Text,
            )
            OllamaButton(
                text = stringResource(R.string.action_edit),
                onClick = { actions.onEdit(server.id) },
                style = OllamaButtonStyle.Text,
            )
            OllamaButton(
                text = stringResource(R.string.action_forget),
                onClick = { actions.onRequestDelete(server.id) },
                style = OllamaButtonStyle.Destructive,
            )
        }
    }
}

/**
 * The discovery panel.
 *
 * The VPN caveat is stated up front and permanently, not tucked behind a "why
 * did this find nothing?" link. A subnet sweep enumerates the Wi-Fi link's own
 * addresses; a Tailscale peer lives on `100.64.0.0/10` behind the tunnel
 * interface and is never in the candidate set. That is a property of the
 * technique, not a bug, and a user whose only server is a Tailscale peer needs
 * to know before they tap, not after they wait.
 */
@Composable
private fun DiscoverySection(
    scan: ScanUiState,
    actions: ScanActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.discovery_title),
            description = stringResource(R.string.discovery_description),
        )
        OllamaCard {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.padding(top = Spacing.Xxs),
                )
                Text(
                    text = stringResource(R.string.discovery_vpn_caveat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.Md),
                thickness = Sizes.DividerThickness,
            )
            if (scan.isScanning) {
                Text(
                    text = stringResource(R.string.discovery_scanning, scan.candidateCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Indeterminate on purpose: the sweep reports Started, Found and
                // Finished but nothing per probe, so a percentage would be an
                // invention and a bar that sticks at 90% reads as a hang.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Sm))
                OllamaButton(
                    text = stringResource(R.string.discovery_stop),
                    onClick = actions.onStop,
                    style = OllamaButtonStyle.Secondary,
                )
            } else {
                OllamaButton(
                    text = stringResource(R.string.discovery_start),
                    onClick = actions.onStart,
                    icon = Icons.Outlined.Wifi,
                    style = OllamaButtonStyle.Secondary,
                )
            }
            if (scan.refusalRes != null) {
                Text(
                    text = stringResource(scan.refusalRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.Sm),
                )
            }
            if (scan.finished && scan.found.isEmpty() && scan.refusalRes == null) {
                Text(
                    text = stringResource(R.string.discovery_none_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.Sm),
                )
            }
            scan.found.forEach { found ->
                DiscoveredRow(found = found, onAdopt = actions.onAdopt)
            }
        }
    }
}

@Composable
private fun DiscoveredRow(
    found: DiscoveredServerUiState,
    onAdopt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = found.baseUrl, style = MonospaceTextStyle)
            Text(
                text = if (found.modelCount == null) {
                    stringResource(R.string.discovery_found_version, found.version)
                } else {
                    stringResource(R.string.discovery_found_version_models, found.version, found.modelCount)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (found.alreadyConfigured) {
            Text(
                text = stringResource(R.string.discovery_already_added),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OllamaButton(
                text = stringResource(R.string.discovery_add),
                onClick = { onAdopt(found.baseUrl) },
                style = OllamaButtonStyle.Text,
            )
        }
    }
}

@Composable
private fun MessageSnackbar(
    messageRes: Int?,
    hostState: SnackbarHostState,
    onConsume: () -> Unit,
) {
    val message = messageRes?.let { stringResource(it) }
    val currentOnShown by rememberUpdatedState(onConsume)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        hostState.showSnackbar(message)
        currentOnShown()
    }
}

@Preview
@Composable
private fun ServersScreenPreview() {
    OllamaPreviewTheme {
        ServersScreen(
            state = ServersUiState(
                isLoading = false,
                servers = persistentListOf(
                    ServerRowUiState(
                        id = "1",
                        label = "Living room Pi",
                        baseUrl = "http://192.168.1.40:11434",
                        enabled = true,
                        reachable = true,
                        circuitOpen = false,
                        everChecked = true,
                        version = "0.12.3",
                        latencyMillis = 14L,
                        authMode = ServerAuthMode.None,
                    ),
                ),
            ),
            actions = previewServersActions(),
            scanActions = ScanActions(onStart = {}, onStop = {}, onAdopt = {}),
            editorActions = previewEditorActions(),
        )
    }
}

private fun previewServersActions() = ServersActions(
    onAdd = {},
    onEdit = {},
    onOpenDetail = {},
    onToggleEnabled = { _, _ -> },
    onRequestDelete = {},
    onConfirmDelete = {},
    onCancelDelete = {},
    onProbe = {},
    onDismissMessage = {},
)

private fun previewEditorActions() = ServerEditorActions(
    onLabelChange = {},
    onUrlChange = {},
    onAuthModeChange = {},
    onUsernameChange = {},
    onSecretChange = {},
    onSave = {},
    onDismiss = {},
)
