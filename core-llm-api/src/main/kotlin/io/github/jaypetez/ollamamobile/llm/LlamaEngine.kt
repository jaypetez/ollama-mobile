package io.github.jaypetez.ollamamobile.llm

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ModelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * On-device inference, as the rest of the app is allowed to see it.
 *
 * ## Why the contract lives here rather than next to the implementation
 *
 * `:core-llm` is the only module that may see llama.cpp, and `checkModuleGraph`
 * lets only `:app`, `:core-llm` itself and `:benchmark` depend on it. A
 * consumer — `:core-data`'s gateway, a RAG indexer, a test — therefore cannot
 * name `NativeLlamaEngine`. It can name this, because this module is pure JVM
 * with no Android types and no native anything.
 *
 * The same property is what makes `-Pollama.nativeSource=none` work: the
 * binding swaps to `StubLlamaEngine` and nothing above this interface changes
 * or even recompiles.
 *
 * ## Contract
 *
 * * [generate] returns a **cold** flow that does not throw. Every failure is an
 *   [InferenceEvent.Failed] and the stream ends there. `CancellationException`
 *   is the only exception that crosses, because structured concurrency needs
 *   it to.
 * * [load], [unload], [embed] and [tokenCount] are suspending and *do* throw —
 *   an [AppErrorException] wrapping a typed [AppError]. They are one-shot calls
 *   with one outcome, and a caller that has to `when` over a result type for
 *   "the file is missing" writes more code than one that catches.
 * * At most one model is loaded per engine instance. Chat and embeddings are
 *   two instances, which is the whole reason the JNI layer keeps no globals.
 * * Implementations serialise their own work; concurrent [generate] collections
 *   on one instance are not undefined, they are queued.
 */
public interface LlamaEngine {
    /**
     * Whether this build can run anything at all.
     *
     * False for the stub engine. Read it to decide whether to *offer* local
     * inference; do not use it to decide whether a specific model will load,
     * which is [load]'s job and depends on the file and on free memory.
     */
    public val isAvailable: Boolean

    /**
     * The currently loaded model, or null.
     *
     * A [StateFlow] because the UI reads it on every recomposition to label the
     * engine and cannot await I/O to do so.
     */
    public val loadedModel: StateFlow<ModelRef?>

    /**
     * Loads [spec], replacing whatever was loaded before.
     *
     * **Not interruptible.** Cancelling the calling coroutine abandons the
     * `await`, but the native load runs to completion on the engine thread
     * regardless — llama.cpp's abort hook is reached through a session handle,
     * and during creation there is not one yet. On a multi-gigabyte model that
     * is a real wait, so do not offer a cancel button that would lie.
     *
     * @throws AppErrorException with [AppError.Engine.NotAvailable],
     *   [AppError.Engine.LoadFailed], [AppError.Model.Unsupported] or
     *   [AppError.Model.InsufficientMemory].
     */
    public suspend fun load(spec: ModelLoadSpec)

    /** Frees the model and its context. Idempotent, and never throws. */
    public suspend fun unload()

    /**
     * Runs one turn against the loaded model.
     *
     * Fails with [AppError.Engine.NotAvailable] rather than throwing when
     * nothing is loaded, so a caller has exactly one failure path to handle.
     */
    public fun generate(request: InferenceRequest): Flow<InferenceEvent>

    /**
     * Embeds [text] with a model loaded as [EngineRole.EMBEDDING].
     *
     * @throws AppErrorException when the loaded model is a chat model, because
     *   a generative context produces no pooled embedding and returning zeroes
     *   would poison a vector index silently.
     */
    public suspend fun embed(text: String): FloatArray

    /**
     * Counts tokens the way the loaded model's tokenizer counts them.
     *
     * This is the only honest way to trim a history to fit a context window; a
     * characters-divided-by-four estimate is wrong by a factor that varies with
     * language and is worst for exactly the non-English text where truncation
     * hurts most.
     */
    public suspend fun tokenCount(text: String): Int
}

/** What a loaded model is for. Decides pooling and whether generation is legal. */
public enum class EngineRole {
    /** Causal generation. [LlamaEngine.embed] is rejected. */
    CHAT,

    /** Pooled embeddings for RAG. [LlamaEngine.generate] is rejected. */
    EMBEDDING,
}

/**
 * Everything the engine needs to open a model.
 *
 * [path] is a real filesystem path, never a `content://` URI: weights are
 * memory-mapped, and a Storage Access Framework descriptor carries no promise
 * of a stable, seekable, mappable region. Import copies the file first. See
 * docs/architecture/jni-boundary.md.
 */
public data class ModelLoadSpec(
    public val model: ModelRef,
    public val path: String,
    public val role: EngineRole = EngineRole.CHAT,
    /** 0 means "whatever the GGUF was trained with". */
    public val contextTokens: Int = 0,
    /**
     * 0 means "let the engine choose".
     *
     * Choosing badly is easy on big.LITTLE: one thread per
     * `availableProcessors()` schedules work onto efficiency cores that finish
     * late and stall every other thread at the next barrier.
     */
    public val threads: Int = 0,
    /** Prompt-processing batch size. 0 means the engine default. */
    public val batchTokens: Int = 0,
    /**
     * False forces a full read into anonymous memory instead of an mmap.
     *
     * Only useful on a filesystem where mapping is unreliable; it roughly
     * doubles peak memory during load and makes the pages dirty, so the kernel
     * can no longer evict them under pressure.
     */
    public val useMmap: Boolean = true,
    public val loraAdapters: List<LoraAdapterSpec> = emptyList(),
)

/**
 * One LoRA adapter and the weight it is applied at.
 *
 * A list of these rather than a single adapter because llama.cpp's
 * `llama_set_adapters_lora` takes an array with parallel scales, and stacking
 * (a style adapter at 0.7 plus a domain adapter at 0.3) is an ordinary thing to
 * want. Shaping the signature for one adapter now would mean breaking it later.
 */
public data class LoraAdapterSpec(
    public val path: String,
    public val scale: Float = 1.0f,
    /** Display name. Never used to locate the file. */
    public val label: String = path.substringAfterLast('/'),
)
