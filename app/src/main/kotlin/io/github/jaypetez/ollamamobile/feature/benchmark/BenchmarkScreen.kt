package io.github.jaypetez.ollamamobile.feature.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.theme.MonospaceTextStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

@Composable
public fun BenchmarkRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BenchmarkViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack) {
        BenchmarkActions(
            onBack = onBack,
            onSelectModel = viewModel::onSelectModel,
            onToggleSweep = viewModel::onToggleSweep,
            onRun = viewModel::onRun,
            onCancel = viewModel::onCancel,
        )
    }
    BenchmarkScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BenchmarkScreen(
    state: BenchmarkUiState,
    actions: BenchmarkActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.benchmark_title)) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            DisclaimerCard()
            MeasuresCard()
            if (!state.nativeEnabled) {
                OllamaCard {
                    Text(
                        text = stringResource(R.string.benchmark_stub_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.Lg),
                    )
                }
            }
            DeviceCard(state = state)
            ModelPicker(state = state, onSelectModel = actions.onSelectModel)
            SweepPicker(state = state, onToggleSweep = actions.onToggleSweep)
            RunControls(state = state, actions = actions)
        }
    }
}

@Composable
private fun DisclaimerCard(modifier: Modifier = Modifier) {
    OllamaCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.benchmark_disclaimer_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.benchmark_disclaimer_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MeasuresCard(modifier: Modifier = Modifier) {
    OllamaCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.benchmark_measures_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.benchmark_measures_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DeviceCard(state: BenchmarkUiState, modifier: Modifier = Modifier) {
    OllamaCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.benchmark_device_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = state.deviceSummary, style = MonospaceTextStyle)
            Text(
                text = stringResource(R.string.benchmark_backend_heading),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = state.backendVariant, style = MonospaceTextStyle)
            Text(
                text = stringResource(R.string.benchmark_thermal_heading),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = state.thermalStatus, style = MonospaceTextStyle)
        }
    }
}

@Composable
private fun ModelPicker(
    state: BenchmarkUiState,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OllamaCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.benchmark_section_models),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.models.isEmpty()) {
                Text(
                    text = stringResource(R.string.benchmark_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.models.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = name == state.selectedModel,
                            role = Role.RadioButton,
                            onClick = { onSelectModel(name) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = name == state.selectedModel, onClick = null)
                    Text(text = name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SweepPicker(
    state: BenchmarkUiState,
    onToggleSweep: (BenchmarkSweep) -> Unit,
    modifier: Modifier = Modifier,
) {
    OllamaCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.benchmark_section_sweeps),
                style = MaterialTheme.typography.titleMedium,
            )
            SweepRow(BenchmarkSweep.THREADS, R.string.benchmark_sweep_threads, state, onToggleSweep)
            SweepRow(BenchmarkSweep.CONTEXT, R.string.benchmark_sweep_context, state, onToggleSweep)
            SweepRow(
                BenchmarkSweep.QUANTISATION,
                R.string.benchmark_sweep_quantisation,
                state,
                onToggleSweep,
            )
        }
    }
}

@Composable
private fun SweepRow(
    sweep: BenchmarkSweep,
    labelRes: Int,
    state: BenchmarkUiState,
    onToggleSweep: (BenchmarkSweep) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = sweep in state.sweeps,
                role = Role.Checkbox,
                onClick = { onToggleSweep(sweep) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = sweep in state.sweeps, onCheckedChange = null)
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RunControls(
    state: BenchmarkUiState,
    actions: BenchmarkActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        if (state.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(
                    R.string.benchmark_status_running,
                    state.currentCellLabel.orEmpty(),
                    state.cellIndex,
                    state.cellCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.repetitionCount > 0) {
                Text(
                    text = stringResource(
                        R.string.benchmark_status_repetition,
                        state.repetitionIndex,
                        state.repetitionCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.benchmark_cancel))
            }
        } else {
            Button(
                onClick = actions.onRun,
                enabled = state.selectedModel != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.benchmark_run))
            }
        }

        state.resultPath?.let { path ->
            Text(
                text = stringResource(R.string.benchmark_status_done, path),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.failure?.let { failure ->
            Text(
                text = stringResource(R.string.benchmark_status_failed, failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Preview
@Composable
private fun BenchmarkScreenPreview() {
    OllamaPreviewTheme {
        BenchmarkScreen(
            state = BenchmarkUiState(
                deviceSummary = "arm64-v8a, 8 cores (1P/7E), features=DOTPROD+FP16+I8MM, source=HWCAP",
                backendVariant = "libggml-cpu-android_armv8.2_3.so",
                thermalStatus = "NONE",
                nativeEnabled = false,
                models = persistentListOf("qwen3-1.7b-instruct-q4_k_m.gguf"),
                selectedModel = "qwen3-1.7b-instruct-q4_k_m.gguf",
            ),
            actions = BenchmarkActions(
                onBack = {},
                onSelectModel = {},
                onToggleSweep = {},
                onRun = {},
                onCancel = {},
            ),
        )
    }
}
