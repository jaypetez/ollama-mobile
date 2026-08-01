package io.github.jaypetez.ollamamobile.feature.settings

import android.content.Context
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.repository.AppSettings
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.data.repository.ThemeMode
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.testing.MainDispatcherRule
import io.github.jaypetez.ollamamobile.testing.awaitUntil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric because the About section reads `BuildConfig` and the licence
 * text comes out of the APK's assets, neither of which exists on a bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = MutableStateFlow(AppSettings())

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
        every { this@mockk.settings } returns this@SettingsViewModelTest.settings
        coEvery { current() } returns AppSettings()
    }

    private val modelRepository = mockk<ModelRepository>(relaxed = true) {
        every { localInferenceAvailable } returns false
    }

    private val context: Context = androidx.test.core.app.ApplicationProvider
        .getApplicationContext()

    private fun viewModel() = SettingsViewModel(
        settingsRepository = settingsRepository,
        modelRepository = modelRepository,
        context = context,
        io = UnconfinedTestDispatcher(mainDispatcherRule.dispatcher.scheduler),
    )

    @Test
    fun `the state reports that on-device inference is unavailable in this build`() = runTest {
        viewModel().uiState.test {
            val loaded = awaitUntil { !it.isLoading }
            assertThat(loaded.isLoading).isFalse()
            assertThat(loaded.localInferenceAvailable).isFalse()
            assertThat(loaded.versionName).isNotEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing the network policy goes straight to the repository`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitUntil { !it.isLoading }
            viewModel.onNetworkPolicyChange(NetworkPolicy.OFFLINE)
            coVerify { settingsRepository.setNetworkPolicy(NetworkPolicy.OFFLINE) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an emptied sampling box is persisted as unset, not as zero`() = runTest {
        val captured = slot<SamplingParams>()
        coEvery { settingsRepository.setDefaultSampling(capture(captured)) } returns Unit
        coEvery { settingsRepository.current() } returns AppSettings(
            defaultSampling = SamplingParams(temperature = 0.7),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onSamplingChange(SamplingField.Temperature, "")
            // null, not 0.0 — "the server decides" is a different request from
            // "be completely deterministic".
            assertThat(captured.captured.temperature).isNull()

            viewModel.onSamplingChange(SamplingField.Temperature, "0.2")
            assertThat(captured.captured.temperature).isEqualTo(0.2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a half-typed number stays in the box even though it parses to unset`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onSamplingChange(SamplingField.TopP, "0.")
            assertThat(expectMostRecentItem().sampling.topP).isEqualTo("0.")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the theme mode follows the stored preference`() = runTest {
        settings.value = AppSettings(themeMode = ThemeMode.DARK, dynamicColor = false)
        viewModel().uiState.test {
            val loaded = awaitUntil { !it.isLoading }
            assertThat(loaded.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(loaded.dynamicColor).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the licence asset ships with the build and can be read`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onShowLicences()
            val shown = expectMostRecentItem()
            assertThat(shown.licencesFailed).isFalse()
            assertThat(shown.licences).isNotNull()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
