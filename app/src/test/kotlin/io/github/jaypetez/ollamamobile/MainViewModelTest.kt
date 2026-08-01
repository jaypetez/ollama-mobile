package io.github.jaypetez.ollamamobile

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.repository.AppSettings
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.data.repository.ThemeMode
import io.github.jaypetez.ollamamobile.feature.onboarding.OnboardingStore
import io.github.jaypetez.ollamamobile.testing.MainDispatcherRule
import io.github.jaypetez.ollamamobile.testing.awaitUntil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = MutableStateFlow(AppSettings())

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
        every { this@mockk.settings } returns this@MainViewModelTest.settings
    }

    private val conversationRepository = mockk<ConversationRepository>(relaxed = true) {
        coEvery { recoverInterruptedTurns() } returns 2
    }

    private fun viewModel(onboardingCompleted: Boolean) = MainViewModel(
        settingsRepository = settingsRepository,
        conversationRepository = conversationRepository,
        onboardingStore = mockk<OnboardingStore>(relaxed = true) {
            every { completed } returns flowOf(onboardingCompleted)
        },
    )

    @Test
    fun `startup sweeps turns stranded by a killed process`() = runTest {
        viewModel(onboardingCompleted = true)
        // Without this a message left `pending` renders as a caret that blinks
        // forever, and no user action clears it.
        coVerify(exactly = 1) { conversationRepository.recoverInterruptedTurns() }
    }

    @Test
    fun `the splash is held until both the theme and the first-run flag are known`() = runTest {
        val viewModel = viewModel(onboardingCompleted = true)

        // This is exactly what `setKeepOnScreenCondition` reads, and before
        // anything collects, `WhileSubscribed` has not started the upstream.
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        viewModel.uiState.test {
            assertThat(awaitUntil { !it.isLoading }.isLoading).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a first run starts on onboarding and a later one does not`() = runTest {
        viewModel(onboardingCompleted = false).uiState.test {
            assertThat(awaitUntil { !it.isLoading }.showOnboarding).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel(onboardingCompleted = true).uiState.test {
            assertThat(awaitUntil { !it.isLoading }.showOnboarding).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the stored theme mode decides the palette, and SYSTEM defers`() {
        assertThat(ThemeMode.DARK.isDark(systemInDarkTheme = false)).isTrue()
        assertThat(ThemeMode.LIGHT.isDark(systemInDarkTheme = true)).isFalse()
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false)).isFalse()
    }
}
