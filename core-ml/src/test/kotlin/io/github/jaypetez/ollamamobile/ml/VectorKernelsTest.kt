package io.github.jaypetez.ollamamobile.ml

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

class VectorKernelsTest {
    @Test
    fun `int8 dot product matches a hand-computed value`() {
        val a = byteArrayOf(1, 2, 3, -4)
        val b = byteArrayOf(5, -6, 7, 8)

        // 5 - 12 + 21 - 32
        assertThat(KotlinVectorKernels.dotInt8(a, b)).isEqualTo(-18)
    }

    @Test
    fun `int8 dot product handles the extreme byte values`() {
        val a = ByteArray(64) { Byte.MIN_VALUE }
        val b = ByteArray(64) { Byte.MIN_VALUE }

        // (-128 * -128) * 64 = 1_048_576. Nowhere near a 32-bit overflow, which
        // is the point: the accumulator is safe for any real embedding length.
        assertThat(KotlinVectorKernels.dotInt8(a, b)).isEqualTo(1_048_576)
    }

    @Test
    fun `float dot product matches a hand-computed value`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(4f, 5f, 6f)

        assertThat(KotlinVectorKernels.dotFloat(a, b)).isWithin(TOLERANCE).of(32f)
    }

    @Test
    fun `cosine of a vector with itself is one`() {
        val v = FloatArray(128) { Random(1).nextFloat() }

        assertThat(KotlinVectorKernels.cosine(v, v)).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)

        assertThat(KotlinVectorKernels.cosine(a, b)).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun `cosine of an opposed vector is minus one`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)

        assertThat(KotlinVectorKernels.cosine(a, b)).isWithin(TOLERANCE).of(-1f)
    }

    @Test
    fun `a zero vector yields zero similarity rather than NaN`() {
        val zero = FloatArray(8)
        val other = FloatArray(8) { it.toFloat() }

        // A NaN ranks unpredictably against real scores and silently corrupts a
        // top-k, which is a bug that surfaces as "retrieval is a bit worse".
        val similarity = KotlinVectorKernels.cosine(zero, other)

        assertThat(similarity).isEqualTo(0f)
        assertThat(similarity.isNaN()).isFalse()
    }

    @Test
    fun `mismatched lengths are rejected`() {
        val failure = runCatching {
            KotlinVectorKernels.dotInt8(ByteArray(4), ByteArray(5))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty vector has a zero dot product`() {
        assertThat(KotlinVectorKernels.dotInt8(ByteArray(0), ByteArray(0))).isEqualTo(0)
        assertThat(KotlinVectorKernels.dotFloat(FloatArray(0), FloatArray(0))).isEqualTo(0f)
    }

    @Test
    fun `the provider falls back to the reference when the native library is absent`() {
        // In every build produced today `:core-ml` compiles no CMake target, so
        // this is the branch that runs. If that ever changes, this assertion is
        // how the change announces itself.
        if (!NativeVectorKernels.isAvailable) {
            assertThat(VectorKernelsProvider.best()).isSameInstanceAs(KotlinVectorKernels)
        } else {
            assertThat(VectorKernelsProvider.best()).isSameInstanceAs(NativeVectorKernels)
        }
    }

    @Test
    fun `the native kernel delegates to the reference when unavailable`() {
        assumeTrue(!NativeVectorKernels.isAvailable)
        val a = ByteArray(37) { (it * 7 - 40).toByte() }
        val b = ByteArray(37) { (60 - it * 3).toByte() }

        assertThat(NativeVectorKernels.dotInt8(a, b))
            .isEqualTo(KotlinVectorKernels.dotInt8(a, b))
    }

    /**
     * The NEON kernel against its Kotlin reference.
     *
     * ## Why this skips
     *
     * `libollama-ml.so` is not built by any current Gradle configuration (see
     * `NativeCpuFeatures`), and even when it is, a host JVM running this test
     * cannot load an Android `.so`. The assumption below therefore skips this
     * test everywhere today.
     *
     * It is written anyway, and written as a *real* comparison rather than a
     * placeholder, because the moment the CMake target is wired up and this runs
     * on a device this is the test that catches a lane-combination bug in the
     * widening path. Integer arithmetic has no rounding to hide behind: the two
     * must agree exactly, and the "tolerance" for the int8 kernel is zero.
     *
     * Sizes are chosen to straddle the 16-byte vector width — 0, 1, 15, 16, 17,
     * 63, 64, 1024 — because the scalar tail after the vector loop is where this
     * kind of kernel is wrong.
     */
    @Test
    fun `neon int8 kernel agrees exactly with the kotlin reference`() {
        assumeTrue(
            "libollama-ml.so is not built in this configuration",
            NativeVectorKernels.isAvailable,
        )
        val random = Random(seed = 20260801)

        listOf(0, 1, 15, 16, 17, 63, 64, 1024).forEach { size ->
            val a = ByteArray(size).also(random::nextBytes)
            val b = ByteArray(size).also(random::nextBytes)

            assertThat(NativeVectorKernels.dotInt8(a, b))
                .isEqualTo(KotlinVectorKernels.dotInt8(a, b))
        }
    }

    @Test
    fun `float kernels agree between implementations within tolerance`() {
        val random = Random(seed = 20260801)
        val a = FloatArray(512) { random.nextFloat() * 2f - 1f }
        val b = FloatArray(512) { random.nextFloat() * 2f - 1f }

        val referenceDot = KotlinVectorKernels.dotFloat(a, b)
        val bestDot = VectorKernelsProvider.best().dotFloat(a, b)

        // Relative tolerance: an absolute epsilon is meaningless once the
        // magnitude of the sum is unknown.
        assertThat(abs(bestDot - referenceDot)).isLessThan(abs(referenceDot) * RELATIVE_TOLERANCE + TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-5f
        const val RELATIVE_TOLERANCE = 1e-5f
    }
}
