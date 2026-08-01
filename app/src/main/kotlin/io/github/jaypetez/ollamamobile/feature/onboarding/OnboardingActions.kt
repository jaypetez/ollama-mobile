package io.github.jaypetez.ollamamobile.feature.onboarding

import androidx.compose.runtime.Immutable

@Immutable
class OnboardingActions(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onSkipNotifications: () -> Unit,
    val onAddServer: () -> Unit,
    val onExplore: () -> Unit,
)
