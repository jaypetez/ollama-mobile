package io.github.jaypetez.ollamamobile.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.EmptyState
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaTextField
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.download.DownloadStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Composable
fun ModelDiscoverRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelDiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack) {
        ModelDiscoverActions(
            onTab = viewModel::selectTab,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            onCustomUrlChange = viewModel::onCustomUrlChange,
            onInspectUrl = viewModel::inspectCustomUrl,
            onDownload = viewModel::download,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onCancel = viewModel::cancel,
            onDismissMessage = viewModel::dismissMessage,
            onBack = onBack,
        )
    }
    ModelDiscoverScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDiscoverScreen(
    state: ModelDiscoverUiState,
    actions: ModelDiscoverActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.discover_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                DiscoverTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { actions.onTab(tab) },
                        text = { Text(text = stringResource(tab.labelRes)) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.Lg,
                    end = Spacing.Lg,
                    top = Spacing.Sm,
                    bottom = Spacing.Huge,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                state.message?.let { note ->
                    item(key = "message") {
                        MessageBanner(message = note, onDismiss = actions.onDismissMessage)
                    }
                }

                // Shown above the results, not instead of them: downloading is
                // still allowed on a build with no engine — the bytes are valid
                // and a native build will run them — but the user has to know
                // that nothing here will load today.
                if (!state.engineAvailable) {
                    item(key = "no-engine") { DiscoverNoEngineNotice() }
                }

                when (state.tab) {
                    DiscoverTab.CATALOGUE -> {
                        entrySection(state, state.catalogue, actions)
                    }

                    DiscoverTab.SEARCH -> {
                        item(key = "search-box") { SearchBox(state = state, actions = actions) }
                        if (state.searched && state.results.isEmpty() && !state.searching) {
                            item(key = "no-results") { NoResults() }
                        }
                        entrySection(state, state.results, actions)
                    }

                    DiscoverTab.URL -> {
                        item(key = "url-box") { CustomUrlBox(state = state, actions = actions) }
                        state.customUrlPreview?.let { entry ->
                            item(key = entry.id) {
                                DiscoverCard(entry = entry, download = state.downloadFor(entry), actions = actions)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.entrySection(
    state: ModelDiscoverUiState,
    entries: ImmutableList<DiscoverEntryUi>,
    actions: ModelDiscoverActions,
) {
    items(items = entries, key = { it.id }) { entry ->
        DiscoverCard(entry = entry, download = state.downloadFor(entry), actions = actions)
    }
}

@Composable
private fun DiscoverNoEngineNotice(modifier: Modifier = Modifier) {
    OllamaCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.models_no_engine_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.discover_no_engine_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.Xs),
        )
    }
}

@Composable
private fun SearchBox(
    state: ModelDiscoverUiState,
    actions: ModelDiscoverActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OllamaTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            label = stringResource(R.string.discover_search_label),
            placeholder = stringResource(R.string.discover_search_placeholder),
        )
        OllamaButton(
            text = stringResource(R.string.discover_search_action),
            onClick = actions.onSearch,
            style = OllamaButtonStyle.Secondary,
            loading = state.searching,
            enabled = state.query.isNotBlank(),
            modifier = Modifier.padding(top = Spacing.Sm),
        )
    }
}

@Composable
private fun CustomUrlBox(
    state: ModelDiscoverUiState,
    actions: ModelDiscoverActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OllamaTextField(
            value = state.customUrl,
            onValueChange = actions.onCustomUrlChange,
            label = stringResource(R.string.discover_url_label),
            placeholder = stringResource(R.string.discover_url_placeholder),
            supportingText = stringResource(R.string.discover_url_warning),
            monospace = true,
        )
        OllamaButton(
            text = stringResource(R.string.discover_url_check),
            onClick = actions.onInspectUrl,
            style = OllamaButtonStyle.Secondary,
            loading = state.inspectingUrl,
            enabled = state.customUrl.isNotBlank(),
            modifier = Modifier.padding(top = Spacing.Sm),
        )
    }
}

