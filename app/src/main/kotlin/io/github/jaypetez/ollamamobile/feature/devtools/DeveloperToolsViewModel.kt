package io.github.jaypetez.ollamamobile.feature.devtools

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.common.inspector.ApiExchange
import io.github.jaypetez.ollamamobile.common.inspector.ApiInspector
import io.github.jaypetez.ollamamobile.common.log.LogRing
import io.github.jaypetez.ollamamobile.ui.formatAbsoluteTime
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class DeveloperToolsTab {
    ApiInspector,
    Logs,
}

@Immutable
data class ApiExchangeUiState(
    val id: Long,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val durationMillis: Long?,
    val startedAtMillis: Long,
    val failure: String?,
    val requestHeaders: ImmutableList<String>,
    val requestBody: String?,
    val responseHeaders: ImmutableList<String>,
    val responseBody: String?,
    val curl: String,
) {
    val succeeded: Boolean get() = statusCode != null && statusCode in SUCCESS_RANGE

    private companion object {
        val SUCCESS_RANGE = 200..299
    }
}

@Immutable
data class LogLineUiState(
    val key: String,
    val timestampMillis: Long,
    val level: Char,
    val tag: String?,
    val message: String,
)

@Immutable
data class DeveloperToolsUiState(
    val tab: DeveloperToolsTab = DeveloperToolsTab.ApiInspector,
    /** False in a release build: capture is off, so the list is genuinely empty. */
    val inspectorEnabled: Boolean = false,
    val exchanges: ImmutableList<ApiExchangeUiState> = persistentListOf(),
    val expandedExchangeId: Long? = null,
    val logs: ImmutableList<LogLineUiState> = persistentListOf(),
    /** Set for one frame when the user asks to share; the screen fires the intent and clears it. */
    val pendingExport: String? = null,
)

/**
 * The API inspector and the log viewer.
 *
 * Everything shown here goes through [CredentialScrubber] on the way out.
 * `ApiInspector` already redacts headers by name; this screen is where the
 * *bodies* and the log lines become visible, and it is also where the share
 * button is, so it is the last place a token can be caught before it lands in
 * an email.
 */
@HiltViewModel
class DeveloperToolsViewModel
    @Inject
    constructor(
        private val apiInspector: ApiInspector,
        private val logRing: LogRing,
    ) : ViewModel() {
        private val tab = MutableStateFlow(DeveloperToolsTab.ApiInspector)
        private val expanded = MutableStateFlow<Long?>(null)
        private val pendingExport = MutableStateFlow<String?>(null)

        /**
         * The log ring is a plain bounded buffer with no change notification —
         * it is written from every Timber call on every thread, and adding a
         * flow emission per line would put allocation on the logging hot path.
         * Polling once a second is the right trade for a diagnostics screen,
         * and `WhileSubscribed` stops it the moment the screen goes away.
         */
        private val logTicker: Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(LOG_POLL_MILLIS)
            }
        }

        val uiState: StateFlow<DeveloperToolsUiState> = combine(
            tab,
            apiInspector.recorded,
            logTicker.map { logRing.snapshot() },
            expanded,
            pendingExport,
        ) { currentTab, exchanges, logs, expandedId, export ->
            DeveloperToolsUiState(
                tab = currentTab,
                inspectorEnabled = apiInspector.enabled,
                exchanges = exchanges.map { it.toUiState() }.toImmutableList(),
                expandedExchangeId = expandedId,
                logs = logs
                    .asReversed()
                    .mapIndexed { index, record ->
                        LogLineUiState(
                            key = "${record.timestampMillis}-$index",
                            timestampMillis = record.timestampMillis,
                            level = record.level.initial,
                            tag = record.tag,
                            message = CredentialScrubber.scrub(record.message),
                        )
                    }.toImmutableList(),
                pendingExport = export,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = DeveloperToolsUiState(),
        )

        fun onSelectTab(selected: DeveloperToolsTab) {
            tab.value = selected
        }

        fun onToggleExchange(id: Long) {
            expanded.value = if (expanded.value == id) null else id
        }

        fun onClear() {
            when (tab.value) {
                DeveloperToolsTab.ApiInspector -> apiInspector.clear()
                DeveloperToolsTab.Logs -> logRing.clear()
            }
        }

        fun onShare() {
            pendingExport.value = when (tab.value) {
                DeveloperToolsTab.ApiInspector -> renderExchanges()
                DeveloperToolsTab.Logs -> renderLogs()
            }
        }

        fun onExportConsume() {
            pendingExport.value = null
        }

        private fun renderExchanges(): String = buildString {
            uiState.value.exchanges.forEach { exchange ->
                appendLine("${exchange.method} ${exchange.url}")
                appendLine(formatAbsoluteTime(exchange.startedAtMillis))
                exchange.requestHeaders.forEach { appendLine("> $it") }
                exchange.requestBody?.let { appendLine("> $it") }
                appendLine("< ${exchange.statusCode ?: exchange.failure.orEmpty()}")
                exchange.responseHeaders.forEach { appendLine("< $it") }
                exchange.responseBody?.let { appendLine("< $it") }
                appendLine()
            }
        }

        private fun renderLogs(): String = buildString {
            uiState.value.logs.asReversed().forEach { line ->
                appendLine(
                    "${formatAbsoluteTime(line.timestampMillis)} ${line.level}/${line.tag.orEmpty()}: ${line.message}",
                )
            }
        }

        internal companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
            const val LOG_POLL_MILLIS = 1_000L
        }
    }

private fun ApiExchange.toUiState(): ApiExchangeUiState = ApiExchangeUiState(
    id = id,
    method = method,
    url = CredentialScrubber.scrub(url),
    statusCode = statusCode,
    durationMillis = durationMillis,
    startedAtMillis = startedAtMillis,
    failure = failure,
    requestHeaders = requestHeaders
        .map { CredentialScrubber.scrub("${it.name}: ${it.value}") }
        .toImmutableList(),
    requestBody = CredentialScrubber.scrubOrNull(requestBodyPreview),
    responseHeaders = responseHeaders
        .map { CredentialScrubber.scrub("${it.name}: ${it.value}") }
        .toImmutableList(),
    responseBody = CredentialScrubber.scrubOrNull(responseBodyPreview),
    curl = CredentialScrubber.scrub(toCurl()),
)
