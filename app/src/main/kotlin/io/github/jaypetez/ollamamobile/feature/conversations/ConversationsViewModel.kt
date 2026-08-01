package io.github.jaypetez.ollamamobile.feature.conversations

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.model.ConversationId
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the conversation list. */
@Immutable
data class ConversationItemUiState(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val pinned: Boolean,
)

/** One FTS5 hit: the matching message, and the thread it belongs to. */
@Immutable
data class SearchHitUiState(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val snippet: String,
    val createdAtMillis: Long,
)

/** The conversation the rename dialog is editing, and the text currently in it. */
@Immutable
data class RenameTarget(
    val id: String,
    val text: String,
)

@Immutable
data class ConversationsUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val conversations: ImmutableList<ConversationItemUiState> = persistentListOf(),
    val searchHits: ImmutableList<SearchHitUiState> = persistentListOf(),
    val renameTarget: RenameTarget? = null,
    /** Non-null while a delete can still be undone. Drives the snackbar. */
    val undoDeleteTitle: String? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    /** True only for a genuinely empty database, never for "your search found nothing". */
    val showFirstRunEmptyState: Boolean
        get() = !isLoading && !isSearching && conversations.isEmpty()

    val showNoSearchResults: Boolean
        get() = isSearching && searchHits.isEmpty()
}

/**
 * The conversation list: search, rename, and delete with an undo window.
 *
 * The undo window is real rather than cosmetic — the row disappears
 * immediately, but `deleteConversation` is not called until the window closes.
 * A "delete now, re-insert on undo" implementation would have to recreate every
 * message, every attachment and every FTS row, and would change every id.
 */
@HiltViewModel
class ConversationsViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val transient = MutableStateFlow(TransientState())

        private var undoTimer: Job? = null

        private val searchHits: Flow<ImmutableList<SearchHitUiState>> = query
            // Every keystroke is an FTS5 query plus a Room re-subscription; at
            // typing speed that is a dozen queries for a word nobody finished.
            .debounce(SEARCH_DEBOUNCE_MILLIS)
            .flatMapLatest { text ->
                if (text.isBlank()) {
                    flowOf(persistentListOf())
                } else {
                    conversationRepository.searchMessages(text).map { hits ->
                        hits
                            .map { hit ->
                                SearchHitUiState(
                                    messageId = hit.message.id.value,
                                    conversationId = hit.conversationId.value,
                                    conversationTitle = hit.conversationTitle,
                                    snippet = hit.message.content.snippet(),
                                    createdAtMillis = hit.message.createdAt,
                                )
                            }.toImmutableList()
                    }
                }
            }

        val uiState: StateFlow<ConversationsUiState> = combine(
            conversationRepository.observeConversations(),
            query,
            searchHits,
            transient,
        ) { summaries, text, hits, state ->
            ConversationsUiState(
                isLoading = false,
                query = text,
                conversations = summaries
                    // The pending row is filtered here rather than removed from
                    // the database, so "undo" is a no-op instead of a restore.
                    .filter { it.conversation.id.value != state.pendingDelete?.id }
                    .map { summary ->
                        ConversationItemUiState(
                            id = summary.conversation.id.value,
                            title = summary.conversation.title,
                            updatedAtMillis = summary.conversation.updatedAt,
                            pinned = summary.pinned,
                        )
                    }.toImmutableList(),
                searchHits = hits,
                renameTarget = state.renameTarget,
                undoDeleteTitle = state.pendingDelete?.title,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = ConversationsUiState(),
        )

        fun onQueryChange(text: String) {
            query.value = text
        }

        fun onTogglePinned(id: String, pinned: Boolean) {
            viewModelScope.launch { conversationRepository.setPinned(ConversationId(id), pinned) }
        }

        fun onStartRename(id: String, currentTitle: String) {
            transient.value = transient.value.copy(renameTarget = RenameTarget(id, currentTitle))
        }

        fun onRenameTextChange(text: String) {
            val target = transient.value.renameTarget ?: return
            transient.value = transient.value.copy(renameTarget = target.copy(text = text))
        }

        fun onCancelRename() {
            transient.value = transient.value.copy(renameTarget = null)
        }

        fun onConfirmRename() {
            val target = transient.value.renameTarget ?: return
            val title = target.text.trim()
            transient.value = transient.value.copy(renameTarget = null)
            if (title.isEmpty()) return
            viewModelScope.launch { conversationRepository.rename(ConversationId(target.id), title) }
        }

        /**
         * Hides the row and starts the undo window.
         *
         * A delete arriving while another is still pending commits the earlier
         * one first: two swipes in a row must delete two conversations, not
         * lose track of the first.
         */
        fun onDelete(id: String) {
            viewModelScope.launch {
                commitPendingDelete()
                val title = conversationRepository.findConversation(ConversationId(id))?.title ?: return@launch
                transient.value = transient.value.copy(pendingDelete = PendingDelete(id, title))
                undoTimer = viewModelScope.launch {
                    delay(UNDO_WINDOW_MILLIS)
                    commitPendingDelete()
                }
            }
        }

        fun onUndoDelete() {
            undoTimer?.cancel()
            undoTimer = null
            transient.value = transient.value.copy(pendingDelete = null)
        }

        /** The snackbar closed without being tapped; the delete stands. */
        fun onUndoWindowExpired() {
            undoTimer?.cancel()
            undoTimer = null
            viewModelScope.launch { commitPendingDelete() }
        }

        /**
         * Commits a hidden row's deletion, if there is one.
         *
         * A delete still inside its undo window when the view model dies is
         * abandoned rather than committed: the scope goes with it, and losing a
         * conversation because the process was backgrounded is not a trade
         * anyone would choose. The row simply reappears.
         */
        private suspend fun commitPendingDelete() {
            val pending = transient.value.pendingDelete ?: return
            transient.value = transient.value.copy(pendingDelete = null)
            // The row is already gone from the UI. Letting the cascade be
            // cancelled half way would leave orphaned messages behind.
            withContext(NonCancellable) {
                conversationRepository.deleteConversation(ConversationId(pending.id))
            }
        }

        private fun String.snippet(): String {
            val collapsed = trim().replace(WHITESPACE, " ")
            return if (collapsed.length <= SNIPPET_LENGTH) collapsed else collapsed.take(SNIPPET_LENGTH) + "…"
        }

        private data class PendingDelete(
            val id: String,
            val title: String,
        )

        private data class TransientState(
            val renameTarget: RenameTarget? = null,
            val pendingDelete: PendingDelete? = null,
        )

        internal companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
            const val SEARCH_DEBOUNCE_MILLIS = 200L

            /** Long enough to notice the snackbar, short enough not to feel broken. */
            const val UNDO_WINDOW_MILLIS = 5_000L

            const val SNIPPET_LENGTH = 140

            private val WHITESPACE = Regex("\\s+")
        }
    }
