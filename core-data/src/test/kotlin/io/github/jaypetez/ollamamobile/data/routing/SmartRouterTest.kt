package io.github.jaypetez.ollamamobile.data.routing

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.FakeClock
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRecord
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerStatus
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.data.testModelRef
import io.github.jaypetez.ollamamobile.data.testServerRef
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.llm.testing.FakeLlamaEngine
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.ServerId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The decision function, under every policy, plus the local branch it can now
 * actually reach.
 *
 * Most of these drive [SmartRouter.choose] directly, which is exactly why it is
 * pure and static: the interesting cases are the awkward ones — a server that is
 * up but lacks the model, a breaker that is half-open, a phone at 8% off the
 * charger — and none of them is convenient to arrange through a database, a
 * network and a battery.
 *
 * The last section drives the *impure* half, [SmartRouter.inputFor], against a
 * [FakeLlamaEngine]. That is where "warm" and "cold" are decided, and deciding
 * them wrongly is invisible in the pure tests because they take the flag as an
 * input.
 */
@RunWith(JUnit4::class)
class SmartRouterTest {
    private val model = "qwen3:1.7b"
    private val modelId = ModelId("catalog/$model")

    private val pi = ServerId("pi")
    private val nuc = ServerId("nuc")
    private val localModelId = ModelId("file:/models/qwen3-1.7b.gguf")

    private val refused = MemoryVerdict.Refuse(
        requiredBytes = 6L * 1024 * 1024 * 1024,
        availableBytes = 1L * 1024 * 1024 * 1024,
        reason = "Choose a smaller quantisation of this model, or a smaller model.",
    )

    private fun candidate(
        id: ServerId,
        serves: Boolean = true,
        reachable: Boolean = true,
        latencyMillis: Long? = 20L,
        breaker: BreakerState = BreakerState.CLOSED,
        enabled: Boolean = true,
    ) = ServerCandidate(
        serverId = id,
        modelNames = if (serves) setOf(model) else emptySet(),
        reachable = reachable,
        latencyMillis = latencyMillis,
        breaker = breaker,
        enabled = enabled,
    )

    private fun local(
        id: ModelId = localModelId,
        resident: Boolean = false,
        verdict: MemoryVerdict = MemoryVerdict.Fits(headroomBytes = 2L * 1024 * 1024 * 1024),
    ) = LocalCandidate(modelId = id, resident = resident, verdict = verdict)

    private fun input(
        policy: RoutingPolicy,
        servers: List<ServerCandidate> = emptyList(),
        localModels: List<LocalCandidate> = emptyList(),
        engineAvailable: Boolean = localModels.isNotEmpty(),
        device: DeviceConditions = DeviceConditions.Unknown,
        promptChars: Int = 0,
    ) = RoutingInput(
        modelName = model,
        modelId = modelId,
        policy = policy,
        servers = servers,
        localModels = localModels,
        engineAvailable = engineAvailable,
        device = device,
        promptChars = promptChars,
    )

    private fun routedTarget(decision: RoutingDecision): InferenceTarget =
        (decision as RoutingDecision.Routed).target

    private fun error(decision: RoutingDecision): AppError = (decision as RoutingDecision.Unavailable).error

    // -----------------------------------------------------------------------
    // REMOTE_ONLY
    // -----------------------------------------------------------------------

    @Test
    fun `REMOTE_ONLY routes to the only reachable server that has the model`() {
        val decision = SmartRouter.choose(input(RoutingPolicy.REMOTE_ONLY, listOf(candidate(pi))))

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }

