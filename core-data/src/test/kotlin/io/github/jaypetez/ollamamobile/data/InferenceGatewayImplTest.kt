package io.github.jaypetez.ollamamobile.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.engine.InferenceActivityTracker
import io.github.jaypetez.ollamamobile.data.engine.ModelLifecycleManager
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.data.repository.ModelCatalogue
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerStatus
import io.github.jaypetez.ollamamobile.data.routing.BreakerState
import io.github.jaypetez.ollamamobile.data.routing.CircuitBreaker
import io.github.jaypetez.ollamamobile.data.routing.RoutingDecision
import io.github.jaypetez.ollamamobile.data.routing.SmartRouter
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.llm.testing.FakeLlamaEngine
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.MessageStatus
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.remote.DoneReason
import io.github.jaypetez.ollamamobile.remote.SelectedClient
import io.github.jaypetez.ollamamobile.remote.ServerClientFactory
import io.github.jaypetez.ollamamobile.remote.ServerProtocol
import io.github.jaypetez.ollamamobile.remote.StreamEvent
import io.github.jaypetez.ollamamobile.storage.OllamaDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gateway, against a real database and a scripted transport.
 *
 * Room is real rather than mocked because half of what is being asserted is
 * *what ended up persisted* after each path — and a mocked DAO would only ever
 * confirm that we called the methods we wrote.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InferenceGatewayImplTest {
    private lateinit var database: OllamaDatabase
    private lateinit var conversations: ConversationRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock()
    private val server = testServerRef()
    private val model = testModelRef()

    private val router = mockk<SmartRouter>()
    private val servers = mockk<ServerRepository>(relaxUnitFun = true)
    private val models = mockk<ModelRepository>()
    private val clientFactory = mockk<ServerClientFactory>()
    private val breaker = CircuitBreaker(clock)

    private val lifecycle = mockk<ModelLifecycleManager>(relaxUnitFun = true)
    private val activity = InferenceActivityTracker()

    private val target = InferenceTarget.Remote(server.id, model.name)

    private val localModelId = ModelId("file:/models/qwen3-1.7b.gguf")
    private val localModel = ModelRef(
        id = localModelId,
        displayName = "Qwen3 1.7B",
        name = "qwen3:1.7b",
        origin = ModelOrigin.Local("/data/models/qwen3-1.7b.gguf"),
    )
    private val localTarget = InferenceTarget.Local(localModelId)

    @Before
    fun setUp() {
        database = OllamaDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
        conversations = ConversationRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            clock = clock,
            io = dispatcher,
        )

        every { servers.statuses } returns MutableStateFlow(listOf(ServerStatus(server, reachable = true)))
        every { models.remoteModels } returns flowOf(listOf(model))
        every { models.localModels } returns flowOf(emptyList())
        every { models.catalogue } returns flowOf(ModelCatalogue(remote = listOf(model)))
        coEvery { servers.findServer(server.id) } returns server
        coEvery { router.route(any(), any()) } returns RoutingDecision.Routed(target)
        coEvery { lifecycle.ensureLoaded(any()) } returns localModel
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun gateway(
        script: List<StreamEvent>,
        engine: LlamaEngine = FakeLlamaEngine.unavailable(),
    ): InferenceGatewayImpl {
        val client = ScriptedChatClient(script)
        coEvery { clientFactory.clientFor(any()) } returns SelectedClient(ServerProtocol.NATIVE, client)
        return InferenceGatewayImpl(
            router = router,
            servers = servers,
            models = models,
            conversations = conversations,
            clientFactory = clientFactory,
            breaker = breaker,
            engine = engine,
            lifecycle = lifecycle,
            activity = activity,
            io = dispatcher,
            scope = CoroutineScope(dispatcher),
        )
    }

    /**
     * A gateway whose local branch is live, with [engine] answering it.
     *
     * The remote script is empty because these tests never reach the transport;
     * routing is stubbed to a Local target instead.
     */
    private fun localGateway(engine: LlamaEngine): InferenceGatewayImpl {
        coEvery { router.route(any(), any()) } returns RoutingDecision.Routed(localTarget)
        return gateway(emptyList(), engine)
    }

    private suspend fun newConversation(): ConversationId = conversations.createConversation("Test").id

    private fun request(
        conversationId: ConversationId?,
        sampling: SamplingParams = SamplingParams.Default,
    ) = InferenceRequest(
        model = model,
        messages = listOf(InferenceMessage.user("hello")),
        sampling = sampling,
        conversationId = conversationId,
    )

    // -----------------------------------------------------------------------
    // Failure mapping and partial persistence
    // -----------------------------------------------------------------------

    @Test
    fun `a mid-stream error becomes Failed and keeps the partial answer`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val transportError = AppError.Network.Timeout()
        val events = gateway(
            listOf(
                StreamEvent.Text("The answer "),
                StreamEvent.Text("is fo"),
                StreamEvent.Failed(transportError),
            ),
        ).chat(request(conversationId)).toList()

        assertThat(events.last()).isEqualTo(InferenceEvent.Failed(transportError))
        // Not "Completed then Failed": a failed generation must never be
        // presentable as a finished one.
        assertThat(events.filterIsInstance<InferenceEvent.Completed>()).isEmpty()

        val persisted = conversations.messages(conversationId).last()
        assertThat(persisted.content).isEqualTo("The answer is fo")
        assertThat(persisted.status).isInstanceOf(MessageStatus.Failed::class.java)
    }

    @Test
    fun `a stream that just stops is a failure, not a finished answer`() = runTest(dispatcher) {
        val conversationId = newConversation()

        val events = gateway(listOf(StreamEvent.Text("half an ans"))).chat(request(conversationId)).toList()

        assertThat(events.last()).isInstanceOf(InferenceEvent.Failed::class.java)
        val persisted = conversations.messages(conversationId).last()
        assertThat(persisted.content).isEqualTo("half an ans")
        assertThat(persisted.status).isInstanceOf(MessageStatus.Failed::class.java)
    }

    @Test
    fun `an unroutable request fails without creating an assistant turn`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val error = AppError.Network.Unreachable(message = "No server is configured.")
        coEvery { router.route(any(), any()) } returns RoutingDecision.Unavailable(error)

        val events = gateway(emptyList()).chat(request(conversationId)).toList()

        assertThat(events).containsExactly(InferenceEvent.Failed(error))
        // No empty pending bubble left behind for a request that never left.
        assertThat(conversations.messages(conversationId)).isEmpty()
    }

    @Test
    fun `a request with no conversation streams without persisting anything`() = runTest(dispatcher) {
        // This is the :server path: it has no conversation store and must not
        // grow one.
        val bystander = newConversation()

        val events = gateway(
            listOf(StreamEvent.Text("hi"), StreamEvent.Completed(DoneReason.STOP, GenerationStats.Empty)),
        ).chat(request(conversationId = null)).toList()

        assertThat(events.filterIsInstance<InferenceEvent.Token>().map { it.text }).containsExactly("hi")
        assertThat(conversations.messages(bystander)).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Stop-sequence hold-back
    // -----------------------------------------------------------------------

    @Test
    fun `a stop sequence spanning two deltas never reaches the UI or the database`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val sampling = SamplingParams(stop = listOf("<|im_end|>"))

        val events = gateway(
            listOf(
                StreamEvent.Text("All done.<|im_"),
                StreamEvent.Text("end|>"),
                StreamEvent.Completed(DoneReason.STOP, GenerationStats.Empty),
            ),
        ).chat(request(conversationId, sampling)).toList()

        val shown = events.filterIsInstance<InferenceEvent.Token>().joinToString("") { it.text }
        assertThat(shown).isEqualTo("All done.")
        // Not even transiently: no emitted token may contain part of the marker.
        assertThat(events.filterIsInstance<InferenceEvent.Token>().none { it.text.contains("<|") }).isTrue()

        assertThat(conversations.messages(conversationId).last().content).isEqualTo("All done.")
    }

    @Test
    fun `text withheld pending a stop match is released when the stream ends`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val sampling = SamplingParams(stop = listOf("<|im_end|>"))

        gateway(
            listOf(
                StreamEvent.Text("value is <|x"),
                StreamEvent.Completed(DoneReason.STOP, GenerationStats.Empty),
            ),
        ).chat(request(conversationId, sampling)).toList()

        // "<|x" turned out not to be a stop sequence, so it is answer text.
        assertThat(conversations.messages(conversationId).last().content).isEqualTo("value is <|x")
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    @Test
    fun `no Stats event at all when the server reported nothing`() = runTest(dispatcher) {
        val conversationId = newConversation()

        val events = gateway(
            listOf(StreamEvent.Text("hi"), StreamEvent.Completed(DoneReason.STOP, GenerationStats.Empty)),
        ).chat(request(conversationId)).toList()

        // Never a Stats carrying zeroes: that renders as "0 tok/s" for data
        // nobody measured.
        assertThat(events.filterIsInstance<InferenceEvent.Stats>()).isEmpty()
        assertThat(conversations.messages(conversationId).last().stats).isNull()
    }

    @Test
    fun `Stats is emitted and persisted when the server reported counters`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val stats = GenerationStats(completionTokens = 12, evalNanos = 2_000_000_000L)

        val events = gateway(
            listOf(StreamEvent.Text("hi"), StreamEvent.Completed(DoneReason.STOP, stats)),
        ).chat(request(conversationId)).toList()

        assertThat(events.filterIsInstance<InferenceEvent.Stats>().single().stats).isEqualTo(stats)
        assertThat(
            conversations
                .messages(conversationId)
                .last()
                .stats
                ?.completionTokens,
        ).isEqualTo(12)
    }

    // -----------------------------------------------------------------------
    // The happy path and its shape
    // -----------------------------------------------------------------------

    @Test
    fun `every stream starts with Started and ends with exactly one terminal event`() = runTest(dispatcher) {
        val conversationId = newConversation()

        val events = gateway(
            listOf(
                StreamEvent.Text("a"),
                StreamEvent.Completed(DoneReason.LENGTH, GenerationStats.Empty),
            ),
        ).chat(request(conversationId)).toList()

        assertThat(events.first()).isEqualTo(InferenceEvent.Started(target))
        assertThat(events.last()).isEqualTo(InferenceEvent.Completed(FinishReason.LENGTH))
        val terminals = events.count { it is InferenceEvent.Completed || it is InferenceEvent.Failed }
        assertThat(terminals).isEqualTo(1)
    }

    @Test
    fun `reasoning is emitted separately and stored out of the answer text`() = runTest(dispatcher) {
        val conversationId = newConversation()

        val events = gateway(
            listOf(
                StreamEvent.Reasoning("thinking hard"),
                StreamEvent.Text("42"),
                StreamEvent.Completed(DoneReason.STOP, GenerationStats.Empty),
            ),
        ).chat(request(conversationId)).toList()

        assertThat(events.filterIsInstance<InferenceEvent.Reasoning>().single().text).isEqualTo("thinking hard")
        val persisted = conversations.messages(conversationId).last()
        assertThat(persisted.content).isEqualTo("42")
        assertThat(persisted.reasoning).isEqualTo("thinking hard")
    }

    @Test
    fun `a transport failure trips the circuit breaker and a completion resets it`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val failing = listOf(StreamEvent.Failed(AppError.Network.Unreachable()))

        repeat(3) { gateway(failing).chat(request(conversationId)).toList() }
        assertThat(breaker.allows(server.id)).isFalse()

        clock.tick(CircuitBreaker.Config().baseCooldownMillis)
        gateway(listOf(StreamEvent.Completed(DoneReason.STOP, GenerationStats.Empty)))
            .chat(request(conversationId))
            .toList()

        // Fully closed, not merely half-open: the next failure has to start
        // from the threshold again.
        assertThat(breaker.state(server.id)).isEqualTo(BreakerState.CLOSED)
    }

    @Test
    fun `a model-level failure leaves the breaker closed`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val notFound = listOf(StreamEvent.Failed(AppError.Network.Http(code = 404, body = "model not found")))

        repeat(5) { gateway(notFound).chat(request(conversationId)).toList() }

        assertThat(breaker.allows(server.id)).isTrue()
    }

    // -----------------------------------------------------------------------
    // The local branch
    // -----------------------------------------------------------------------

    private fun localRequest(
        conversationId: ConversationId?,
        sampling: SamplingParams = SamplingParams.Default,
    ) = InferenceRequest(
        model = localModel,
        messages = listOf(InferenceMessage.user("hello")),
        sampling = sampling,
        conversationId = conversationId,
    )

    /** An engine that already holds the model, as `ensureLoaded` would have left it. */
    private suspend fun warmEngine(engine: FakeLlamaEngine = FakeLlamaEngine()): FakeLlamaEngine =
        engine.also { it.load(ModelLoadSpec(model = localModel, path = "/data/models/qwen3-1.7b.gguf")) }

    @Test
    fun `a Local target with no engine in this build fails with a typed, explainable error`() = runTest(dispatcher) {
        val conversationId = newConversation()
        // FakeLlamaEngine.unavailable() is the shape of the default
        // -Pollama.nativeSource=none build, where StubLlamaEngine is bound.
        val events = localGateway(FakeLlamaEngine.unavailable()).chat(localRequest(conversationId)).toList()

        val failure = events.single() as InferenceEvent.Failed
        assertThat(failure.error).isInstanceOf(AppError.Engine.NotAvailable::class.java)
        // Explainable: it names the build, not a generic "something went wrong".
        assertThat(failure.error.message).contains("no on-device inference engine")
        // Nothing was started, so no empty assistant bubble is left behind.
        assertThat(events.filterIsInstance<InferenceEvent.Started>()).isEmpty()
        assertThat(conversations.messages(conversationId)).isEmpty()
    }

    @Test
    fun `a Local target with no engine throws nothing`() = runTest(dispatcher) {
        // The contract is that `chat` never throws. A stub engine reached by
        // mistake has to arrive as an event, not as an exception nobody catches.
        val events = localGateway(FakeLlamaEngine.unavailable()).chat(localRequest(null)).toList()

        assertThat(events).hasSize(1)
    }

    @Test
    fun `a local generation streams tokens, persists them and reports the routed target`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val engine = warmEngine()

        val events = localGateway(engine).chat(localRequest(conversationId)).toList()

        assertThat(events.first()).isEqualTo(InferenceEvent.Started(localTarget))
        assertThat(events.last()).isEqualTo(InferenceEvent.Completed(FinishReason.STOP))
        val shown = events.filterIsInstance<InferenceEvent.Token>().joinToString("") { it.text }
        assertThat(shown).isEqualTo("Hello, world!")
        assertThat(conversations.messages(conversationId).last().content).isEqualTo("Hello, world!")
    }

    @Test
    fun `a locally generated message keeps the counters the engine reported`() = runTest(dispatcher) {
        val conversationId = newConversation()

        val events = localGateway(warmEngine()).chat(localRequest(conversationId)).toList()

        val reported = events.filterIsInstance<InferenceEvent.Stats>().single().stats
        assertThat(reported).isEqualTo(FakeLlamaEngine.DEFAULT_STATS)
        // Persisted too: the stats row under the bubble is read back from the
        // database after the stream has gone.
        val persisted = conversations.messages(conversationId).last().stats
        assertThat(persisted).isEqualTo(FakeLlamaEngine.DEFAULT_STATS)
    }

    @Test
    fun `a locally generated message reports nothing when the engine measured nothing`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val engine = warmEngine(FakeLlamaEngine(stats = null))

        val events = localGateway(engine).chat(localRequest(conversationId)).toList()

        // Never a zeroed Stats: it renders as "0 tok/s" for a measurement
        // nobody made, which is worse than silence.
        assertThat(events.filterIsInstance<InferenceEvent.Stats>()).isEmpty()
        assertThat(conversations.messages(conversationId).last().stats).isNull()
    }

    @Test
    fun `a local failure mid-stream keeps the partial answer and never completes`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val engine = warmEngine(FakeLlamaEngine.failing(afterTokens = 2))

        val events = localGateway(engine).chat(localRequest(conversationId)).toList()

        assertThat(events.last()).isInstanceOf(InferenceEvent.Failed::class.java)
        assertThat(events.filterIsInstance<InferenceEvent.Completed>()).isEmpty()
        val persisted = conversations.messages(conversationId).last()
        assertThat(persisted.content).isEqualTo("Hello, ")
        assertThat(persisted.status).isInstanceOf(MessageStatus.Failed::class.java)
    }

    @Test
    fun `a load the memory estimate refuses fails before any turn is opened`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val verdict = MemoryVerdict.Refuse(
            requiredBytes = 6L * 1024 * 1024 * 1024,
            availableBytes = 1L * 1024 * 1024 * 1024,
            reason = "Choose a smaller quantisation of this model, or a smaller model.",
        )
        coEvery { lifecycle.ensureLoaded(any()) } throws AppErrorException(
            AppError.Model.InsufficientMemory(verdict = verdict),
        )

        val events = localGateway(warmEngine()).chat(localRequest(conversationId)).toList()

        val failure = events.single() as InferenceEvent.Failed
        // The specific, actionable error survives — not "generation failed".
        assertThat(failure.error).isInstanceOf(AppError.Model.InsufficientMemory::class.java)
        assertThat(conversations.messages(conversationId)).isEmpty()
    }

    @Test
    fun `a local stop sequence is withheld from the screen and the database`() = runTest(dispatcher) {
        val conversationId = newConversation()
        val engine = warmEngine(FakeLlamaEngine(script = listOf("All done.<|im_", "end|>"), stats = null))
        val sampling = SamplingParams(stop = listOf("<|im_end|>"))

        val events = localGateway(engine).chat(localRequest(conversationId, sampling)).toList()

        val shown = events.filterIsInstance<InferenceEvent.Token>().joinToString("") { it.text }
        assertThat(shown).isEqualTo("All done.")
        assertThat(conversations.messages(conversationId).last().content).isEqualTo("All done.")
    }

    @Test
    fun `a local turn restarts the keep-alive timer however it ends`() = runTest(dispatcher) {
        localGateway(warmEngine()).chat(localRequest(null)).toList()

        verify(exactly = 1) { lifecycle.onGenerationStarted() }
        // Without this the model stays resident forever after the last answer.
        verify(exactly = 1) { lifecycle.onGenerationFinished() }
    }

    @Test
    fun `a local generation deregisters from the activity tracker on the way out`() = runTest(dispatcher) {
        localGateway(warmEngine()).chat(localRequest(null)).toList()

        // A leaked registration pins the foreground service's wake lock for the
        // rest of the process.
        assertThat(activity.active.value).isEmpty()
    }

    @Test
    fun `a local generation is visible to the tracker while it runs`() = runTest(dispatcher) {
        val seen = mutableListOf<Boolean>()

        localGateway(warmEngine())
            .chat(localRequest(null))
            .collect { seen += activity.active.value.any { entry -> entry.isLocal } }

        assertThat(seen.first()).isTrue()
    }

    @Test
    fun `a local engine that stops without a terminal event is a failure, not a finished answer`() =
        runTest(dispatcher) {
            val conversationId = newConversation()

            val events = localGateway(TruncatingEngine(warmEngine())).chat(localRequest(conversationId)).toList()

            // Presenting a truncated stream as a finished answer is exactly the
            // bug the event protocol exists to prevent.
            assertThat(events.last()).isInstanceOf(InferenceEvent.Failed::class.java)
            assertThat(events.filterIsInstance<InferenceEvent.Completed>()).isEmpty()
            assertThat(conversations.messages(conversationId).last().status)
                .isInstanceOf(MessageStatus.Failed::class.java)
        }

    /**
     * An engine whose stream simply stops.
     *
     * [FakeLlamaEngine] always ends with a terminal event, which is correct of
     * it — that is the contract. This wrapper is the contract being *broken*,
     * which is the one thing the fake deliberately cannot express and the exact
     * case the gateway's "no terminal event" branch exists for. Everything
     * except [generate] is delegated, so it stays a real engine in every other
     * respect.
     */
    private class TruncatingEngine(
        private val delegate: FakeLlamaEngine,
    ) : LlamaEngine by delegate {
        override fun generate(request: InferenceRequest): Flow<InferenceEvent> = flowOf(
            InferenceEvent.Token("half an ans"),
        )
    }
}
