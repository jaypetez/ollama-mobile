package io.github.jaypetez.ollamamobile.feature.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.server.BindPolicy
import io.github.jaypetez.ollamamobile.server.OllamaServerController
import io.github.jaypetez.ollamamobile.server.OllamaServerService
import io.github.jaypetez.ollamamobile.server.ServerConfig
import io.github.jaypetez.ollamamobile.server.ServerState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the embedded-server settings card shows and does.
 *
 * The screen never touches the Ktor engine: it starts and stops a foreground
 * service, and observes [OllamaServerController]. That split is what keeps the
 * listening socket tied to a visible notification rather than to a composable's
 * lifetime.
 */
@Immutable
data class ServerSettingsUiState(
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    /** `127.0.0.1:11434` while running; empty otherwise. */
    val address: String = "",
    val lanExposed: Boolean = false,
    /**
     * The session bearer token, shown so it can be copied to a laptop.
     *
     * Present only while LAN exposure is on. It is never persisted, so closing
     * the app invalidates it — which is the point.
     */
    val token: String? = null,
    val requestCount: Long = 0L,
    val error: String? = null,
) {
    /** The curl line a user can paste on their laptop. */
    val exampleCommand: String
        get() = buildString {
            append("curl http://")
            append(address.ifEmpty { "127.0.0.1:${ServerConfig.DEFAULT_PORT}" })
            append("/api/tags")
            token?.let { append(" -H \"Authorization: Bearer $it\"") }
        }
}

@HiltViewModel
class ServerSettingsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        controller: OllamaServerController,
    ) : ViewModel() {
        val uiState: StateFlow<ServerSettingsUiState> =
            combine(
                controller.state,
                controller.lanToken,
                controller.requestCount,
            ) { state, token, count ->
                ServerSettingsUiState(
                    isRunning = state is ServerState.Running,
                    isBusy = state is ServerState.Starting,
                    address = (state as? ServerState.Running)?.config?.displayAddress.orEmpty(),
                    lanExposed = token != null,
                    token = token,
                    requestCount = count,
                    error = (state as? ServerState.Failed)?.reason,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ServerSettingsUiState(),
            )

        /**
         * Starts the server.
         *
         * [lanExposure] is passed per start and never remembered: exposing the
         * phone to a network is a decision about *this* network, and a
         * remembered "yes" would silently apply to the next café Wi-Fi.
         */
        fun start(lanExposure: Boolean) {
            OllamaServerService.start(context, ServerConfig.DEFAULT_PORT, lanExposure)
        }

        fun stop() {
            OllamaServerService.stop(context)
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
