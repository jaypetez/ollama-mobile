package io.github.jaypetez.ollamamobile.feature.devtools

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ShareCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.EmptyState
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.theme.MonospaceTextStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.ui.absoluteTime
import kotlinx.collections.immutable.persistentListOf

@Composable
fun DeveloperToolsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeveloperToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack) {
        DeveloperToolsActions(
            onBack = onBack,
            onSelectTab = viewModel::onSelectTab,
            onToggleExchange = viewModel::onToggleExchange,
            onClear = viewModel::onClear,
            onShare = viewModel::onShare,
            onExportConsume = viewModel::onExportConsume,
        )
    }
    DeveloperToolsScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(
    state: DeveloperToolsUiState,
    actions: DeveloperToolsActions,
    modifier: Modifier = Modifier,
) {
    ShareEffect(text = state.pendingExport, onConsume = actions.onExportConsume)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.devtools_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions.onShare) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.devtools_share),
                        )
                    }
                    IconButton(onClick = actions.onClear) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = stringResource(R.string.devtools_clear),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                DeveloperToolsTab.entries.forEach { entry ->
                    Tab(
                        selected = state.tab == entry,
                        onClick = { actions.onSelectTab(entry) },
                        text = { Text(text = stringResource(entry.titleRes())) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.devtools_redaction_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            )
            when (state.tab) {
                DeveloperToolsTab.ApiInspector -> InspectorList(state = state, actions = actions)
                DeveloperToolsTab.Logs -> LogList(state = state)
            }
        }
    }
}

@Composable
private fun InspectorList(
    state: DeveloperToolsUiState,
    actions: DeveloperToolsActions,
    modifier: Modifier = Modifier,
) {
    if (state.exchanges.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.devtools_inspector_empty_title),
            description = stringResource(
                if (state.inspectorEnabled) {
                    R.string.devtools_inspector_empty_description
                } else {
                    R.string.devtools_inspector_disabled_description
                },
            ),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.Lg, end = Spacing.Lg, bottom = Spacing.Huge),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        items(items = state.exchanges, key = { exchange -> exchange.id }) { exchange ->
            ExchangeCard(
                exchange = exchange,
                expanded = state.expandedExchangeId == exchange.id,
                onToggle = actions.onToggleExchange,
            )
        }
    }
}

@Composable
private fun ExchangeCard(
    exchange: ApiExchangeUiState,
    expanded: Boolean,
    onToggle: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    OllamaCard(
        modifier = modifier,
        onClick = { onToggle(exchange.id) },
        clickLabel = stringResource(
            if (expanded) R.string.devtools_collapse else R.string.devtools_expand,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = exchange.method, style = MaterialTheme.typography.labelLarge)
            Text(
                text = exchange.statusCode?.toString()
                    ?: exchange.failure
                    ?: stringResource(R.string.devtools_in_flight),
                style = MaterialTheme.typography.labelLarge,
                color = if (exchange.succeeded) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Text(text = exchange.url, style = MonospaceTextStyle)
        Text(
            text = exchange.durationMillis?.let {
                stringResource(R.string.devtools_duration, absoluteTime(exchange.startedAtMillis), it)
            } ?: absoluteTime(exchange.startedAtMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Column(
                modifier = Modifier.padding(top = Spacing.Sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                exchange.requestHeaders.forEach { header ->
                    Text(text = "> $header", style = MonospaceTextStyle)
                }
                exchange.requestBody?.let { body ->
                    Text(text = body, style = MonospaceTextStyle)
                }
                exchange.responseHeaders.forEach { header ->
                    Text(text = "< $header", style = MonospaceTextStyle)
                }
                exchange.responseBody?.let { body ->
                    Text(text = body, style = MonospaceTextStyle)
                }
                Text(text = exchange.curl, style = MonospaceTextStyle)
            }
        }
    }
}

@Composable
private fun LogList(
    state: DeveloperToolsUiState,
    modifier: Modifier = Modifier,
) {
    if (state.logs.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.devtools_logs_empty_title),
            description = stringResource(R.string.devtools_logs_empty_description),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.Lg, end = Spacing.Lg, bottom = Spacing.Huge),
    ) {
        items(items = state.logs, key = { line -> line.key }) { line ->
            Text(
                text = "${line.level}/${line.tag.orEmpty()}  ${line.message}",
                style = MonospaceTextStyle,
                modifier = Modifier.padding(vertical = Spacing.Xxs),
            )
        }
    }
}

/**
 * Hands the export to the system share sheet.
 *
 * `ShareCompat` builds an explicit chooser rather than a bare `ACTION_SEND`,
 * which is what keeps lint's `UnsafeImplicitIntentLaunch` satisfied and, more
 * to the point, stops the text going somewhere the user did not pick.
 */
@Composable
private fun ShareEffect(text: String?, onConsume: () -> Unit) {
    val context = LocalContext.current
    val subject = stringResource(R.string.devtools_share_subject)
    val chooserTitle = stringResource(R.string.devtools_share)
    val currentOnConsumed by rememberUpdatedState(onConsume)
    LaunchedEffect(text) {
        if (text.isNullOrEmpty()) return@LaunchedEffect
        val intent = ShareCompat
            .IntentBuilder(context)
            .setType("text/plain")
            .setSubject(subject)
            .setText(text)
            .setChooserTitle(chooserTitle)
            .createChooserIntent()
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        currentOnConsumed()
    }
}

private fun DeveloperToolsTab.titleRes(): Int = when (this) {
    DeveloperToolsTab.ApiInspector -> R.string.devtools_tab_inspector
    DeveloperToolsTab.Logs -> R.string.devtools_tab_logs
}

@Preview
@Composable
private fun DeveloperToolsScreenPreview() {
    OllamaPreviewTheme {
        DeveloperToolsScreen(
            state = DeveloperToolsUiState(
                inspectorEnabled = true,
                logs = persistentListOf(
                    LogLineUiState(
                        key = "1",
                        timestampMillis = 0L,
                        level = 'I',
                        tag = "OllamaClient",
                        message = "GET /api/tags -> 200",
                    ),
                ),
                tab = DeveloperToolsTab.Logs,
            ),
            actions = DeveloperToolsActions(
                onBack = {},
                onSelectTab = {},
                onToggleExchange = {},
                onClear = {},
                onShare = {},
                onExportConsume = {},
            ),
        )
    }
}
