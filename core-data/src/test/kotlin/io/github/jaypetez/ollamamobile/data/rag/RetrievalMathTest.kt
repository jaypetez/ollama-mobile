package io.github.jaypetez.ollamamobile.data.rag

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.ml.KotlinVectorKernels
import io.github.jaypetez.ollamamobile.ml.VectorKernelsProvider
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Test

/**
 * The two pieces of arithmetic that decide what the model gets to read.
 *
 * Both are tested on **ranking**, not on scores. A quantised dot product will
 * never equal an fp32 one and asserting that it does would only produce a test
 * with an arbitrary tolerance in it. What retrieval actually depends on is the
 * *order*, and that is what is asserted.
 */
class RetrievalMathTest {
    // --- int8 store versus the fp32 reference ----------------------------

    @Test
    fun `the quantised top-K matches the fp32 reference ranking`() {
        val random = Random(seed = 20260731)
        val dimensions = 128
        val corpus = (0 until 400).associate { index ->
            "chunk-$index" to randomUnitVector(random, dimensions)
        }
        val store = VectorStore(VectorKernelsProvider.reference()).apply {
            load(corpus.map { (id, vector) -> VectorStore.Entry(id, VectorQuantizer.quantize(vector)) })
        }

        var queries = 0
        var exactOrderAgreements = 0
        repeat(50) {
            val query = randomUnitVector(random, dimensions)

            val quantised = store.search(query, TOP_K).map { it.chunkUuid }
            val referenceScores = corpus.entries
                .map { (id, vector) -> id to KotlinVectorKernels.cosine(query, vector) }
                .sortedByDescending { it.second }
            val reference = referenceScores.take(TOP_K).map { it.first }

            queries++
            if (quantised == reference) exactOrderAgreements++

            // The best match must be exactly right. That is the one the model is
            // most likely to actually use, and int8 rounding is nowhere near
            // large enough to disturb a genuine top-1.
            assertThat(quantised.first()).isEqualTo(reference.first())

            // Membership is allowed to differ by at most one, and only at the
            // boundary. On random vectors the 8th and 9th cosines are routinely
            // within a rounding step of each other, so demanding an identical
            // set would be asserting that quantisation is lossless — which it is
            // not, and does not need to be.
            val overlap = quantised.toSet().intersect(reference.toSet()).size
            assertThat(overlap).isAtLeast(TOP_K - 1)
            if (overlap < TOP_K) {
                val boundary = referenceScores[TOP_K - 1].second - referenceScores[TOP_K].second
                assertThat(boundary).isLessThan(BOUNDARY_TOLERANCE)
            }
        }

        // Exact order agreement on the clear majority. Anything less means the
        // quantiser is losing more than rounding noise.
        assertThat(exactOrderAgreements).isAtLeast((queries * MIN_EXACT_AGREEMENT_PERCENT) / 100)
    }

    @Test
    fun `the selected kernel and the reference kernel produce the same int8 dot product`() {
        // On a host JVM the "best" kernel falls back to the Kotlin one, so this
        // is a tautology here and a real assertion on an arm64 device, where the
        // NEON path is live. It is the only place the two are held to the same
        // answer inside the retrieval module.
        val random = Random(seed = 7)
        repeat(200) {
            val a = ByteArray(64) { random.nextInt(-127, 128).toByte() }
            val b = ByteArray(64) { random.nextInt(-127, 128).toByte() }

            assertThat(VectorKernelsProvider.best().dotInt8(a, b))
                .isEqualTo(KotlinVectorKernels.dotInt8(a, b))
        }
    }

    @Test
    fun `an identical vector is its own best match`() {
        val random = Random(seed = 11)
        val target = randomUnitVector(random, 64)
        val store = VectorStore().apply {
            load(
                buildList {
                    add(VectorStore.Entry("target", VectorQuantizer.quantize(target)))
                    repeat(
                        50,
                    ) { add(VectorStore.Entry("noise-$it", VectorQuantizer.quantize(randomUnitVector(random, 64)))) }
                },
            )
        }

        assertThat(store.search(target, 1).single().chunkUuid).isEqualTo("target")
    }

    @Test
    fun `quantisation round-trips within the step size`() {
        val random = Random(seed = 3)
        val original = randomUnitVector(random, 96)

        val restored = VectorQuantizer.dequantize(VectorQuantizer.quantize(original))

        val step = original.maxOf { kotlin.math.abs(it) } / 127f
        for (index in original.indices) {
            assertThat(kotlin.math.abs(restored[index] - original[index])).isLessThan(step)
        }
    }

    @Test
    fun `an all-zero vector quantises without producing a poisoned scale`() {
        val quantised = VectorQuantizer.quantize(FloatArray(16))

        // A zero scale would silently zero every vector it were applied to.
        assertThat(quantised.scale).isEqualTo(1f)
        assertThat(VectorQuantizer.dequantize(quantised).toList()).containsExactlyElementsIn(List(16) { 0f })
    }

