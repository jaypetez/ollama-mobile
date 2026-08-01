package io.github.jaypetez.ollamamobile.data.routing

import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ServerId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** One server the router could send to, and everything it needs to score it. */
data class ServerCandidate(
    val serverId: ServerId,
    /** The model tags this server serves. Matched against [RoutingInput.modelName]. */
    val modelNames: Set<String>,
    val reachable: Boolean,
    /** Last measured round-trip time. Null when it has never answered. */
    val latencyMillis: Long? = null,
    val breaker: BreakerState = BreakerState.CLOSED,
    val enabled: Boolean = true,
)

/**
 * Everything a routing decision depends on, as plain data.
 *
 * The decision is a pure function of this — see [SmartRouter.choose] — so
 * every policy, every tie-break and every failure message is testable without
 * a database, a network or a clock.
 */
data class RoutingInput(
    /** The tag to look for, e.g. `qwen3:1.7b`. Not a [ModelId]: see [ServerCandidate.modelNames]. */
    val modelName: String,
    /** Only used to build a precise `AppError.Model.NotFound`. */
    val modelId: ModelId,
    val policy: RoutingPolicy,
    val servers: List<ServerCandidate>,
    /**
     * On-device models that could serve this request.
     *
     * **Always empty in this build.** See the local branch in
     * [SmartRouter.choose] for why the parameter exists anyway.
     */
    val localModels: Set<ModelId> = emptySet(),
)

/** What the router decided. */
sealed interface RoutingDecision {
    /**
     * A target was chosen.
     *
     * [alternatives] are the runners-up, best first, so a caller can fail over
     * without asking again — which matters because by the time the first
     * target fails, the reachability data that produced this decision is
     * seconds stale.
     */
    data class Routed(
        val target: InferenceTarget,
        val alternatives: List<InferenceTarget> = emptyList(),
    ) : RoutingDecision

    /** Nothing can serve the request. [error] says which of the several reasons applies. */
    data class Unavailable(
        val error: AppError,
    ) : RoutingDecision
}

/**
 * Chooses where a request runs.
 *
 * The class is a thin shell around [choose], which is pure and static. That
 * split is deliberate: routing is the part of this module with real branching,
 * and a decision function that needs Room, OkHttp and a clock to exercise is
 * one nobody writes the awkward cases for.
 */
