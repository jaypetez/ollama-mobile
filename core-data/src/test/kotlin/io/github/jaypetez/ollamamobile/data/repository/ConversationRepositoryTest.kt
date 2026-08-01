package io.github.jaypetez.ollamamobile.data.repository

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.FakeClock
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MessageStatus
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.storage.OllamaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationRepositoryTest {
    private lateinit var database: OllamaDatabase
    private lateinit var repository: ConversationRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock()

    @Before
    fun setUp() {
        database = OllamaDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
        repository = ConversationRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            clock = clock,
            io = dispatcher,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a new conversation is created with the settings it was given`() = runTest(dispatcher) {
        val conversation = repository.createConversation(
            title = "Weather",
            systemPrompt = "Be brief.",
            sampling = SamplingParams(temperature = 0.2, stop = listOf("</s>")),
        )

        val stored = repository.findConversation(conversation.id)

        assertThat(stored?.title).isEqualTo("Weather")
        assertThat(stored?.systemPrompt).isEqualTo("Be brief.")
        assertThat(stored?.sampling?.temperature).isEqualTo(0.2)
        assertThat(stored?.sampling?.stop).containsExactly("</s>")
        // Unset stays unset: null is "the engine decides", which is not zero.
        assertThat(stored?.sampling?.topP).isNull()
    }

    @Test
    fun `editing a conversation does not silently un-pin it`() = runTest(dispatcher) {
        val conversation = repository.createConversation("Pinned")
        repository.setPinned(conversation.id, true)

        repository.updateConversation(conversation.copy(title = "Renamed"))

        val summary = repository.observeConversations().first().single()
        assertThat(summary.conversation.title).isEqualTo("Renamed")
        assertThat(summary.pinned).isTrue()
    }

    @Test
    fun `an archived conversation leaves the active list without being deleted`() = runTest(dispatcher) {
        val conversation = repository.createConversation("Old")

        repository.setArchived(conversation.id, true)

        assertThat(repository.observeConversations().first()).isEmpty()
        assertThat(repository.observeConversations(includeArchived = true).first()).hasSize(1)
    }

    @Test
    fun `messages come back in the order they were written`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        repository.appendMessage(conversation.id, Role.USER, "first")
        clock.tick()
        repository.appendMessage(conversation.id, Role.ASSISTANT, "second")

        assertThat(repository.messages(conversation.id).map { it.content })
            .containsExactly("first", "second")
            .inOrder()
    }

    // -----------------------------------------------------------------------
    // The streaming turn
    // -----------------------------------------------------------------------

    @Test
    fun `an assistant turn exists as pending before its first token`() = runTest(dispatcher) {
        val conversation = repository.createConversation()

        repository.beginAssistantTurn(conversation.id)

        // Created up front so a process killed during model load still leaves
        // evidence that a reply was attempted.
        val message = repository.messages(conversation.id).single()
        assertThat(message.status).isEqualTo(MessageStatus.Pending)
        assertThat(message.isStreaming).isTrue()
        assertThat(message.content).isEmpty()
    }

    @Test
    fun `completing a turn writes the whole buffered answer exactly once`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        val turn = repository.beginAssistantTurn(conversation.id)

        turn.append("Hello, ")
        turn.append("world.")
        turn.complete(GenerationStats(completionTokens = 3))

        val message = repository.messages(conversation.id).single()
        assertThat(message.content).isEqualTo("Hello, world.")
        assertThat(message.status).isEqualTo(MessageStatus.Complete)
        assertThat(message.stats?.completionTokens).isEqualTo(3)
    }

    @Test
    fun `text longer than the flush threshold is durable before the turn finishes`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        val turn = repository.beginAssistantTurn(conversation.id)

        val long = "x".repeat(ConversationRepository.FLUSH_THRESHOLD_CHARS + 10)
        turn.append(long)

        // No complete() call: this is what a process kill would leave behind.
        assertThat(repository.messages(conversation.id).single().content).isEqualTo(long)
    }

    @Test
    fun `failing a turn keeps the partial answer and records the error`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        val turn = repository.beginAssistantTurn(conversation.id)
        turn.append("As far as I ")

        turn.fail(AppError.Network.Timeout())

        val message = repository.messages(conversation.id).single()
        assertThat(message.content).isEqualTo("As far as I ")
        assertThat(message.status).isInstanceOf(MessageStatus.Failed::class.java)
    }

    @Test
    fun `finishing twice is a no-op rather than a second write`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        val turn = repository.beginAssistantTurn(conversation.id)
        turn.append("done")
        turn.complete()

        turn.fail(AppError.Network.Timeout())

        assertThat(repository.messages(conversation.id).single().status).isEqualTo(MessageStatus.Complete)
    }

    @Test
    fun `reasoning is stored separately from the answer`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        val turn = repository.beginAssistantTurn(conversation.id)

        turn.appendReasoning("first I consider")
        turn.append("42")
        turn.complete()

        val message = repository.messages(conversation.id).single()
        assertThat(message.content).isEqualTo("42")
        assertThat(message.reasoning).isEqualTo("first I consider")
    }

    @Test
    fun `a turn stranded by a killed process is recovered as failed, keeping its text`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        val turn = repository.beginAssistantTurn(conversation.id)
        turn.append("x".repeat(ConversationRepository.FLUSH_THRESHOLD_CHARS))
        // No complete() and no fail(): the process died here.

        val recovered = repository.recoverInterruptedTurns()

        assertThat(recovered).isEqualTo(1)
        val message = repository.messages(conversation.id).single()
        // Pending would render as a caret that blinks forever.
        assertThat(message.status).isInstanceOf(MessageStatus.Failed::class.java)
        assertThat(message.content).hasLength(ConversationRepository.FLUSH_THRESHOLD_CHARS)
    }

    @Test
    fun `recovery leaves finished turns alone`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        repository.appendMessage(conversation.id, Role.ASSISTANT, "fine")

        assertThat(repository.recoverInterruptedTurns()).isEqualTo(0)
        assertThat(repository.messages(conversation.id).single().status).isEqualTo(MessageStatus.Complete)
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    @Test
    fun `full-text search finds a message and names its conversation`() = runTest(dispatcher) {
        val conversation = repository.createConversation("Astronomy")
        repository.appendMessage(conversation.id, Role.ASSISTANT, "Betelgeuse is a red supergiant")
        repository.appendMessage(conversation.id, Role.ASSISTANT, "unrelated text")

        val hits = repository.searchMessages("supergiant").first()

        assertThat(hits).hasSize(1)
        assertThat(hits.single().message.content).contains("Betelgeuse")
        assertThat(hits.single().conversationTitle).isEqualTo("Astronomy")
    }

    @Test
    fun `search is scoped when a conversation is given`() = runTest(dispatcher) {
        val a = repository.createConversation("A")
        val b = repository.createConversation("B")
        repository.appendMessage(a.id, Role.USER, "quasar")
        repository.appendMessage(b.id, Role.USER, "quasar")

        assertThat(repository.searchMessages("quasar").first()).hasSize(2)
        assertThat(repository.searchMessagesIn(a.id, "quasar").first()).hasSize(1)
    }

    @Test
    fun `a query the tokeniser reduces to nothing returns no hits rather than failing`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        repository.appendMessage(conversation.id, Role.USER, "hello")

        // A bare quotation mark is a syntax error in FTS5's MATCH grammar.
        assertThat(repository.searchMessages("\"").first()).isEmpty()
    }

    @Test
    fun `streamed text becomes searchable once it is flushed`() = runTest(dispatcher) {
        val conversation = repository.createConversation("Chat")
        val turn = repository.beginAssistantTurn(conversation.id)
        turn.append("pulsar")
        turn.complete()

        assertThat(repository.searchMessages("pulsar").first()).hasSize(1)
    }

    // -----------------------------------------------------------------------
    // Reactivity
    // -----------------------------------------------------------------------

    @Test
    fun `observing a thread re-emits when a message is appended`() = runTest(dispatcher) {
        val conversation = repository.createConversation()

        repository.observeMessages(conversation.id).test {
            assertThat(awaitItem()).isEmpty()

            repository.appendMessage(conversation.id, Role.USER, "hi")

            assertThat(awaitItem().map { it.content }).containsExactly("hi")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a conversation takes its messages with it`() = runTest(dispatcher) {
        val conversation = repository.createConversation()
        repository.appendMessage(conversation.id, Role.USER, "hi")

        repository.deleteConversation(conversation.id)

        assertThat(repository.findConversation(conversation.id)).isNull()
        assertThat(repository.messages(conversation.id)).isEmpty()
    }
}
