package io.github.jaypetez.ollamamobile.data

import io.github.jaypetez.ollamamobile.common.dispatcher.ApplicationScope
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.data.engine.InferenceActivityTracker
import io.github.jaypetez.ollamamobile.data.engine.ModelLifecycleManager
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.routing.CircuitBreaker
import io.github.jaypetez.ollamamobile.data.routing.RoutingDecision
import io.github.jaypetez.ollamamobile.data.routing.SmartRouter
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceGateway
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.StopSequenceFilter
import io.github.jaypetez.ollamamobile.llm.ToolInvocation
import io.github.jaypetez.ollamamobile.llm.ToolSpec
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.ChatTurn
import io.github.jaypetez.ollamamobile.remote.DoneReason
import io.github.jaypetez.ollamamobile.remote.RemoteError
import io.github.jaypetez.ollamamobile.remote.RemoteMessage
import io.github.jaypetez.ollamamobile.remote.RemoteToolCall
import io.github.jaypetez.ollamamobile.remote.ServerClientFactory
import io.github.jaypetez.ollamamobile.remote.StreamEvent
import io.github.jaypetez.ollamamobile.remote.ToolDefinition
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * The [InferenceGateway] over `:core-remote` and the on-device engine.
 *
 * Five things happen here that happen nowhere else, and each is the kind of
 * thing that otherwise gets re-implemented slightly differently at three call
 * sites:
 *
 *  1. **Routing.** [SmartRouter] picks the target, and "no target" becomes an
 *     [InferenceEvent.Failed] rather than an exception.
 *  2. **The stop-sequence hold-back.** [StopSequenceFilter] sits between the
 *     backend's raw deltas and everything downstream, *including the persisted
 *     text* — so a stop marker reaches neither the screen nor the database.
 *  3. **Persistence.** The assistant turn is created before the first token
 *     and finished on the way out of every path, failure and cancellation
 *     included.
 *  4. **The circuit breaker.** Transport failures feed it; request failures —
 *     a 404 for a model that does not exist — deliberately do not. A local
 *     failure never touches it: there is no server to stop talking to.
 *  5. **Registration.** Every turn, local or remote, is registered with
 *     [InferenceActivityTracker] for its whole life, which is what lets `:app`
 *     run a foreground service for exactly the generation window and not a
 *     moment longer.
 *
 * ## The local branch and the default build
 *
 * [LlamaEngine] is consumed through the `:core-llm-api` interface, never as
 * `NativeLlamaEngine`: `checkModuleGraph` forbids `:core-data` from depending
 * on `:core-llm`, and `:app` binds the concrete engine at assembly. On the
 * default `-Pollama.nativeSource=none` build that binding is `StubLlamaEngine`
 * and [LlamaEngine.isAvailable] is false, which is checked *here* so a Local
 * target produces one typed, explainable [AppError.Engine.NotAvailable] rather
 * than a thrown `NotImplementedError` or an empty answer.
 */
