package io.github.jaypetez.ollamamobile.data.routing

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ServerId
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The decision function, under every policy.
 *
 * All of these drive [SmartRouter.choose] directly, which is exactly why it is
 * pure and static: the interesting cases here are the awkward ones — a server
 * that is up but lacks the model, a breaker that is half-open, two servers with
 * identical latency — and none of them is convenient to arrange through a
 * database and a network.
 */
@RunWith(JUnit4::class)
class SmartRouterTest {
    private val model = "qwen3:1.7b"
    private val modelId = ModelId("catalog/$model")

    private val pi = ServerId("pi")
    private val nuc = ServerId("nuc")
    private val localModelId = ModelId("file:/models/qwen3-1.7b.gguf")

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

    private fun input(
        policy: RoutingPolicy,
        servers: List<ServerCandidate> = emptyList(),
        localModels: Set<ModelId> = emptySet(),
    ) = RoutingInput(
        modelName = model,
        modelId = modelId,
        policy = policy,
        servers = servers,
        localModels = localModels,
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
    // Scoring
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
        // A null latency read as zero would make an untested server look like
        // the fastest machine on the network.
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
    // LOCAL_ONLY — the branch nothing can reach in the shipped app
    // -----------------------------------------------------------------------

    @Test
    fun `LOCAL_ONLY fails with an engine error because this build has no engine`() {
        val decision = SmartRouter.choose(input(RoutingPolicy.LOCAL_ONLY, listOf(candidate(pi))))

        assertThat(error(decision)).isInstanceOf(AppError.Engine.NotAvailable::class.java)
    }

    @Test
    fun `LOCAL_ONLY routes locally once a local model exists`() {
        // Reachable only from a test: nothing in this build writes a
        // local-origin model row. The branch is exercised so that shipping an
        // engine is a wiring change and not an edit to the router.
        val decision = SmartRouter.choose(
            input(RoutingPolicy.LOCAL_ONLY, listOf(candidate(pi)), localModels = setOf(localModelId)),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `LOCAL_ONLY never falls back to a reachable server`() {
        val decision = SmartRouter.choose(input(RoutingPolicy.LOCAL_ONLY, listOf(candidate(pi))))

        assertThat(decision).isInstanceOf(RoutingDecision.Unavailable::class.java)
    }

    // -----------------------------------------------------------------------
    // PREFER_LOCAL / PREFER_REMOTE / AUTO
    // -----------------------------------------------------------------------

    @Test
    fun `PREFER_LOCAL takes the local target when there is one`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.PREFER_LOCAL, listOf(candidate(pi)), localModels = setOf(localModelId)),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `PREFER_LOCAL falls back to remote when there is not`() {
        val decision = SmartRouter.choose(input(RoutingPolicy.PREFER_LOCAL, listOf(candidate(pi))))

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Remote(pi, model))
    }

    @Test
    fun `PREFER_REMOTE takes the server even when a local model exists`() {
        val decision = SmartRouter.choose(
            input(RoutingPolicy.PREFER_REMOTE, listOf(candidate(pi)), localModels = setOf(localModelId)),
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
                localModels = setOf(localModelId),
            ),
        )

        assertThat(routedTarget(decision)).isEqualTo(InferenceTarget.Local(localModelId))
    }

    @Test
    fun `AUTO and PREFER_REMOTE agree on every input while no local engine exists`() {
        val servers = listOf(candidate(pi, latencyMillis = 100L), candidate(nuc, latencyMillis = 10L))

        assertThat(SmartRouter.choose(input(RoutingPolicy.AUTO, servers)))
            .isEqualTo(SmartRouter.choose(input(RoutingPolicy.PREFER_REMOTE, servers)))
    }

    @Test
    fun `AUTO with everything down reports the remote failure, not an engine one`() {
        // The remote path is the one that was actually attempted, so that is
        // what the message has to be about.
        val decision = SmartRouter.choose(
            input(RoutingPolicy.AUTO, listOf(candidate(pi, reachable = false))),
        )

        assertThat(error(decision)).isInstanceOf(AppError.Network.Unreachable::class.java)
    }
}
