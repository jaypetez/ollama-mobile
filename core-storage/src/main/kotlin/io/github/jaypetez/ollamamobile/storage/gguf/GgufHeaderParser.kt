package io.github.jaypetez.ollamamobile.storage.gguf

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GgmlType
import io.github.jaypetez.ollamamobile.model.GgufMetadata
import io.github.jaypetez.ollamamobile.model.asException

/**
 * Reads the metadata block at the front of a GGUF file. Never the tensor data.
 *
 * ## The format, in the order the bytes appear
 *
 * ```
 * magic          "GGUF"   (0x46554747 read as a little-endian uint32)
 * version        uint32
 * tensor_count   uint64
 * kv_count       uint64
 * kv_count × {
 *     key        uint64 length + that many UTF-8 bytes
 *     type       uint32 type tag
 *     value      type 8 = string (uint64 length + bytes)
 *                type 9 = array  (uint32 element type + uint64 count + elements)
 *                everything else is a fixed-width scalar
 * }
 * tensor_count × { name, n_dims, dims[], ggml_type, offset }   ← only read on demand
 * ```
 *
 * Everything is little-endian.
 *
 * ## Traps this parser exists to survive
 *
 * Each is annotated at the point it is handled; they are listed here because
 * every one of them produces a *plausible* wrong answer rather than an error.
 *
 * 1. `llama.attention.head_count_kv` is a scalar on most models and an **int32
 *    array** on some architectures.
 * 2. `general.file_type` is an `llama_ftype`, not a `ggml_type`. The two
 *    numberings collide.
 * 3. `tokenizer.chat_template` is routinely hundreds of kilobytes, so the
 *    header is not small and a fixed read is not enough.
 * 4. Some `ggml_type` ids have been retired from the format and current ggml
 *    refuses them — better to say so here than to hand the bytes to JNI.
 */
