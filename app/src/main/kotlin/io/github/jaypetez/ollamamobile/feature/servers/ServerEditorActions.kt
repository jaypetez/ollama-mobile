package io.github.jaypetez.ollamamobile.feature.servers

import androidx.compose.runtime.Immutable

@Immutable
class ServerEditorActions(
    val onLabelChange: (String) -> Unit,
    val onUrlChange: (String) -> Unit,
    val onAuthModeChange: (ServerAuthMode) -> Unit,
    val onUsernameChange: (String) -> Unit,
    val onSecretChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)