@Composable
private fun NoResults(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.CloudDownload,
        title = stringResource(R.string.discover_no_results_title),
        description = stringResource(R.string.discover_no_results_description),
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DiscoverCard(
    entry: DiscoverEntryUi,
    download: DownloadUi?,
    actions: ModelDiscoverActions,
    modifier: Modifier = Modifier,
) {
    OllamaCard(modifier = modifier) {
        Text(text = entry.displayName, style = MaterialTheme.typography.titleSmall)
        Text(
            text = entry.sourceLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            entry.sizeLabel?.let { Detail(it) }
            entry.quantizationLabel?.let { Detail(it) }
            entry.parameterLabel?.let { Detail(stringResource(R.string.models_parameters, it)) }
        }

        if (!entry.hashVerified) {
            // A real reduction in guarantee, stated rather than hidden: the
            // transfer can only be checked against its declared length and the
            // GGUF magic.
            Text(
                text = stringResource(R.string.discover_unverified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = Spacing.Xs),
            )
        }

        if (download != null && !download.isFinished) {
            DownloadProgressRow(download = download, modifier = Modifier.padding(top = Spacing.Sm))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadControls(entry = entry, download = download, actions = actions)
        }
    }
}

@Composable
private fun DownloadControls(
    entry: DiscoverEntryUi,
    download: DownloadUi?,
    actions: ModelDiscoverActions,
) {
    when {
        entry.installed || download?.isFinished == true -> {
            Text(
                text = stringResource(R.string.discover_installed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        download == null -> {
            OllamaButton(
                text = stringResource(R.string.discover_download),
                onClick = { actions.onDownload(entry) },
                style = OllamaButtonStyle.Secondary,
            )
        }

        else -> {
            if (download.canPause) {
                OllamaButton(
                    text = stringResource(R.string.discover_pause),
                    onClick = { actions.onPause(entry) },
                    style = OllamaButtonStyle.Text,
                )
            }
            if (download.canResume) {
                OllamaButton(
                    text = stringResource(R.string.discover_resume),
                    onClick = { actions.onResume(entry) },
                    style = OllamaButtonStyle.Secondary,
                )
            }
            if (download.canCancel) {
                OllamaButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { actions.onCancel(entry) },
                    style = OllamaButtonStyle.Destructive,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressRow(download: DownloadUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        // An indeterminate bar before the total is known, rather than a
        // determinate one sitting at zero that looks stuck.
        val fraction = download.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = stringResource(download.status.labelRes(), download.bytesLabel),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.Xs),
        )
        if (download.restartedFromZero) {
            // Otherwise the user simply watches the bar go backwards with no
            // explanation: the server answered 200 to a ranged request.
            Text(
                text = stringResource(R.string.discover_restarted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun Detail(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private fun DownloadStatus.labelRes(): Int = when (this) {
    DownloadStatus.QUEUED -> R.string.discover_status_queued
    DownloadStatus.RUNNING -> R.string.discover_status_running
    DownloadStatus.VERIFYING -> R.string.discover_status_verifying
    DownloadStatus.PAUSED -> R.string.discover_status_paused
    DownloadStatus.COMPLETED -> R.string.discover_status_completed
    DownloadStatus.FAILED -> R.string.discover_status_failed
    DownloadStatus.CANCELLED -> R.string.discover_status_cancelled
}

@Preview
@Composable
private fun ModelDiscoverScreenPreview() {
    OllamaPreviewTheme {
        ModelDiscoverScreen(
            state = ModelDiscoverUiState(
                engineAvailable = false,
                catalogue = persistentListOf(
                    DiscoverEntryUi(
                        id = "hf:Qwen/Qwen3-1.7B-GGUF:qwen3-1.7b-q4_k_m.gguf",
                        displayName = "Qwen3 1.7B Instruct",
                        sourceLabel = "Qwen/Qwen3-1.7B-GGUF",
                        fileName = "qwen3-1.7b-q4_k_m.gguf",
                        sizeLabel = "1.1 GiB",
                        quantizationLabel = "Q4_K_M",
                        parameterLabel = "1.7B",
                    ),
                ),
            ),
            actions = ModelDiscoverActions(
                onTab = {},
                onQueryChange = {},
                onSearch = {},
                onCustomUrlChange = {},
                onInspectUrl = {},
                onDownload = {},
                onPause = {},
                onResume = {},
                onCancel = {},
                onDismissMessage = {},
                onBack = {},
            ),
        )
    }
}
