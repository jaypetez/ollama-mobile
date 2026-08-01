package io.github.jaypetez.ollamamobile.feature.chat

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.export.ConversationExporter
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.data.repository.ModelRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerStatus
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceGateway
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.StopSequenceFilter
import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.Conversation
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.MessageStatus
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which serialisation the export menu asked for. */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
) {
    MARKDOWN("md", "text/markdown"),
    JSON("json", "application/json"),
}

/**
 * The chat screen's state, and the only place a turn is driven from.
 *
 * ## The frame pump, and why there is one
 *
 * A remote model on a fast link emits tokens considerably faster than a display
 * refreshes. Publishing state per token therefore does not make the answer
 * appear sooner — it makes the UI thread spend its frame budget on
 * recompositions whose results are overwritten before they are ever drawn, so
 * the answer appears *later*. Deltas are accumulated into a [StringBuilder] and
 * a conflated channel carries a single "something changed" tick; the pump
 * publishes one [StreamFrame] and then sleeps [FRAME_INTERVAL_MILLIS], so N
 * tokens arriving inside one frame cost one publish rather than N.
 *
 * The buffer is touched from two coroutines — the collector and the pump — and
 * needs no lock because both are children of `viewModelScope`, which is
 * confined to the main dispatcher.
 *
 * ## What is *not* in [uiState]
 *
 * The in-flight text. [uiState] changes only when a turn starts, ends, or the
 * transcript gains a row; the growing answer goes out through [stream], which
 * only the streaming bubble subscribes to. That is what stops a hundred
 * finalised bubbles from recomposing behind every token.
 */
