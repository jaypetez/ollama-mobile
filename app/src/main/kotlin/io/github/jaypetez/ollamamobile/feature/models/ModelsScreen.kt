package io.github.jaypetez.ollamamobile.feature.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import io.github.jaypetez.ollamamobile.designsystem.component.ConfirmDialog
import io.github.jaypetez.ollamamobile.designsystem.component.EmptyState
import io.github.jaypetez.ollamamobile.designsystem.component.ErrorBanner
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.model.ModelId
import kotlinx.collections.immutable.persistentListOf

@Composable
fun ModelsRoute(
    onBack: () -> Unit,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack, onDiscover) {
        ModelsActions(
            onRefresh = viewModel::refresh,
            onLoad = viewModel::load,
            onUnload = viewModel::unload,
            onDeleteRequested = viewModel::requestDelete,
            onDeleteConfirmed = viewModel::confirmDelete,
            onDeleteDismissed = viewModel::dismissDelete,
            onImportPicked = viewModel::import,
            onDismissMessage = viewModel::dismissMessage,
            onDiscover = onDiscover,
            onBack = onBack,
        )
    }
    ModelsScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    actions: ModelsActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.models_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions.onDiscover) {
                        Icon(
                            imageVector = Icons.Outlined.CloudDownload,
                            contentDescription = stringResource(R.string.models_discover),
                        )
                    }
                },
            )
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
            state.message?.let { note ->
                item(key = "message") {
                    MessageBanner(message = note, onDismiss = actions.onDismissMessage)
                }
            }

            // Always first, and always present when there is no engine: this is
            // the sentence that stops an empty list reading as "nothing
            // downloaded yet".
            if (!state.engineAvailable) {
                item(key = "no-engine") { NoEngineNotice() }
            }

            item(key = "import") { ImportRow(actions = actions, enabled = !state.isLoading) }

            if (state.isEmpty) {
                item(key = "empty") { ModelsEmptyState(state = state, actions = actions) }
            }

            items(items = state.models, key = { it.id.value }) { model ->
                ModelCard(
                    model = model,
                    engineAvailable = state.engineAvailable,
                    busy = state.busyModelId == model.id,
                    actions = actions,
                )
            }
        }
    }

    state.pendingDelete?.let { model ->
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title),
            message = stringResource(R.string.models_delete_message, model.displayName, model.sizeLabel),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = actions.onDeleteConfirmed,
            onDismiss = actions.onDeleteDismissed,
            destructive = true,
        )
    }
}

/**
 * The honesty notice.
 *
 * Deliberately not conditional on the list being empty. A build with no engine
 * can still have models on disk — a previous native build downloaded them, or
 * the user imported one — and those rows must not look loadable.
 */
@Composable
private fun NoEngineNotice(modifier: Modifier = Modifier) {
    OllamaCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.models_no_engine_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.models_no_engine_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.Xs),
        )
    }
}

@Composable
private fun ModelsEmptyState(
    state: ModelsUiState,
    actions: ModelsActions,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Outlined.Memory,
        title = stringResource(R.string.models_empty_title),
        description = if (state.engineAvailable) {
            stringResource(R.string.models_empty_description)
        } else {
            // The same empty list means something different here, and saying
            // "download one to get started" would be a lie in this build.
            stringResource(R.string.models_empty_description_no_engine)
        },
        modifier = modifier,
        action = {
            OllamaButton(
                text = stringResource(R.string.models_discover),
                onClick = actions.onDiscover,
                style = OllamaButtonStyle.Text,
            )
        },
    )
}

