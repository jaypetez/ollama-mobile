package io.github.jaypetez.ollamamobile.feature.servers

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.repository.ServerCredential
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.designsystem.component.messageRes
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ServerAuth
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.remote.ServerUrls
import io.github.jaypetez.ollamamobile.remote.discovery.DiscoveryEvent
import io.github.jaypetez.ollamamobile.remote.discovery.SubnetScanner
import io.github.jaypetez.ollamamobile.remote.discovery.SweepRefusal
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How a server authenticates, in the shape the edit sheet needs. */
enum class ServerAuthMode {
    None,
    BearerToken,
    BasicAuth,
}

@Immutable
data class ServerRowUiState(
    val id: String,
    val label: String,
    val baseUrl: String,
    val enabled: Boolean,
    val reachable: Boolean,
    val circuitOpen: Boolean,
    /** False until the health monitor has actually probed it once. */
    val everChecked: Boolean,
    val version: String?,
    val latencyMillis: Long?,
    val authMode: ServerAuthMode,
)

/** The add/edit sheet. Null in [ServersUiState] means the sheet is closed. */
@Immutable
data class ServerEditorUiState(
    /** Null while adding a new server. */
    val serverId: String? = null,
    val label: String = "",
    val baseUrl: String = "",
    val authMode: ServerAuthMode = ServerAuthMode.None,
    val username: String = "",
    val secret: String = "",
    val isSaving: Boolean = false,
    @param:StringRes val labelErrorRes: Int? = null,
    @param:StringRes val urlErrorRes: Int? = null,
    @param:StringRes val secretErrorRes: Int? = null,
    @param:StringRes val saveErrorRes: Int? = null,
) {
    val isEditing: Boolean get() = serverId != null

    /**
     * The URL as it will actually be stored.
     *
     * Shown live under the field, because `ServerUrls` adds a scheme and port
     * 11434 for a bare `192.168.1.40` and a user who does not see that happen
     * assumes it did not.
     */
    val normalisedBaseUrl: String? get() = ServerUrls.parseOrNull(baseUrl)?.toString()?.trimEnd('/')
}

@Immutable
data class DiscoveredServerUiState(
    val baseUrl: String,
    val address: String,
    val version: String,
    val modelCount: Int?,
    val alreadyConfigured: Boolean,
)

@Immutable
data class ScanUiState(
    val isScanning: Boolean = false,
    /** How many addresses the sweep is probing. Zero until the sweep starts. */
    val candidateCount: Int = 0,
    val found: ImmutableList<DiscoveredServerUiState> = persistentListOf(),
    @param:StringRes val refusalRes: Int? = null,
    /** The subnet size, for the "too wide" refusal. Null otherwise. */
    val refusalDetail: String? = null,
    val finished: Boolean = false,
)

@Immutable
data class ServersUiState(
    val isLoading: Boolean = true,
    val servers: ImmutableList<ServerRowUiState> = persistentListOf(),
    val editor: ServerEditorUiState? = null,
    val scan: ScanUiState = ScanUiState(),
    val deleteTarget: ServerRowUiState? = null,
    @param:StringRes val messageRes: Int? = null,
) {
    val isEmpty: Boolean get() = !isLoading && servers.isEmpty()
}

/**
 * The server list, the add/edit sheet, and the discovery sweep.
 *
 * Discovery deliberately never runs on its own. A subnet sweep is 250-odd
 * connection attempts against machines the user does not own; doing that on a
 * timer, or on screen entry, is the kind of behaviour that gets an app flagged
 * on a managed network. It happens on a tap and only on a tap.
 */