@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
        private val conversations: ConversationRepository,
        private val models: ModelRepository,
        servers: ServerRepository,
        private val settings: SettingsRepository,
        private val gateway: InferenceGateway,
        private val exporter: ConversationExporter,
    ) : ViewModel() {
        private val conversationKey = MutableStateFlow<ConversationId?>(null)
        private val session = MutableStateFlow<TurnSession?>(null)
        private val failure = MutableStateFlow<ChatFailure?>(null)

        // Settings chosen before the thread exists. `Edited` rather than a bare
        // nullable because for the system prompt "cleared" and "not touched"
        // are different requests: null means send none, absent means inherit.
        private val draftModel = MutableStateFlow<ModelId?>(null)
        private val draftSystemPrompt = MutableStateFlow<Edited<String?>?>(null)
        private val draftSampling = MutableStateFlow<SamplingParams?>(null)

        private val buffer = StringBuilder()
        private val reasoningBuffer = StringBuilder()
        private val ticks = Channel<Unit>(Channel.CONFLATED)
        private val _stream = MutableStateFlow<StreamFrame?>(null)
        private val _events = Channel<ChatEvent>(Channel.BUFFERED)
        private var streamJob: Job? = null

        /** The in-flight answer, republished at most every [FRAME_INTERVAL_MILLIS]. */
        val stream: StateFlow<StreamFrame?> = _stream

        /** One-shot work — sharing an export, confirming a copy. */
        val events: Flow<ChatEvent> = _events.receiveAsFlow()

        private val settingsFlow = settings.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val conversationFlow: StateFlow<Conversation?> = conversationKey
            .flatMapLatest { id -> if (id == null) flowOf(null) else conversations.observeConversation(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /**
         * The finalised transcript. Null until the first read comes back.
         *
         * Pending rows are filtered out here and nowhere else. The assistant row
         * exists in the database from before the first token and grows as the
         * answer is flushed; leaving it in the list would re-emit the whole
         * transcript every few hundred characters, which is precisely the
         * recomposition storm the streaming bubble exists to avoid.
         */
        private val transcript: StateFlow<ImmutableList<ChatMessageUi>?> = conversationKey
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(persistentListOf())
                } else {
                    conversations.observeMessages(id).map { it.toUi() }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val activeModel: StateFlow<ActiveModel?> = combine(
            conversationFlow.map { it?.modelId },
            draftModel,
            settingsFlow.map { it?.defaultModelId },
        ) { fromThread, fromDraft, fromSettings -> fromThread ?: fromDraft ?: fromSettings }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(null) else models.observeModel(id) }
            .combine(servers.statuses) { model, statuses -> model?.toActive(statuses) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val uiState: StateFlow<ChatUiState> = combine(
            conversationFlow,
            transcript,
            activeModel,
            session,
            failure,
        ) { conversation, messages, model, turn, error ->
            build(conversation, messages, model, turn, error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), ChatUiState.Loading)

        val targets: StateFlow<TargetPickerState> = combine(
            models.catalogue,
            servers.statuses,
            activeModel,
        ) { catalogue, statuses, active ->
            TargetPickerState(
                options = catalogue.remote.map { it.toOption(statuses, active?.ref?.id) }.toImmutableList(),
                localAvailable = models.localInferenceAvailable,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            TargetPickerState(persistentListOf(), localAvailable = false),
        )

        /** What the sampler sheet edits: the thread's settings, or the defaults it would inherit. */
        val sampling: StateFlow<SamplingParams> = combine(
            conversationFlow,
            draftSampling,
            settingsFlow,
        ) { conversation, draft, prefs ->
            conversation?.sampling ?: draft ?: prefs?.defaultSampling ?: SamplingParams.Default
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), SamplingParams.Default)

        /** As [sampling], for the system prompt. Null means "send no system message". */
        val systemPrompt: StateFlow<String?> = combine(
            conversationFlow,
            draftSystemPrompt,
            settingsFlow,
        ) { conversation, draft, prefs ->
            conversation?.systemPrompt ?: draft?.value ?: prefs?.defaultSystemPrompt
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), null)

        init {
            viewModelScope.launch { watchForSettledTurn() }
        }

        // -------------------------------------------------------------------
        // Screen entry
        // -------------------------------------------------------------------

        /**
         * Points the screen at a thread, or at a new one when [id] is null.
         *
         * Idempotent: the screen calls this from a `LaunchedEffect` keyed on the
         * navigation argument, which re-runs on configuration change.
         */
        fun openConversation(id: ConversationId?) {
            if (conversationKey.value == id) return
            streamJob?.cancel()
            streamJob = null
            session.value = null
            failure.value = null
            _stream.value = null
            draftModel.value = null
            draftSystemPrompt.value = null
            draftSampling.value = null
            conversationKey.value = id
        }

        // -------------------------------------------------------------------
        // Sending
        // -------------------------------------------------------------------

        fun send(text: String) {
            val prompt = text.trim()
            if (prompt.isEmpty() || session.value != null) return
            viewModelScope.launch {
                failure.value = null
                val conversation = ensureConversation()
                val model = resolveModel(conversation)
                if (model == null) {
                    failure.value = ChatFailure.ModelNotLoaded(null)
                    return@launch
                }
                conversations.appendMessage(conversation.id, Role.USER, prompt)
                autoTitle(conversation, prompt)
                startTurn(conversation, model)
            }
        }

        /** Drops the newest assistant turn and asks for another one. */
        fun regenerate() {
            if (session.value != null) return
            viewModelScope.launch {
                val id = conversationKey.value ?: return@launch
                val conversation = conversations.findConversation(id) ?: return@launch
                val model = resolveModel(conversation)
                if (model == null) {
                    failure.value = ChatFailure.ModelNotLoaded(null)
                    return@launch
                }
                conversations
                    .messages(id)
                    .lastOrNull()
                    ?.takeIf { it.role == Role.ASSISTANT }
                    ?.let { conversations.deleteMessage(it.id) }
                failure.value = null
                startTurn(conversation, model)
            }
        }

        /**
         * Cancels the turn in flight.
         *
         * Cancellation reaches the transport at its next suspension point, which
         * for a streaming read is the next chunk boundary — well inside a couple
         * of hundred milliseconds. The gateway persists whatever arrived under
         * `NonCancellable`, so nothing already generated is lost.
         */
        fun stop() {
            failure.value = null
            streamJob?.cancel()
            streamJob = null
        }

        fun dismissFailure() {
            failure.value = null
        }

        // -------------------------------------------------------------------
        // Settings the screen can change
        // -------------------------------------------------------------------

        fun selectModel(modelId: ModelId) {
            viewModelScope.launch {
                val id = conversationKey.value
                if (id == null) draftModel.value = modelId else conversations.setModel(id, modelId)
                models.markUsed(modelId)
            }
        }

        fun setSystemPrompt(prompt: String?) {
            val normalised = prompt?.trim()?.takeIf { it.isNotEmpty() }
            viewModelScope.launch {
                val id = conversationKey.value
                if (id == null) {
                    draftSystemPrompt.value = Edited(normalised)
                } else {
                    conversations.setSystemPrompt(id, normalised)
                }
            }
        }

        fun setSampling(params: SamplingParams) {
            viewModelScope.launch {
                val id = conversationKey.value
                if (id == null) draftSampling.value = params else conversations.setSampling(id, params)
            }
        }

        fun export(format: ExportFormat) {
            val id = conversationKey.value ?: return
            viewModelScope.launch {
                when (val result = exporter.export(id)) {
                    is AppResult.Failure -> {
                        _events.send(ChatEvent.Notice(R.string.chat_export_failed))
                    }

                    is AppResult.Success -> {
                        val body = when (format) {
                            ExportFormat.MARKDOWN -> exporter.renderMarkdown(result.value)
                            ExportFormat.JSON -> exporter.renderJson(result.value)
                        }
                        _events.send(
                            ChatEvent.Share(
                                text = body,
                                fileName = exporter.suggestedFileName(result.value, format.extension),
                                mimeType = format.mimeType,
                            ),
                        )
                    }
                }
            }
        }

        fun showNotice(
            @StringRes messageRes: Int,
        ) {
            _events.trySend(ChatEvent.Notice(messageRes))
        }

        // -------------------------------------------------------------------
        // The turn
        // -------------------------------------------------------------------

        private suspend fun ensureConversation(): Conversation {
            conversationKey.value
                ?.let { conversations.findConversation(it) }
                ?.let { return it }

            val prefs = settings.current()
            val created = conversations.createConversation(
                modelId = draftModel.value ?: prefs.defaultModelId,
                systemPrompt = draftSystemPrompt.value?.value ?: prefs.defaultSystemPrompt,
                sampling = draftSampling.value ?: prefs.defaultSampling,
            )
            conversationKey.value = created.id
            _events.send(ChatEvent.Created(created.id))
            return created
        }

        private suspend fun resolveModel(conversation: Conversation): ModelRef? {
            val id = conversation.modelId ?: draftModel.value ?: settings.current().defaultModelId
            return id?.let { models.findModel(it) }
        }

        private suspend fun startTurn(conversation: Conversation, model: ModelRef) {
            val history = conversations
                .messages(conversation.id)
                .filter { it.status is MessageStatus.Complete && it.content.isNotBlank() }
            models.markUsed(model.id)
            launchStream(
                InferenceRequest(
                    model = model,
                    messages = history.map { InferenceMessage.of(it) },
                    sampling = conversation.sampling,
                    systemPrompt = conversation.systemPrompt,
                    wantReasoning = settings.current().requestReasoning,
                    conversationId = conversation.id,
                ),
            )
        }

        private fun launchStream(request: InferenceRequest) {
            session.value = TurnSession(knownMessageIds = transcript.value.orEmpty().mapTo(HashSet()) { it.id })
            buffer.setLength(0)
            reasoningBuffer.setLength(0)
            // Ticks left over from the previous turn would publish its text.
            var stale = ticks.tryReceive()
            while (stale.isSuccess) stale = ticks.tryReceive()
            _stream.value = StreamFrame("", "", StreamPhase.WAITING)

            // Applied even though the shipped gateway already filters: `chat` is
            // consumed through the interface, whose contract calls Token a raw
            // delta. Filtering text that carries no stop sequence withholds only
            // a short tail, which `flush` releases on every exit path.
            val filter = StopSequenceFilter(request.sampling)

            streamJob = viewModelScope.launch {
                val pump = launch { pumpFrames() }
                try {
                    gateway.chat(request).collect { event -> onEvent(event, filter) }
                    settle()
                } catch (cancellation: CancellationException) {
                    append(filter.flush())
                    settle()
                    throw cancellation
                } finally {
                    pump.cancel()
                }
            }
        }

        private fun onEvent(event: InferenceEvent, filter: StopSequenceFilter) {
            when (event) {
                is InferenceEvent.Started, is InferenceEvent.ToolCall, is InferenceEvent.Stats -> {
                    Unit
                }

                is InferenceEvent.Token -> {
                    append(filter.push(event.text))
                }

                is InferenceEvent.Reasoning -> {
                    reasoningBuffer.append(event.text)
                    ticks.trySend(Unit)
                }

                is InferenceEvent.Completed -> {
                    append(filter.flush())
                    settle()
                }

                is InferenceEvent.Failed -> {
                    append(filter.flush())
                    failure.value = event.error.toChatFailureOrNull()
                    settle()
                }
            }
        }

        private fun append(text: String) {
            if (text.isEmpty()) return
            buffer.append(text)
            ticks.trySend(Unit)
        }

        /**
         * Publishes one frame per tick, then sleeps out the rest of the frame.
         *
         * The channel is conflated, so every tick that arrives during the sleep
         * collapses into the single one waiting when the loop comes back round.
         */
        private suspend fun pumpFrames() {
            while (true) {
                ticks.receive()
                publish(StreamPhase.GENERATING)
                delay(FRAME_INTERVAL_MILLIS)
            }
        }

        private fun publish(phase: StreamPhase) {
            _stream.value = StreamFrame(buffer.toString(), reasoningBuffer.toString(), phase)
        }

        /**
         * Ends the turn and holds the finished text on screen.
         *
         * The bubble is not removed here. The persisted row is written before
         * the terminal event is emitted, but Room's invalidation is
         * asynchronous, so dropping the bubble now would blank the answer for
         * the frame or two before the transcript catches up.
         */
        private fun settle() {
            val current = session.value ?: return
            if (current.settling) return
            session.value = current.copy(settling = true)
            publish(StreamPhase.SETTLING)
            streamJob = null
            viewModelScope.launch {
                // A gateway that never wrote the row would otherwise leave the
                // bubble on screen for the rest of the process.
                delay(SETTLE_TIMEOUT_MILLIS)
                clearSettled()
            }
        }

        private suspend fun watchForSettledTurn() {
            transcript.collect { messages ->
                val current = session.value ?: return@collect
                if (!current.settling) return@collect
                val landed = messages
                    ?.any { it.role == Role.ASSISTANT && it.id !in current.knownMessageIds } == true
                if (landed) clearSettled()
            }
        }

        private fun clearSettled() {
            if (session.value?.settling != true) return
            session.value = null
            _stream.value = null
        }

        private suspend fun autoTitle(conversation: Conversation, prompt: String) {
            if (conversation.title != ConversationRepository.DEFAULT_TITLE) return
            val title = prompt
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(AUTO_TITLE_MAX_CHARS)
                ?: return
            conversations.rename(conversation.id, title)
        }

        // -------------------------------------------------------------------
        // Projection
        // -------------------------------------------------------------------

        private fun build(
            conversation: Conversation?,
            messages: ImmutableList<ChatMessageUi>?,
            model: ActiveModel?,
            turn: TurnSession?,
            error: ChatFailure?,
        ): ChatUiState {
            if (messages == null) return ChatUiState.Loading
            val header = ChatHeader(
                title = conversation?.title,
                modelName = model?.ref?.name,
                serverLabel = model?.serverLabel,
                hasSystemPrompt = !conversation?.systemPrompt.isNullOrBlank(),
                canExport = conversation != null && messages.isNotEmpty(),
            )
            val blocker = when {
                model == null -> ComposerBlocker.NO_MODEL_SELECTED
                !model.reachable -> ComposerBlocker.NO_REACHABLE_SERVER
                else -> null
            }
            // An unreachable server is advisory, not a lock: health is probed on
            // a timer, so refusing to send would strand a user whose server came
            // back thirty seconds ago.
            val composer = ComposerState(canSend = model != null && turn == null, blocker = blocker)
            return when {
                turn != null -> ChatUiState.Streaming(header, composer, messages)
                error != null -> ChatUiState.Failed(header, composer, messages, error)
                messages.isEmpty() -> ChatUiState.Empty(header, composer)
                else -> ChatUiState.Ready(header, composer, messages)
            }
        }

        private fun List<ChatMessage>.toUi(): ImmutableList<ChatMessageUi> {
            val visible = filterNot { it.status is MessageStatus.Pending }
            val newestAssistant = visible.lastOrNull { it.role == Role.ASSISTANT }?.id
            return visible
                .map { message ->
                    ChatMessageUi(
                        id = message.id,
                        role = message.role,
                        text = message.content,
                        reasoning = message.reasoning?.takeIf { it.isNotBlank() },
                        stats = MessageStatsUi.from(message.stats),
                        outcome = when (message.status) {
                            is MessageStatus.Failed -> MessageOutcome.INTERRUPTED
                            else -> MessageOutcome.COMPLETE
                        },
                        isLastAssistant = message.id == newestAssistant,
                    )
                }.toImmutableList()
        }

        private fun ModelRef.toActive(statuses: List<ServerStatus>): ActiveModel {
            val serverId = (origin as? ModelOrigin.Remote)?.serverId
            val status = statuses.firstOrNull { it.server.id == serverId }
            return ActiveModel(
                ref = this,
                serverLabel = status?.server?.label,
                reachable = status?.reachable == true,
            )
        }

        private fun ModelRef.toOption(statuses: List<ServerStatus>, selected: ModelId?): TargetOptionUi {
            val active = toActive(statuses)
            return TargetOptionUi(
                modelId = id,
                displayName = displayName,
                modelName = name,
                serverLabel = active.serverLabel,
                reachable = active.reachable,
                selected = id == selected,
            )
        }

        /** A model the screen is pointed at, with the server that would run it. */
        private data class ActiveModel(
            val ref: ModelRef,
            val serverLabel: String?,
            val reachable: Boolean,
        )

        /**
         * One turn, from send to the moment its row reaches the transcript.
         *
         * [knownMessageIds] is the transcript as it was when the turn started.
         * An assistant id outside that set is this turn's row arriving, which is
         * how the bubble knows it can hand over without a gap.
         */
        private data class TurnSession(
            val knownMessageIds: Set<MessageId>,
            val settling: Boolean = false,
        )

        /** "The user set this", as distinct from "the user has not touched it". */
        private data class Edited<T>(
            val value: T,
        )

        companion object {
            /**
             * Roughly 25 Hz.
             *
             * Chosen against the display rather than against the model: below a
             * frame interval the extra publishes are invisible, and above about
             * 60 ms the text starts to look like it is arriving in blocks.
             */
            const val FRAME_INTERVAL_MILLIS: Long = 40L

            /** How long the finished bubble waits for its persisted row. */
            const val SETTLE_TIMEOUT_MILLIS: Long = 5_000L

            private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
            private const val AUTO_TITLE_MAX_CHARS = 48
        }
    }
