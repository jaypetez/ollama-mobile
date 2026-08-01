package io.github.jaypetez.ollamamobile.feature.benchmark

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.ml.BackendPolicy
import io.github.jaypetez.ollamamobile.ml.BackendQuarantine
import io.github.jaypetez.ollamamobile.ml.DeviceCapabilitiesProbe
import io.github.jaypetez.ollamamobile.ml.ThermalMonitor
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which sweeps the user asked for. */
public enum class BenchmarkSweep {
    THREADS,
    CONTEXT,
    QUANTISATION,
}

@Immutable
public data class BenchmarkUiState(
    val deviceSummary: String = "",
    val backendVariant: String = "",
    val thermalStatus: String = "",
    val nativeEnabled: Boolean = false,
    val models: ImmutableList<String> = persistentListOf(),
    val selectedModel: String? = null,
    val sweeps: Set<BenchmarkSweep> = setOf(BenchmarkSweep.THREADS),
    val isRunning: Boolean = false,
    val currentCellLabel: String? = null,
    val cellIndex: Int = 0,
    val cellCount: Int = 0,
    val repetitionIndex: Int = 0,
    val repetitionCount: Int = 0,
    /** Absolute path of the written document, set once a run finishes. */
    val resultPath: String? = null,
    val failure: String? = null,
)

/**
 * Drives the harness from the UI.
 *
 * The screen deliberately reports what the harness *measures* and states that no
 * result ships with the app. It shows the number it just produced on this device
 * and nothing else — there is no bundled reference figure to compare against,
 * because producing one would require hardware this project does not have.
 */
@HiltViewModel
public class BenchmarkViewModel
    @Inject
    constructor(
        private val runner: BenchmarkRunner,
        private val store: BenchmarkResultStore,
        private val capabilitiesProbe: DeviceCapabilitiesProbe,
        private val thermalMonitor: ThermalMonitor,
        private val quarantine: BackendQuarantine,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(BenchmarkUiState())
        public val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

        private var runJob: Job? = null

        init {
            val capabilities = capabilitiesProbe.capabilities()
            val models = runner.availableModels().map { it.displayName }
            _uiState.update { state ->
                state.copy(
                    deviceSummary = capabilities.describe(),
                    backendVariant = BackendPolicy
                        .select(capabilities, quarantine.quarantinedVariants())
                        .describe(),
                    thermalStatus = thermalMonitor.currentStatus().name,
                    nativeEnabled = runner.describeEnvironment().nativeEnabled,
                    models = models.toImmutableList(),
                    selectedModel = models.firstOrNull(),
                )
            }
        }

        public fun onSelectModel(name: String) {
            _uiState.update { it.copy(selectedModel = name) }
        }

        public fun onToggleSweep(sweep: BenchmarkSweep) {
            _uiState.update { state ->
                val next = if (sweep in state.sweeps) state.sweeps - sweep else state.sweeps + sweep
                state.copy(sweeps = next)
            }
        }

        public fun onRun() {
            if (runJob?.isActive == true) return
            val state = _uiState.value
            val cells = planCells(state)
            if (cells.isEmpty()) {
                _uiState.update { it.copy(failure = "No cells to run") }
                return
            }
            runJob = viewModelScope.launch {
                runner.run(cells).collect(::onProgress)
            }
        }

        public fun onCancel() {
            runJob?.cancel()
            runJob = null
            _uiState.update { it.copy(isRunning = false, currentCellLabel = null) }
        }

        private fun onProgress(progress: BenchmarkProgress) {
            _uiState.update { state ->
                when (progress) {
                    is BenchmarkProgress.Started -> {
                        state.copy(
                            isRunning = true,
                            cellCount = progress.totalCells,
                            resultPath = null,
                            failure = null,
                        )
                    }

                    is BenchmarkProgress.Cell -> {
                        state.copy(
                            currentCellLabel = progress.label,
                            cellIndex = progress.index,
                            cellCount = progress.total,
                        )
                    }

                    is BenchmarkProgress.Repetition -> {
                        state.copy(
                            repetitionIndex = progress.index,
                            repetitionCount = progress.total,
                        )
                    }

                    is BenchmarkProgress.Finished -> {
                        state.copy(
                            isRunning = false,
                            currentCellLabel = null,
                            resultPath = store.write(progress.document)?.absolutePath,
                        )
                    }

                    is BenchmarkProgress.Failed -> {
                        state.copy(isRunning = false, failure = progress.message)
                    }
                }
            }
        }

        private fun planCells(state: BenchmarkUiState): List<BenchmarkCell> {
            val available = runner.availableModels()
            val model = available.firstOrNull { it.displayName == state.selectedModel }
                ?: return emptyList()
            val topology = capabilitiesProbe.capabilities().topology

            return buildList {
                if (BenchmarkSweep.THREADS in state.sweeps) {
                    addAll(
                        BenchmarkPlan.threadCells(
                            model = model,
                            performanceCores = topology.performanceCores,
                            totalCores = topology.totalCores,
                        ),
                    )
                }
                if (BenchmarkSweep.CONTEXT in state.sweeps) {
                    addAll(
                        BenchmarkPlan.contextCells(
                            model = model,
                            threads = topology.performanceCores,
                        ),
                    )
                }
                if (BenchmarkSweep.QUANTISATION in state.sweeps) {
                    addAll(
                        BenchmarkPlan.quantisationCells(
                            available = available,
                            threads = topology.performanceCores,
                        ),
                    )
                }
            }
        }
    }
