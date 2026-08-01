package io.github.jaypetez.ollamamobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaMobileTheme
import io.github.jaypetez.ollamamobile.navigation.ConversationsDestination
import io.github.jaypetez.ollamamobile.navigation.OllamaMobileApp
import io.github.jaypetez.ollamamobile.navigation.OnboardingDestination

/**
 * The single activity.
 *
 * Everything it does is here on purpose and nothing else is: edge-to-edge, the
 * splash hand-off, the theme, and the nav graph. Logic that grew here would be
 * logic no unit test could reach, because a test cannot construct an Activity
 * without an instrumentation host.
 *
 * Predictive back needs nothing in code: `enableOnBackInvokedCallback` is
 * declared on the application in the manifest, and navigation-compose 2.8+
 * drives the in-app back gesture from the `NavHost`'s own transitions.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // targetSdk 35+ enforces edge-to-edge with no opt-out, so opt in
        // explicitly and pad with safeDrawing rather than fighting insets later.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Held until the theme and the first-run flag have been read off disk.
        // Without this the app draws the light default for one frame and then
        // repaints dark, which on an OLED screen at night is a flash in the eye.
        splashScreen.setKeepOnScreenCondition { viewModel.uiState.value.isLoading }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            OllamaMobileTheme(
                darkTheme = state.themeMode.isDark(isSystemInDarkTheme()),
                dynamicColor = state.dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!state.isLoading) {
                        AppContent(showOnboarding = state.showOnboarding)
                    }
                }
            }
        }
    }
}

/**
 * The start destination is captured once.
 *
 * Recomputing it would recreate the whole `NavHost` the moment onboarding marks
 * itself complete, throwing away the back stack the user is standing on.
 */
@Composable
private fun AppContent(showOnboarding: Boolean) {
    val startDestination = remember {
        if (showOnboarding) OnboardingDestination else ConversationsDestination
    }
    OllamaMobileApp(startDestination = startDestination)
}