    @Test
    fun `mixing vector widths is rejected rather than scored`() {
        val store = VectorStore()

        val failure = runCatching {
            store.load(
                listOf(
                    VectorStore.Entry("a", VectorQuantizer.quantize(FloatArray(8) { 1f })),
                    VectorStore.Entry("b", VectorQuantizer.quantize(FloatArray(16) { 1f })),
                ),
            )
        }

        // Scanning a short vector against a long one reads the neighbouring
        // vector's bytes and returns a plausible number, which is worse than
        // crashing.
        assertThat(failure.isFailure).isTrue()
    }

    // --- reciprocal rank fusion ------------------------------------------

    /**
     * A golden fixture, computed by hand from `1 / (60 + rank)`.
     *
     * Hand-computed on purpose: recomputing the expectation with the same code
     * under test would assert only that the function is deterministic. These
     * numbers are what the published formula says the answer is.
     */
    @Test
    fun `fusion matches the hand-computed golden fixture`() {
        val lexical = listOf("a", "b", "c", "d")
        val dense = listOf("c", "a", "e")

        val fused = ReciprocalRankFusion().fuse(lexical, dense, limit = 10)

        // a: 1/61 + 1/62 = 0.016393 + 0.016129 = 0.032522
        // c: 1/63 + 1/61 = 0.015873 + 0.016393 = 0.032266
        // b: 1/62                              = 0.016129
        // d: 1/64                              = 0.015625
        // e: 1/63                              = 0.015873
        assertThat(fused.map { it.chunkUuid }).containsExactly("a", "c", "b", "e", "d").inOrder()
        assertThat(fused[0].fusedScore).isWithin(TOLERANCE).of(1.0 / 61 + 1.0 / 62)
        assertThat(fused[1].fusedScore).isWithin(TOLERANCE).of(1.0 / 63 + 1.0 / 61)
        assertThat(fused[2].fusedScore).isWithin(TOLERANCE).of(1.0 / 62)

        // The provenance a debugger needs.
        assertThat(fused[0].lexicalRank).isEqualTo(1)
        assertThat(fused[0].denseRank).isEqualTo(2)
        assertThat(fused.single { it.chunkUuid == "e" }.lexicalRank).isNull()
    }

    @Test
    fun `agreement between the two sides beats a single first place`() {
        // The property k=60 is bought for. "consensus" is 3rd on both sides and
        // must outrank "spike", which is 1st on one side and absent from the
        // other.
        val fused = ReciprocalRankFusion().fuse(
            lexical = listOf("spike", "x", "consensus"),
            dense = listOf("y", "z", "consensus"),
            limit = 5,
        )

        assertThat(fused.first().chunkUuid).isEqualTo("consensus")
    }

    @Test
    fun `a small k would invert that, which is why it is 60`() {
        // Documents the sensitivity rather than the preference: at k=1 a single
        // first place dominates any consensus, which is the ranking RRF exists
        // to avoid.
        val fused = ReciprocalRankFusion(k = 1).fuse(
            lexical = listOf("spike", "x", "consensus"),
            dense = listOf("y", "z", "consensus"),
            limit = 5,
        )

        assertThat(fused.first().chunkUuid).isEqualTo("spike")
    }

    @Test
    fun `ties break deterministically`() {
        val first = ReciprocalRankFusion().fuse(listOf("b", "a"), listOf("d", "c"), limit = 4)
        val second = ReciprocalRankFusion().fuse(listOf("b", "a"), listOf("d", "c"), limit = 4)

        assertThat(first.map { it.chunkUuid }).isEqualTo(second.map { it.chunkUuid })
        // b and d both rank 1; a and c both rank 2. Within a tie, id order.
        assertThat(first.map { it.chunkUuid }).containsExactly("b", "d", "a", "c").inOrder()
    }

    @Test
    fun `an empty side degrades to the other side's ranking`() {
        val fused = ReciprocalRankFusion().fuse(listOf("a", "b", "c"), emptyList(), limit = 2)

        // Retrieval must still work when the embedding model is unavailable.
        assertThat(fused.map { it.chunkUuid }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a duplicated id keeps its best rank`() {
        val fused = ReciprocalRankFusion().fuse(listOf("a", "b", "a"), emptyList(), limit = 5)

        assertThat(fused.map { it.chunkUuid }).containsExactly("a", "b").inOrder()
        assertThat(fused.first().lexicalRank).isEqualTo(1)
    }

    private fun randomUnitVector(random: Random, dimensions: Int): FloatArray {
        val vector = FloatArray(dimensions) { random.nextFloat() * 2f - 1f }
        var norm = 0.0
        for (value in vector) norm += value.toDouble() * value
        val inverse = (1.0 / sqrt(norm)).toFloat()
        for (index in vector.indices) vector[index] *= inverse
        return vector
    }

    private companion object {
        const val TOP_K = 8
        const val MIN_EXACT_AGREEMENT_PERCENT = 60

        /**
         * How close two cosines may be for int8 to be allowed to swap them.
         *
         * One quantisation step at 127 levels is ~0.8% of the vector's largest
         * component; 0.01 in cosine is comfortably above the resulting score
         * perturbation and far below any difference that would matter to a user.
         */
        const val BOUNDARY_TOLERANCE = 0.01f
        const val TOLERANCE = 1e-9
    }
}
