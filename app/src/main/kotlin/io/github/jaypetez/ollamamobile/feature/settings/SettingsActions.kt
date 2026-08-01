package io.github.jaypetez.ollamamobile.feature.settings

import androidx.compose.runtime.Immutable
import io.github.jaypetez.ollamamobile.data.repository.ThemeMode
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.model.NetworkPolicy

@Immutable
class SettingsActions(
    val onNetworkPolicy: (NetworkPolicy) -> Unit,
    val onRoutingPolicy: (RoutingPolicy) -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onDynamicColor: (Boolean) -> Unit,
    val onShowReasoning: (Boolean) -> Unit,
    val onRequestReasoning: (Boolean) -> Unit,
    val onSamplingChange: (SamplingField, String) -> Unit,
    val onShowLicences: () -> Unit,
    val onHideLicences: () -> Unit,
    val onOpenDeveloperTools: () -> Unit,
)
