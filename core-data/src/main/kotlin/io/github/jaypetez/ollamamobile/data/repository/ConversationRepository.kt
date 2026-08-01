package io.github.jaypetez.ollamamobile.data.repository

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.data.mapper.toDomain
import io.github.jaypetez.ollamamobile.data.mapper.toEntity
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AttachmentRef
import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.Conversation
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.MessageStatus
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import io.github.jaypetez.ollamamobile.storage.dao.ConversationDao
import io.github.jaypetez.ollamamobile.storage.dao.MessageDao
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageStatusColumn
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A conversation plus the two list-level flags that live on the row rather
 * than on the domain type.
 *
 * `Conversation` in `:core-model` deliberately has no `pinned`/`archived`:
 * they are properties of the *list*, not of the thread, and a transcript
 * exported or replayed does not carry them. The list screen still has to draw
 * a pin, so it gets them here.
 */
data class ConversationSummary(
    val conversation: Conversation,
    val pinned: Boolean,
    val archived: Boolean,
)

/** One full-text hit, with the conversation it belongs to already resolved. */
data class MessageSearchHit(
    val message: ChatMessage,
    val conversationId: ConversationId,
    val conversationTitle: String,
)

/**
 * Conversations and messages, as the UI sees them.
 *
 * Reads are `Flow`s straight off Room, so a write anywhere re-emits
 * everywhere; writes are `suspend` and land on the IO dispatcher. Nothing here
 * returns a Room entity — see `DomainMappers`.
 */