@Composable
private fun ImportRow(
    actions: ModelsActions,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // OpenDocument rather than GetContent: only the former can be persisted and
    // re-read reliably, and the import copies the bytes immediately anyway.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { actions.onImportPicked(it, it.lastPathSegment.orEmpty().substringAfterLast('/')) }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        OllamaButton(
            text = stringResource(R.string.models_import),
            onClick = { picker.launch(IMPORT_MIME_TYPES) },
            style = OllamaButtonStyle.Secondary,
            enabled = enabled,
        )
        OllamaButton(
            text = stringResource(R.string.action_refresh),
            onClick = actions.onRefresh,
            style = OllamaButtonStyle.Text,
            enabled = enabled,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelCard(
    model: LocalModelUi,
    engineAvailable: Boolean,
    busy: Boolean,
    actions: ModelsActions,
    modifier: Modifier = Modifier,
) {
    OllamaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = model.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = model.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (model.resident) {
                // The resident model is marked rather than merely sorted first:
                // "which one is in memory" is the fact that decides whether the
                // next answer is instant or a multi-gigabyte wait.
                AssistChip(
                    onClick = actions.onUnload,
                    label = { Text(text = stringResource(R.string.models_loaded)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(Sizes.ListIcon),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Fact(model.sizeLabel)
            model.quantizationLabel?.let { Fact(it) }
            model.parameterLabel?.let { Fact(stringResource(R.string.models_parameters, it)) }
            model.contextLabel?.let { Fact(stringResource(R.string.models_context, it)) }
        }

        Text(
            text = stringResource(model.verdict.kind.labelRes) + " · " + model.verdict.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = when (model.verdict.kind) {
                VerdictKind.REFUSE -> MaterialTheme.colorScheme.error
                VerdictKind.TIGHT -> MaterialTheme.colorScheme.tertiary
                VerdictKind.FITS -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = Spacing.Sm),
        )

        Text(
            text = model.originLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.Xs),
        )

        ModelCardActions(
            model = model,
            engineAvailable = engineAvailable,
            busy = busy,
            actions = actions,
        )
    }
}

@Composable
private fun ModelCardActions(
    model: LocalModelUi,
    engineAvailable: Boolean,
    busy: Boolean,
    actions: ModelsActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(Sizes.InlineProgress),
                strokeWidth = Sizes.InlineProgressStroke,
            )
        }
        if (model.resident) {
            OllamaButton(
                text = stringResource(R.string.models_unload),
                onClick = actions.onUnload,
                style = OllamaButtonStyle.Secondary,
                enabled = !busy,
            )
        } else {
            OllamaButton(
                text = stringResource(R.string.models_load),
                onClick = { actions.onLoad(model.id) },
                style = OllamaButtonStyle.Secondary,
                // Refused is refused. Offering a button that cannot succeed
                // teaches the user that the app's warnings are decorative.
                enabled = !busy && engineAvailable && model.loadable,
            )
        }
        OllamaButton(
            text = stringResource(R.string.action_delete),
            onClick = { actions.onDeleteRequested(model.id) },
            style = OllamaButtonStyle.Destructive,
            enabled = !busy,
        )
    }
}

@Composable
private fun Fact(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * One notice, drawn as an error only when it is one.
 *
 * A successful delete rendered in the error colour teaches the user to ignore
 * the error colour, which is the one thing it has to keep meaning.
 */
@Composable
internal fun MessageBanner(
    message: ModelsMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = stringResource(message.messageRes)
    val text = message.detail?.let { "$body $it" } ?: body
    if (message.isError) {
        ErrorBanner(message = text, onDismiss = onDismiss, modifier = modifier)
    } else {
        OllamaCard(modifier = modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OllamaButton(
                    text = stringResource(R.string.action_dismiss),
                    onClick = onDismiss,
                    style = OllamaButtonStyle.Text,
                )
            }
        }
    }
}

// GGUF has no registered MIME type, so the picker is opened on the wildcard.
// Filtering on `application/octet-stream` looks tighter and is worse: providers
// disagree about what they report for a `.gguf`, and the ones that guess wrong
// simply hide the file the user is trying to pick. The extension is checked on
// import instead, where it can produce a message the user can act on.
private val IMPORT_MIME_TYPES = arrayOf("*/*")

@Preview
@Composable
private fun ModelsScreenNoEnginePreview() {
    OllamaPreviewTheme {
        ModelsScreen(
            state = ModelsUiState(isLoading = false, engineAvailable = false),
            actions = previewActions(),
        )
    }
}

@Preview
@Composable
private fun ModelsScreenWithModelsPreview() {
    OllamaPreviewTheme {
        ModelsScreen(
            state = ModelsUiState(
                isLoading = false,
                engineAvailable = true,
                models = persistentListOf(
                    LocalModelUi(
                        id = ModelId("hf:Qwen/Qwen3-1.7B-GGUF:qwen3-1.7b-q4_k_m.gguf"),
                        displayName = "Qwen3 1.7B",
                        fileName = "qwen3-1.7b-q4_k_m.gguf",
                        sizeLabel = "1.1 GiB",
                        quantizationLabel = "Q4_K_M",
                        parameterLabel = "1.7B",
                        contextLabel = "32K",
                        originLabel = "huggingface.co/Qwen/Qwen3-1.7B-GGUF",
                        verdict = MemoryVerdictUi(VerdictKind.FITS, "Fits with 2.4 GiB to spare."),
                        resident = true,
                    ),
                ),
            ),
            actions = previewActions(),
        )
    }
}

private fun previewActions() = ModelsActions(
    onRefresh = {},
    onLoad = {},
    onUnload = {},
    onDeleteRequested = {},
    onDeleteConfirmed = {},
    onDeleteDismissed = {},
    onImportPicked = { _, _ -> },
    onDismissMessage = {},
    onDiscover = {},
    onBack = {},
)
