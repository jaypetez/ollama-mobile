package io.github.jaypetez.ollamamobile.data.routing

import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
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
 * One on-device model the router could run, and everything it needs to score it.
 *
 * [resident] is the single largest term in the whole comparison and it is the
 * reason this type exists rather than a bare [ModelId]. Answering from a model
 * that is already in memory starts decoding immediately; answering from one that
 * is not means mapping several gigabytes and building a context first, during
 * which a reachable server on the LAN has usually finished the whole reply. The
 * two cases are not the same candidate wearing different hats, and a router that
 * cannot tell them apart will choose a cold local load over a warm remote server
 * every time.
 */
data class LocalCandidate(
    val modelId: ModelId,
    /** True when *this* model — not merely some model — is loaded in the engine. */
    val resident: Boolean = false,
    /**
     * The memory estimate for loading it, taken now.
     *
     * A [MemoryVerdict.Refuse] removes the candidate outright: the load would
     * fail, or worse, succeed and get the process killed mid-answer.
     */
    val verdict: MemoryVerdict = MemoryVerdict.Fits(headroomBytes = 0L),
)

/**
 * Everything a routing decision depends on, as plain data.
 *
 * The decision is a pure function of this — see [SmartRouter.choose] — so
 * every policy, every tie-break and every failure message is testable without
 * a database, a network, a battery or a thermal sensor.
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
     * Empty when nothing is installed **and** when the build has no engine —
     * two states that produce different failures, which is what
     * [engineAvailable] distinguishes.
     */
    val localModels: List<LocalCandidate> = emptyList(),
    /**
     * False for a `-Pollama.nativeSource=none` build.
     *
     * Kept separate from an empty [localModels] because "you have no models" and
     * "this build cannot run models" are different sentences with different
     * fixes, and collapsing them sends the user to download something that will
     * never load.
     */
    val engineAvailable: Boolean = false,
    val device: DeviceConditions = DeviceConditions.Unknown,
    /**
     * How much text this turn has to process, in characters of prompt.
     *
     * Characters and not tokens, because the only honest token count comes from
     * the model's own tokenizer and getting one means loading the model — which
     * is the decision being made. The threshold it feeds is a coarse one and is
     * documented as such.
     */
    val promptChars: Int = 0,
) {
    /** True when the prompt is short enough that prefill on the device is cheap. */
    val shortPrompt: Boolean
        get() = promptChars <= SmartRouter.SHORT_PROMPT_CHARS
}

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
 * and a decision function that needs Room, OkHttp, a battery and a thermal
 * sensor to exercise is one nobody writes the awkward cases for.
 */
