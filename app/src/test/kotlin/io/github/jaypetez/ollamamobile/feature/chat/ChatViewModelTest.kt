package io.github.jaypetez.ollamamobile.feature.chat

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.export.ConversationExporter
import io.github.jaypetez.ollamamobile.data.repository.AppSettings
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRecord
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ModelCatalogue
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerStatus
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceGateway
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.Conversation
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.MessageStatus
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The view model's streaming contract.
 *
 * These are the properties that are invisible in review and expensive in
 * production: how often state is published, what is withheld, and what happens
 * when the user presses Stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val conversationId = ConversationId("conversation-1")
    private val modelId = ModelId("model-1")
    private val serverId = ServerId("server-1")

    private val model = ModelRef(
        id = modelId,
        displayName = "Qwen3 1.7B",
        name = "qwen3:1.7b",
        origin = ModelOrigin.Remote(serverId),
    )

    private val conversation = MutableStateFlow<Conversation?>(
        Conversation(
            id = conversationId,
            title = "Thread",
            createdAt = 0L,
            updatedAt = 0L,
            modelId = modelId,
        ),
    )
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    private val gateway = ScriptedGateway()
    private lateinit var conversations: ConversationRepository
    private lateinit var models: ModelRepository
    private lateinit var localModels: LocalModelRepository
    private lateinit var servers: ServerRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        conversations = mockk(relaxed = true)
        models = mockk(relaxed = true)
        localModels = mockk(relaxed = true)
        servers = mockk(relaxed = true)
        settings = mockk(relaxed = true)

        every { conversations.observeConversation(any()) } returns conversation
        every { conversations.observeMessages(any()) } returns messages
        coEvery { conversations.findConversation(any()) } answers { conversation.value }
        coEvery { conversations.messages(any(), any()) } answers { messages.value }
        coEvery { conversations.appendMessage(any(), any(), any(), any(), any()) } answers {
            val appended = ChatMessage(
                id = MessageId("user-${messages.value.size}"),
                conversationId = conversationId,
                role = Role.USER,
                content = arg(CONTENT_ARG),
                createdAt = 1L,
            )
            messages.value = messages.value + appended
            appended
        }

        every { localModels.models } returns MutableStateFlow(emptyList())
        every { localModels.resident } returns MutableStateFlow(null)
        every { localModels.engineAvailable } returns false
        every { models.observeModel(any()) } returns flowOf(model)
        every { models.catalogue } returns flowOf(ModelCatalogue(remote = listOf(model)))
        coEvery { models.findModel(any()) } returns model
        every { servers.statuses } returns flowOf(
            listOf(
                ServerStatus(
                    server = ServerRef(id = serverId, label = "Desk", baseUrl = "http://10.0.0.2:11434"),
                    reachable = true,
                ),
            ),
        )
        every { settings.settings } returns flowOf(AppSettings())
        coEvery { settings.current() } returns AppSettings()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): ChatViewModel = ChatViewModel(
        conversations = conversations,
        models = models,
        localModels = localModels,
        servers = servers,
        settings = settings,
        gateway = gateway,
        exporter = mockk<ConversationExporter>(relaxed = true),
    ).also { it.openConversation(conversationId) }

    /**
     * Two hundred tokens must not be two hundred published frames.
     *
     * This is the property the frame pump exists for; if it regresses the app
     * still looks correct in a screenshot and drops to single-digit frames per
     * second on a long answer.
     */
    @Test
    fun `deltas are coalesced into frames rather than published per token`() = runTest {
        gateway.script = flow {
            emit(InferenceEvent.Started(InferenceTarget.Remote(serverId, model.name)))
            repeat(TOKEN_COUNT) {
                emit(InferenceEvent.Token("x"))
                delay(1)
            }
            emit(InferenceEvent.Completed(FinishReason.STOP))
        }

        val subject = viewModel()
        val published = mutableListOf<StreamFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subject.stream.collect { frame -> frame?.let { published += it } }
        }

        subject.send("hello")
        advanceUntilIdle()

        val generating = published.filter { it.phase == StreamPhase.GENERATING }
        assertThat(generating).isNotEmpty()
        // 200 tokens over 200 virtual milliseconds at one frame per 40 ms.
        assertThat(generating.size).isAtMost(TOKEN_COUNT / 10)
        assertThat(published.last { it.phase == StreamPhase.SETTLING }.text)
            .isEqualTo("x".repeat(TOKEN_COUNT))
    }

    /**
     * A stop sequence split across two deltas must never reach the screen.
     *
     * The gateway shipped in `:core-data` already filters, but `chat` is
     * consumed through the interface, whose contract calls `Token` a raw delta.
     * A different implementation — or a test double — will hand over the split.
     */
    @Test
    fun `a stop sequence spanning two deltas is never published`() = runTest {
        conversation.value = conversation.value?.copy(
            sampling = SamplingParams(stop = listOf("<|im_end|>")),
        )
        gateway.script = flow {
            emit(InferenceEvent.Started(InferenceTarget.Remote(serverId, model.name)))
            emit(InferenceEvent.Token("Hello <|im_"))
            emit(InferenceEvent.Token("end|> and more"))
            emit(InferenceEvent.Completed(FinishReason.STOP))
        }

        val subject = viewModel()
        val published = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subject.stream.collect { frame -> frame?.let { published += it.text } }
        }

        subject.send("hello")
        advanceUntilIdle()

        assertThat(published).isNotEmpty()
        published.forEach { text ->
            assertThat(text).doesNotContain("<|im_")
            assertThat(text).doesNotContain("end|>")
        }
        assertThat(published.last()).isEqualTo("Hello ")
    }

    /** Stop has to reach the transport, not merely hide the bubble. */
    @Test
    fun `stop cancels the inference flow`() = runTest {
        gateway.script = flow {
            emit(InferenceEvent.Started(InferenceTarget.Remote(serverId, model.name)))
            while (true) {
                gateway.emitted++
                emit(InferenceEvent.Token("x"))
                delay(TOKEN_INTERVAL_MILLIS)
            }
        }

        val subject = viewModel()
        subject.send("hello")
        advanceTimeBy(HUNDRED_MILLIS)

        val emittedBeforeStop = gateway.emitted
        assertThat(emittedBeforeStop).isGreaterThan(1)

        subject.stop()
        advanceUntilIdle()

        assertThat(gateway.emitted).isEqualTo(emittedBeforeStop)
        assertThat(subject.stream.value).isNull()
    }

    /** A server that reported no counters must not produce a statistics line. */
    @Test
    fun `statistics are absent when the server reported none`() = runTest {
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.uiState.collect { } }

        messages.value = listOf(
            ChatMessage(
                id = MessageId("a"),
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = "no counters here",
                createdAt = 1L,
                stats = null,
            ),
            ChatMessage(
                id = MessageId("b"),
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = "counters here",
                createdAt = 2L,
                stats = GenerationStats(completionTokens = 20, evalNanos = 2_000_000_000L),
            ),
        )
        advanceUntilIdle()

        val state = subject.uiState.value as ChatUiState.Ready
        assertThat(state.messages[0].stats).isNull()
        assertThat(state.messages[1].stats?.tokensPerSecond).isEqualTo(10.0)
        assertThat(state.messages[1].stats?.secondsToFirstToken).isNull()
    }

    /** A failure has to survive into the state as the specific failure it was. */
    @Test
    fun `a policy failure becomes the LAN-only state rather than a generic error`() = runTest {
        gateway.script = flow {
            emit(InferenceEvent.Failed(AppError.Policy.LanOnlyViolation(host = "api.example.com")))
        }

        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.uiState.collect { } }

        subject.send("hello")
        advanceUntilIdle()

        val state = subject.uiState.value as ChatUiState.Failed
        assertThat(state.failure).isEqualTo(ChatFailure.LanOnlyBlocked("api.example.com"))
    }

    /** A pending row is the streaming turn; showing it as well would double it. */
    @Test
    fun `pending rows are kept out of the finalised transcript`() = runTest {
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.uiState.collect { } }

        messages.value = listOf(
            ChatMessage(
                id = MessageId("u"),
                conversationId = conversationId,
                role = Role.USER,
                content = "question",
                createdAt = 1L,
            ),
            ChatMessage(
                id = MessageId("a"),
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = "half an ans",
                createdAt = 2L,
                status = MessageStatus.Pending,
            ),
        )
        advanceUntilIdle()

        val state = subject.uiState.value as ChatUiState.Open
        assertThat(state.messages.map { it.id }).containsExactly(MessageId("u"))
    }

    private class ScriptedGateway : InferenceGateway {
        var script: Flow<InferenceEvent> = emptyFlow()
        var emitted: Int = 0

        override fun chat(request: InferenceRequest): Flow<InferenceEvent> = script

        override suspend fun listAvailableModels(): List<ModelRef> = emptyList()

        override val reachableTargets: MutableStateFlow<List<InferenceTarget>> = MutableStateFlow(emptyList())
    }

    private companion object {
        const val TOKEN_COUNT = 200
        const val HUNDRED_MILLIS = 100L
        const val TOKEN_INTERVAL_MILLIS = 10L
        const val CONTENT_ARG = 2
    }

    // -----------------------------------------------------------------------
    // The target picker
    // -----------------------------------------------------------------------

    private val localModelId = ModelId("hf:Qwen/Qwen3-1.7B-GGUF:qwen3-1.7b-q4_k_m.gguf")
    private val localRef = ModelRef(
        id = localModelId,
        displayName = "Qwen3 1.7B",
        name = "qwen3-1.7b-q4_k_m.gguf",
        origin = ModelOrigin.Local("/data/models/qwen3-1.7b-q4_k_m.gguf"),
        quantization = Quantization.Q4_K_M,
        sizeBytes = 1_200_000_000L,
    )

    private fun localRecord(verdict: MemoryVerdict = MemoryVerdict.Fits(headroomBytes = 2_000_000_000L)) =
        LocalModelRecord(
            ref = localRef,
            path = "/data/models/qwen3-1.7b-q4_k_m.gguf",
            sizeBytes = 1_200_000_000L,
            origin = "huggingface.co/Qwen/Qwen3-1.7B-GGUF",
            downloadedAtMillis = 1L,
            verdict = verdict,
            architecture = "qwen3",
            budgetedContextLength = 4096,
        )

    @Test
    fun `the picker reports that this build has no engine, rather than an empty local list`() = runTest {
        every { localModels.engineAvailable } returns false
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.targets.collect { } }
        advanceUntilIdle()

        // Both facts, separately: the sheet needs to say "no engine" rather
        // than "nothing downloaded", and those look identical from the list.
        assertThat(subject.targets.value.localAvailable).isFalse()
        assertThat(subject.targets.value.localOptions).isEmpty()
    }

    @Test
    fun `the picker distinguishes an engine with no models from no engine at all`() = runTest {
        every { localModels.engineAvailable } returns true
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.targets.collect { } }
        advanceUntilIdle()

        assertThat(subject.targets.value.localAvailable).isTrue()
        assertThat(subject.targets.value.localOptions).isEmpty()
    }

    @Test
    fun `a resident local model is shown as warm`() = runTest {
        every { localModels.engineAvailable } returns true
        every { localModels.models } returns MutableStateFlow(listOf(localRecord()))
        every { localModels.resident } returns MutableStateFlow(localRef)
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.targets.collect { } }
        advanceUntilIdle()

        val option = subject.targets.value.localOptions
            .single()
        assertThat(option.warm).isTrue()
        assertThat(option.loadable).isTrue()
    }

    @Test
    fun `an installed model that is not loaded is shown as cold`() = runTest {
        every { localModels.engineAvailable } returns true
        every { localModels.models } returns MutableStateFlow(listOf(localRecord()))
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.targets.collect { } }
        advanceUntilIdle()

        assertThat(
            subject.targets.value.localOptions
                .single()
                .warm,
        ).isFalse()
    }

    @Test
    fun `a model the device cannot fit is listed but not selectable`() = runTest {
        val refused = MemoryVerdict.Refuse(
            requiredBytes = 6_000_000_000L,
            availableBytes = 1_000_000_000L,
            reason = "Choose a smaller quantisation of this model, or a smaller model.",
        )
        every { localModels.engineAvailable } returns true
        every { localModels.models } returns MutableStateFlow(listOf(localRecord(verdict = refused)))
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.targets.collect { } }
        advanceUntilIdle()

        val option = subject.targets.value.localOptions
            .single()
        // Visible, so the user is not left hunting for a model they know they
        // downloaded — and refused, so the app's warnings keep meaning something.
        assertThat(option.loadable).isFalse()
        assertThat(option.detail).contains("short")
    }

    // -----------------------------------------------------------------------
    // Per-message stats
    // -----------------------------------------------------------------------

    @Test
    fun `a message with engine-reported counters shows them`() = runTest {
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.uiState.collect { } }

        messages.value = listOf(
            ChatMessage(
                id = MessageId("a"),
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = "Hello, world!",
                createdAt = 2L,
                stats = GenerationStats(completionTokens = 4, evalNanos = 400_000_000L),
            ),
        )
        advanceUntilIdle()

        val row = (subject.uiState.value as ChatUiState.Open).messages.single()
        assertThat(row.stats?.completionTokens).isEqualTo(4)
        assertThat(row.stats?.tokensPerSecond).isNotNull()
    }

    @Test
    fun `a message whose backend measured nothing reports nothing`() = runTest {
        // Never a zeroed row: "0 tok/s" for a measurement nobody made looks
        // like a real number and is worse than silence.
        val subject = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.uiState.collect { } }

        messages.value = listOf(
            ChatMessage(
                id = MessageId("a"),
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = "Hello, world!",
                createdAt = 2L,
                stats = null,
            ),
        )
        advanceUntilIdle()

        assertThat((subject.uiState.value as ChatUiState.Open).messages.single().stats).isNull()
    }
}
