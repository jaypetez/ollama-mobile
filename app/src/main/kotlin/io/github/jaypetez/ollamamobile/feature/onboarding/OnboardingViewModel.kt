package io.github.jaypetez.ollamamobile.feature.onboarding

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The pages of the first-run flow, in order. */
enum class OnboardingPage {
    Welcome,
    Notifications,
    Choice,
}

@Immutable
data class OnboardingUiState(
    val page: OnboardingPage = OnboardingPage.Welcome,
    /** False below API 33, where notifications need no runtime grant. */
    val notificationPermissionRequired: Boolean = false,
    val notificationPermissionAnswered: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    /**
     * Always false in this build. The onboarding copy is written from this
     * rather than hard-coded so the day an engine lands, the promise changes
     * with the capability instead of separately from it.
     */
    val localInferenceAvailable: Boolean = false,
) {
    val isFirstPage: Boolean get() = page == OnboardingPage.Welcome
    val isLastPage: Boolean get() = page == OnboardingPage.Choice
}

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val onboardingStore: OnboardingStore,
        modelRepository: ModelRepository,
    ) : ViewModel() {
        private val internalState = MutableStateFlow(
            OnboardingUiState(
                notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                localInferenceAvailable = modelRepository.localInferenceAvailable,
            ),
        )

        val uiState: StateFlow<OnboardingUiState> = internalState
            .map { it }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = internalState.value,
            )

        fun onNext() {
            val next = when (internalState.value.page) {
                OnboardingPage.Welcome -> {
                    if (internalState.value.notificationPermissionRequired) {
                        OnboardingPage.Notifications
                    } else {
                        OnboardingPage.Choice
                    }
                }

                OnboardingPage.Notifications -> {
                    OnboardingPage.Choice
                }

                OnboardingPage.Choice -> {
                    OnboardingPage.Choice
                }
            }
            internalState.value = internalState.value.copy(page = next)
        }

        fun onBack() {
            val previous = when (internalState.value.page) {
                OnboardingPage.Welcome -> {
                    OnboardingPage.Welcome
                }

                OnboardingPage.Notifications -> {
                    OnboardingPage.Welcome
                }

                OnboardingPage.Choice -> {
                    if (internalState.value.notificationPermissionRequired) {
                        OnboardingPage.Notifications
                    } else {
                        OnboardingPage.Welcome
                    }
                }
            }
            internalState.value = internalState.value.copy(page = previous)
        }

        fun onNotificationPermissionResult(granted: Boolean) {
            internalState.value = internalState.value.copy(
                notificationPermissionAnswered = true,
                notificationPermissionGranted = granted,
            )
        }

        /**
         * Marks the flow seen.
         *
         * Called whichever exit the user takes, including "just let me look
         * around": an onboarding screen that reappears because the user did not
         * finish it the approved way is a bug, not a nudge.
         */
        fun onFinish() {
            onboardingStore.markCompleted()
        }

        internal companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        }
    }
