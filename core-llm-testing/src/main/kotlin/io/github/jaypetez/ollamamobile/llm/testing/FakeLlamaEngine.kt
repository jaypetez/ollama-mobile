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

    /**
     * Every string passed to [embed], oldest first.
     *
     * Recorded because the task prefix is applied several layers above the
     * engine and there is no other way to prove it survived the trip. A test
     * that only checks the returned vectors cannot tell a missing prefix from a
     * present one — which is the entire problem prefixes have.
     */
    val embedCalls: MutableList<String> = mutableListOf()

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
     * A deterministic bag-of-words embedding that is *prefix sensitive on purpose*.
     *
     * ## Why this is not a hash of the whole string
     *
     * A pure hash gives "same text embeds the same, different text differs",
     * which is enough to test a cache and useless for testing retrieval: every
     * pair of distinct chunks is equidistant, so top-k is arbitrary and a
     * ranking assertion cannot fail. Retrieval tests need *geometry* — texts
     * that share words must be closer than texts that do not. So the content
     * block is a feature hash of the tokens, which gives exactly that.
     *
     * ## Why the prefix changes where tokens land
     *
     * This is a **simulation** of one real, load-bearing property: these models
     * are asymmetric, and the task prefix is the instruction that puts a query
     * and a passage into the *same* representation. Deprived of the instruction,
     * a real encoder falls back on surface form — and a six-word question and a
     * three-hundred-word passage have almost none in common, so the two land in
     * regions that do not line up. That is the documented failure the prefixes
     * exist to fix, and it is silent: the vectors are finite, the cosines are
     * plausible, only the ranking is wrong.
     *
     * So: text carrying a recognised task prefix is hashed with one shared salt,
     * and query and passage are directly comparable. Text carrying none is
     * hashed with a salt derived from its length band, so a short query and a
     * long passage are projected differently and their overlap mostly vanishes.
     * A pipeline that forgets to prefix therefore retrieves measurably worse
     * here, exactly as it would in production.
     *
     * This models the failure; it does not reproduce any real model's numbers.
     * Nothing about the absolute cosines means anything.
     */
    override suspend fun embed(text: String): FloatArray {
        if (!isAvailable) throw AppError.Engine.NotAvailable().asException()
        embedCalls += text

        val prefixEnd = recognisedPrefixEnd(text)
        val body = if (prefixEnd > 0) text.substring(prefixEnd) else text
        val tokens = body.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

        // The salt is the whole mechanism. Prefixed text shares one; unprefixed
        // text gets one per length band, so query-shaped and passage-shaped
        // inputs stop being comparable.
        val salt = if (prefixEnd > 0) {
            ALIGNED_SALT
        } else {
            ALIGNED_SALT + 1 + (tokens.size / FORM_BAND_TOKENS).coerceAtMost(MAX_FORM_BANDS - 1)
        }

        val vector = FloatArray(EMBEDDING_DIMENSIONS)
        for (token in tokens) {
            val hash = token.hashCode() * PRIME + salt * SALT_STRIDE
            val bucket = Math.floorMod(hash, EMBEDDING_DIMENSIONS)
            // The sign bit spreads tokens that collide into the same bucket, so
            // a collision degrades a score instead of inventing a match.
            val sign = if (hash and 1 == 0) 1f else -1f
            vector[bucket] += sign
        }

        var norm = 0.0
        for (value in vector) norm += value.toDouble() * value
        if (norm == 0.0) return vector
        val inverse = (1.0 / kotlin.math.sqrt(norm)).toFloat()
        for (index in vector.indices) vector[index] *= inverse
        return vector
    }

    /**
     * Where a recognised task prefix ends, or 0 if there is none.
     *
     * Deliberately generic: it matches the *shape* every one of these models
     * uses — a short instruction terminated by a colon before any sentence
     * punctuation — rather than a hard-coded list of nomic's and
     * EmbeddingGemma's exact strings. Hard-coding them would make this fake
     * agree only with the prefixes it already knows, so a wrong-but-present
     * prefix for some third model would look correct here and fail in the
     * field. The fake's job is to punish *absence*, not to grade wording.
     */
    private fun recognisedPrefixEnd(text: String): Int {
        val colon = text.indexOf(':')
        if (colon <= 0 || colon > MAX_PREFIX_CHARS) return 0
        val candidate = text.substring(0, colon)
        if (candidate.any { it in SENTENCE_PUNCTUATION }) return 0
        return colon + 1
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
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** Small enough to read in a failure message, wide enough to limit collisions. */
        private const val EMBEDDING_DIMENSIONS = 64

        /** Token count per length band. 8 keeps a query and a long chunk in different bands. */
        private const val FORM_BAND_TOKENS = 8
        private const val MAX_FORM_BANDS = 16

        /** The salt every correctly prefixed text shares. */
        private const val ALIGNED_SALT = 0

        private const val PRIME = 31
        private const val SALT_STRIDE = 0x9E3779B1.toInt()

        /** A task instruction is short. Past this a colon is punctuation in prose. */
        private const val MAX_PREFIX_CHARS = 64

        /** Any of these before the colon means it was prose, not an instruction. */
        private val SENTENCE_PUNCTUATION = setOf('.', '?', '!', '\n')

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
