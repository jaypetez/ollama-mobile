package io.github.jaypetez.ollamamobile.ml

import kotlin.math.sqrt

/**
 * The small numeric kernels RAG needs.
 *
 * Retrieval scores a query vector against every stored chunk. At a few thousand
 * chunks and 384-to-1024 dimensions that is a few million multiply-accumulates
 * per query, which is fast in C and not fast in a JIT-warmed Kotlin loop over a
 * boxed-free `FloatArray` — but it is also not slow enough to justify a JNI
 * dependency on its own. Hence two implementations and a test that holds them to
 * the same answer.
 *
 * [dotInt8] is the one worth accelerating: quantised embeddings are stored as
 * signed bytes with a per-vector scale, and the NEON `SDOT` form of this loop is
 * where the difference actually shows up.
 */
public interface VectorKernels {
    /**
     * Signed 8-bit dot product, accumulated in 32 bits.
     *
     * Both arrays must be the same length. The maximum magnitude is
     * `127 * 128 * n`, so a 32-bit accumulator overflows above ~132k elements —
     * far beyond any embedding dimension, and asserted rather than handled
     * because an embedding that long is a bug elsewhere.
     */
    public fun dotInt8(a: ByteArray, b: ByteArray): Int

    /** fp32 dot product. */
    public fun dotFloat(a: FloatArray, b: FloatArray): Float

    /**
     * Cosine similarity.
     *
     * Returns 0 when either vector has zero magnitude, rather than NaN. A NaN
     * ranks unpredictably against real scores and silently corrupts a top-k;
     * zero says "no similarity", which is the truth about a zero vector.
     */
    public fun cosine(a: FloatArray, b: FloatArray): Float
}

/**
 * The reference implementation, and the one that actually runs today.
 *
 * "Reference" here means normative: it defines the answer, and the NEON kernel
 * is correct exactly insofar as it agrees with this. It is written to be
 * obviously right rather than fast — no unrolling, no fused paths, no
 * reassociation beyond what the JIT does on its own.
 */
public object KotlinVectorKernels : VectorKernels {
    override fun dotInt8(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size) { "Length mismatch: ${a.size} vs ${b.size}" }
        var sum = 0
        for (i in a.indices) {
            sum += a[i].toInt() * b[i].toInt()
        }
        return sum
    }

    override fun dotFloat(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Length mismatch: ${a.size} vs ${b.size}" }
        // Accumulated in double, then narrowed once.
        //
        // A float accumulator loses low bits progressively across a thousand
        // terms, and the loss depends on the summation order — so a vectorised
        // implementation that sums in four lanes and combines at the end would
        // legitimately disagree with a scalar one by more than a tolerance a
        // test would want to allow. Accumulating wide makes the reference the
        // stable target rather than one arbitrary rounding schedule.
        var sum = 0.0
        for (i in a.indices) {
            sum += a[i].toDouble() * b[i].toDouble()
        }
        return sum.toFloat()
    }

    override fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Length mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }
}

/**
 * The NEON path, when `libollama-ml.so` is present.
 *
 * Only [dotInt8] is native. The fp32 loops delegate to [KotlinVectorKernels]:
 * there is no NEON form of an fp32 dot product that beats what the JIT already
 * emits by enough to pay for a JNI transition per call, and shipping a second
 * implementation of something means maintaining two ways to be wrong.
 *
 * See [NativeCpuFeatures] for why [isAvailable] is false in the current build.
 */
public object NativeVectorKernels : VectorKernels {
    /** False in any build that does not compile this module's CMake target. */
    public val isAvailable: Boolean get() = NativeCpuFeatures.isAvailable

    override fun dotInt8(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size) { "Length mismatch: ${a.size} vs ${b.size}" }
        if (!isAvailable) return KotlinVectorKernels.dotInt8(a, b)
        return NativeCpuFeatures.nativeDotInt8(a, b, a.size)
    }

    override fun dotFloat(a: FloatArray, b: FloatArray): Float = KotlinVectorKernels.dotFloat(a, b)

    override fun cosine(a: FloatArray, b: FloatArray): Float = KotlinVectorKernels.cosine(a, b)
}

/**
 * Picks an implementation.
 *
 * A function rather than a constant so a test can ask for the reference
 * explicitly, and so the choice is visible at the call site instead of being a
 * property of whichever build happened to be produced.
 */
public object VectorKernelsProvider {
    public fun best(): VectorKernels =
        if (NativeVectorKernels.isAvailable) NativeVectorKernels else KotlinVectorKernels

    public fun reference(): VectorKernels = KotlinVectorKernels
}
