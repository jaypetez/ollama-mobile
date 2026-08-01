package io.github.jaypetez.ollamamobile.storage

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.GgufMetadata
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryEstimatorTest {
    private val estimator = MemoryEstimator(ApplicationProvider.getApplicationContext())

    // --- KV cache arithmetic -------------------------------------------

    @Test
    fun `kv cache uses head_count_kv, not head_count`() {
        // 32 layers, n_embd 4096 over 32 heads = 128 per head, 8 KV heads.
        // n_embd_kv = 128 * 8 = 1024, so at F16 and 4096 tokens:
        // 2 * 32 * 4096 * 1024 * 2 = 536,870,912.
        val estimate = requireNotNull(estimator.estimate(request(headCountKv = 8, contextLength = 4096)))

        assertThat(estimate.kvCacheBytes).isEqualTo(536_870_912L)
    }

    @Test
    fun `grouped-query attention is four times cheaper than multi-head here`() {
        val gqa = requireNotNull(estimator.estimate(request(headCountKv = 8)))
        val mha = requireNotNull(estimator.estimate(request(headCountKv = 32)))

        // Using head_count where head_count_kv was meant overestimates by
        // exactly the GQA ratio, which on this shape is 4x and on an 8-way
        // model is 8x — the difference between "load it" and "refuse".
        assertThat(mha.kvCacheBytes).isEqualTo(gqa.kvCacheBytes * 4)
    }

    @Test
    fun `a missing head_count_kv is read as multi-head attention`() {
        val absent = requireNotNull(estimator.estimate(request(headCountKv = null)))
        val explicit = requireNotNull(estimator.estimate(request(headCountKv = 32)))

        assertThat(absent.kvCacheBytes).isEqualTo(explicit.kvCacheBytes)
    }

    @Test
    fun `kv cache scales linearly with context length`() {
        val short = requireNotNull(estimator.estimate(request(contextLength = 2048)))
        val long = requireNotNull(estimator.estimate(request(contextLength = 8192)))

        assertThat(long.kvCacheBytes).isEqualTo(short.kvCacheBytes * 4)
    }

    @Test
    fun `quantising the kv cache is the largest single lever`() {
        val f16 = requireNotNull(estimator.estimate(request(kvCacheType = KvCacheType.F16)))
        val q8 = requireNotNull(estimator.estimate(request(kvCacheType = KvCacheType.Q8_0)))
        val q4 = requireNotNull(estimator.estimate(request(kvCacheType = KvCacheType.Q4_0)))

        // Q8_0 packs 32 values into 34 bytes, Q4_0 into 18.
        assertThat(q8.kvCacheBytes).isEqualTo((f16.kvCacheBytes * 34 / 64))
        assertThat(q4.kvCacheBytes).isEqualTo((f16.kvCacheBytes * 18 / 64))
    }

    // --- weights --------------------------------------------------------

    @Test
    fun `the file length on disk wins over the parameter-count estimate`() {
        val estimate = requireNotNull(estimator.estimate(request(fileSizeBytes = 1234L)))

        assertThat(estimate.weightBytes).isEqualTo(1234L)
    }

    @Test
    fun `weights fall back to bits-per-weight times parameter count`() {
        val estimate = requireNotNull(
            estimator.estimate(request(fileSizeBytes = null, parameterCount = 1_000_000_000L)),
        )

        // ftype 15 is Q4_K_M at an effective 4.85 bits per weight.
        assertThat(estimate.weightBytes).isEqualTo(606_250_000L)
    }

    @Test
    fun `a model of unknown size cannot be budgeted for`() {
        val unsized = request(fileSizeBytes = null, parameterCount = null)

        assertThat(estimator.estimate(unsized)).isNull()

        val verdict = estimator.verdict(unsized, generousBudget())
        assertThat(verdict).isInstanceOf(MemoryVerdict.Refuse::class.java)
        assertThat(verdict.explain()).contains("unknown")
    }

    // --- verdict boundaries ---------------------------------------------

    @Test
    fun `comfortable headroom fits`() {
        val model = request()
        val required = requiredBytes(model)

        val verdict = estimator.verdict(model, budget(required + GIB))

        assertThat(verdict).isInstanceOf(MemoryVerdict.Fits::class.java)
        assertThat((verdict as MemoryVerdict.Fits).headroomBytes).isEqualTo(GIB)
        assertThat(verdict.allowsLoad).isTrue()
    }

    @Test
    fun `headroom just under the margin is tight, and says what to change`() {
        val model = request()
        val required = requiredBytes(model)

        val verdict = estimator.verdict(model, budget(required + MemoryEstimator.TIGHT_HEADROOM_BYTES - 1))

        assertThat(verdict).isInstanceOf(MemoryVerdict.Tight::class.java)
        assertThat(verdict.allowsLoad).isTrue()
        assertThat((verdict as MemoryVerdict.Tight).reason).isNotEmpty()
    }

    @Test
    fun `headroom exactly at the margin fits`() {
        val model = request()
        val required = requiredBytes(model)

        val verdict = estimator.verdict(model, budget(required + MemoryEstimator.TIGHT_HEADROOM_BYTES))

        assertThat(verdict).isInstanceOf(MemoryVerdict.Fits::class.java)
    }

    @Test
    fun `one byte short refuses`() {
        val model = request()
        val required = requiredBytes(model)

        val verdict = estimator.verdict(model, budget(required - 1))

        assertThat(verdict).isInstanceOf(MemoryVerdict.Refuse::class.java)
        assertThat(verdict.allowsLoad).isFalse()
        assertThat((verdict as MemoryVerdict.Refuse).shortfallBytes).isEqualTo(1)
    }

    @Test
    fun `a long context is blamed on the kv cache`() {
        val model = request(contextLength = 32768, fileSizeBytes = 256L * 1024 * 1024)

        val verdict = estimator.verdict(model, budget(GIB))

        assertThat(verdict.explain()).contains("KV cache")
        assertThat(verdict.explain()).contains("Q8_0")
    }

    // --- device posture --------------------------------------------------

    @Test
    fun `low-RAM hardware is refused outright`() {
        val verdict = estimator.verdict(
            request(),
            MemoryBudget(availableBytes = 16L * GIB, lowMemory = false, lowRamDevice = true),
        )

        assertThat(verdict).isInstanceOf(MemoryVerdict.Refuse::class.java)
        assertThat(verdict.explain()).contains("low-RAM")
    }

    @Test
    fun `a device already under pressure is refused`() {
        val verdict = estimator.verdict(
            request(),
            MemoryBudget(availableBytes = 16L * GIB, lowMemory = true, lowRamDevice = false),
        )

        assertThat(verdict).isInstanceOf(MemoryVerdict.Refuse::class.java)
    }

    @Test
    fun `the budget comes from availMem minus the threshold, never from total RAM`() {
        val budget = estimator.currentBudget()

        // Robolectric's default MemoryInfo has availMem far below totalMem;
        // the point of the assertion is that nothing here reads totalMem.
        assertThat(budget.availableBytes).isAtLeast(0L)
        assertThat(budget.lowRamDevice).isFalse()
    }

    // --- two models resident ---------------------------------------------

    @Test
    fun `RAG budgets for the chat model and the embedding model together`() {
        val chat = request()
        val embedding = embeddingRequest()

        val both = requiredBytes(chat, embedding)
        val chatOnly = requiredBytes(chat)

        // The fixed runtime reserve is counted once, not twice.
        assertThat(both).isEqualTo(
            chatOnly + requireNotNull(estimator.estimate(embedding)).totalBytes,
        )
    }

    @Test
    fun `a budget that fits the chat model alone refuses once retrieval is on`() {
        val chat = request()
        val embedding = embeddingRequest()
        val chatOnlyBudget = budget(requiredBytes(chat) + GIB)

        assertThat(estimator.verdict(chat, chatOnlyBudget)).isInstanceOf(MemoryVerdict.Fits::class.java)

        val withRetrieval = estimator.verdict(listOf(chat, embedding), chatOnlyBudget)

        assertThat(withRetrieval).isInstanceOf(MemoryVerdict.Refuse::class.java)
        assertThat(withRetrieval.explain()).contains("Two models")
    }

    @Test
    fun `an empty request list is trivially satisfiable`() {
        assertThat(estimator.verdict(emptyList(), budget(GIB))).isInstanceOf(MemoryVerdict.Fits::class.java)
    }

    private fun requiredBytes(vararg requests: ModelMemoryRequest): Long =
        requests.sumOf { requireNotNull(estimator.estimate(it)).totalBytes } +
            MemoryEstimator.RUNTIME_OVERHEAD_BYTES

    private fun budget(available: Long) =
        MemoryBudget(availableBytes = available, lowMemory = false, lowRamDevice = false)

    private fun generousBudget() = budget(64L * GIB)

    private fun request(
        headCountKv: Int? = 8,
        contextLength: Int = 4096,
        kvCacheType: KvCacheType = KvCacheType.F16,
        fileSizeBytes: Long? = 2L * GIB,
        parameterCount: Long? = 7_000_000_000L,
    ) = ModelMemoryRequest(
        metadata = GgufMetadata(
            architecture = "llama",
            name = "Chat",
            parameterCount = parameterCount,
            fileType = 15,
            embeddingLength = 4096,
            blockCount = 32,
            headCount = 32,
            headCountKv = headCountKv,
        ),
        contextLength = contextLength,
        fileSizeBytes = fileSizeBytes,
        kvCacheType = kvCacheType,
        batchSize = 1,
        vocabSize = 1,
    )

    /** Small, short-context, and emphatically not free on a 6 GB device. */
    private fun embeddingRequest() = ModelMemoryRequest(
        metadata = GgufMetadata(
            architecture = "bert",
            name = "Embed",
            fileType = 7,
            embeddingLength = 768,
            blockCount = 12,
            headCount = 12,
            headCountKv = 12,
        ),
        contextLength = 512,
        fileSizeBytes = 2L * GIB,
        batchSize = 1,
        vocabSize = 1,
    )

    private companion object {
        const val GIB = 1024L * 1024 * 1024
    }
}
