package io.github.jaypetez.ollamamobile.llm.testing

import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.InferenceTarget
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.asException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * A [LlamaEngine] that answers from a script.
 *
 * ## What this is for
 *
 * Every consumer of on-device inference — the router, the gateway, a RAG
 * indexer, a ViewModel — has logic worth testing that has nothing to do with
 * matrix multiplication: what it does with a partial answer, how it handles a
 * failure halfway through, whether it cancels correctly, what it shows while
 * loading. None of that needs llama.cpp, and making it need llama.cpp would
 * mean an NDK on every CI runner and an arm64 device to run the tests on.
 *
 * This is shipped as a **normal artifact**, not a test fixture, so `:app`'s
 * debug build can bind it too and the whole local-inference UI can be exercised
 * on an emulator with no model file present.
 *
 * ## Deterministic by construction
 *
 * There is no randomness and no wall-clock timing anywhere below. The same
 * script produces the same events in the same order every run, which is the
 * only property that makes a streaming assertion worth writing. [tokenDelay] is
 * a `delay`, so `runTest`'s virtual clock skips it entirely and a test of a
 * 500-token stream still finishes instantly.
 */
class FakeLlamaEngine(
    /** Chunks emitted as [InferenceEvent.Token], in order, for every request. */
    private val script: List<String> = DEFAULT_SCRIPT,
    /** What ends a successful stream. */
    private val finishReason: FinishReason = FinishReason.STOP,
    /** Emitted between [InferenceEvent.Stats] and the terminal event when set. */
    private val failAfterTokens: Int? = null,
    private val failure: AppError = AppError.Engine.GenerationFailed("scripted failure"),
    /** Virtual-time gap between tokens. Zero by default. */
    private val tokenDelay: Long = 0L,
    private val stats: GenerationStats? = DEFAULT_STATS,
    override val isAvailable: Boolean = true,
    /** Thrown by [load]. Lets a test drive the "this model will not open" path. */
    private val loadFailure: AppError? = null,
) : LlamaEngine {
    private val _loadedModel = MutableStateFlow<ModelRef?>(null)

    override val loadedModel: StateFlow<ModelRef?> = _loadedModel.asStateFlow()

    /** Every request this engine has been asked to run, oldest first. */
    val requests: MutableList<InferenceRequest> = mutableListOf()

    /** Every spec passed to [load], oldest first. */
    val loads: MutableList<ModelLoadSpec> = mutableListOf()

    var unloadCount: Int = 0
        private set

    override suspend fun load(spec: ModelLoadSpec) {
        loads += spec
        loadFailure?.let { throw it.asException() }
        _loadedModel.value = spec.model
    }

    override suspend fun unload() {
        unloadCount += 1
        _loadedModel.value = null
    }

    override fun generate(request: InferenceRequest): Flow<InferenceEvent> = flow {
        requests += request
        val model = _loadedModel.value
        if (!isAvailable || model == null) {
            emit(InferenceEvent.Failed(AppError.Engine.NotAvailable()))
            return@flow
        }
        emit(InferenceEvent.Started(InferenceTarget.Local(model.id)))

        script.forEachIndexed { index, chunk ->
            // The cooperative check a real engine makes between tokens. Without
            // it a test of cancellation would pass against this fake and fail
            // against the real thing, which is worse than having no fake.
            currentCoroutineContext().ensureActive()
            if (tokenDelay > 0) delay(tokenDelay)
            emit(InferenceEvent.Token(chunk))
            if (failAfterTokens == index + 1) {
                emit(InferenceEvent.Failed(failure))
                return@flow
            }
        }

        stats?.let { emit(InferenceEvent.Stats(it)) }
        emit(InferenceEvent.Completed(finishReason))
    }

    /**
     * A stable vector derived from [text].
     *
     * Deterministic and cheap, and deliberately *not* uniform: identical text
     * embeds identically and different text embeds differently, which is the
     * only property a similarity-search test can rely on. It is not a real
     * embedding and nothing about its geometry is meaningful.
     */
    override suspend fun embed(text: String): FloatArray {
        if (!isAvailable) throw AppError.Engine.NotAvailable().asException()
        val seed = text.hashCode()
        return FloatArray(EMBEDDING_DIMENSIONS) { index ->
            val mixed = seed * MIX_A + index * MIX_B
            ((mixed % SCALE).toFloat() / SCALE) - HALF
        }
    }

    /**
     * Whitespace-delimited words.
     *
     * Not an attempt to model BPE. A test that depends on the exact count of a
     * real tokenizer is a test that breaks when the model changes, which is not
     * a signal anybody wants.
     */
    override suspend fun tokenCount(text: String): Int =
        text.split(WHITESPACE).count { it.isNotEmpty() }

    companion object {
        val DEFAULT_SCRIPT: List<String> = listOf("Hello", ", ", "world", "!")

        val DEFAULT_STATS: GenerationStats = GenerationStats(
            promptTokens = 12,
            completionTokens = 4,
            promptEvalNanos = 120_000_000L,
            evalNanos = 400_000_000L,
        )

        private val WHITESPACE = Regex("\\s+")
        private const val EMBEDDING_DIMENSIONS = 8
        private const val MIX_A = 31
        private const val MIX_B = 17
        private const val SCALE = 1000
        private const val HALF = 0.5f

        /** An engine that emits [InferenceEvent.Started] and then never a token. */
        fun silent(): FakeLlamaEngine = FakeLlamaEngine(script = emptyList(), stats = null)

        /** An engine whose stream is cut short. The tokens already emitted stand. */
        fun failing(
            afterTokens: Int = 1,
            error: AppError = AppError.Engine.GenerationFailed("scripted failure"),
        ): FakeLlamaEngine = FakeLlamaEngine(failAfterTokens = afterTokens, failure = error)

        /** An engine that behaves like a build with `-Pollama.nativeSource=none`. */
        fun unavailable(): FakeLlamaEngine = FakeLlamaEngine(isAvailable = false)

        /**
         * An engine that never finishes on its own.
         *
         * Emits [count] tokens one virtual second apart so a test can cancel
         * partway and assert on what was delivered. Cancelling it raises a
         * `CancellationException` out of the collector, as the real engine does.
         */
        fun slow(count: Int = 100): FakeLlamaEngine = FakeLlamaEngine(
            script = List(count) { "tok$it " },
            tokenDelay = 1_000L,
        )
    }
}
