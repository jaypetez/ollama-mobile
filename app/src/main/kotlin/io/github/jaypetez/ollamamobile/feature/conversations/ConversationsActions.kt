package io.github.jaypetez.ollamamobile.feature.conversations

import androidx.compose.runtime.Immutable

/**
 * Everything the conversation list can do, in one holder.
 *
 * A screen with a dozen callbacks either takes a dozen parameters — which is
 * unreadable at the call site and trips the parameter-count limit — or bundles
 * them. The holder is `@Immutable` and built once from method references, so
 * Compose still skips the screen when only the state changed.
 */
@Immutable
class ConversationsActions(
    val onQueryChange: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val onNew: () -> Unit,
    val onDelete: (String) -> Unit,
    val onUndoDelete: () -> Unit,
    val onUndoExpire: () -> Unit,
    val onStartRename: (String, String) -> Unit,
    val onRenameTextChange: (String) -> Unit,
    val onConfirmRename: () -> Unit,
    val onCancelRename: () -> Unit,
    val onTogglePinned: (String, Boolean) -> Unit,
)