    @Test
    fun `REMOTE_ONLY skips a reachable server that does not have the model`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.REMOTE_ONLY,
                listOf(candidate(pi, serves = false, latencyMillis = 1L), candidate(nuc, latencyMillis = 500L)),
            ),
        )

        // Faster, but useless: it cannot serve this model.
        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(nuc, model))
    }

    @Test
    fun `no server having the model is a model error, not a network one`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.REMOTE_ONLY, listOf(candidate(pi, serves = false))),
        )

        // The recovery is the model picker, not the network settings.
        val failure = error(decision)
        assertThat(failure).isInstanceOf(AppError.Model.NotFound::class.java)
        assertThat((failure as AppError.Model.NotFound).modelId).isEqualTo(modelId)
    }

    @Test
    fun `a server that has the model but is unreachable is a network error`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.REMOTE_ONLY, listOf(candidate(pi, reachable = false))),
        )

        assertThat(error(decision)).isInstanceOf(AppError.Network.Unreachable::class.java)
    }

    @Test
    fun `no configured server at all says so`() {
        val decision = SmartRouter.choose(input(RoutingPolicy.REMOTE_ONLY))

        assertThat(error(decision).message).contains("No server is configured")
    }

    @Test
    fun `a disabled server is never routed to`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.REMOTE_ONLY, listOf(candidate(pi, enabled = false))),
        )

        assertThat(decision).isInstanceOf(RoutingDecision.Unavailable::class.java)
    }

    // -----------------------------------------------------------------------
    // Remote scoring
    // -----------------------------------------------------------------------

    @Test
    fun `the lower-latency server wins`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.REMOTE_ONLY,
                listOf(candidate(pi, latencyMillis = 300L), candidate(nuc, latencyMillis = 12L)),
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(nuc, model))
    }

    @Test
    fun `a server nobody has ever timed sorts last, not first`() {
        // A null latency read as a small number would make an untested server
        // look like the fastest machine on the network.
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.REMOTE_ONLY,
                listOf(candidate(pi, latencyMillis = null), candidate(nuc, latencyMillis = 900L)),
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(nuc, model))
    }

    @Test
    fun `a closed breaker beats a half-open one even when it is slower`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.REMOTE_ONLY,
                listOf(
                    candidate(pi, latencyMillis = 5L, breaker = BreakerState.HALF_OPEN),
                    candidate(nuc, latencyMillis = 400L, breaker = BreakerState.CLOSED),
                ),
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(nuc, model))
    }

    @Test
    fun `an open breaker removes the server from the candidate set`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.REMOTE_ONLY, listOf(candidate(pi, breaker = BreakerState.OPEN))),
        )

        assertThat(decision).isInstanceOf(RoutingDecision.Unavailable::class.java)
    }

    @Test
    fun `ties break deterministically, so a thread does not swap machines mid-conversation`() {
        val servers = listOf(candidate(nuc), candidate(pi))

        val first = SmartRouter.choose(input(RoutingPolicy.REMOTE_ONLY, servers))
        val second = SmartRouter.choose(input(RoutingPolicy.REMOTE_ONLY, servers.reversed()))

        assertThat(routedTarget(first)).isEqualTo(routedTarget(second))
    }

    @Test
    fun `the runners-up come back as alternatives, best first`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.REMOTE_ONLY,
                listOf(candidate(pi, latencyMillis = 400L), candidate(nuc, latencyMillis = 10L)),
            ),
        ) as RoutingDecision.Routed

        assertThat(decision.target).isEqualTo(InferenceTarget.Remote(nuc, model))
        assertThat(decision.alternatives).containsExactly(InferenceTarget.Remote(pi, model))
    }

    // -----------------------------------------------------------------------
    // LOCAL_ONLY
    // -----------------------------------------------------------------------

    @Test
    fun `LOCAL_ONLY on a build with no engine says exactly that`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.LOCAL_ONLY, listOf(candidate(pi)), engineAvailable = false),
        )

        val failure = error(decision)
        assertThat(failure).isInstanceOf(AppError.Engine.NotAvailable::class.java)
        // The honest sentence: not "no models downloaded", which would send the
        // user to a download that could never load.
        assertThat(failure.message).contains("no on-device inference engine")
    }

    @Test
    fun `LOCAL_ONLY with an engine but nothing installed is a model error, not an engine one`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.LOCAL_ONLY, listOf(candidate(pi)), engineAvailable = true),
        )

        // Downloading something genuinely would fix this, so the sentence is
        // allowed to say so.
        assertThat(error(decision)).isInstanceOf(AppError.Model.NotFound::class.java)
    }

    @Test
    fun `LOCAL_ONLY routes to the local model`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.LOCAL_ONLY, listOf(candidate(pi)), localModels = listOf(local())),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `LOCAL_ONLY never falls back to a reachable server`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.LOCAL_ONLY, listOf(candidate(pi)), engineAvailable = true),
        )

        assertThat(decision).isInstanceOf(RoutingDecision.Unavailable::class.java)
    }

    @Test
    fun `a refused memory verdict removes the local candidate and explains the shortfall`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.LOCAL_ONLY, localModels = listOf(local(verdict = refused))),
        )

        val failure = error(decision)
        assertThat(failure).isInstanceOf(AppError.Model.InsufficientMemory::class.java)
        assertThat((failure as AppError.Model.InsufficientMemory).verdict).isEqualTo(refused)
    }

    @Test
    fun `a tight verdict still routes, because tight is a warning and not a refusal`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.LOCAL_ONLY,
                localModels = listOf(local(verdict = MemoryVerdict.Tight(headroomBytes = 1_000L, reason = "close"))),
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    // -----------------------------------------------------------------------
    // PREFER_LOCAL / PREFER_REMOTE
    // -----------------------------------------------------------------------

    @Test
    fun `PREFER_LOCAL takes the local target even when it is cold`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.PREFER_LOCAL, listOf(candidate(pi)), localModels = listOf(local(resident = false))),
        )

        // PREFER_LOCAL ranks by kind, not by score: the user asked for the
        // device, and silently going remote because a load would be slow is not
        // a preference this policy is allowed to have.
        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `PREFER_LOCAL falls back to remote when there is no local model`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.PREFER_LOCAL, listOf(candidate(pi)), engineAvailable = true),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }

    @Test
    fun `PREFER_REMOTE takes the server even when a warm local model exists`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.PREFER_REMOTE, listOf(candidate(pi)), localModels = listOf(local(resident = true))),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
        assertThat((decision as RoutingDecision.Routed).alternatives)
            .containsExactly(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `PREFER_REMOTE falls back to local when no server can be reached`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.PREFER_REMOTE,
                listOf(candidate(pi, reachable = false)),
                localModels = listOf(local()),
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    // -----------------------------------------------------------------------
    // AUTO — the scoring branch
    // -----------------------------------------------------------------------

    @Test
    fun `AUTO prefers a warm local model for a short prompt`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L)),
                localModels = listOf(local(resident = true)),
                promptChars = 40,
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `AUTO prefers a healthy server for a long prompt even when the model is warm`() {
        // Prefill is the part a phone is worst at, and a long prompt is almost
        // all prefill.
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L)),
                localModels = listOf(local(resident = true)),
                promptChars = SmartRouter.SHORT_PROMPT_CHARS + 1,
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }

    @Test
    fun `AUTO prefers a healthy server over a cold local model even for a short prompt`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L)),
                localModels = listOf(local(resident = false)),
                promptChars = 20,
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }

    @Test
    fun `AUTO takes a cold local model when every server is down`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, reachable = false)),
                localModels = listOf(local(resident = false)),
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `AUTO moves work off a phone that is low on battery and not charging`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L)),
                localModels = listOf(local(resident = true)),
                device = DeviceConditions(batteryPercent = 8, charging = false),
                promptChars = 20,
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }

    @Test
    fun `the same low battery on a charger keeps the work local`() {
        // The cost being avoided is the user's remaining runtime, and a phone on
        // a charger has none to lose.
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L)),
                localModels = listOf(local(resident = true)),
                device = DeviceConditions(batteryPercent = 8, charging = true),
                promptChars = 20,
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `AUTO moves work off a seriously throttled device`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L)),
                localModels = listOf(local(resident = true)),
                device = DeviceConditions(thermal = ThermalState.SERIOUS),
                promptChars = 20,
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }

    @Test
    fun `a critically hot device is not offered a local target at all`() {
        // Not a penalty: starting a model on a device that is shedding clock to
        // protect itself makes the situation worse, and there is no score at
        // which that is the right answer.
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.LOCAL_ONLY,
                localModels = listOf(local(resident = true)),
                device = DeviceConditions(thermal = ThermalState.CRITICAL),
            ),
        )

        assertThat(decision).isInstanceOf(RoutingDecision.Unavailable::class.java)
        assertThat(error(decision).message).contains("too hot")
    }

    @Test
    fun `an unknown thermal reading costs a local candidate nothing`() {
        // Plenty of devices report no thermal status at all. Refusing on a
        // missing sensor would break local inference on exactly the hardware
        // nobody can debug on.
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L)),
                localModels = listOf(local(resident = true)),
                device = DeviceConditions(thermal = ThermalState.UNKNOWN),
                promptChars = 20,
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `AUTO with no engine and everything down reports the remote failure`() {
        // The remote path is the one that was actually attempted, so that is
        // what the message has to be about.
        val decision = SmartRouter.choose(
            input(RoutingPolicy.AUTO, listOf(candidate(pi, reachable = false)), engineAvailable = false),
        )

        assertThat(error(decision)).isInstanceOf(AppError.Network.Unreachable::class.java)
    }

    @Test
    fun `AUTO ranks a warm local model above the servers in the alternatives too`() {
        val decision = SmartRouter.choose(
            input(
                RoutingPolicy.AUTO,
                listOf(candidate(pi, latencyMillis = 10L), candidate(nuc, latencyMillis = 800L)),
                localModels = listOf(local(resident = true)),
                promptChars = 20,
            ),
        ) as RoutingDecision.Routed

        assertThat(decision.target).isEqualTo(InferenceTarget.Local(localModelId))
        assertThat(decision.alternatives)
            .containsExactly(
                InferenceTarget.Remote(pi, model),
                InferenceTarget.Remote(nuc, model),
            ).inOrder()
    }

    // -----------------------------------------------------------------------
    // inputFor — where warm and cold are actually decided
    // -----------------------------------------------------------------------

    private val localRef = ModelRef(
        id = localModelId,
        displayName = "Qwen3 1.7B",
        name = model,
        origin = ModelOrigin.Local("/data/models/qwen3-1.7b.gguf"),
    )

    private val localRecord = LocalModelRecord(
        ref = localRef,
        path = "/data/models/qwen3-1.7b.gguf",
        sizeBytes = 1_200_000_000L,
        origin = "huggingface.co/Qwen/Qwen3-1.7B-GGUF",
        downloadedAtMillis = 1L,
        verdict = MemoryVerdict.Fits(headroomBytes = 2L * 1024 * 1024 * 1024),
        architecture = "qwen3",
        budgetedContextLength = 4096,
    )

    private fun routerWith(engine: FakeLlamaEngine, records: List<LocalModelRecord>): SmartRouter {
        val servers = mockk<ServerRepository>()
        val models = mockk<ModelRepository>()
        val local = mockk<LocalModelRepository>()
        val settings = mockk<SettingsRepository>()
        val device = mockk<DeviceStateProvider>()

        every { servers.statuses } returns MutableStateFlow(listOf(ServerStatus(testServerRef(pi), reachable = true)))
        coEvery { models.serversServing(any()) } returns setOf(pi)
        every { local.models } returns MutableStateFlow(records)
        coEvery { local.ensureScanned() } returns Unit
        coEvery { local.verdictFor(any(), any()) } answers { records.firstOrNull()?.verdict }
        every { settings.routingPolicy } returns flowOf(RoutingPolicy.AUTO)
        every { device.current() } returns DeviceConditions.Unknown

        return SmartRouter(
            servers = servers,
            models = models,
            localModels = local,
            settings = settings,
            breaker = CircuitBreaker(FakeClock()),
            deviceState = device,
            engine = engine,
        )
    }

    private fun requestFor(ref: ModelRef, prompt: String = "hello") = InferenceRequest(
        model = ref,
        messages = listOf(InferenceMessage.user(prompt)),
    )

    @Test
    fun `inputFor reports a model the engine is holding as warm`() = runTest {
        val engine = FakeLlamaEngine()
        engine.load(ModelLoadSpec(model = localRef, path = localRecord.path))
        val router = routerWith(engine, listOf(localRecord))

        val assembled = router.inputFor(requestFor(localRef))

        assertThat(assembled.localModels.single().resident).isTrue()
        assertThat(assembled.engineAvailable).isTrue()
    }

    @Test
    fun `inputFor reports a model that has never been loaded as cold`() = runTest {
        val router = routerWith(FakeLlamaEngine(), listOf(localRecord))

        val assembled = router.inputFor(requestFor(localRef))

        assertThat(assembled.localModels.single().resident).isFalse()
    }

    @Test
    fun `inputFor reports a different resident model as cold for this request`() = runTest {
        // Warm means *this* model is in memory, not that some model is. Getting
        // this wrong scores a two-second swap as if it were free.
        val engine = FakeLlamaEngine()
        engine.load(ModelLoadSpec(model = testModelRef(name = "llama3.2:3b"), path = "/data/models/other.gguf"))
        val router = routerWith(engine, listOf(localRecord))

        val assembled = router.inputFor(requestFor(localRef))

        assertThat(assembled.localModels.single().resident).isFalse()
    }

    @Test
    fun `inputFor offers no local candidates at all when the build has no engine`() = runTest {
        // FakeLlamaEngine.unavailable() behaves like a build with
        // -Pollama.nativeSource=none, where StubLlamaEngine is bound.
        val router = routerWith(FakeLlamaEngine.unavailable(), listOf(localRecord))

        val assembled = router.inputFor(requestFor(localRef))

        assertThat(assembled.localModels).isEmpty()
        assertThat(assembled.engineAvailable).isFalse()
    }

    @Test
    fun `a warm model established through the engine wins AUTO end to end`() = runTest {
        val engine = FakeLlamaEngine()
        engine.load(ModelLoadSpec(model = localRef, path = localRecord.path))
        val router = routerWith(engine, listOf(localRecord))

        val decision = router.route(requestFor(localRef))

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `the same request on a build with no engine routes to the server instead`() = runTest {
        val router = routerWith(FakeLlamaEngine.unavailable(), listOf(localRecord))

        val decision = router.route(requestFor(localRef))

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }
}