class GgufHeaderParser(
    private val windowSteps: List<Int> = DEFAULT_WINDOW_STEPS,
) {
    /**
     * @param validateTensorTypes when true, continues past the metadata into
     *   the tensor-info block and rejects any tensor whose `ggml_type` current
     *   ggml can no longer read. Off by default because it forces the whole
     *   tensor-info table — megabytes of names on a large model — to be
     *   fetched, which defeats the point of a cheap remote header read. Turn it
     *   on before *loading* a file, not before listing it.
     */
    suspend fun parse(source: GgufSource, validateTensorTypes: Boolean = false): GgufMetadata {
        val total = source.sizeBytes
        var previousSize = -1
        windowSteps.forEachIndexed { index, step ->
            val window = source.prefix(step)
            // The source has nothing more to give when it returns no more bytes
            // than last time, or when we already hold the whole file. Asking
            // again would be a pointless extra request against a truncated file.
            val exhausted = window.size <= previousSize ||
                (total != null && window.size.toLong() >= total)
            previousSize = window.size
            try {
                return parseWindow(window, validateTensorTypes)
            } catch (_: NeedMoreBytesException) {
                if (exhausted || index == windowSteps.lastIndex) throw truncated()
            }
        }
        throw truncated()
    }

    private fun parseWindow(window: ByteArray, validateTensorTypes: Boolean): GgufMetadata {
        val cursor = Cursor(window)

        val magic = cursor.uint32()
        if (magic != GGUF_MAGIC) {
            throw AppError.Model
                .Corrupt(
                    message = "This is not a GGUF file (magic 0x${magic.toString(16)}).",
                ).asException()
        }
        val version = cursor.uint32()
        if (version !in SUPPORTED_VERSIONS) {
            throw AppError.Model
                .Unsupported(
                    reason = "GGUF version $version. Only versions ${SUPPORTED_VERSIONS.joinToString()} are readable.",
                ).asException()
        }

        val tensorCount = cursor.uint64()
        val kvCount = cursor.uint64()
        if (tensorCount < 0 || kvCount < 0) {
            throw AppError.Model.Corrupt(message = "GGUF header declares a negative count.").asException()
        }

        val collected = HashMap<String, Any?>(WANTED_SLOTS)
        var index = 0L
        while (index < kvCount) {
            val key = cursor.string()
            val type = cursor.uint32()
            val slot = slotFor(key)
            if (slot == null) {
                cursor.skipValue(type)
            } else {
                collected[slot] = cursor.readValue(type)
                // Stop the moment nothing useful can still appear. On a file
                // that carries every key we ask for this ends the scan before
                // the tensor-info table, which on a large model is megabytes of
                // tensor names we would otherwise walk for nothing.
                if (!validateTensorTypes && collected.size == WANTED_SLOTS) break
            }
            index++
        }

        if (validateTensorTypes) {
            // Only reachable when the loop above ran to completion, so the
            // cursor is positioned at the first tensor-info record.
            validateTensors(cursor, tensorCount)
        }

        val architecture = collected[SLOT_ARCHITECTURE].asString()
            ?: throw AppError.Model
                .Corrupt(
                    message = "GGUF header has no general.architecture, so the model cannot be identified.",
                ).asException()

        return GgufMetadata(
            architecture = architecture,
            name = collected[SLOT_NAME].asString(),
            parameterCount = collected[SLOT_PARAMETER_COUNT].asLong(),
            // Deliberately stored raw. This is an `llama_ftype`, and
            // GgufMetadata.quantizationFromFileType is the only thing that
            // knows the mapping — ftype 15 is Q4_K_M while ggml type 15 is
            // Q8_K, so reading it with the wrong table mis-sizes every model.
            fileType = collected[SLOT_FILE_TYPE].asInt(),
            contextLength = collected[SLOT_CONTEXT_LENGTH].asInt(),
            embeddingLength = collected[SLOT_EMBEDDING_LENGTH].asInt(),
            blockCount = collected[SLOT_BLOCK_COUNT].asInt(),
            headCount = collected[SLOT_HEAD_COUNT].asInt(),
            // Scalar on most models, an INT32 ARRAY on architectures with
            // per-layer KV head counts (hybrid attention, some SSM variants).
            // `asLong` takes the maximum across an array, which keeps the KV
            // cache estimate an upper bound rather than a number that happens
            // to match layer zero.
            headCountKv = collected[SLOT_HEAD_COUNT_KV].asInt(),
            ropeFreqBase = collected[SLOT_ROPE_FREQ_BASE].asDouble(),
            // Hundreds of kilobytes on Llama-3-family and Qwen chat models.
            // This is the key that makes the "grow the window" path normal
            // rather than exceptional.
            chatTemplate = collected[SLOT_CHAT_TEMPLATE].asString(),
            tokenizerModel = collected[SLOT_TOKENIZER_MODEL].asString(),
        )
    }

    private fun validateTensors(cursor: Cursor, tensorCount: Long) {
        var index = 0L
        while (index < tensorCount) {
            val name = cursor.string()
            val dimensions = cursor.uint32()
            if (dimensions < 0 || dimensions > MAX_TENSOR_DIMENSIONS) {
                throw AppError.Model
                    .Corrupt(
                        message = "Tensor '$name' declares $dimensions dimensions.",
                    ).asException()
            }
            repeat(dimensions) { cursor.uint64() }
            val typeId = cursor.uint32()
            cursor.uint64() // offset into the tensor data, which we never read
            val type = GgmlType.fromId(typeId)
            if (type == null || type.removed) {
                // A file using a retired id looks perfectly well-formed all the
                // way to the JNI boundary, where ggml aborts the *process* and
                // no Kotlin `try` can catch it.
                throw AppError.Model
                    .Unsupported(
                        reason = "tensor '$name' uses ggml type ${type?.name ?: typeId}, " +
                            "which was removed from the format and current ggml refuses to load.",
                    ).asException()
            }
            index++
        }
    }

    private fun truncated(): Throwable = AppError.Model
        .Corrupt(
            message = "The GGUF header is incomplete — the file is truncated or is not a GGUF at all.",
        ).asException()

    companion object {
        /** 0x46554747: "GGUF" read as a little-endian uint32. */
        const val GGUF_MAGIC: Int = 0x4655_4747

        /**
         * v1 used 32-bit lengths and is extinct; v2 and v3 are byte-compatible
         * for everything this parser reads.
         */
        val SUPPORTED_VERSIONS: List<Int> = listOf(2, 3)

        /**
         * 64 KiB covers the metadata of a model with no chat template, which is
         * the common remote case and costs one small range request. The steps
         * after it exist for the chat template and for the tokeniser's token
         * and merge arrays, which must be walked byte by byte to be skipped —
         * their lengths are only discoverable by reading them.
         */
        val DEFAULT_WINDOW_STEPS: List<Int> = listOf(
            64 * 1024,
            1024 * 1024,
            4 * 1024 * 1024,
            16 * 1024 * 1024,
        )

        private const val MAX_TENSOR_DIMENSIONS = 8

        private const val SLOT_ARCHITECTURE = "architecture"
        private const val SLOT_NAME = "name"
        private const val SLOT_PARAMETER_COUNT = "parameterCount"
        private const val SLOT_FILE_TYPE = "fileType"
        private const val SLOT_CONTEXT_LENGTH = "contextLength"
        private const val SLOT_EMBEDDING_LENGTH = "embeddingLength"
        private const val SLOT_BLOCK_COUNT = "blockCount"
        private const val SLOT_HEAD_COUNT = "headCount"
        private const val SLOT_HEAD_COUNT_KV = "headCountKv"
        private const val SLOT_ROPE_FREQ_BASE = "ropeFreqBase"
        private const val SLOT_CHAT_TEMPLATE = "chatTemplate"
        private const val SLOT_TOKENIZER_MODEL = "tokenizerModel"

        private const val WANTED_SLOTS = 12

        /**
         * Architecture-scoped keys are matched by suffix rather than by
         * `"$arch."` prefix on purpose: nothing in the spec requires
         * `general.architecture` to be written first, and a prefix match would
         * silently drop every shape field in a file that happens to order them
         * the other way.
         */
        private fun slotFor(key: String): String? = when {
            key == "general.architecture" -> SLOT_ARCHITECTURE
            key == "general.name" -> SLOT_NAME
            key == "general.parameter_count" -> SLOT_PARAMETER_COUNT
            key == "general.file_type" -> SLOT_FILE_TYPE
            key == "tokenizer.chat_template" -> SLOT_CHAT_TEMPLATE
            key == "tokenizer.ggml.model" -> SLOT_TOKENIZER_MODEL
            key.startsWith("general.") || key.startsWith("tokenizer.") -> null
            key.endsWith(".attention.head_count_kv") -> SLOT_HEAD_COUNT_KV
            key.endsWith(".attention.head_count") -> SLOT_HEAD_COUNT
            key.endsWith(".context_length") -> SLOT_CONTEXT_LENGTH
            key.endsWith(".embedding_length") -> SLOT_EMBEDDING_LENGTH
            key.endsWith(".block_count") -> SLOT_BLOCK_COUNT
            key.endsWith(".rope.freq_base") -> SLOT_ROPE_FREQ_BASE
            else -> null
        }
    }
}

