package io.github.jaypetez.ollamamobile.data.export

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.common.result.getOrNull
import io.github.jaypetez.ollamamobile.data.FakeClock
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.storage.OllamaDatabase
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
class ConversationExporterTest {
    private lateinit var database: OllamaDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var exporter: ConversationExporter

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock()

    @Before
    fun setUp() {
        database = OllamaDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
        conversations = ConversationRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            clock = clock,
            io = dispatcher,
        )
        exporter = ConversationExporter(conversations)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun populated(): ConversationExport {
        val conversation = conversations.createConversation(
            title = "Stellar evolution",
            systemPrompt = "Answer like an astronomer.",
            sampling = SamplingParams(temperature = 0.3, stop = listOf("</s>")),
        )
        conversations.appendMessage(conversation.id, Role.USER, "What is a red supergiant?")
        clock.tick()
        val turn = conversations.beginAssistantTurn(conversation.id)
        turn.appendReasoning("recall the HR diagram")
        turn.append("A late-stage massive star.")
        turn.complete(GenerationStats(completionTokens = 7, evalNanos = 1_000_000_000L))
        return (exporter.export(conversation.id) as AppResult.Success).value
    }

    // -----------------------------------------------------------------------
    // JSON round trip
    // -----------------------------------------------------------------------

    @Test
    fun `rendering then parsing returns an identical export`() = runTest(dispatcher) {
        val export = populated()

        val parsed = exporter.parseJson(exporter.renderJson(export)).getOrNull()

        assertThat(parsed).isEqualTo(export)
    }

    @Test
    fun `an absent counter stays absent through the round trip`() = runTest(dispatcher) {
        val conversation = conversations.createConversation()
        conversations.appendMessage(conversation.id, Role.ASSISTANT, "no stats here")
        val export = (exporter.export(conversation.id) as AppResult.Success).value

        val parsed = exporter.parseJson(exporter.renderJson(export)).getOrNull()

        // Not zero: the server reported nothing, and "0 tok/s" is a claim.
        assertThat(parsed?.messages?.single()?.stats).isNull()
    }

    @Test
    fun `importing a rendered export reproduces the whole thread`() = runTest(dispatcher) {
        val export = populated()
        val json = exporter.renderJson(export)

        val importedId = (exporter.importJson(json) as AppResult.Success).value
        val reExported = (exporter.export(importedId) as AppResult.Success).value

        // Identities are regenerated on import — see importConversation — so
        // they are normalised away before comparing.
        assertThat(reExported.withoutIdentity()).isEqualTo(export.withoutIdentity())
    }

    @Test
    fun `importing adds a copy rather than colliding with the original`() = runTest(dispatcher) {
        val export = populated()

        val importedId = (exporter.importJson(exporter.renderJson(export)) as AppResult.Success).value

        assertThat(importedId.value).isNotEqualTo(export.id)
        assertThat(conversations.findConversation(ConversationId(export.id))).isNotNull()
        assertThat(conversations.findConversation(importedId)?.title).isEqualTo(export.title)
    }

    @Test
    fun `a failed turn keeps its status and its text across a round trip`() = runTest(dispatcher) {
        val conversation = conversations.createConversation()
        val turn = conversations.beginAssistantTurn(conversation.id)
        turn.append("as far as I got")
        turn.fail(AppError.Network.Timeout())
        val export = (exporter.export(conversation.id) as AppResult.Success).value

        val parsed = exporter.parseJson(exporter.renderJson(export)).getOrNull()

        val message = parsed?.messages?.single()
        assertThat(message?.status).isEqualTo(MessageExport.STATUS_FAILED)
        assertThat(message?.content).isEqualTo("as far as I got")
        assertThat(message?.error).isNotEmpty()
    }

    @Test
    fun `a file that is not an export fails rather than importing something blank`() = runTest(dispatcher) {
        assertThat(exporter.parseJson("not json at all")).isInstanceOf(AppResult.Failure::class.java)
        assertThat(exporter.parseJson("{}")).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `an export from a newer app version is refused instead of half-read`() = runTest(dispatcher) {
        val future = exporter.renderJson(populated().copy(schemaVersion = ConversationExport.SCHEMA_VERSION + 1))

        val result = exporter.parseJson(future)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.message).contains("newer version")
    }

    @Test
    fun `exporting a conversation that does not exist is a not-found failure`() = runTest(dispatcher) {
        val result = exporter.export(ConversationId("missing"))

        assertThat((result as AppResult.Failure).error).isInstanceOf(AppError.Storage.NotFound::class.java)
    }

    // -----------------------------------------------------------------------
    // Markdown
    // -----------------------------------------------------------------------

    @Test
    fun `markdown carries the title, the roles and the answer text`() = runTest(dispatcher) {
        val markdown = exporter.renderMarkdown(populated())

        assertThat(markdown).contains("# Stellar evolution")
        assertThat(markdown).contains("## User")
        assertThat(markdown).contains("## Assistant")
        assertThat(markdown).contains("A late-stage massive star.")
    }

    @Test
    fun `reasoning is collapsed rather than leading the document`() = runTest(dispatcher) {
        val markdown = exporter.renderMarkdown(populated())

        assertThat(markdown).contains("<details><summary>Reasoning</summary>")
        assertThat(markdown).contains("recall the HR diagram")
    }

    @Test
    fun `a title with a colon does not break the front matter`() = runTest(dispatcher) {
        val conversation = conversations.createConversation(title = "Q: what now?")
        val export = (exporter.export(conversation.id) as AppResult.Success).value

        val frontMatterTitle = exporter
            .renderMarkdown(export)
            .lineSequence()
            .first { it.startsWith("title:") }

        // Unquoted, `Q: what now?` splits the line into two YAML keys and the
        // block stops parsing.
        assertThat(frontMatterTitle).isEqualTo("title: \"Q: what now?\"")
    }

    @Test
    fun `an unfinished turn is marked as such in the markdown`() = runTest(dispatcher) {
        val conversation = conversations.createConversation()
        val turn = conversations.beginAssistantTurn(conversation.id)
        turn.append("partial")
        turn.fail(AppError.Network.Timeout())
        val export = (exporter.export(conversation.id) as AppResult.Success).value

        assertThat(exporter.renderMarkdown(export)).contains("This response did not finish.")
    }

    @Test
    fun `the suggested filename is safe on any filesystem`() = runTest(dispatcher) {
        val conversation = conversations.createConversation(title = "Rocket/Science: 2026 !!")
        val export = (exporter.export(conversation.id) as AppResult.Success).value

        assertThat(exporter.suggestedFileName(export, "md")).isEqualTo("rocket-science-2026.md")
    }

    @Test
    fun `an untitled conversation still gets a filename`() = runTest(dispatcher) {
        val conversation = conversations.createConversation(title = "???")
        val export = (exporter.export(conversation.id) as AppResult.Success).value

        assertThat(exporter.suggestedFileName(export, "json")).isEqualTo("conversation.json")
    }
}

/** Drops the regenerated identities so two copies of the same thread compare equal. */
private fun ConversationExport.withoutIdentity(): ConversationExport = copy(
    id = "",
    messages = messages.map { it.copy(id = "") },
)
