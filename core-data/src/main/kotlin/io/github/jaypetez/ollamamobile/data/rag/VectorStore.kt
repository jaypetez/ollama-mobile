package io.github.jaypetez.ollamamobile.data.rag

import io.github.jaypetez.ollamamobile.ml.VectorKernels
import io.github.jaypetez.ollamamobile.ml.VectorKernelsProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** One vector, quantised. [scale] is what multiplies a byte back to its float. */
public class QuantizedVector(
    public val bytes: ByteArray,
    public val scale: Float,
)

/**
 * Symmetric per-vector int8 quantisation.
 *
 * ## Why int8 and not fp32
 *
 * At 768 dimensions an fp32 vector is 3 KB. Ten thousand chunks is 30 MB, which
 * has to be resident to be scanned and is competing for the same memory as two
 * loaded models. int8 makes it 7.5 MB, and the accuracy cost is close to nil for
 * *ranking*, which is all retrieval needs — quantisation perturbs each score by
 * a fraction of a percent, and the ordering of the top few is determined by
 * differences far larger than that.
 *
 * ## Why per-vector and symmetric
 *
 * A single global scale would be set by whichever vector had the largest
 * component anywhere in the corpus, crushing everything else into a handful of
 * levels. A per-dimension scale would need a second array as large as the
 * saving. Per-vector is the middle: one float per vector, and each vector uses
 * the full [-127, 127] range.
 *
 * Symmetric (no zero point) because embedding vectors are near-symmetric about
 * zero already, and an asymmetric scheme costs a second correction term in the
 * dot product — which is the loop being optimised.
 *
 * 127 rather than 128 so that negation is representable and the kernel's
 * accumulator bound stays the stated `127 * 127 * n`.
 */
public object VectorQuantizer {
    public fun quantize(vector: FloatArray): QuantizedVector {
        var maximum = 0f
        for (value in vector) {
            val magnitude = abs(value)
            if (magnitude > maximum) maximum = magnitude
        }
        // An all-zero vector has no scale. Returning 1f rather than 0f keeps
        // dequantisation total: 0 * 1 is 0, whereas a 0 scale would make every
        // stored vector decode to zero if it were ever reused by mistake.
        if (maximum == 0f) return QuantizedVector(ByteArray(vector.size), 1f)

        val scale = maximum / MAX_LEVEL
        val bytes = ByteArray(vector.size)
        for (index in vector.indices) {
            bytes[index] = Math
                .round(vector[index] / scale)
                .coerceIn(-MAX_LEVEL_INT, MAX_LEVEL_INT)
                .toByte()
        }
        return QuantizedVector(bytes, scale)
    }

    public fun dequantize(vector: QuantizedVector): FloatArray =
        FloatArray(vector.bytes.size) { vector.bytes[it] * vector.scale }

    private const val MAX_LEVEL = 127f
    private const val MAX_LEVEL_INT = 127
}

/** A chunk id and its dense score, best-first. */
public data class ScoredChunk(
    public val chunkUuid: String,
    public val score: Float,
)

/**
 * An in-memory, off-heap store of quantised vectors, scanned exhaustively.
 *
 * ## Why a flat scan and not an ANN index
 *
 * At the corpus size a phone will ever hold — a few thousand to a few tens of
 * thousands of chunks — a linear scan over contiguous int8 is *faster* than an
 * HNSW traversal, because it is a pure sequential read with no pointer chasing
 * and the SDOT kernel saturates the load unit. An approximate index would add a
 * build step, a serialisation format, a tuning surface and a recall cliff, in
 * exchange for nothing until the corpus is an order of magnitude larger than
 * anything a user will import through a file picker.
 *
 * ## Why one off-heap arena
 *
 * Ten thousand separate `ByteArray`s are ten thousand objects the GC must trace
 * on every full collection, scattered across the heap so the scan misses cache
 * on each one. One [ByteBuffer.allocateDirect] is a single contiguous region the
 * collector never walks into, and the scan reads it front to back exactly the
 * way the prefetcher expects.
 *
 * ## Why not sqlite-vec
 *
 * It publishes no Android artifact, and the AndroidX SQLite driver exposes no
 * `load_extension` hook to register one if it did. The alternative — a second
 * database engine bundled alongside Room purely for vectors — means two write
 * paths, two migration stories and two things to keep consistent when a document
 * is deleted. The vectors live in `rag_chunks` as blobs and are loaded into this
 * arena on demand.
 *
 * ## Threading
 *
 * Not thread-safe. One instance per retrieval session, rebuilt from the DAO;
 * mutation during a scan would read a half-written vector.
 */
