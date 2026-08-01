package io.github.jaypetez.ollamamobile.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

@Composable
fun OnboardingRoute(
    onAddServer: () -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            viewModel.onNotificationPermissionResult(granted)
            viewModel.onNext()
        },
    )
    val actions = remember(viewModel, onAddServer, onExplore, permissionLauncher) {
        OnboardingActions(
            onNext = viewModel::onNext,
            onBack = viewModel::onBack,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(postNotificationsPermission())
                } else {
                    viewModel.onNext()
                }
            },
            onSkipNotifications = viewModel::onNext,
            onAddServer = {
                viewModel.onFinish()
                onAddServer()
            },
            onExplore = {
                viewModel.onFinish()
                onExplore()
            },
        )
    }
    OnboardingScreen(state = state, actions = actions, modifier = modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun postNotificationsPermission(): String = Manifest.permission.POST_NOTIFICATIONS

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // No Scaffold here: onboarding has no bars, so it applies the
                // safe-drawing inset itself rather than inheriting one.
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Xl, vertical = Spacing.Xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg, Alignment.CenterVertically),
        ) {
            when (state.page) {
                OnboardingPage.Welcome -> WelcomePage(state = state, onNext = actions.onNext)
                OnboardingPage.Notifications -> NotificationsPage(state = state, actions = actions)
                OnboardingPage.Choice -> ChoicePage(actions = actions)
            }
        }
    }
}

@Composable
private fun WelcomePage(
    state: OnboardingUiState,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        icon = Icons.AutoMirrored.Outlined.Chat,
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
        modifier = modifier,
    ) {
        // Stated on the first screen, not buried in an FAQ. This build talks to
        // an Ollama server over the network; there is no on-device engine, and
        // a user who installed it expecting offline inference should find that
        // out now rather than after configuring a model that cannot run.
        Text(
            text = stringResource(
                if (state.localInferenceAvailable) {
                    R.string.onboarding_local_available
                } else {
                    R.string.onboarding_local_unavailable
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OllamaButton(
            text = stringResource(R.string.onboarding_continue),
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NotificationsPage(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        icon = Icons.Outlined.NotificationsActive,
        title = stringResource(R.string.onboarding_notifications_title),
        body = stringResource(R.string.onboarding_notifications_body),
        modifier = modifier,
    ) {
        if (state.notificationPermissionAnswered && !state.notificationPermissionGranted) {
            Text(
                text = stringResource(R.string.onboarding_notifications_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        OllamaButton(
            text = stringResource(R.string.onboarding_notifications_allow),
            onClick = actions.onRequestNotifications,
            modifier = Modifier.fillMaxWidth(),
        )
        OllamaButton(
            text = stringResource(R.string.onboarding_notifications_skip),
            onClick = actions.onSkipNotifications,
            modifier = Modifier.fillMaxWidth(),
            style = OllamaButtonStyle.Text,
        )
    }
}

@Composable
private fun ChoicePage(
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        icon = Icons.Outlined.RocketLaunch,
        title = stringResource(R.string.onboarding_choice_title),
        body = stringResource(R.string.onboarding_choice_body),
        modifier = modifier,
    ) {
        OllamaButton(
            text = stringResource(R.string.onboarding_choice_add_server),
            onClick = actions.onAddServer,
            modifier = Modifier.fillMaxWidth(),
        )
        OllamaButton(
            text = stringResource(R.string.onboarding_choice_explore),
            onClick = actions.onExplore,
            modifier = Modifier.fillMaxWidth(),
            style = OllamaButtonStyle.Secondary,
        )
        OllamaButton(
            text = stringResource(R.string.action_back),
            onClick = actions.onBack,
            modifier = Modifier.fillMaxWidth(),
            style = OllamaButtonStyle.Text,
        )
    }
}

@Composable
private fun PageScaffold(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(Sizes.OnboardingIcon),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
    }
}

@Preview
@Composable
private fun OnboardingScreenPreview() {
    OllamaPreviewTheme {
        OnboardingScreen(
            state = OnboardingUiState(),
            actions = OnboardingActions(
                onNext = {},
                onBack = {},
                onRequestNotifications = {},
                onSkipNotifications = {},
                onAddServer = {},
                onExplore = {},
            ),
        )
    }
}
