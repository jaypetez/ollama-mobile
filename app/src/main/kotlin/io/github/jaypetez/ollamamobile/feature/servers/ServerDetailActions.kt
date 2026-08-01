package io.github.jaypetez.ollamamobile.feature.servers

import androidx.compose.runtime.Immutable

@Immutable
class ServerDetailActions(
    val onBack: () -> Unit,
    val onProbe: () -> Unit,
    val onRefreshModels: () -> Unit,
    val onPullNameChange: (String) -> Unit,
    val onStartPull: () -> Unit,
    val onDismissPull: () -> Unit,
    val onRequestDeleteModel: (String) -> Unit,
    val onConfirmDeleteModel: () -> Unit,
    val onCancelDeleteModel: () -> Unit,
    val onDismissMessage: () -> Unit,
)
