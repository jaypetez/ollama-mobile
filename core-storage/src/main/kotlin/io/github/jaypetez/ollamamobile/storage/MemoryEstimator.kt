package io.github.jaypetez.ollamamobile.storage

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.model.GgufMetadata
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the K/V cache is stored, and what one element of it costs.
 *
 * The sub-byte figures are the block layouts: `Q8_0` packs 32 values into 34
 * bytes (32 quants plus an f16 scale), `Q4_0` into 18. Quantising the cache is
 * the largest single lever available for a long context.
 */
enum class KvCacheType(
    val bytesPerElement: Double,
) {
    F32(4.0),
    F16(2.0),
    BF16(2.0),
    Q8_0(34.0 / 32.0),
    Q5_1(24.0 / 32.0),
    Q4_0(18.0 / 32.0),
}

/** One model the caller intends to hold resident. */
data class ModelMemoryRequest(
    val metadata: GgufMetadata,
    /** The context to allocate — what the user configured, not the model's maximum. */
    val contextLength: Int,
    /**
     * The GGUF's actual length on disk when it is known. Exact and free, so it
     * always wins over the parameter-count estimate.
     */
    val fileSizeBytes: Long? = null,
    val kvCacheType: KvCacheType = KvCacheType.F16,
    /** `n_batch`. Drives the logits buffer, which is usually the largest part of the compute buffer. */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** Vocabulary size, when the caller knows it. */
    val vocabSize: Int? = null,
) {
    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 512
    }
}

/** The arithmetic behind a verdict, so the UI can show it rather than just the answer. */
data class MemoryEstimate(
    val weightBytes: Long,
    val kvCacheBytes: Long,
    val computeBufferBytes: Long,
) {
    val totalBytes: Long get() = weightBytes + kvCacheBytes + computeBufferBytes
}

/** What the device says it can spare right now. */
data class MemoryBudget(
    /** `availMem` minus the low-memory threshold; never total RAM. */
    val availableBytes: Long,
    val lowMemory: Boolean,
    val lowRamDevice: Boolean,
)

/**
 * Decides whether a set of models can be loaded on this device right now.
 *
 * The estimate is `weights + kv_cache + compute_buffer + a fixed runtime
 * reserve`, and every term is here because omitting any of them produces an
 * estimate that is wrong in the direction that gets the process killed.
 *
 * It takes a *list* of models rather than one, because RAG needs the embedding
 * model resident at the same time as the chat model — the query has to be
 * embedded with the model that built the index, at query time, while the chat
 * model still holds its weights and KV cache. Sizing them one at a time makes
 * RAG appear to work right up until a long conversation pushes the process
 * over.
 */
