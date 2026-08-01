package io.github.jaypetez.ollamamobile.feature.servers

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.designsystem.component.messageRes
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.navigation.ServerDetailDestination
import io.github.jaypetez.ollamamobile.remote.OllamaClient
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import io.github.jaypetez.ollamamobile.remote.health.RequestOutcome
import io.github.jaypetez.ollamamobile.remote.health.RequestRecord
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ModelRowUiState(
    val id: String,
    val displayName: String,
    val name: String,
    val sizeBytes: Long?,
    val quantization: String?,
)

/**
 * One request *this app* made to this server.
 *
 * Not a server log line, and the type name says so. Ollama exposes no log
 * endpoint — there is no `/api/logs` and no protocol affordance that could
 * provide one — so anything labelled "server logs" would be a promise the
 * product cannot keep. See `RequestHistory`'s KDoc.
 */
@Immutable
data class RequestRecordUiState(
    val key: String,
    val method: String,
    val path: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val statusCode: Int?,
    @param:StringRes val errorRes: Int?,
    val cancelled: Boolean,
    val success: Boolean,
)

/** Progress of a `/api/pull`. */
@Immutable
data class PullUiState(
    val model: String,
    /** The server's own status text ("pulling manifest", "verifying sha256"). Not translated. */
    val status: String,
    /** 0..1, or null for the phases the server does not size. */
    val fraction: Float?,
    @param:StringRes val errorRes: Int? = null,
    val done: Boolean = false,
)

@Immutable
data class ServerDetailUiState(
    val isLoading: Boolean = true,
    /** True once the server row is gone — deleted from another screen, usually. */
    val missing: Boolean = false,
    val label: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = true,
    val reachable: Boolean = false,
    val everChecked: Boolean = false,
    val version: String? = null,
    val latencyMillis: Long? = null,
    @param:StringRes val lastErrorRes: Int? = null,
    val circuitOpen: Boolean = false,
    val models: ImmutableList<ModelRowUiState> = persistentListOf(),
    val loadedModels: ImmutableList<String> = persistentListOf(),
    val history: ImmutableList<RequestRecordUiState> = persistentListOf(),
    val isRefreshing: Boolean = false,
    val pullModelName: String = "",
    val pull: PullUiState? = null,
    val deleteTargetModel: String? = null,
    @param:StringRes val messageRes: Int? = null,
)

