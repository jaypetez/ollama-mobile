package io.github.jaypetez.ollamamobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.data.repository.ThemeMode
import io.github.jaypetez.ollamamobile.feature.onboarding.OnboardingStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What the activity needs before it can draw anything: the theme, and whether
 * this is a first run.
 */
data class MainUiState(
    /** True until both the theme and the onboarding flag have been read. */
    val isLoading: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showOnboarding: Boolean = false,
)

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        private val conversationRepository: ConversationRepository,
        onboardingStore: OnboardingStore,
    ) : ViewModel() {
        val uiState: StateFlow<MainUiState> = combine(
            settingsRepository.settings,
            onboardingStore.completed,
        ) { settings, onboardingCompleted ->
            MainUiState(
                isLoading = false,
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                showOnboarding = !onboardingCompleted,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = MainUiState(),
        )

        init {
            // A process killed mid-stream leaves an assistant row in `pending`,
            // which the chat screen renders as a caret that blinks forever. The
            // sweep has to run once per launch and nowhere else — see
            // ConversationRepository.recoverInterruptedTurns.
            viewModelScope.launch {
                val recovered = conversationRepository.recoverInterruptedTurns()
                if (recovered > 0) {
                    Timber.i("Recovered %d interrupted assistant turn(s) from a previous run", recovered)
                }
            }
        }

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        }
    }

/** Whether the app should draw dark, given the stored preference and the system setting. */
fun ThemeMode.isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