public class VectorStore(
    private val kernels: VectorKernels = VectorKernelsProvider.best(),
) {
    private var arena: ByteBuffer = EMPTY
    private var ids: Array<String> = emptyArray()
    private var scales: FloatArray = FloatArray(0)

    /** Vector length, adopted from the first vector loaded. 0 when empty. */
    public var dimensions: Int = 0
        private set

    public val size: Int
        get() = ids.size

    /**
     * Replaces the contents wholesale.
     *
     * Wholesale rather than incremental because the arena is contiguous:
     * removing one vector from the middle means either a compaction or a hole,
     * and the corpus is small enough that rebuilding costs a few milliseconds.
     *
     * @throws IllegalArgumentException on a dimension mismatch. Loudly, because a
     *   short vector scanned against a long one is not a bad score, it is a read
     *   of the next vector's bytes and a score with no meaning.
     */
    public fun load(entries: List<Entry>) {
        if (entries.isEmpty()) {
            arena = EMPTY
            ids = emptyArray()
            scales = FloatArray(0)
            dimensions = 0
            return
        }
        val width = entries
            .first()
            .vector.bytes.size
        require(width > 0) { "A vector cannot be empty" }
        entries.forEachIndexed { index, entry ->
            require(entry.vector.bytes.size == width) {
                "Vector ${entry.chunkUuid} has ${entry.vector.bytes.size} dimensions, expected $width " +
                    "(index $index). Vectors from two models are not comparable."
            }
        }

        val buffer = ByteBuffer.allocateDirect(width * entries.size).order(ByteOrder.nativeOrder())
        val identifiers = ArrayList<String>(entries.size)
        val scaleValues = FloatArray(entries.size)
        entries.forEachIndexed { index, entry ->
            buffer.put(entry.vector.bytes)
            identifiers += entry.chunkUuid
            scaleValues[index] = entry.vector.scale
        }
        buffer.flip()

        arena = buffer
        ids = identifiers.toTypedArray()
        scales = scaleValues
        dimensions = width
    }

    /**
     * The [limit] best matches for [query] by cosine, best first.
     *
     * The query is quantised too. Quantising both sides is what lets the whole
     * scan stay in the int8 kernel; mixing int8 storage with an fp32 query means
     * dequantising every stored vector, which is the allocation and the
     * arithmetic this representation exists to avoid.
     */
    public fun search(query: FloatArray, limit: Int): List<ScoredChunk> {
        if (size == 0 || limit <= 0) return emptyList()
        require(query.size == dimensions) {
            "Query has ${query.size} dimensions, index has $dimensions"
        }
        val quantized = VectorQuantizer.quantize(query)
        val queryNorm = normOf(quantized.bytes)
        if (queryNorm == 0.0) return emptyList()

        val row = ByteArray(dimensions)
        val scored = ArrayList<ScoredChunk>(size)
        for (index in ids.indices) {
            // Positional bulk get rather than the absolute-index overload: that
            // overload is a Java 13 addition and is not in every API 29 runtime
            // this app supports. Mutating the position is safe because this
            // class is single-threaded by contract.
            arena.position(index * dimensions)
            arena.get(row, 0, dimensions)
            val dot = kernels.dotInt8(quantized.bytes, row).toDouble()
            val norm = normOf(row)
            // Cosine is scale-invariant, so the per-vector scales cancel
            // entirely and never need to be applied here. They are kept for
            // callers that want the dequantised vector back.
            val cosine = if (norm == 0.0) 0f else (dot / (queryNorm * norm)).toFloat()
            scored += ScoredChunk(ids[index], cosine)
        }
        // A full sort of a few thousand elements is cheaper than the branchy
        // heap it would replace, and it is not the hot part of the scan.
        return scored.sortedByDescending { it.score }.take(limit)
    }

    private fun normOf(vector: ByteArray): Double {
        var sum = 0L
        for (value in vector) {
            val magnitude = value.toLong()
            sum += magnitude * magnitude
        }
        return sqrt(sum.toDouble())
    }

    /** A vector and the chunk it belongs to. */
    public data class Entry(
        public val chunkUuid: String,
        public val vector: QuantizedVector,
    )

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
