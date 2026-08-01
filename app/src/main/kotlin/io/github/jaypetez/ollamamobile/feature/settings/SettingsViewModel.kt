package io.github.jaypetez.ollamamobile.feature.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.BuildConfig
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.data.repository.ThemeMode
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import io.github.jaypetez.ollamamobile.model.SamplingParams
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Which sampling box the user is typing in. One callback instead of five. */
enum class SamplingField {
    Temperature,
    TopP,
    TopK,
    NumPredict,
    NumCtx,
}

/**
 * The sampling defaults as text.
 *
 * Strings and not numbers, because every one of these is nullable in
 * [SamplingParams] and null means "let the server decide" — which is a
 * different request from sending an explicit `0`. An empty box is the only
 * honest representation of "unset", and a `Double?` field cannot hold the
 * half-typed `0.` a user is in the middle of.
 */
@Immutable
data class SamplingUiState(
    val temperature: String = "",
    val topP: String = "",
    val topK: String = "",
    val numPredict: String = "",
    val numCtx: String = "",
) {
    fun with(field: SamplingField, value: String): SamplingUiState = when (field) {
        SamplingField.Temperature -> copy(temperature = value)
        SamplingField.TopP -> copy(topP = value)
        SamplingField.TopK -> copy(topK = value)
        SamplingField.NumPredict -> copy(numPredict = value)
        SamplingField.NumCtx -> copy(numCtx = value)
    }

    fun toParams(existing: SamplingParams): SamplingParams = existing.copy(
        temperature = temperature.toDoubleOrNull(),
        topP = topP.toDoubleOrNull(),
        topK = topK.toIntOrNull(),
        numPredict = numPredict.toIntOrNull(),
        numCtx = numCtx.toIntOrNull(),
    )

    companion object {
        fun from(params: SamplingParams): SamplingUiState = SamplingUiState(
            temperature = params.temperature?.toString().orEmpty(),
            topP = params.topP?.toString().orEmpty(),
            topK = params.topK?.toString().orEmpty(),
            numPredict = params.numPredict?.toString().orEmpty(),
            numCtx = params.numCtx?.toString().orEmpty(),
        )
    }
}

@Immutable
data class SettingsUiState(
    val isLoading: Boolean = true,
    val networkPolicy: NetworkPolicy = NetworkPolicy.LAN_ONLY,
    val routingPolicy: RoutingPolicy = RoutingPolicy.Default,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showReasoning: Boolean = false,
    val requestReasoning: Boolean = true,
    val sampling: SamplingUiState = SamplingUiState(),
    val versionName: String = "",
    val versionCode: Int = 0,
    /** Non-null while the licence sheet is open. */
    val licences: String? = null,
    val licencesFailed: Boolean = false,
    /** Always false in this build. Drives the honest wording next to the routing options. */
    val localInferenceAvailable: Boolean = false,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        modelRepository: ModelRepository,
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val samplingDraft = MutableStateFlow<SamplingUiState?>(null)
        private val licences = MutableStateFlow(LicenceState())

        private val localInferenceAvailable = modelRepository.localInferenceAvailable

        val uiState: StateFlow<SettingsUiState> = combine(
            settingsRepository.settings,
            samplingDraft,
            licences,
        ) { settings, draft, licenceState ->
            SettingsUiState(
                isLoading = false,
                networkPolicy = settings.networkPolicy,
                routingPolicy = settings.routingPolicy,
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                showReasoning = settings.showReasoning,
                requestReasoning = settings.requestReasoning,
                sampling = draft ?: SamplingUiState.from(settings.defaultSampling),
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                licences = licenceState.text,
                licencesFailed = licenceState.failed,
                localInferenceAvailable = localInferenceAvailable,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(),
        )

        fun onNetworkPolicyChange(policy: NetworkPolicy) {
            viewModelScope.launch { settingsRepository.setNetworkPolicy(policy) }
        }

        fun onRoutingPolicyChange(policy: RoutingPolicy) {
            viewModelScope.launch { settingsRepository.setRoutingPolicy(policy) }
        }

        fun onThemeModeChange(mode: ThemeMode) {
            viewModelScope.launch { settingsRepository.setThemeMode(mode) }
        }

        fun onDynamicColorChange(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
        }

        fun onShowReasoningChange(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setShowReasoning(enabled) }
        }

        fun onRequestReasoningChange(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setRequestReasoning(enabled) }
        }

        /**
         * Persists on every keystroke.
         *
         * The write is a single upsert into the settings table and the draft is
         * what the field shows, so a half-typed `0.` stays on screen and simply
         * parses to null — the setting reverts to "server default" until the
         * number is finished, which is exactly what the user has typed so far.
         */
        fun onSamplingChange(field: SamplingField, value: String) {
            val current = samplingDraft.value ?: uiState.value.sampling
            val updated = current.with(field, value)
            samplingDraft.value = updated
            viewModelScope.launch {
                val existing = settingsRepository.current().defaultSampling
                settingsRepository.setDefaultSampling(updated.toParams(existing))
            }
        }

        fun onShowLicences() {
            viewModelScope.launch {
                val text = withContext(io) {
                    runCatching {
                        context.assets
                            .open(LICENCES_ASSET)
                            .bufferedReader()
                            .use { it.readText() }
                    }.getOrElse { failure ->
                        if (failure is IOException) {
                            Timber.w(failure, "Third-party licence asset could not be read")
                            null
                        } else {
                            throw failure
                        }
                    }
                }
                licences.value = LicenceState(text = text, failed = text == null)
            }
        }

        fun onHideLicences() {
            licences.value = LicenceState()
        }

        private data class LicenceState(
            val text: String? = null,
            val failed: Boolean = false,
        )

        internal companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

            /** Generated at build time from the repository's THIRD_PARTY_LICENSES.md. */
            const val LICENCES_ASSET = "third_party_licenses.md"
        }
    }