@Singleton
@Suppress("LongParameterList") // Each collaborator is an independent seam; see the KDoc.
class InferenceGatewayImpl
    @Inject
    constructor(
        private val router: SmartRouter,
        private val servers: ServerRepository,
        private val models: ModelRepository,
        private val conversations: ConversationRepository,
        private val clientFactory: ServerClientFactory,
        private val breaker: CircuitBreaker,
        private val engine: LlamaEngine,
        private val lifecycle: ModelLifecycleManager,
        private val activity: InferenceActivityTracker,
        @param:IoDispatcher private val io: CoroutineDispatcher,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : InferenceGateway {
        /**
         * Every (reachable server × model it serves) pair.
         *
         * The cross product rather than one entry per server, because
         * [InferenceTarget.Remote] names a model: the question the send button
         * asks is "can *this* model run on *some* server", which a per-server
         * list cannot answer. `WhileSubscribed` so a backgrounded app is not
         * recombining this on every health probe.
         */
        override val reachableTargets: StateFlow<List<InferenceTarget>> = combine(
            servers.statuses,
            models.remoteModels,
            models.localModels,
        ) { statuses, remoteModels, localModels ->
            val reachable = statuses
                .filter { it.reachable && it.server.enabled }
                .map { it.server.id }
                .toSet()
            val remote = remoteModels.mapNotNull { model ->
                (model.origin as? ModelOrigin.Remote)
                    ?.serverId
                    ?.takeIf { it in reachable }
                    ?.let { InferenceTarget.Remote(it, model.name) }
            }
            // Local rows only count when there is an engine to run them. On a
            // build with none the rows can still exist — a previous native
            // build may have downloaded models — and offering them as reachable
            // targets would enable a send button that cannot succeed.
            val local = if (engine.isAvailable) {
                localModels.map { InferenceTarget.Local(it.id) }
            } else {
                emptyList()
            }
            (local + remote).distinct()
        }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), emptyList())

        override suspend fun listAvailableModels(): List<ModelRef> = models.catalogue.first().all

        override fun chat(request: InferenceRequest): Flow<InferenceEvent> = flow {
            // The override is passed explicitly rather than relying on the
            // default: a defaulted call compiles to a synthetic `route$default`
            // bridge, which is one more thing between this call site and the
            // function a test is trying to substitute.
            when (val decision = router.route(request, policyOverride = null)) {
                is RoutingDecision.Unavailable -> {
                    emit(InferenceEvent.Failed(decision.error))
                }

                is RoutingDecision.Routed -> {
                    val target = decision.target
                    // Registered here rather than at the top of `chat` so the
                    // registration knows which kind of target it is — the
                    // foreground service says "on this device" or names the
                    // server, and a request that never routed is not a
                    // generation anybody needs a notification for.
                    val handle = activity.begin(
                        modelLabel = request.model.displayName,
                        isLocal = target.isLocal,
                        cancel = cancelHandleFor(currentCoroutineContext()[Job]),
                    )
                    try {
                        when (target) {
                            is InferenceTarget.Remote -> runRemote(request, target)
                            is InferenceTarget.Local -> runLocal(request, target)
                        }
                    } finally {
                        // In a `finally` and not after the `when`: cancellation
                        // is the ordinary way a chat stream ends, and a
                        // registration leaked on that path pins the foreground
                        // service's wake lock for the rest of the process.
                        activity.end(handle)
                    }
                }
            }
        }.flowOn(io)

        /**
         * The Stop action's handle.
         *
         * Cancelling the flow's own job is what the user pressing Stop in the UI
         * does too, so the notification action and the button take the same path
         * rather than two paths that drift apart.
         */
        private fun cancelHandleFor(job: Job?): () -> Unit = {
            job?.cancel(CancellationException("Generation stopped from the notification."))
        }

        /**
         * Runs a turn on the on-device engine.
         *
         * The unavailable check is first and is not an assertion: on the default
         * build this is the path a `PREFER_LOCAL` or `LOCAL_ONLY` policy reaches
         * with a model row that a previous native build left behind, and the
         * user is owed the reason rather than an empty bubble.
         */
        private suspend fun FlowCollector<InferenceEvent>.runLocal(
            request: InferenceRequest,
            target: InferenceTarget.Local,
        ) {
            if (!engine.isAvailable) {
                emit(
                    InferenceEvent.Failed(
                        AppError.Engine.NotAvailable(
                            message = "This build has no on-device inference engine, so " +
                                "${request.modelName} cannot be run here. Use a remote server instead.",
                        ),
                    ),
                )
                return
            }

            try {
                lifecycle.ensureLoaded(target.modelId)
            } catch (failure: AppErrorException) {
                // Load failures are typed all the way from the engine — the file
                // is missing, the memory estimate refused it, the architecture
                // is unsupported — and each has a different fix, so none of them
                // may be flattened into "generation failed".
                emit(InferenceEvent.Failed(failure.error))
                return
            }

            emit(InferenceEvent.Started(target))

            val turn = request.conversationId?.let { conversations.beginAssistantTurn(it, request.model.id) }
            val filter = StopSequenceFilter(request.sampling)
            val state = LocalTurnState()

            lifecycle.onGenerationStarted()
            try {
                engine.generate(request).collect { event -> handleLocal(event, filter, turn, state) }
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) { turn?.fail(AppError.Network.Cancelled()) }
                throw cancellation
            } finally {
                // Restarts the keep-alive timer on every exit, cancellation
                // included: a model left resident because the user pressed Stop
                // is a model that never unloads.
                lifecycle.onGenerationFinished()
            }

            if (!state.terminal) {
                val error = AppError.Engine.GenerationFailed(
                    message = "The on-device engine stopped without finishing the answer.",
                )
                flushTail(filter, turn)
                turn?.fail(error)
                emit(InferenceEvent.Failed(error))
            }
        }

        /**
         * What one local turn has to remember between events.
         *
         * Per-call state, not a field on this singleton: `chat` is a cold flow
         * that several conversations may be collecting at once, and a shared
         * `pendingStats` would attach one turn's counters to another turn's
         * message.
         */
        private class LocalTurnState {
            /** The counters the engine reported, forwarded to the persisted row at the end. */
            var stats: GenerationStats? = null

            /** Whether a Completed or Failed has been seen. */
            var terminal: Boolean = false
        }

        /**
         * Deliberately not shared with the remote handler. The two differ in
         * every side effect that matters — the breaker, `markSeen`, the
         * `DoneReason` translation — and a merged version would be a parameter
         * list of nullable server bits with three `if`s inside.
         */
        private suspend fun FlowCollector<InferenceEvent>.handleLocal(
            event: InferenceEvent,
            filter: StopSequenceFilter,
            turn: ConversationRepository.StreamingTurn?,
            state: LocalTurnState,
        ) {
            when (event) {
                // The engine announces its own Started; the gateway has already
                // emitted one naming the routed target, and two would break the
                // "exactly one Started" contract.
                is InferenceEvent.Started -> {
                    Unit
                }

                is InferenceEvent.Token -> {
                    val visible = filter.push(event.text)
                    if (visible.isNotEmpty()) {
                        turn?.append(visible)
                        emit(InferenceEvent.Token(visible))
                    }
                }

                is InferenceEvent.Reasoning -> {
                    turn?.appendReasoning(event.text)
                    emit(event)
                }

                is InferenceEvent.ToolCall -> {
                    emit(event)
                }

                // Kept as well as forwarded, so the same counters reach the
                // persisted row. The engine emits this only when it measured
                // something, so there is never a zeroed instance to filter out.
                is InferenceEvent.Stats -> {
                    state.stats = event.stats
                    emit(event)
                }

                is InferenceEvent.Completed -> {
                    flushTail(filter, turn)
                    turn?.complete(state.stats)
                    state.terminal = true
                    emit(event)
                }

                is InferenceEvent.Failed -> {
                    flushTail(filter, turn)
                    turn?.fail(event.error, state.stats)
                    state.terminal = true
                    emit(event)
                }
            }
        }

        private suspend fun FlowCollector<InferenceEvent>.runRemote(
            request: InferenceRequest,
            target: InferenceTarget.Remote,
        ) {
            val server = servers.findServer(target.serverId)
            if (server == null) {
                // Deleted between routing and sending. Reported precisely
                // rather than as a connection failure, because "check your
                // network" is unhelpful advice for it.
                emit(InferenceEvent.Failed(AppError.Storage.NotFound(what = "server ${target.serverId.value}")))
                return
            }

            emit(InferenceEvent.Started(target))

            val turn = request.conversationId?.let { conversations.beginAssistantTurn(it, request.model.id) }
            val filter = StopSequenceFilter(request.sampling)
            val client = clientFactory.clientFor(server).client
            var terminal = false

            try {
                client.chat(server, request.toChatTurn()).collect { event ->
                    if (handle(event, filter, turn, target, server)) terminal = true
                }
            } catch (cancellation: CancellationException) {
                // The partial answer is still worth keeping, and the write has
                // to run outside the cancelled job or it suspends and dies.
                // Nothing is emitted: a cancelled collector cannot receive.
                withContext(NonCancellable) { turn?.fail(AppError.Network.Cancelled()) }
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") throwable: Throwable,
            ) {
                val error = RemoteError.fromThrowable(throwable)
                breaker.onFailure(target.serverId, error)
                flushTail(filter, turn)
                turn?.fail(error)
                emit(InferenceEvent.Failed(error))
                return
            }

            if (!terminal) {
                // The transport ended the stream without saying how. That is a
                // truncation, and presenting it as a finished answer is exactly
                // the bug this event protocol exists to prevent.
                val error = AppError.Network.Unreachable(
                    message = "The server closed the connection before the response finished.",
                )
                breaker.onFailure(target.serverId, error)
                flushTail(filter, turn)
                turn?.fail(error)
                emit(InferenceEvent.Failed(error))
            }
        }

        /** @return true when [event] was terminal. */
        private suspend fun FlowCollector<InferenceEvent>.handle(
            event: StreamEvent,
            filter: StopSequenceFilter,
            turn: ConversationRepository.StreamingTurn?,
            target: InferenceTarget.Remote,
            server: ServerRef,
        ): Boolean = when (event) {
            is StreamEvent.Text -> {
                // Filtered before both the emit and the write.
                val visible = filter.push(event.delta)
                if (visible.isNotEmpty()) {
                    turn?.append(visible)
                    emit(InferenceEvent.Token(visible))
                }
                false
            }

            is StreamEvent.Reasoning -> {
                turn?.appendReasoning(event.delta)
                emit(InferenceEvent.Reasoning(event.delta))
                false
            }

            is StreamEvent.ToolCall -> {
                emit(InferenceEvent.ToolCall(event.call.toInvocation()))
                false
            }

            is StreamEvent.Completed -> {
                breaker.onSuccess(target.serverId)
                servers.markSeen(server.id)
                flushTail(filter, turn)
                // Only when the server actually reported counters: an Empty
                // instance forwarded here renders as "0 tok/s" for data nobody
                // measured. See InferenceEvent.Stats.
                val stats = event.stats.takeUnless { it.isEmpty }
                if (stats != null) emit(InferenceEvent.Stats(stats))
                turn?.complete(stats)
                emit(InferenceEvent.Completed(event.doneReason.toFinishReason()))
                true
            }

            is StreamEvent.Failed -> {
                breaker.onFailure(target.serverId, event.error)
                // Flushed first, so the partial answer keeps text that was
                // withheld pending a stop match which never came.
                flushTail(filter, turn)
                turn?.fail(event.error)
                emit(InferenceEvent.Failed(event.error))
                true
            }
        }

        private suspend fun FlowCollector<InferenceEvent>.flushTail(
            filter: StopSequenceFilter,
            turn: ConversationRepository.StreamingTurn?,
        ) {
            val tail = filter.flush()
            if (tail.isEmpty()) return
            turn?.append(tail)
            emit(InferenceEvent.Token(tail))
        }

        private fun InferenceRequest.toChatTurn(): ChatTurn = ChatTurn(
            model = modelName,
            messages = buildList {
                systemPrompt?.let { add(RemoteMessage(Role.SYSTEM, it)) }
                addAll(messages.map { it.toRemote() })
            },
            sampling = sampling,
            tools = tools.map { it.toDefinition() },
            // Null rather than false when reasoning is unwanted: `think: false`
            // is a real instruction that a non-reasoning model rejects, while
            // null leaves the server's own behaviour alone.
            think = true.takeIf { wantReasoning },
        )

        private companion object {
            /**
             * How long [reachableTargets] keeps combining after its last
             * collector leaves: long enough to survive a configuration change,
             * short enough that a backgrounded app stops recomputing.
             */
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        }
    }

