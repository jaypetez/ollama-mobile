package io.github.jaypetez.ollamamobile.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MessageStatus
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
import kotlinx.coroutines.CoroutineScope
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

    private val target = InferenceTarget.Remote(server.id, model.name)

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
        every { models.catalogue } returns flowOf(ModelCatalogue(remote = listOf(model)))
        coEvery { servers.findServer(server.id) } returns server
        coEvery { router.route(any(), any()) } returns RoutingDecision.Routed(target)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun gateway(script: List<StreamEvent>): InferenceGatewayImpl {
        val client = ScriptedChatClient(script)
        coEvery { clientFactory.clientFor(any()) } returns SelectedClient(ServerProtocol.NATIVE, client)
        return InferenceGatewayImpl(
            router = router,
            servers = servers,
            models = models,
            conversations = conversations,
            clientFactory = clientFactory,
            breaker = breaker,
            io = dispatcher,
            scope = CoroutineScope(dispatcher),
        )
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
}
