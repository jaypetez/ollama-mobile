package io.github.jaypetez.ollamamobile.llm.internal

import android.util.Log
import io.github.jaypetez.ollamamobile.llm.EngineRole
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.llm.internal.lora.LoraAdapterManager
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.model.asException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [LlamaEngine] over one native session handle.
 *
 * ## Threading
 *
 * Every call that touches the native context runs on [engineDispatcher], which
 * is backed by **one** OS thread for the life of the engine. Two reasons, and
 * the first is not the interesting one:
 *
 *  * a `llama_context` is not thread-safe, so the calls have to be serialised
 *    somewhere;
 *  * `llama_decode` blocks for seconds. Parking that on `Dispatchers.IO` takes
 *    one of a shared, bounded pool of threads out of circulation for the whole
 *    prefill, which stalls unrelated disk and network work. A private thread
 *    costs 1 MB of stack and cannot starve anything else.
 *
 * The token loop is a plain blocking `while` on that thread. It exists as a
 * `Flow` to the caller and as a synchronous pull loop to llama.cpp, which is
 * the whole point of the pull design — see [NativeSessionApi.nativeGenerateNextToken].
 *
 * ## Cancellation, in two layers
 *
 * A collector that stops collecting has to stop a generation that is blocked
 * inside a multi-second `llama_decode`, on a thread that cannot check anything
 * while it is in there.
 *
 *  1. **Between tokens** — `ensureActive()` at the top of each loop iteration.
 *     Cheap, and enough for the token-by-token phase where each `llama_decode`
 *     is milliseconds.
 *  2. **During a decode** — an `invokeOnCompletion` handler on the flow's own
 *     job calls `nativeRequestAbort` from *the cancelling thread*, setting an
 *     atomic that ggml's abort callback reads between graph nodes. Without
 *     this, cancelling during the prefill of a long prompt does nothing at all
 *     until the prefill finishes on its own, which is exactly the case where
 *     the user is most likely to give up and press stop.
 */