@Singleton
class SmartRouter
    @Inject
    constructor(
        private val servers: ServerRepository,
        private val models: ModelRepository,
        private val localModels: LocalModelRepository,
        private val settings: SettingsRepository,
        private val breaker: CircuitBreaker,
        private val deviceState: DeviceStateProvider,
        /**
         * Asked two questions and nothing else: is there an engine at all, and
         * which model is in it. Both are properties of the engine rather than
         * of the model library, and reading them from the interface keeps
         * `:core-data` off `:core-llm`.
         */
        private val engine: LlamaEngine,
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
                localModels = localCandidates(request),
                engineAvailable = engine.isAvailable,
                device = deviceState.current(),
                promptChars = request.messages.sumOf { it.content.length },
            )
        }

        /**
         * The on-device models that could serve this request.
         *
         * Matched on the id the request already carries as well as on the file
         * name, because a request built from the model picker names the local
         * model exactly, while one replayed from a conversation started against
         * a remote server names a tag that a local GGUF may also answer to.
         */
        private suspend fun localCandidates(request: InferenceRequest): List<LocalCandidate> {
            if (!engine.isAvailable) return emptyList()
            // The first request after a cold start would otherwise see an
            // unscanned, empty library and route remote for no reason.
            localModels.ensureScanned()
            val resident = engine.loadedModel.value?.id
            return localModels.models.value
                .filter { it.id == request.model.id || it.ref.name == request.modelName }
                .map { record ->
                    LocalCandidate(
                        modelId = record.id,
                        resident = record.id == resident,
                        // Re-taken rather than reused from the scan: available
                        // memory moves, and the scan may be minutes old.
                        verdict = localModels.verdictFor(record.id) ?: record.verdict,
                    )
                }
        }

        companion object {
            /**
             * The decision, as a pure function.
             *
             * ## How AUTO compares a phone with a Raspberry Pi
             *
             * Every candidate is reduced to one integer and the highest wins.
             * The alternative — a cascade of `if`s comparing incommensurable
             * things — is where "prefer local unless the battery is low, unless
             * it is charging, unless it is hot" turns into four branches nobody
             * can test. The weights below are policy, they are stated as named
             * constants, and each one is defended where it is declared.
             *
             * The headline behaviours the scores are chosen to produce:
             *
             *  * a **warm** local model beats a healthy server on a **short**
             *    prompt, because there is no network round trip and no load,
             *  * a healthy server beats even a warm local model on a **long**
             *    prompt, because prefill is the part a phone is worst at,
             *  * a **cold** local model loses to any healthy server, because
             *    mapping gigabytes costs more than the whole remote request,
             *  * a low battery off the charger, or a seriously throttled
             *    device, moves work off the phone,
             *  * a [MemoryVerdict.Refuse] or a critically hot device removes the
             *    local candidate entirely rather than merely deprioritising it.
             */
            fun choose(input: RoutingInput): RoutingDecision {
                val local = if (input.policy.allowsLocal) localTargets(input) else emptyList()
                val remote = if (input.policy.allowsRemote) remoteTargets(input) else emptyList()

                val ordered = when {
                    input.policy.prefersLocal -> local.map { it.target } + remote.map { it.target }

                    input.policy == RoutingPolicy.PREFER_REMOTE -> remote.map { it.target } + local.map { it.target }

                    // AUTO ranks across both kinds on the one score. The
                    // secondary keys are what stop two equally-scored targets
                    // swapping places between requests, which would move a
                    // conversation between machines mid-thread.
                    else -> (remote + local)
                        .sortedWith(
                            compareByDescending<ScoredTarget> { it.score }
                                .thenBy { if (it.target.isLocal) 0 else 1 }
                                .thenBy { it.tieBreak },
                        ).map { it.target }
                }

                val first = ordered.firstOrNull()
                    ?: return RoutingDecision.Unavailable(explain(input))
                return RoutingDecision.Routed(first, ordered.drop(1))
            }

            /** A candidate reduced to a comparable number, with a stable tie-break. */
            private data class ScoredTarget(
                val target: InferenceTarget,
                val score: Int,
                val tieBreak: String,
            )

            /**
             * Every usable local target for the request, best first.
             *
             * A refused memory verdict and a critically hot device are filters,
             * not penalties: a load that the estimate says cannot fit will not
             * start succeeding because nothing else was available, and offering
             * it means an OOM kill instead of a sentence explaining the problem.
             */
            private fun localTargets(input: RoutingInput): List<ScoredTarget> {
                if (!input.engineAvailable) return emptyList()
                if (input.device.thermal == ThermalState.CRITICAL) return emptyList()
                return input.localModels
                    .filter { it.verdict.allowsLoad }
                    .map { candidate ->
                        ScoredTarget(
                            target = InferenceTarget.Local(candidate.modelId),
                            score = scoreLocal(candidate, input),
                            tieBreak = candidate.modelId.value,
                        )
                    }.sortedWith(compareByDescending<ScoredTarget> { it.score }.thenBy { it.tieBreak })
            }

            private fun scoreLocal(candidate: LocalCandidate, input: RoutingInput): Int {
                var score = if (candidate.resident) WARM_LOCAL_SCORE else COLD_LOCAL_SCORE
                if (candidate.verdict is MemoryVerdict.Tight) score -= TIGHT_MEMORY_PENALTY
                if (!input.shortPrompt) score -= LONG_PROMPT_PENALTY
                if (input.device.batteryConstrained) score -= LOW_BATTERY_PENALTY
                if (input.device.charging) score += CHARGING_BONUS
                score -= when (input.device.thermal) {
                    ThermalState.FAIR -> THERMAL_FAIR_PENALTY

                    ThermalState.SERIOUS -> THERMAL_SERIOUS_PENALTY

                    // CRITICAL never reaches here; UNKNOWN and NOMINAL cost nothing,
                    // because refusing on a missing sensor reading would break local
                    // inference on every device whose vendor HAL does not report one.
                    else -> 0
                }
                return score
            }

            /**
             * Reachable, enabled servers that serve the model, best first.
             *
             * The ordering is the same one this router has always used —
             * breaker state, then latency, then server id — expressed as a
             * score so AUTO can compare it against a local candidate. The
             * server-id key is not decoration: without it two servers with
             * identical latency swap places between calls, and a conversation
             * ends up alternating machines mid-thread.
             */
            private fun remoteTargets(input: RoutingInput): List<ScoredTarget> = input.servers
                .asSequence()
                .filter { it.enabled && it.reachable }
                .filter { input.modelName in it.modelNames }
                .filter { it.breaker != BreakerState.OPEN }
                .map { candidate ->
                    ScoredTarget(
                        target = InferenceTarget.Remote(candidate.serverId, input.modelName),
                        score = scoreRemote(candidate),
                        tieBreak = candidate.serverId.value,
                    )
                }.sortedWith(compareByDescending<ScoredTarget> { it.score }.thenBy { it.tieBreak })
                .toList()

            private fun scoreRemote(candidate: ServerCandidate): Int {
                var score = HEALTHY_REMOTE_SCORE
                if (candidate.breaker != BreakerState.CLOSED) score -= HALF_OPEN_PENALTY
                // A server nobody has ever timed is penalised as if it were
                // slow. A null read as zero would make an untested machine look
                // like the fastest one on the network.
                score -= candidate.latencyMillis
                    ?.let { (it / LATENCY_MILLIS_PER_POINT).toInt().coerceAtMost(MAX_LATENCY_PENALTY) }
                    ?: UNMEASURED_LATENCY_PENALTY
                return score
            }

            /**
             * Why nothing was chosen.
             *
             * Ordered from the most specific cause to the least, because the
             * first true statement is the one the user can act on: "this model
             * needs more memory than the device has" sends them to a smaller
             * quantisation, "no server has this model" sends them to the model
             * picker, "everything is unreachable" sends them to the network.
             */
            private fun explain(input: RoutingInput): AppError {
                if (!input.policy.allowsRemote) return explainLocalOnly(input)
                // A local model that the memory estimate refused outright is
                // the most specific cause available, and the fix — a smaller
                // quantisation — is one the user can act on immediately.
                return refusedLocally(input) ?: explainRemote(input)
            }

            /** The `Refuse` verdict on a local candidate, when the policy could have used one. */
            private fun refusedLocally(input: RoutingInput): AppError? {
                if (!input.policy.allowsLocal || !input.engineAvailable) return null
                return input.localModels
                    .map { it.verdict }
                    .filterIsInstance<MemoryVerdict.Refuse>()
                    .firstOrNull()
                    ?.let { AppError.Model.InsufficientMemory(verdict = it) }
            }

            /** Why no server took it, from the most specific cause to the least. */
            private fun explainRemote(input: RoutingInput): AppError {
                val usable = input.servers.filter { it.enabled }
                if (usable.isEmpty()) {
                    // With no server configured, a local-capable build that
                    // simply has nothing installed deserves the engine-side
                    // sentence rather than a network one.
                    val localCapable = input.policy.allowsLocal && input.engineAvailable
                    return if (localCapable) {
                        explainLocalOnly(input)
                    } else {
                        AppError.Network.Unreachable(message = "No server is configured.")
                    }
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
             * The local-side failure, with the actual reason.
             *
             * Four genuinely different things end up here and each has its own
             * fix: no engine in the build, no model installed, the model does
             * not fit, and the device is too hot to start one.
             */
            private fun explainLocalOnly(input: RoutingInput): AppError {
                if (!input.engineAvailable) {
                    return AppError.Engine.NotAvailable(
                        message = "This build has no on-device inference engine, so nothing can run locally.",
                    )
                }
                if (input.localModels.isEmpty()) {
                    return AppError.Model.NotFound(
                        modelId = input.modelId,
                        message = "${input.modelName} is not installed on this device.",
                    )
                }
                val refusal = input.localModels
                    .map { it.verdict }
                    .filterIsInstance<MemoryVerdict.Refuse>()
                    .firstOrNull()
                if (refusal != null) return AppError.Model.InsufficientMemory(verdict = refusal)
                if (input.device.thermal == ThermalState.CRITICAL) {
                    return AppError.Engine.NotAvailable(
                        message = "The device is too hot to start a model. Let it cool down, or use a server.",
                    )
                }
                return AppError.Engine.NotAvailable(
                    message = "No on-device model can serve ${input.modelName} right now.",
                )
            }

            // ------------------------------------------------------------ weights
            //
            // Relative, not absolute: only the comparisons between them mean
            // anything. The load-bearing relationships are asserted in
            // SmartRouterTest, so changing one of these without changing the
            // behaviour it encodes fails the build rather than shifting routing
            // silently.

            /** A model already in memory. Beats a healthy server; see the class KDoc. */
            const val WARM_LOCAL_SCORE: Int = 60

            /** A model that has to be mapped and contexted first. Loses to any healthy server. */
            const val COLD_LOCAL_SCORE: Int = 10

            /** A reachable server with a closed breaker, before its latency is charged. */
            const val HEALTHY_REMOTE_SCORE: Int = 50

            /** Enough to put a warm local model behind a healthy server. */
            const val LONG_PROMPT_PENALTY: Int = 45

            /** Prompt length at or below which local prefill is treated as cheap. */
            const val SHORT_PROMPT_CHARS: Int = 400

            /** A load that fits, but only just. Worth avoiding when there is an alternative. */
            private const val TIGHT_MEMORY_PENALTY = 25

            /** Below `DeviceConditions.LOW_BATTERY_PERCENT` and off the charger. */
            private const val LOW_BATTERY_PENALTY = 40

            /** On a charger, the runtime being spent is not the user's. */
            private const val CHARGING_BONUS = 15

            private const val THERMAL_FAIR_PENALTY = 10
            private const val THERMAL_SERIOUS_PENALTY = 50

            /**
             * A half-open breaker: usable, but this request is the probe.
             *
             * Strictly larger than any latency penalty, which is what preserves
             * the older ordering rule that a closed breaker beats a half-open
             * one *however* much slower it is. A server that has just been
             * failing is a worse bet than a server that is merely far away.
             */
            private const val HALF_OPEN_PENALTY = 45

            /** One point of penalty per 20 ms of measured round trip. */
            private const val LATENCY_MILLIS_PER_POINT = 20L
            private const val MAX_LATENCY_PENALTY = 30

            /**
             * One point worse than the worst measured latency.
             *
             * "Never timed" has to sort *last* among reachable servers rather
             * than mid-table: a null read as a small number would make a machine
             * nobody has ever probed look like the fastest one on the network.
             */
            private const val UNMEASURED_LATENCY_PENALTY = MAX_LATENCY_PENALTY + 1
        }
    }
