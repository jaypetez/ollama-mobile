package io.github.jaypetez.ollamamobile.feature.devtools

import androidx.compose.runtime.Immutable

@Immutable
class DeveloperToolsActions(
    val onBack: () -> Unit,
    val onSelectTab: (DeveloperToolsTab) -> Unit,
    val onToggleExchange: (Long) -> Unit,
    val onClear: () -> Unit,
    val onShare: () -> Unit,
    val onExportConsume: () -> Unit,
)
