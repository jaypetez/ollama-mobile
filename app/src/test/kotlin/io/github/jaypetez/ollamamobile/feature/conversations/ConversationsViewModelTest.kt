package io.github.jaypetez.ollamamobile.feature.conversations

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.data.repository.ConversationSummary
import io.github.jaypetez.ollamamobile.data.repository.MessageSearchHit
import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.Conversation
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.testing.MainDispatcherRule
import io.github.jaypetez.ollamamobile.testing.awaitUntil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ConversationsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val summaries = MutableStateFlow(
        listOf(summary("a", "Weather"), summary("b", "Groceries")),
    )

    private val repository = mockk<ConversationRepository>(relaxed = true) {
        every { observeConversations(any()) } returns summaries
        every { searchMessages(any(), any()) } returns flowOf(emptyList())
        coEvery { findConversation(ConversationId("a")) } returns summaries.value.first().conversation
    }

    private fun viewModel() = ConversationsViewModel(repository)

    @Test
    fun `the list is exposed once the repository emits`() = runTest {
        val viewModel = viewModel()
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        viewModel.uiState.test {
            val loaded = awaitUntil { !it.isLoading }
            assertThat(loaded.conversations.map { it.title }).containsExactly("Weather", "Groceries").inOrder()
            assertThat(loaded.showFirstRunEmptyState).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty database shows the first-run empty state, an empty search does not`() = runTest {
        summaries.value = emptyList()
        every { repository.searchMessages(any(), any()) } returns flowOf(emptyList())
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertThat(awaitUntil { !it.isLoading }.showFirstRunEmptyState).isTrue()

            viewModel.onQueryChange("qwen")
            advanceTimeBy(SEARCH_SETTLE_MILLIS)
            val searching = expectMostRecentItem()
            assertThat(searching.showFirstRunEmptyState).isFalse()
            assertThat(searching.showNoSearchResults).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search hits are mapped with their conversation title`() = runTest {
        every { repository.searchMessages("rain", any()) } returns flowOf(
            listOf(
                MessageSearchHit(
                    message = ChatMessage(
                        id = MessageId("m1"),
                        conversationId = ConversationId("a"),
                        role = Role.ASSISTANT,
                        content = "  It will   rain later  ",
                        createdAt = 10L,
                    ),
                    conversationId = ConversationId("a"),
                    conversationTitle = "Weather",
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }
            viewModel.onQueryChange("rain")
            advanceTimeBy(SEARCH_SETTLE_MILLIS)
            val hits = expectMostRecentItem().searchHits
            assertThat(hits).hasSize(1)
            assertThat(hits.first().conversationTitle).isEqualTo("Weather")
            // Whitespace is collapsed so a snippet does not render as a gap.
            assertThat(hits.first().snippet).isEqualTo("It will rain later")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting hides the row but does not touch the database until the window closes`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onDelete("a")
            val hidden = expectMostRecentItem()
            assertThat(hidden.conversations.map { it.id }).containsExactly("b")
            assertThat(hidden.undoDeleteTitle).isEqualTo("Weather")
            coVerify(exactly = 0) { repository.deleteConversation(any()) }

            advanceTimeBy(ConversationsViewModel.UNDO_WINDOW_MILLIS + 1)
            coVerify(exactly = 1) { repository.deleteConversation(ConversationId("a")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `undo puts the row back and never deletes`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onDelete("a")
            expectMostRecentItem()
            viewModel.onUndoDelete()

            val restored = expectMostRecentItem()
            assertThat(restored.conversations.map { it.id }).containsExactly("a", "b").inOrder()
            assertThat(restored.undoDeleteTitle).isNull()

            advanceTimeBy(ConversationsViewModel.UNDO_WINDOW_MILLIS * 2)
            coVerify(exactly = 0) { repository.deleteConversation(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `renaming trims and refuses an all-whitespace title`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onStartRename("a", "Weather")
            viewModel.onRenameTextChange("   ")
            viewModel.onConfirmRename()
            coVerify(exactly = 0) { repository.rename(any(), any()) }

            viewModel.onStartRename("a", "Weather")
            viewModel.onRenameTextChange("  Forecast  ")
            viewModel.onConfirmRename()
            coVerify(exactly = 1) { repository.rename(ConversationId("a"), "Forecast") }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun summary(id: String, title: String) = ConversationSummary(
        conversation = Conversation(
            id = ConversationId(id),
            title = title,
            createdAt = 0L,
            updatedAt = 0L,
        ),
        pinned = false,
        archived = false,
    )

    private companion object {
        /** Comfortably past the view model's search debounce. */
        const val SEARCH_SETTLE_MILLIS = 500L
    }
}
