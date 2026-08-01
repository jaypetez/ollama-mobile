package io.github.jaypetez.ollamamobile.storage.gguf

import java.io.ByteArrayOutputStream

/**
 * Builds GGUF headers byte by byte.
 *
 * Deliberately not a wrapper around the parser's own reader: a test fixture
 * that shares the production encoder cannot catch an endianness or field-order
 * mistake, because both sides would be wrong in the same direction. And no
 * `.gguf` is committed — the smallest real one is hundreds of megabytes and the
 * repo-size gate rejects it.
 */
class GgufHeaderBuilder(
    private val magic: ByteArray = byteArrayOf(0x47, 0x47, 0x55, 0x46),
    private val version: Int = 3,
) {
    private val entries = mutableListOf<Pair<String, ByteArray>>()
    private val tensors = mutableListOf<ByteArray>()

    fun string(key: String, value: String) = apply {
        entries += key to buildBytes {
            uint32(TYPE_STRING)
            lengthPrefixed(value)
        }
    }

    fun uint32(key: String, value: Long) = apply {
        entries += key to buildBytes {
            uint32(TYPE_UINT32)
            uint32(value.toInt())
        }
    }

    fun int32(key: String, value: Int) = apply {
        entries += key to buildBytes {
            uint32(TYPE_INT32)
            uint32(value)
        }
    }

    fun uint64(key: String, value: Long) = apply {
        entries += key to buildBytes {
            uint32(TYPE_UINT64)
            uint64(value)
        }
    }

    fun float32(key: String, value: Float) = apply {
        entries += key to buildBytes {
            uint32(TYPE_FLOAT32)
            uint32(value.toRawBits())
        }
    }

    fun bool(key: String, value: Boolean) = apply {
        entries += key to buildBytes {
            uint32(TYPE_BOOL)
            write(if (value) 1 else 0)
        }
    }

    /** The `head_count_kv`-as-array shape. */
    fun int32Array(key: String, values: List<Int>) = apply {
        entries += key to buildBytes {
            uint32(TYPE_ARRAY)
            uint32(TYPE_INT32)
            uint64(values.size.toLong())
            values.forEach { uint32(it) }
        }
    }

    /** The `tokenizer.ggml.tokens` shape: no total length anywhere, so it can only be walked. */
    fun stringArray(key: String, values: List<String>) = apply {
        entries += key to buildBytes {
            uint32(TYPE_ARRAY)
            uint32(TYPE_STRING)
            uint64(values.size.toLong())
            values.forEach { lengthPrefixed(it) }
        }
    }

    fun tensor(name: String, ggmlTypeId: Int, dimensions: List<Long> = listOf(4096L)) = apply {
        tensors += buildBytes {
            lengthPrefixed(name)
            uint32(dimensions.size)
            dimensions.forEach { uint64(it) }
            uint32(ggmlTypeId)
            uint64(0)
        }
    }

    fun build(): ByteArray = buildBytes {
        write(magic)
        uint32(version)
        uint64(tensors.size.toLong())
        uint64(entries.size.toLong())
        entries.forEach { (key, value) ->
            lengthPrefixed(key)
            write(value)
        }
        tensors.forEach { write(it) }
    }

    private fun buildBytes(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()

    private fun ByteArrayOutputStream.uint32(value: Int) {
        repeat(4) { index -> write((value ushr (index * 8)) and 0xFF) }
    }

    private fun ByteArrayOutputStream.uint64(value: Long) {
        repeat(8) { index -> write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }

    private fun ByteArrayOutputStream.lengthPrefixed(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        uint64(bytes.size.toLong())
        write(bytes)
    }

    private companion object {
        const val TYPE_INT32 = 5
        const val TYPE_UINT32 = 4
        const val TYPE_FLOAT32 = 6
        const val TYPE_BOOL = 7
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
        const val TYPE_UINT64 = 10
    }
}