private fun InferenceMessage.toRemote(): RemoteMessage = RemoteMessage(
    role = role,
    content = content,
    imagesBase64 = imagesBase64,
    toolCalls = toolCalls.map { it.toRemoteToolCall() },
    toolName = toolName,
    toolCallId = toolCallId,
)

private fun ToolInvocation.toRemoteToolCall(): RemoteToolCall = RemoteToolCall(
    id = id,
    name = name,
    arguments = argumentsJson.asJsonObjectOrEmpty(),
)

private fun RemoteToolCall.toInvocation(): ToolInvocation = ToolInvocation(
    id = id,
    name = name,
    argumentsJson = arguments.toString(),
)

private fun ToolSpec.toDefinition(): ToolDefinition = ToolDefinition(
    name = name,
    description = description,
    parametersSchema = parametersSchemaJson.asJsonObjectOrEmpty(),
)

/**
 * Parses a JSON Schema or an arguments blob.
 *
 * An unparseable one becomes `{}` rather than an exception. The contract
 * carries these as text precisely so `:core-llm-api` needs no serialization
 * runtime, and a malformed tool schema should cost the model that one tool,
 * not the whole request.
 */
private fun String.asJsonObjectOrEmpty(): JsonObject = runCatching {
    RemoteJson.parseToJsonElement(this) as? JsonObject
}.getOrNull() ?: buildJsonObject { }

private fun DoneReason?.toFinishReason(): FinishReason = when (this) {
    DoneReason.STOP -> FinishReason.STOP

    DoneReason.LENGTH -> FinishReason.LENGTH

    DoneReason.TOOL_CALLS -> FinishReason.TOOL_CALLS

    DoneReason.CONTENT_FILTER -> FinishReason.CONTENT_FILTER

    // LOAD means the server loaded the model and generated nothing, which is a
    // complete response to a warm-up request rather than a truncation.
    DoneReason.LOAD -> FinishReason.STOP

    DoneReason.UNKNOWN, null -> FinishReason.UNKNOWN
}