@HiltViewModel
class ServersViewModel
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val subnetScanner: SubnetScanner,
    ) : ViewModel() {
        private val editor = MutableStateFlow<ServerEditorUiState?>(null)
        private val scan = MutableStateFlow(ScanUiState())
        private val transient = MutableStateFlow(TransientState())

        private var scanJob: Job? = null

        val uiState: StateFlow<ServersUiState> = combine(
            serverRepository.statuses,
            editor,
            scan,
            transient,
        ) { statuses, editorState, scanState, state ->
            val rows = statuses.map { status ->
                ServerRowUiState(
                    id = status.server.id.value,
                    label = status.server.label,
                    baseUrl = status.server.baseUrl,
                    enabled = status.server.enabled,
                    reachable = status.reachable,
                    circuitOpen = status.circuitOpen,
                    everChecked = status.lastCheckedAtMillis != null,
                    version = status.version,
                    latencyMillis = status.latencyMillis,
                    authMode = status.server.auth.toMode(),
                )
            }
            val configuredUrls = rows.map { it.baseUrl.trimEnd('/') }.toSet()
            ServersUiState(
                isLoading = false,
                servers = rows.toImmutableList(),
                editor = editorState,
                scan = scanState.copy(
                    found = scanState.found
                        .map { it.copy(alreadyConfigured = it.baseUrl.trimEnd('/') in configuredUrls) }
                        .toImmutableList(),
                ),
                deleteTarget = rows.firstOrNull { it.id == state.deleteTargetId },
                messageRes = state.messageRes,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = ServersUiState(),
        )

        // ---- list ---------------------------------------------------------

        fun onToggleEnabled(id: String, enabled: Boolean) {
            viewModelScope.launch { serverRepository.setEnabled(ServerId(id), enabled) }
        }

        fun onProbe(id: String) {
            viewModelScope.launch { serverRepository.probeNow(ServerId(id)) }
        }

        fun onRequestDelete(id: String) {
            transient.value = transient.value.copy(deleteTargetId = id)
        }

        fun onCancelDelete() {
            transient.value = transient.value.copy(deleteTargetId = null)
        }

        /**
         * No undo here, unlike the conversation list.
         *
         * Forgetting a server destroys its stored credential, and a Keystore
         * entry cannot be un-deleted — an "undo" that silently failed to
         * restore the token would be worse than the confirmation dialog.
         */
        fun onConfirmDelete() {
            val id = transient.value.deleteTargetId ?: return
            transient.value = transient.value.copy(deleteTargetId = null)
            viewModelScope.launch {
                serverRepository.deleteServer(ServerId(id))
                transient.value = transient.value.copy(messageRes = R.string.server_deleted)
            }
        }

        fun onDismissMessage() {
            transient.value = transient.value.copy(messageRes = null)
        }

        // ---- editor -------------------------------------------------------

        fun onAddServer() {
            editor.value = ServerEditorUiState()
        }

        fun onEditServer(id: String) {
            viewModelScope.launch {
                val server = serverRepository.findServer(ServerId(id)) ?: return@launch
                editor.value = ServerEditorUiState(
                    serverId = server.id.value,
                    label = server.label,
                    baseUrl = server.baseUrl,
                    authMode = server.auth.toMode(),
                    username = (server.auth as? ServerAuth.BasicAuth)?.username.orEmpty(),
                    // The stored secret is never pre-filled. It would have to be
                    // read out of the Keystore and held in Compose state, in a
                    // saved-instance bundle, for as long as the sheet is open.
                    secret = "",
                )
            }
        }

        fun onDismissEditor() {
            editor.value = null
        }

        fun onEditorLabelChange(value: String) {
            editor.value = editor.value?.copy(label = value, labelErrorRes = null)
        }

        fun onEditorUrlChange(value: String) {
            editor.value = editor.value?.copy(baseUrl = value, urlErrorRes = null)
        }

        fun onEditorAuthModeChange(mode: ServerAuthMode) {
            editor.value = editor.value?.copy(authMode = mode, secretErrorRes = null)
        }

        fun onEditorUsernameChange(value: String) {
            editor.value = editor.value?.copy(username = value, secretErrorRes = null)
        }

        fun onEditorSecretChange(value: String) {
            editor.value = editor.value?.copy(secret = value, secretErrorRes = null)
        }

        fun onSaveServer() {
            val current = editor.value ?: return
            val validated = current.validated()
            if (validated.hasErrors) {
                editor.value = validated
                return
            }
            editor.value = validated.copy(isSaving = true)
            viewModelScope.launch { save(validated) }
        }

        // ---- discovery ----------------------------------------------------

        fun onStartScan() {
            scanJob?.cancel()
            scan.value = ScanUiState(isScanning = true)
            scanJob = viewModelScope.launch {
                subnetScanner.scan().collect { event -> scan.value = scan.value.reduce(event) }
                // The flow can also end because the collector was cancelled, in
                // which case nothing above cleared the spinner.
                scan.value = scan.value.copy(isScanning = false)
            }
        }

        fun onStopScan() {
            scanJob?.cancel()
            scanJob = null
            scan.value = scan.value.copy(isScanning = false, finished = true)
        }

        /** Opens the add sheet pre-filled from a discovered host, rather than adding silently. */
        fun onAdoptDiscovered(baseUrl: String) {
            val discovered = scan.value.found.firstOrNull { it.baseUrl == baseUrl } ?: return
            editor.value = ServerEditorUiState(
                label = discovered.address,
                baseUrl = discovered.baseUrl,
            )
        }

        // ---- internals ----------------------------------------------------

        private suspend fun save(state: ServerEditorUiState) {
            val credential = state.credential()
            val existingId = state.serverId
            val result = if (existingId == null) {
                serverRepository.addServer(
                    label = state.label.trim(),
                    baseUrl = state.baseUrl.trim(),
                    credential = credential,
                )
            } else {
                val existing = serverRepository.findServer(ServerId(existingId))
                if (existing == null) {
                    AppResult.Failure(AppError.Storage.NotFound(what = existingId))
                } else {
                    serverRepository.saveServer(
                        server = existing.copy(
                            label = state.label.trim(),
                            baseUrl = state.baseUrl.trim().trimEnd('/'),
                        ),
                        credential = credential,
                    )
                }
            }
            when (result) {
                is AppResult.Success -> {
                    editor.value = null
                    transient.value = transient.value.copy(
                        messageRes = if (existingId == null) R.string.server_added else R.string.server_saved,
                    )
                    serverRepository.probeNow(result.value.id)
                }

                is AppResult.Failure -> {
                    editor.value = state.copy(isSaving = false, saveErrorRes = result.error.messageRes())
                }
            }
        }

        private data class TransientState(
            val deleteTargetId: String? = null,
            @param:StringRes val messageRes: Int? = null,
        )

        internal companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        }
    }