@HiltViewModel
class ServerDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val serverRepository: ServerRepository,
        private val modelRepository: ModelRepository,
        private val ollamaClient: OllamaClient,
        requestHistory: RequestHistory,
    ) : ViewModel() {
        private val serverId = ServerId(savedStateHandle.toRoute<ServerDetailDestination>().serverId)

        private val transient = MutableStateFlow(TransientState())

        private var pullJob: Job? = null

        val uiState: StateFlow<ServerDetailUiState> = combine(
            serverRepository.statuses.map { list -> list.firstOrNull { it.server.id == serverId } },
            modelRepository.observeForServer(serverId),
            requestHistory.records.map { records -> records[serverId].orEmpty() },
            transient,
        ) { status, models, records, state ->
            if (status == null) {
                ServerDetailUiState(isLoading = false, missing = true)
            } else {
                ServerDetailUiState(
                    isLoading = false,
                    label = status.server.label,
                    baseUrl = status.server.baseUrl,
                    enabled = status.server.enabled,
                    reachable = status.reachable,
                    everChecked = status.lastCheckedAtMillis != null,
                    version = status.version,
                    latencyMillis = status.latencyMillis,
                    lastErrorRes = status.lastError?.messageRes(),
                    circuitOpen = status.circuitOpen,
                    models = models
                        .map { model ->
                            ModelRowUiState(
                                id = model.id.value,
                                displayName = model.displayName,
                                name = model.name,
                                sizeBytes = model.sizeBytes,
                                quantization = model.quantization?.name,
                            )
                        }.toImmutableList(),
                    loadedModels = status.loadedModels.toImmutableList(),
                    // Newest first: the interesting request is the one that just
                    // failed, and it is at the end of the stored history.
                    history = records.asReversed().map { it.toUiState() }.toImmutableList(),
                    isRefreshing = state.isRefreshing,
                    pullModelName = state.pullModelName,
                    pull = state.pull,
                    deleteTargetModel = state.deleteTargetModel,
                    messageRes = state.messageRes,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = ServerDetailUiState(),
        )

        fun onProbe() {
            viewModelScope.launch { serverRepository.probeNow(serverId) }
        }

        fun onRefreshModels() {
            viewModelScope.launch {
                transient.value = transient.value.copy(isRefreshing = true)
                val server = serverRepository.findServer(serverId)
                val result = server?.let { modelRepository.refreshServer(it) }
                transient.value = transient.value.copy(
                    isRefreshing = false,
                    messageRes = result?.error?.messageRes(),
                )
            }
        }

        fun onPullModelNameChange(value: String) {
            transient.value = transient.value.copy(pullModelName = value)
        }

        /**
         * Starts a `/api/pull`.
         *
         * The pull runs on the *server*, not on this device — the bytes never
         * touch the phone. Cancelling here stops watching it; it does not stop
         * the server, because the protocol has no way to say so.
         */
        fun onStartPull() {
            val model = transient.value.pullModelName.trim()
            if (model.isEmpty()) return
            pullJob?.cancel()
            pullJob = viewModelScope.launch {
                val server = serverRepository.findServer(serverId) ?: return@launch
                transient.value = transient.value.copy(
                    pull = PullUiState(model = model, status = "", fraction = null),
                )
                ollamaClient.pullModel(server, model).collect { progress ->
                    transient.value = transient.value.copy(
                        pull = PullUiState(
                            model = model,
                            status = progress.status,
                            fraction = progress.fraction?.toFloat(),
                            errorRes = progress.error?.messageRes(),
                            done = progress.done || progress.error != null,
                        ),
                    )
                }
                modelRepository.refreshServer(server)
            }
        }

        fun onDismissPull() {
            pullJob?.cancel()
            pullJob = null
            transient.value = transient.value.copy(pull = null)
        }

        fun onRequestDeleteModel(name: String) {
            transient.value = transient.value.copy(deleteTargetModel = name)
        }

        fun onCancelDeleteModel() {
            transient.value = transient.value.copy(deleteTargetModel = null)
        }

        fun onConfirmDeleteModel() {
            val name = transient.value.deleteTargetModel ?: return
            transient.value = transient.value.copy(deleteTargetModel = null)
            viewModelScope.launch {
                val server = serverRepository.findServer(serverId) ?: return@launch
                val messageRes = when (val result = ollamaClient.deleteModel(server, name)) {
                    is AppResult.Success -> R.string.server_model_deleted
                    is AppResult.Failure -> result.error.messageRes()
                }
                transient.value = transient.value.copy(messageRes = messageRes)
                modelRepository.refreshServer(server)
            }
        }

        fun onDismissMessage() {
            transient.value = transient.value.copy(messageRes = null)
        }

        private data class TransientState(
            val isRefreshing: Boolean = false,
            val pullModelName: String = "",
            val pull: PullUiState? = null,
            val deleteTargetModel: String? = null,
            @param:StringRes val messageRes: Int? = null,
        )

        internal companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        }
    }

private fun RequestRecord.toUiState(): RequestRecordUiState =
    RequestRecordUiState(
        key = "$startedAtMillis-$method-$path",
        method = method,
        path = path,
        startedAtMillis = startedAtMillis,
        durationMillis = durationMillis,
        statusCode = (outcome as? RequestOutcome.Answered)?.code,
        errorRes = (outcome as? RequestOutcome.Failed)?.error?.messageRes(),
        cancelled = outcome is RequestOutcome.Cancelled,
        success = isSuccess,
    )