internal class NativeLlamaEngine(
    private val loader: NativeLibraryLoader,
    private val session: NativeSessionApi,
    private val arbiter: InferenceArbiter,
    private val loraAdapters: LoraAdapterManager,
    private val engineDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) : LlamaEngine {
    private val _loadedModel = MutableStateFlow<ModelRef?>(null)

    /** Guarded by [engineDispatcher]'s single thread; 0 means nothing is loaded. */
    private var handle: Long = 0L

    private var role: EngineRole = EngineRole.CHAT

    override val isAvailable: Boolean
        get() = loader.status is NativeStatus.Ready

    override val loadedModel: StateFlow<ModelRef?> = _loadedModel.asStateFlow()

    override suspend fun load(spec: ModelLoadSpec) {
        val ready = requireReady()
        arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) {
            withContext(engineDispatcher) {
                releaseHandle()
                val created = session.nativeCreateSession(
                    modelPath = spec.path,
                    contextTokens = spec.contextTokens,
                    threads = if (spec.threads > 0) spec.threads else defaultThreadCount(),
                    batchTokens = if (spec.batchTokens > 0) spec.batchTokens else DEFAULT_BATCH,
                    embeddingMode = spec.role == EngineRole.EMBEDDING,
                    useMmap = spec.useMmap,
                )
                if (created == 0L) {
                    throw EngineErrors.loadFailure(session.nativeLastError(0L)).asException()
                }
                handle = created
                role = spec.role
                applyAdapters(created, spec)
                _loadedModel.value = spec.model
            }
        }
        // Only interesting when the sentinel forced us here, and worth a line
        // in the log ring so a bug report says which backend actually ran.
        if (ready.mode == BackendMode.SAFE_BASELINE) {
            Log.w(
                TAG,
                "Loaded ${spec.model.name} on the baseline CPU backend after a previous crash.",
            )
        }
    }

    override suspend fun unload() {
        withContext(engineDispatcher) { releaseHandle() }
        _loadedModel.value = null
    }

    override fun generate(request: InferenceRequest): Flow<InferenceEvent> = flow {
        val model = _loadedModel.value
        val current = handle
        if (current == 0L || model == null) {
            emit(
                InferenceEvent.Failed(
                    EngineErrors.notAvailable("No model is loaded in the on-device engine."),
                ),
            )
            return@flow
        }
        if (role != EngineRole.CHAT) {
            emit(
                InferenceEvent.Failed(
                    AppError.Model.Unsupported("this engine holds an embedding model"),
                ),
            )
            return@flow
        }
        emit(InferenceEvent.Started(InferenceTarget.Local(model.id)))

        arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) {
            runGeneration(current, request)
        }
    }.flowOn(engineDispatcher)

    override suspend fun embed(text: String): FloatArray {
        requireReady()
        return arbiter.withAccess(InferenceArbiter.Priority.EMBEDDING) {
            withContext(engineDispatcher) {
                val current = handle
                if (current == 0L || role != EngineRole.EMBEDDING) {
                    throw AppError.Model
                        .Unsupported("no embedding model is loaded in this engine")
                        .asException()
                }
                session.nativeEmbed(current, text)
                    ?: throw EngineErrors
                        .generationFailure(session.nativeLastError(current))
                        .asException()
            }
        }
    }

    /**
     * Not serialised through the arbiter, and not on the engine thread.
     *
     * `llama_tokenize` is documented thread-safe and the JNI entry point takes
     * no session lock, so counting tokens while a generation runs is legal.
     * That matters: history trimming happens *while* deciding what to send, and
     * queueing it behind an in-flight generation would make composing a message
     * wait for the previous answer.
     */
    override suspend fun tokenCount(text: String): Int = withContext(ioDispatcher) {
        val current = handle
        if (current == 0L) 0 else session.nativeTokenCount(current, text).coerceAtLeast(0)
    }

    private fun requireReady(): NativeStatus.Ready = when (val status = loader.status) {
        is NativeStatus.Ready -> status
        is NativeStatus.Unavailable -> throw status.error.asException()
    }

    private suspend fun applyAdapters(current: Long, spec: ModelLoadSpec) {
        val adapters = spec.loraAdapters + loraAdapters.adaptersFor(spec.model)
        if (adapters.isEmpty()) return
        val applied = session.nativeSetLoraAdapters(
            handle = current,
            paths = adapters.map { it.path }.toTypedArray(),
            scales = adapters.map { it.scale }.toFloatArray(),
        )
        if (!applied) {
            throw EngineErrors.loadFailure(session.nativeLastError(current)).asException()
        }
    }

    private fun releaseHandle() {
        if (handle != 0L) {
            session.nativeDestroySession(handle)
            handle = 0L
        }
    }

    private suspend fun FlowCollector<InferenceEvent>.runGeneration(
        current: Long,
        request: InferenceRequest,
    ) {
        val prompt = renderPrompt(current, request)
        if (prompt == null) {
            emit(
                InferenceEvent.Failed(
                    AppError.Model.Unsupported(
                        reason = session.nativeLastError(current)
                            ?: "the model carries no usable chat template",
                    ),
                ),
            )
            return
        }

        configureSampler(current, request.sampling)
        val maxTokens = request.sampling.numPredict ?: 0
        if (!session.nativeStartGeneration(current, prompt, maxTokens)) {
            emit(
                InferenceEvent.Failed(
                    EngineErrors.generationFailure(session.nativeLastError(current)),
                ),
            )
            return
        }

        // Layer two of cancellation, and the only one that can do anything
        // while this coroutine is parked inside llama_decode.
        //
        // A watchdog coroutine on another dispatcher rather than
        // Job.invokeOnCompletion on this one. invokeOnCompletion fires when the
        // job *completes*, and a job whose body is blocked in a JNI call cannot
        // complete — so the abort would arrive after the decode it was supposed
        // to interrupt, which is to say never. The watchdog is suspended in
        // awaitCancellation(), so its `finally` runs the instant cancellation
        // is delivered, on a thread that is not this one.
        val aborted = AtomicBoolean(false)
        val finished = AtomicBoolean(false)

        fun abortOnce() {
            if (aborted.compareAndSet(false, true)) session.nativeRequestAbort(current)
        }

        var reachedEnd = false
        coroutineScope {
            val watchdog = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) abortOnce()
                }
            }
            try {
                pumpTokens(current)
                reachedEnd = true
            } finally {
                // Ordered: mark first, then stop the watchdog, so cancelling it
                // on the happy path cannot be mistaken for a real cancellation.
                // On the unhappy path — a collector that threw, or a `take`
                // that unwound through emit() while the job is still active —
                // this frame is the only thing that runs, so it aborts itself.
                if (reachedEnd) finished.set(true) else abortOnce()
                watchdog.cancel()
            }
        }

        emitStats(current)
        val code = session.nativeFinishReason(current)
        if (NativeFinishReason.isFailure(code)) {
            emit(
                InferenceEvent.Failed(
                    EngineErrors.generationFailure(session.nativeLastError(current)),
                ),
            )
        } else {
            emit(InferenceEvent.Completed(NativeFinishReason.toFinishReason(code)))
        }
    }

    private suspend fun FlowCollector<InferenceEvent>.pumpTokens(current: Long) {
        // Armed immediately before the first llama_decode, which is the first
        // moment a SIGILL in a ggml kernel becomes possible.
        loader.armSentinel()
        var sawToken = false
        while (true) {
            // Layer one of cancellation.
            currentCoroutineContext().ensureActive()
            val bytes = session.nativeGenerateNextToken(current) ?: break
            if (!sawToken) {
                // The first token proves the chosen kernels execute on this
                // CPU. Everything the sentinel guards against has already
                // either happened or not.
                sawToken = true
                loader.disarmSentinel()
            }
            // An empty array is not the end: that token completed no code point
            // and its bytes are carried into the next one.
            if (bytes.isNotEmpty()) {
                emit(InferenceEvent.Token(bytes.toString(Charsets.UTF_8)))
            }
        }
        // Nothing came out at all — a prompt that produced an immediate
        // end-of-generation, or a failure. Either way the sentinel has served
        // its purpose and must not survive into the next launch.
        loader.disarmSentinel()
    }

    private suspend fun FlowCollector<InferenceEvent>.emitStats(current: Long) {
        val raw = session.nativeStats(current) ?: return
        if (raw.size < STATS_FIELDS) return
        emit(
            InferenceEvent.Stats(
                GenerationStats(
                    promptTokens = raw[0].toInt(),
                    completionTokens = raw[1].toInt(),
                    // Absent stays absent: a zero here would render as
                    // "0 tok/s" for something nobody measured.
                    promptEvalNanos = raw[2].takeIf { it > 0 },
                    evalNanos = raw[3].takeIf { it > 0 },
                    loadNanos = raw[4].takeIf { it > 0 },
                ),
            ),
        )
    }

    /**
     * Renders the request through the model's own Jinja chat template.
     *
     * Not string concatenation. A GGUF's `tokenizer.chat_template` is real
     * Jinja2 and the special tokens it emits are what the model was trained on;
     * getting them wrong does not throw, it quietly degrades the answer in a
     * way that reads as the model being bad.
     *
     * Two things are deliberately not passed down yet, and both would be
     * silently wrong rather than loud if faked: [InferenceRequest.tools], which
     * are JSON Schema documents and need a codec on both sides of JNI, and
     * image attachments, which need a vision projector this engine does not
     * load. Requests carrying either still run as plain text.
     */
    private fun renderPrompt(current: Long, request: InferenceRequest): String? {
        val roles = ArrayList<String>(request.messages.size + 1)
        val contents = ArrayList<String>(request.messages.size + 1)
        request.systemPrompt?.let {
            roles += "system"
            contents += it
        }
        request.messages.forEach { message ->
            roles += message.role.name.lowercase()
            contents += message.content
        }
        return session.nativeApplyChatTemplate(
            handle = current,
            roles = roles.toTypedArray(),
            contents = contents.toTypedArray(),
            addAssistant = true,
            enableThinking = request.wantReasoning,
        )
    }

    /**
     * Translates [SamplingParams] into a llama.cpp sampler chain.
     *
     * Every field of [SamplingParams] is nullable and null means "the engine's
     * default", never zero — so the fallbacks here are llama.cpp's own defaults
     * rather than neutral values. A `topK = 0` sent because the user set
     * nothing would disable top-k entirely, which is a different request.
     */
    private fun configureSampler(current: Long, sampling: SamplingParams) {
        session.nativeConfigureSampler(
            handle = current,
            temperature = sampling.temperature?.toFloat() ?: DEFAULT_TEMPERATURE,
            topP = sampling.topP?.toFloat() ?: DEFAULT_TOP_P,
            topK = sampling.topK ?: DEFAULT_TOP_K,
            minP = sampling.minP?.toFloat() ?: DEFAULT_MIN_P,
            repeatPenalty = sampling.repeatPenalty?.toFloat() ?: DEFAULT_REPEAT_PENALTY,
            repeatLastN = sampling.repeatLastN ?: DEFAULT_REPEAT_LAST_N,
            // -1 rather than 0: 0 is a perfectly good fixed seed, so it cannot
            // also mean "pick one at random".
            seed = sampling.seed ?: -1L,
        )
    }

    internal companion object {
        private const val TAG = "NativeLlamaEngine"
        private const val STATS_FIELDS = 5
        private const val DEFAULT_BATCH = 512
        private const val DEFAULT_TEMPERATURE = 0.8f
        private const val DEFAULT_TOP_P = 0.95f
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_MIN_P = 0.05f
        private const val DEFAULT_REPEAT_PENALTY = 1.1f
        private const val DEFAULT_REPEAT_LAST_N = 64
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 6

        /**
         * Threads for generation when the caller does not choose.
         *
         * Half the reported cores, clamped. `availableProcessors()` on a
         * big.LITTLE phone counts efficiency cores that finish their share of a
         * matmul late and stall every other thread at the next barrier, so
         * spawning one thread per core is reliably slower than spawning fewer.
         * Half is a heuristic that approximates "the big cluster" without
         * needing to identify it — and it is a heuristic, not a measurement,
         * because there is no device here to measure on.
         */
        fun defaultThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(MIN_THREADS, MAX_THREADS)
    }
}