/** Thrown when the window ran out mid-value; the caller refetches a larger prefix. */
private class NeedMoreBytesException : Exception(null, null, false, false)

/**
 * Little-endian reader over one window.
 *
 * Values are widened to `Long`/`Double`/`String`/`List<*>` rather than modelled
 * as a sealed type: the caller only ever asks "give me this as an int", and the
 * scalar-or-array ambiguity in the format is easier to absorb in one accessor
 * than to propagate through a type hierarchy.
 */
private class Cursor(
    private val bytes: ByteArray,
) {
    private var position = 0

    private fun require(count: Long) {
        if (count < 0 || position + count > bytes.size) throw NeedMoreBytesException()
    }

    fun uint8(): Int {
        require(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun uint16(): Int {
        require(2)
        val value = (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value
    }

    /** Returned as a signed Int because both consumers (magic, type tags) want the raw 32 bits. */
    fun uint32(): Int {
        require(4)
        var value = 0
        for (i in 3 downTo 0) {
            value = (value shl 8) or (bytes[position + i].toInt() and 0xFF)
        }
        position += 4
        return value
    }

    fun uint64(): Long {
        require(8)
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[position + i].toLong() and 0xFF)
        }
        position += 8
        return value
    }

    fun float32(): Double = Float.fromBits(uint32()).toDouble()

    fun float64(): Double = Double.fromBits(uint64())

    fun string(): String {
        val length = uint64()
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw AppError.Model.Corrupt(message = "GGUF string length $length is not believable.").asException()
        }
        require(length)
        val value = String(bytes, position, length.toInt(), Charsets.UTF_8)
        position += length.toInt()
        return value
    }

    private fun skipString() {
        val length = uint64()
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw AppError.Model.Corrupt(message = "GGUF string length $length is not believable.").asException()
        }
        require(length)
        position += length.toInt()
    }

    fun readValue(type: Int): Any? = when (type) {
        TYPE_UINT8 -> uint8().toLong()
        TYPE_INT8 -> uint8().toByte().toLong()
        TYPE_UINT16 -> uint16().toLong()
        TYPE_INT16 -> uint16().toShort().toLong()
        TYPE_UINT32 -> uint32().toLong() and 0xFFFF_FFFFL
        TYPE_INT32 -> uint32().toLong()
        TYPE_FLOAT32 -> float32()
        TYPE_BOOL -> uint8() != 0
        TYPE_STRING -> string()
        TYPE_UINT64, TYPE_INT64 -> uint64()
        TYPE_FLOAT64 -> float64()
        TYPE_ARRAY -> readArray()
        else -> throw AppError.Model.Corrupt(message = "Unknown GGUF value type $type.").asException()
    }

    private fun readArray(): List<Any?> {
        val elementType = uint32()
        val count = uint64()
        if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
            throw AppError.Model.Corrupt(message = "GGUF array of $count elements is not believable.").asException()
        }
        val values = ArrayList<Any?>(minOf(count, ARRAY_PREALLOCATE_CAP).toInt())
        var index = 0L
        while (index < count) {
            values += readValue(elementType)
            index++
        }
        return values
    }

    fun skipValue(type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> advance(1)
            TYPE_UINT16, TYPE_INT16 -> advance(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> advance(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> advance(8)
            TYPE_STRING -> skipString()
            TYPE_ARRAY -> skipArray()
            else -> throw AppError.Model.Corrupt(message = "Unknown GGUF value type $type.").asException()
        }
    }

    private fun skipArray() {
        val elementType = uint32()
        val count = uint64()
        if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
            throw AppError.Model.Corrupt(message = "GGUF array of $count elements is not believable.").asException()
        }
        val fixedWidth = FIXED_WIDTHS[elementType]
        if (fixedWidth != null) {
            // The only case where a skip is genuinely free.
            advance(fixedWidth * count)
            return
        }
        // A string array — `tokenizer.ggml.tokens` is 128k+ entries on a modern
        // vocabulary — has no total length recorded anywhere, so every element
        // has to be walked to find the end of it. This is why the window grows
        // to megabytes on real files even though we want twelve keys.
        var index = 0L
        while (index < count) {
            skipValue(elementType)
            index++
        }
    }

    private fun advance(count: Long) {
        require(count)
        position += count.toInt()
    }

    private companion object {
        const val TYPE_UINT8 = 0
        const val TYPE_INT8 = 1
        const val TYPE_UINT16 = 2
        const val TYPE_INT16 = 3
        const val TYPE_UINT32 = 4
        const val TYPE_INT32 = 5
        const val TYPE_FLOAT32 = 6
        const val TYPE_BOOL = 7
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
        const val TYPE_UINT64 = 10
        const val TYPE_INT64 = 11
        const val TYPE_FLOAT64 = 12

        val FIXED_WIDTHS: Map<Int, Long> = mapOf(
            TYPE_UINT8 to 1L,
            TYPE_INT8 to 1L,
            TYPE_BOOL to 1L,
            TYPE_UINT16 to 2L,
            TYPE_INT16 to 2L,
            TYPE_UINT32 to 4L,
            TYPE_INT32 to 4L,
            TYPE_FLOAT32 to 4L,
            TYPE_UINT64 to 8L,
            TYPE_INT64 to 8L,
            TYPE_FLOAT64 to 8L,
        )

        /** 64 MiB. A chat template is large; nothing in a header is this large. */
        const val MAX_STRING_BYTES = 64L * 1024 * 1024
        const val MAX_ARRAY_ELEMENTS = 64L * 1024 * 1024
        const val ARRAY_PREALLOCATE_CAP = 1024L
    }
}

private fun Any?.asString(): String? = this as? String

/**
 * Widens whatever the format actually carried into a Long.
 *
 * The array branch is the `head_count_kv` trap: a value the spec treats as one
 * number is written as an int32 array by some converters, and reading only the
 * first element under-counts the KV cache on any model with uneven per-layer
 * head counts. Taking the maximum keeps the estimate an upper bound.
 */
private fun Any?.asLong(): Long? = when (this) {
    is Long -> this
    is Int -> toLong()
    is Double -> toLong()
    is Boolean -> if (this) 1L else 0L
    is List<*> -> mapNotNull { it.asLong() }.maxOrNull()
    else -> null
}

private fun Any?.asInt(): Int? = asLong()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

private fun Any?.asDouble(): Double? = when (this) {
    is Double -> this
    is Long -> toDouble()
    is Int -> toDouble()
    is List<*> -> mapNotNull { it.asDouble() }.maxOrNull()
    else -> null
}