@Singleton
class SmartRouter
    @Inject
    constructor(
        private val servers: ServerRepository,
        private val models: ModelRepository,
        private val settings: SettingsRepository,
        private val breaker: CircuitBreaker,
    ) {
        /**
         * Routes a live request, reading the current state of the world.
         *
         * @param policyOverride forces a policy for this one request, for a
         *   "run this on the device" affordance that must not change the
         *   user's saved default.
         */
        suspend fun route(
            request: InferenceRequest,
            policyOverride: RoutingPolicy? = null,
        ): RoutingDecision = choose(inputFor(request, policyOverride))

        /** Assembles the pure input from the repositories. Exposed so a caller can inspect it. */
        suspend fun inputFor(
            request: InferenceRequest,
            policyOverride: RoutingPolicy? = null,
        ): RoutingInput {
            val statuses = servers.statuses.first()
            val serving = models.serversServing(request.modelName)
            return RoutingInput(
                modelName = request.modelName,
                modelId = request.model.id,
                policy = policyOverride ?: settings.routingPolicy.first(),
                servers = statuses.map { status ->
                    ServerCandidate(
                        serverId = status.server.id,
                        // The cached model list is the source of truth for
                        // "does this server have it". `loadedModels` from
                        // /api/ps is what is resident *right now*, which is a
                        // latency hint and not an availability one — a server
                        // will happily load a model it has on disk.
                        modelNames = if (status.server.id in serving) setOf(request.modelName) else emptySet(),
                        reachable = status.reachable,
                        latencyMillis = status.latencyMillis,
                        breaker = breaker.state(status.server.id),
                        enabled = status.server.enabled,
                    )
                },
                // The only producer of local models is ModelRepository, and it
                // reads a table nothing writes. See the local branch in
                // `choose`.
                localModels = models.localModels
                    .first()
                    .filter { it.origin is ModelOrigin.Local && it.name == request.modelName }
                    .map { it.id }
                    .toSet(),
            )
        }

        companion object {
            /**
             * The decision, as a pure function.
             *
             * ## The local branch
             *
             * It is written out below rather than left as a `TODO`, and that is
             * the point of this whole shape. Today
             * [RoutingInput.localModels] is always empty — nothing in this
             * build writes a local-origin model row, because there is no
             * engine to load one — so `LOCAL_ONLY` always returns
             * [AppError.Engine.NotAvailable] and `PREFER_LOCAL` always falls
             * through to remote. The branch is therefore *unreachable by
             * construction* in the shipped app while being fully reachable
             * from a test that supplies a local model.
             *
             * Shipping an engine is then a wiring change — populate that set —
             * and not an edit to this function. A `TODO` here would have been
             * the same amount of thinking deferred to the least convenient
             * moment.
             */
            fun choose(input: RoutingInput): RoutingDecision {
                val local = if (input.policy.allowsLocal) localTargets(input) else emptyList()
                val remote = if (input.policy.allowsRemote) remoteTargets(input) else emptyList()

                val ordered = when {
                    input.policy.prefersLocal -> local + remote

                    input.policy == RoutingPolicy.PREFER_REMOTE -> remote + local

                    // AUTO ranks across both kinds. With no local candidates
                    // this is exactly PREFER_REMOTE, which is what
                    // RoutingPolicy.AUTO documents.
                    else -> remote + local
                }

                val first = ordered.firstOrNull()
                    ?: return RoutingDecision.Unavailable(explain(input))
                return RoutingDecision.Routed(first, ordered.drop(1))
            }

            /**
             * Every usable local target for the request.
             *
             * There is no scoring: an on-device engine has no latency to
             * measure until it has run, and there is only ever one device.
             */
            private fun localTargets(input: RoutingInput): List<InferenceTarget> =
                input.localModels.map(InferenceTarget::Local)

            /**
             * Reachable, enabled servers that serve the model, best first.
             *
             * Sorted by a stable, total ordering: breaker state, then latency,
             * then server id. The last key is not decoration — without it two
             * servers with identical latency swap places between calls, and a
             * conversation ends up alternating machines mid-thread.
             */
            private fun remoteTargets(input: RoutingInput): List<InferenceTarget> = input.servers
                .asSequence()
                .filter { it.enabled && it.reachable }
                .filter { input.modelName in it.modelNames }
                .filter { it.breaker != BreakerState.OPEN }
                .sortedWith(
                    compareBy<ServerCandidate> { if (it.breaker == BreakerState.CLOSED) 0 else 1 }
                        .thenBy { it.latencyMillis ?: UNMEASURED_LATENCY_MILLIS }
                        .thenBy { it.serverId.value },
                ).map { InferenceTarget.Remote(it.serverId, input.modelName) }
                .toList()

            /**
             * Why nothing was chosen.
             *
             * Ordered from the most specific cause to the least, because the
             * first true statement is the one the user can act on: "no server
             * has this model" sends them to the model picker, "everything is
             * unreachable" sends them to the network.
             */
            private fun explain(input: RoutingInput): AppError {
                if (!input.policy.allowsRemote) {
                    return AppError.Engine.NotAvailable(
                        message = "On-device inference is not available in this build, and routing is local-only.",
                    )
                }

                val usable = input.servers.filter { it.enabled }
                if (usable.isEmpty()) {
                    return AppError.Network.Unreachable(message = "No server is configured.")
                }

                val serving = usable.filter { input.modelName in it.modelNames }
                if (serving.isEmpty()) {
                    return AppError.Model.NotFound(
                        modelId = input.modelId,
                        message = "No configured server offers ${input.modelName}.",
                    )
                }

                if (serving.any { it.breaker == BreakerState.OPEN && it.reachable }) {
                    return AppError.Network.Unreachable(
                        message = "Every server with ${input.modelName} is failing and is not being retried yet.",
                    )
                }

                return AppError.Network.Unreachable(
                    message = "No server with ${input.modelName} can be reached right now.",
                )
            }

            /**
             * Sort key for a server that has never answered.
             *
             * `Long.MAX_VALUE` and not zero: an unmeasured server must sort
             * *last* among reachable ones, and a null treated as zero would
             * make a server nobody has ever timed look like the fastest one on
             * the network.
             */
            private const val UNMEASURED_LATENCY_MILLIS = Long.MAX_VALUE
        }
    }