@Singleton
class ConversationRepository
    @Inject
    constructor(
        private val conversationDao: ConversationDao,
        private val messageDao: MessageDao,
        // The clock is injected rather than read as System.currentTimeMillis()
        // at each call site, because ordering by `createdAt` is what the whole
        // transcript depends on and a test has to be able to control it. This
        // is :core-remote's binding rather than a second one of our own: two
        // clock abstractions in one graph is how half the app ends up on
        // virtual time and the other half on wall time.
        private val clock: WallClock,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Serialises the streaming writers.
         *
         * One assistant turn is written by one coroutine, but two turns in two
         * conversations can stream at once, and `appendContent` plus the
         * finalising update must not interleave with each other's read of the
         * same row.
         */
        private val streamLock = Mutex()

        // -------------------------------------------------------------------
        // Reads
        // -------------------------------------------------------------------

        fun observeConversations(includeArchived: Boolean = false): Flow<List<ConversationSummary>> {
            val source = if (includeArchived) conversationDao.observeAll() else conversationDao.observeActive()
            return source
                .map { rows ->
                    rows.map { ConversationSummary(it.toDomain(), pinned = it.pinned, archived = it.archived) }
                }.flowOn(io)
        }

        fun observeConversation(id: ConversationId): Flow<Conversation?> = conversationDao
            .observe(id.value)
            .map { it?.toDomain() }
            .flowOn(io)

        /**
         * The whole thread, oldest first, with attachments attached.
         *
         * Two flows combined rather than one query with a `@Relation`: Room
         * would have to materialise every message *and* every attachment into
         * one POJO on each change, and the attachment set changes far less
         * often than the message list does while a response streams.
         */
        fun observeMessages(id: ConversationId): Flow<List<ChatMessage>> = combine(
            messageDao.observeConversation(id.value),
            messageDao.observeAttachmentsForConversation(id.value),
        ) { messages, attachments ->
            val byMessage = attachments.groupBy { it.messageUuid }
            messages.map { row -> row.toDomain(byMessage[row.uuid].orEmpty().map { it.toDomain() }) }
        }.distinctUntilChanged()
            // Both queries name the `messages` table, so Room invalidates both
            // on every append and the combine re-emits an identical list. That
            // is a full recomposition of the transcript per token, which is
            // exactly what a streaming response must not cost.
            .flowOn(io)

        fun observeMessage(id: MessageId): Flow<ChatMessage?> = messageDao
            .observe(id.value)
            .map { it?.toDomain() }
            .flowOn(io)

        fun observeConversationCount(): Flow<Int> = conversationDao.observeCount().flowOn(io)

        suspend fun findConversation(id: ConversationId): Conversation? = withContext(io) {
            conversationDao.find(id.value)?.toDomain()
        }

        suspend fun findMessage(id: MessageId): ChatMessage? = withContext(io) {
            messageDao.find(id.value)?.toDomain()
        }

        suspend fun messages(id: ConversationId, limit: Int = HISTORY_LIMIT): List<ChatMessage> = withContext(io) {
            conversationDao.findMessages(id.value, limit).map { it.toDomain() }
        }

        // -------------------------------------------------------------------
        // Search
        // -------------------------------------------------------------------

        /**
         * FTS5 search across every conversation, ranked by bm25.
         *
         * The query text is sanitised inside `MessageDao` — an unescaped `"` or
         * a bare `NEAR` is a syntax error in FTS5's MATCH grammar, and a user
         * typing a quotation mark must not produce a crash.
         */
        fun searchMessages(query: String, limit: Int = MessageDao.DEFAULT_SEARCH_LIMIT): Flow<List<MessageSearchHit>> =
            messageDao
                .search(query, limit)
                .map { rows ->
                    // Titles are resolved per distinct conversation rather than
                    // per hit: fifty hits in one thread is one lookup, not fifty.
                    val titles = rows
                        .map { it.conversationId }
                        .distinct()
                        .associateWith { conversationDao.find(it)?.title.orEmpty() }
                    rows.map { row ->
                        MessageSearchHit(
                            message = row.toDomain(),
                            conversationId = ConversationId(row.conversationId),
                            conversationTitle = titles[row.conversationId].orEmpty(),
                        )
                    }
                }.flowOn(io)

        fun searchMessagesIn(
            conversationId: ConversationId,
            query: String,
            limit: Int = MessageDao.DEFAULT_SEARCH_LIMIT,
        ): Flow<List<ChatMessage>> = messageDao
            .searchInConversation(conversationId.value, query, limit)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

        // -------------------------------------------------------------------
        // Conversation writes
        // -------------------------------------------------------------------

        suspend fun createConversation(
            title: String = DEFAULT_TITLE,
            modelId: ModelId? = null,
            systemPrompt: String? = null,
            sampling: SamplingParams = SamplingParams.Default,
        ): Conversation = withContext(io) {
            val now = clock.nowMillis()
            val conversation = Conversation(
                id = ConversationId.random(),
                title = title,
                createdAt = now,
                updatedAt = now,
                modelId = modelId,
                systemPrompt = systemPrompt,
                sampling = sampling,
            )
            conversationDao.insert(conversation.toEntity())
            conversation
        }

        /**
         * Rewrites the thread's settings, preserving its list flags.
         *
         * Read-modify-write rather than an upsert of a freshly built row:
         * `pinned` and `archived` are not part of [Conversation], so a blind
         * upsert would silently un-pin a thread whose temperature the user
         * changed.
         */
        suspend fun updateConversation(conversation: Conversation): Unit = withContext(io) {
            val existing = conversationDao.find(conversation.id.value)
            conversationDao.upsert(
                conversation
                    .copy(updatedAt = clock.nowMillis())
                    .toEntity(
                        pinned = existing?.pinned == true,
                        archived = existing?.archived == true,
                    ),
            )
        }

        suspend fun setModel(id: ConversationId, modelId: ModelId?): Unit = withContext(io) {
            val existing = conversationDao.find(id.value) ?: return@withContext
            conversationDao.update(existing.copy(modelId = modelId?.value, updatedAt = clock.nowMillis()))
        }

        suspend fun setSystemPrompt(id: ConversationId, prompt: String?): Unit = withContext(io) {
            val existing = conversationDao.find(id.value) ?: return@withContext
            conversationDao.update(existing.copy(systemPrompt = prompt, updatedAt = clock.nowMillis()))
        }

        suspend fun setSampling(id: ConversationId, sampling: SamplingParams): Unit = withContext(io) {
            val existing = conversationDao.find(id.value) ?: return@withContext
            conversationDao.update(
                existing.copy(
                    temperature = sampling.temperature,
                    topP = sampling.topP,
                    topK = sampling.topK,
                    minP = sampling.minP,
                    repeatPenalty = sampling.repeatPenalty,
                    repeatLastN = sampling.repeatLastN,
                    seed = sampling.seed,
                    numPredict = sampling.numPredict,
                    numCtx = sampling.numCtx,
                    stopSequences = sampling.stop,
                    updatedAt = clock.nowMillis(),
                ),
            )
        }

        suspend fun rename(id: ConversationId, title: String): Unit = withContext(io) {
            conversationDao.rename(id.value, title, clock.nowMillis())
        }

        suspend fun setPinned(id: ConversationId, pinned: Boolean): Unit = withContext(io) {
            conversationDao.setPinned(id.value, pinned, clock.nowMillis())
        }

        suspend fun setArchived(id: ConversationId, archived: Boolean): Unit = withContext(io) {
            conversationDao.setArchived(id.value, archived, clock.nowMillis())
        }

        /** Messages, attachments and citations go with it: the foreign keys cascade. */
        suspend fun deleteConversation(id: ConversationId): Unit = withContext(io) {
            conversationDao.deleteById(id.value)
        }

        // -------------------------------------------------------------------
        // Message writes
        // -------------------------------------------------------------------

        /** Appends a finished turn — a user message, or an imported one. */
        suspend fun appendMessage(
            conversationId: ConversationId,
            role: Role,
            content: String,
            attachments: List<AttachmentRef> = emptyList(),
            modelId: ModelId? = null,
        ): ChatMessage = withContext(io) {
            val message = ChatMessage(
                id = MessageId.random(),
                conversationId = conversationId,
                role = role,
                content = content,
                createdAt = clock.nowMillis(),
                status = MessageStatus.Complete,
                attachments = attachments,
            )
            messageDao.insertWithAttachments(
                message = message.toEntity(modelId),
                attachments = attachments.map { it.toEntity(message.id) },
            )
            conversationDao.touch(conversationId.value, message.createdAt)
            message
        }

        suspend fun deleteMessage(id: MessageId): Unit = withContext(io) {
            messageDao.deleteByUuid(id.value)
        }

        /**
         * Writes a whole thread verbatim — the import half of export/import.
         *
         * Identities are regenerated by default. `uuid` is uniquely indexed, so
         * importing a file that came from this device would otherwise abort
         * halfway through on the first duplicate, leaving a conversation with
         * some of its messages. Regenerating also makes "import" mean "add a
         * copy", which is what a user who still has the original expects.
         *
         * Timestamps, roles, statuses and counters are preserved exactly:
         * re-dating an imported transcript to now would destroy the only
         * ordering information the file carries.
         */
        suspend fun importConversation(
            conversation: Conversation,
            messages: List<ChatMessage>,
            regenerateIds: Boolean = true,
        ): ConversationId = withContext(io) {
            val conversationId = if (regenerateIds) ConversationId.random() else conversation.id
            conversationDao.insert(conversation.copy(id = conversationId).toEntity())
            messages.forEach { message ->
                messageDao.insert(
                    message
                        .copy(
                            id = if (regenerateIds) MessageId.random() else message.id,
                            conversationId = conversationId,
                        ).toEntity(),
                )
            }
            conversationId
        }

        // -------------------------------------------------------------------
        // The streaming turn
        // -------------------------------------------------------------------

        /**
         * Opens an assistant turn and returns the handle that finishes it.
         *
         * The row exists, with `status = pending` and empty content, *before*
         * the first token arrives. That ordering is the point: if the row were
         * created on first token, a process killed during model load would
         * leave a user message with no reply and no evidence that one was ever
         * attempted, and the UI would offer no way to retry.
         *
         * Every exit from a stream — completion, mid-stream error, cancellation
         * — must call one of [StreamingTurn.complete] or [StreamingTurn.fail].
         * A turn abandoned without either is recovered on next launch by
         * [recoverInterruptedTurns].
         */
        suspend fun beginAssistantTurn(
            conversationId: ConversationId,
            modelId: ModelId? = null,
        ): StreamingTurn = withContext(io) {
            val id = MessageId.random()
            val now = clock.nowMillis()
            messageDao.insert(
                MessageEntity(
                    uuid = id.value,
                    conversationId = conversationId.value,
                    role = Role.ASSISTANT.wireName,
                    content = "",
                    createdAt = now,
                    status = MessageStatusColumn.PENDING,
                    modelId = modelId?.value,
                ),
            )
            conversationDao.touch(conversationId.value, now)
            StreamingTurn(id, conversationId)
        }

        /**
         * Flips every turn still marked pending to failed, keeping its text.
         *
         * Call once at startup. A pending row is renderable — `MessageStatus`
         * has a case for it — but it renders as a caret that blinks forever,
         * because the coroutine that would have finished it died with the
         * process. Marking it failed keeps whatever text did land and gives the
         * UI something to offer a retry from.
         *
         * @return how many turns were recovered.
         */
        suspend fun recoverInterruptedTurns(): Int = withContext(io) {
            val stranded = messageDao.findByStatus(MessageStatusColumn.PENDING)
            stranded.forEach { row ->
                messageDao.finish(
                    uuid = row.uuid,
                    status = MessageStatusColumn.FAILED,
                    errorMessage = INTERRUPTED_MESSAGE,
                    completionTokens = row.completionTokens,
                    evalNanos = row.evalNanos,
                    totalNanos = row.totalNanos,
                )
            }
            stranded.size
        }

        /**
         * A half-written assistant turn.
         *
         * ## Why the text is buffered rather than written per token
         *
         * `messages` is the content table of an FTS5 external-content index,
         * and its `AFTER UPDATE` trigger deletes and reinserts the row's tokens
         * on every write. One write per delta means one full reindex of the
         * message per delta — several hundred for a normal answer, each one
         * larger than the last. Buffering to [FLUSH_THRESHOLD_CHARS] bounds the
         * loss on a process kill to the last fragment of a sentence while
         * turning the reindex count from O(tokens) into O(length / 256).
         *
         * The flush itself is `content = content || :delta` in SQL, so a reader
         * never observes a half-applied token and the two writers of a row
         * cannot lose each other's text.
         */
        inner class StreamingTurn internal constructor(
            val messageId: MessageId,
            val conversationId: ConversationId,
        ) {
            private val buffered = StringBuilder()
            private val reasoning = StringBuilder()
            private var finished = false

            /** Everything appended so far, flushed or not. */
            val pendingLength: Int get() = buffered.length

            /** Buffers display text, flushing once it is worth a write. */
            suspend fun append(delta: String) {
                if (delta.isEmpty()) return
                withContext(io) {
                    streamLock.withLock {
                        buffered.append(delta)
                        if (buffered.length >= FLUSH_THRESHOLD_CHARS) flushLocked()
                    }
                }
            }

            /**
             * Accumulates reasoning in memory only.
             *
             * It is written once, at the end. Chain-of-thought is frequently an
             * order of magnitude longer than the answer, it is collapsed in the
             * UI, and it is not in the FTS index — so streaming it to disk buys
             * nothing and costs a write per delta.
             */
            fun appendReasoning(delta: String) {
                reasoning.append(delta)
            }

            /** Flushes and marks the turn complete. Idempotent. */
            suspend fun complete(stats: GenerationStats? = null) {
                finish(MessageStatusColumn.COMPLETE, errorMessage = null, stats = stats)
            }

            /**
             * Flushes and marks the turn failed, keeping the partial answer.
             *
             * The text is written *before* the status flips, so a crash between
             * the two leaves a pending row that [recoverInterruptedTurns] will
             * fix rather than a "complete" row that is missing its last words.
             */
            suspend fun fail(error: AppError, stats: GenerationStats? = null) {
                finish(MessageStatusColumn.FAILED, errorMessage = error.message, stats = stats)
            }

            private suspend fun finish(status: String, errorMessage: String?, stats: GenerationStats?) {
                withContext(io) {
                    streamLock.withLock {
                        if (finished) return@withLock
                        finished = true
                        flushLocked()
                        val row = messageDao.find(messageId.value) ?: return@withLock
                        messageDao.update(
                            row.copy(
                                status = status,
                                errorMessage = errorMessage,
                                reasoning = reasoning.toString().takeIf { it.isNotEmpty() },
                                promptTokens = stats?.promptTokens,
                                completionTokens = stats?.completionTokens,
                                promptEvalNanos = stats?.promptEvalNanos,
                                evalNanos = stats?.evalNanos,
                                loadNanos = stats?.loadNanos,
                                totalNanos = stats?.totalNanos,
                            ),
                        )
                        conversationDao.touch(conversationId.value, clock.nowMillis())
                    }
                }
            }

            private suspend fun flushLocked() {
                if (buffered.isEmpty()) return
                val delta = buffered.toString()
                buffered.setLength(0)
                messageDao.appendContent(messageId.value, delta)
            }
        }

        companion object {
            /** Shown until the first turn gives the thread a real name. */
            const val DEFAULT_TITLE: String = "New chat"

            /** How much history `messages()` replays by default. */
            const val HISTORY_LIMIT: Int = 200

            /**
             * Characters buffered before a streamed turn hits the database.
             *
             * Roughly a sentence: small enough that a process kill loses text
             * nobody had finished reading, large enough that the FTS reindex
             * cost stops scaling with the token count.
             */
            const val FLUSH_THRESHOLD_CHARS: Int = 256

            internal const val INTERRUPTED_MESSAGE: String =
                "This response was interrupted before it finished."
        }
    }