@Singleton
class MemoryEstimator
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * Budgets against `MemoryInfo.availMem` minus `threshold`, never against
         * `totalMem`. What the device shipped with is not what an app can have:
         * between the kernel, the vendor HAL stack, the system server and whatever
         * the user has open, the obtainable fraction varies enormously between
         * devices and between moments on the same device. `threshold` is subtracted
         * because that is the point at which the low-memory killer starts work, so
         * memory below it was never really available.
         */
        fun currentBudget(): MemoryBudget {
            val activityManager = context.getSystemService(ActivityManager::class.java)
                ?: return MemoryBudget(availableBytes = 0, lowMemory = true, lowRamDevice = true)
            val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            return MemoryBudget(
                availableBytes = (info.availMem - info.threshold).coerceAtLeast(0L),
                lowMemory = info.lowMemory,
                lowRamDevice = activityManager.isLowRamDevice,
            )
        }

        /** Null when the file size is unknown and the metadata carries no parameter count either. */
        fun estimate(request: ModelMemoryRequest): MemoryEstimate? {
            val weights = weightBytes(request) ?: return null
            return MemoryEstimate(
                weightBytes = weights,
                kvCacheBytes = kvCacheBytes(request),
                computeBufferBytes = computeBufferBytes(request),
            )
        }

        fun verdict(request: ModelMemoryRequest, budget: MemoryBudget = currentBudget()): MemoryVerdict =
            verdict(listOf(request), budget)

        fun verdict(
            requests: List<ModelMemoryRequest>,
            budget: MemoryBudget = currentBudget(),
        ): MemoryVerdict {
            if (requests.isEmpty()) return MemoryVerdict.Fits(headroomBytes = budget.availableBytes)

            // A low-RAM device is a configuration, not a moment: the platform trims
            // caches harder, the killer is more aggressive, and a multi-gigabyte
            // native allocation will not survive backgrounding. Steer to a remote
            // server instead of failing halfway through a load.
            if (budget.lowRamDevice) {
                return MemoryVerdict.Refuse(
                    requiredBytes = 0,
                    availableBytes = budget.availableBytes,
                    reason = "This device reports itself as low-RAM hardware, where a local model cannot be " +
                        "kept loaded. Use a remote server instead.",
                )
            }

            val estimates = requests.map { request ->
                estimate(request) ?: return MemoryVerdict.Refuse(
                    requiredBytes = 0,
                    availableBytes = budget.availableBytes,
                    reason = "The size of ${request.metadata.name ?: request.metadata.architecture} is unknown, " +
                        "so it cannot be budgeted for.",
                )
            }

            val required = estimates.sumOf { it.totalBytes } + RUNTIME_OVERHEAD_BYTES
            if (budget.lowMemory) {
                return MemoryVerdict.Refuse(
                    requiredBytes = required,
                    availableBytes = budget.availableBytes,
                    reason = "The system is already under memory pressure. Close some apps and try again.",
                )
            }

            val headroom = budget.availableBytes - required
            return when {
                headroom < 0 -> MemoryVerdict.Refuse(
                    requiredBytes = required,
                    availableBytes = budget.availableBytes,
                    reason = shortfallAdvice(requests, estimates),
                )

                headroom < TIGHT_HEADROOM_BYTES -> MemoryVerdict.Tight(
                    headroomBytes = headroom,
                    reason = shortfallAdvice(requests, estimates),
                )

                else -> MemoryVerdict.Fits(headroomBytes = headroom)
            }
        }

        private fun weightBytes(request: ModelMemoryRequest): Long? {
            request.fileSizeBytes?.let { return it }
            val parameters = request.metadata.parameterCount ?: return null
            val quantization = request.metadata.quantization ?: return null
            return quantization.estimateWeightBytes(parameters)
        }

        /**
         * `2 * n_layer * n_ctx * n_embd_kv * bytes_per_element`, where the 2 is K
         * and V.
         *
         * `n_embd_kv` is **not** `n_embd`. Under grouped-query attention it is
         * `head_dim * n_head_kv`, and `n_head_kv` is commonly a quarter to an
         * eighth of `n_head` on current models — using `n_head` overestimates the
         * cache by that same factor, which on a phone is the difference between
         * "load it" and "refuse". `head_dim` is derived as `n_embd / n_head`
         * because `GgufMetadata` does not carry `attention.key_length`; models
         * whose K and V head widths differ therefore come out approximate, and the
         * result should be read as an upper bound.
         */
        private fun kvCacheBytes(request: ModelMemoryRequest): Long {
            val metadata = request.metadata
            val layers = metadata.blockCount ?: return 0
            val embedding = metadata.embeddingLength ?: return 0
            val heads = metadata.headCount ?: return 0
            if (layers <= 0 || embedding <= 0 || heads <= 0) return 0

            val headDimension = embedding.toDouble() / heads
            // Absent head_count_kv means multi-head attention, where every head has
            // its own K/V. That is the conservative reading as well as the correct
            // one for older architectures.
            val kvHeads = metadata.headCountKv?.takeIf { it > 0 } ?: heads
            val embeddingKv = headDimension * kvHeads

            return (
                2.0 * layers * request.contextLength * embeddingKv * request.kvCacheType.bytesPerElement
            ).toLong()
        }

        /**
         * Scratch space for one forward pass.
         *
         * The logits buffer usually dominates: `n_vocab * n_batch * 4`. With
         * 128k–256k vocabularies now normal, a batch of 512 wants hundreds of
         * megabytes for logits alone, which is the strongest argument there is for
         * a modest `n_batch` on mobile. The activation term is a coarse multiple of
         * `n_embd * n_batch * 4`; llama.cpp reports the real figure at load time,
         * and this only has to be non-trivial and never zero.
         */
        private fun computeBufferBytes(request: ModelMemoryRequest): Long {
            val vocab = request.vocabSize ?: DEFAULT_VOCAB_SIZE
            val logits = vocab.toLong() * request.batchSize * BYTES_PER_FLOAT
            val embedding = request.metadata.embeddingLength ?: DEFAULT_EMBEDDING_LENGTH
            val activations = embedding.toLong() * request.batchSize * BYTES_PER_FLOAT * ACTIVATION_MULTIPLIER
            return logits + activations
        }

        /** Names the lever with the largest effect, because a refusal without a fix is not useful. */
        private fun shortfallAdvice(
            requests: List<ModelMemoryRequest>,
            estimates: List<MemoryEstimate>,
        ): String {
            val kv = estimates.sumOf { it.kvCacheBytes }
            val weights = estimates.sumOf { it.weightBytes }
            val cacheDominates = kv > weights / 2
            val unquantisedCache = requests.any {
                it.kvCacheType == KvCacheType.F16 || it.kvCacheType == KvCacheType.F32
            }
            return when {
                cacheDominates && unquantisedCache -> QUANTISE_CACHE_ADVICE
                cacheDominates -> SHORTEN_CONTEXT_ADVICE
                requests.size > 1 -> TWO_MODELS_ADVICE
                else -> SMALLER_MODEL_ADVICE
            }
        }

        companion object {
            /**
             * The app is more than a model: Compose, Skia, the Room database, the
             * OkHttp pool, bitmap caches and the JVM heap all need room, and the
             * process has to survive a configuration change and the keyboard or
             * camera coming up alongside it.
             */
            const val RUNTIME_OVERHEAD_BYTES: Long = 256L * 1024 * 1024

            /**
             * Below this much slack a background app waking up can tip the process
             * over, which the user experiences as a random crash. Load, but say
             * what to change.
             */
            const val TIGHT_HEADROOM_BYTES: Long = 256L * 1024 * 1024

            private const val QUANTISE_CACHE_ADVICE =
                "The KV cache is the largest part of this. Shorten the context or quantise the cache to Q8_0."
            private const val SHORTEN_CONTEXT_ADVICE =
                "The KV cache is the largest part of this. Shorten the context length."
            private const val TWO_MODELS_ADVICE =
                "Two models are resident at once. Turn off retrieval, or choose a smaller embedding model."
            private const val SMALLER_MODEL_ADVICE =
                "Choose a smaller quantisation of this model, or a smaller model."

            private const val DEFAULT_VOCAB_SIZE = 128_000
            private const val DEFAULT_EMBEDDING_LENGTH = 4096
            private const val BYTES_PER_FLOAT = 4L
            private const val ACTIVATION_MULTIPLIER = 8L
        }
    }