private fun ServerAuth.toMode(): ServerAuthMode = when (this) {
    is ServerAuth.None -> ServerAuthMode.None
    is ServerAuth.BearerToken -> ServerAuthMode.BearerToken
    is ServerAuth.BasicAuth -> ServerAuthMode.BasicAuth
}

private val ServerEditorUiState.hasErrors: Boolean
    get() = labelErrorRes != null || urlErrorRes != null || secretErrorRes != null

private fun ServerEditorUiState.validated(): ServerEditorUiState = copy(
    labelErrorRes = R.string.server_error_label_required.takeIf { label.isBlank() },
    urlErrorRes = when {
        baseUrl.isBlank() -> R.string.server_error_url_required
        ServerUrls.parseOrNull(baseUrl) == null -> R.string.server_error_url_invalid
        else -> null
    },
    secretErrorRes = when {
        authMode == ServerAuthMode.BearerToken && secret.isBlank() && !isEditing -> {
            R.string.server_error_token_required
        }

        authMode == ServerAuthMode.BasicAuth && username.isBlank() -> {
            R.string.server_error_username_required
        }

        else -> {
            null
        }
    },
    saveErrorRes = null,
)

/**
 * What to do with the stored secret.
 *
 * An empty box while editing means "leave it alone", not "clear it": the sheet
 * never pre-fills the stored value, so an empty field is the normal state for
 * an existing server whose token the user is not changing.
 */
private fun ServerEditorUiState.credential(): ServerCredential = when (authMode) {
    ServerAuthMode.None -> {
        ServerCredential.None
    }

    ServerAuthMode.BearerToken -> {
        if (secret.isBlank() && isEditing) {
            ServerCredential.Unchanged
        } else {
            ServerCredential.BearerToken(secret)
        }
    }

    ServerAuthMode.BasicAuth -> {
        if (secret.isBlank() && isEditing) {
            ServerCredential.Unchanged
        } else {
            ServerCredential.BasicAuth(username.trim(), secret)
        }
    }
}

private fun ScanUiState.reduce(event: DiscoveryEvent): ScanUiState = when (event) {
    is DiscoveryEvent.Started -> copy(isScanning = true, candidateCount = event.candidateCount, finished = false)

    is DiscoveryEvent.Found -> copy(
        found = (
            found + DiscoveredServerUiState(
                baseUrl = event.server.baseUrl,
                address = event.server.address,
                version = event.server.version,
                modelCount = event.server.modelCount,
                alreadyConfigured = false,
            )
        ).toImmutableList(),
    )

    is DiscoveryEvent.Finished -> copy(isScanning = false, finished = true)

    is DiscoveryEvent.Refused -> copy(
        isScanning = false,
        finished = true,
        refusalRes = event.reason.messageRes(),
        refusalDetail = (event.reason as? SweepRefusal.SubnetTooWide)?.let { "/${it.prefixLength}" },
    )
}

@StringRes
private fun SweepRefusal.messageRes(): Int = when (this) {
    is SweepRefusal.SubnetTooWide -> R.string.discovery_refused_subnet_too_wide
    is SweepRefusal.NoActiveNetwork -> R.string.discovery_refused_no_network
    is SweepRefusal.NoIpv4Link -> R.string.discovery_refused_no_ipv4
    is SweepRefusal.Blocked -> error.messageRes()
}
