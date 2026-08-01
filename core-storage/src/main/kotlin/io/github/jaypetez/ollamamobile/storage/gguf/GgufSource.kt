package io.github.jaypetez.ollamamobile.storage.gguf

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.asException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import okhttp3.Call
import okhttp3.Request

/**
 * A prefix of a GGUF file.
 *
 * The parser never needs the middle or the end of the file, only a growing
 * window from byte zero, so this deliberately cannot express a random-access
 * read. That keeps the HTTP implementation honest: it can only ever issue
 * `Range: bytes=0-n`, which is the one request shape a CDN will reliably serve
 * from cache.
 */
interface GgufSource : Closeable {
    /** Total length when the transport knows it; null for a server that will not say. */
    val sizeBytes: Long?

    /**
     * Returns the first [length] bytes, or the whole file if it is shorter.
     *
     * Implementations may return more than asked for and must return the same
     * bytes for the same prefix on every call.
     */
    suspend fun prefix(length: Int): ByteArray
}

/** Reads from a file already on disk. Growing the window costs nothing here. */
class LocalFileGgufSource(
    private val file: File,
) : GgufSource {
    private val handle: RandomAccessFile by lazy { RandomAccessFile(file, "r") }

    override val sizeBytes: Long get() = file.length()

    override suspend fun prefix(length: Int): ByteArray {
        val wanted = minOf(length.toLong(), file.length()).toInt()
        val buffer = ByteArray(wanted)
        handle.seek(0)
        handle.readFully(buffer)
        return buffer
    }

    override fun close() {
        if (handle.channel.isOpen) handle.close()
    }
}

/**
 * Reads the header of a remote GGUF over HTTP range requests.
 *
 * Sizing a 5 GB model before downloading it should cost one request of a few
 * tens of kilobytes, so each call fetches exactly the prefix asked for and
 * caches the largest one seen. The parser only asks for a bigger window when it
 * actually ran out of bytes.
 *
 * Takes a [Call.Factory] rather than building a client: the app has exactly one
 * `OkHttpClient`, configured in `:core-common` with the network policy, the
 * pinning and the redaction. A second client here would bypass all three.
 */
class HttpRangeGgufSource(
    private val url: String,
    private val callFactory: Call.Factory,
) : GgufSource {
    private var cached: ByteArray = ByteArray(0)
    private var knownSize: Long? = null

    override val sizeBytes: Long? get() = knownSize

    override suspend fun prefix(length: Int): ByteArray {
        if (cached.size >= length) return cached
        knownSize?.let { total -> if (cached.size.toLong() >= total) return cached }

        val request = Request
            .Builder()
            .url(url)
            .header("Range", "bytes=0-${length - 1}")
            .build()
        val response = try {
            callFactory.newCall(request).execute()
        } catch (e: IOException) {
            throw AppError.Network.Unreachable(cause = e).asException()
        }

        response.use {
            if (!it.isSuccessful) {
                throw AppError.Network.Http(code = it.code).asException()
            }
            // A server that ignores Range answers 200 with the whole body. That
            // is correct HTTP and a disaster for a multi-gigabyte file, so it
            // is refused rather than streamed.
            if (it.code != HTTP_PARTIAL_CONTENT && (knownSize ?: Long.MAX_VALUE) > length) {
                val contentLength = it.body.contentLength()
                if (contentLength > length) {
                    throw AppError.Network
                        .Http(
                            code = it.code,
                            message = "The server ignored the Range header and offered the whole file.",
                        ).asException()
                }
            }
            knownSize = parseTotalLength(it.header("Content-Range")) ?: knownSize
            val bytes = try {
                it.body.bytes()
            } catch (e: IOException) {
                throw AppError.Network.Unreachable(cause = e).asException()
            }
            if (bytes.size > cached.size) cached = bytes
        }
        return cached
    }

    override fun close() = Unit

    private fun parseTotalLength(contentRange: String?): Long? =
        contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
    }
}

/** Serves a byte array already in memory. Used by the parser's tests and by cached headers. */
class ByteArrayGgufSource(
    private val bytes: ByteArray,
) : GgufSource {
    /** Every prefix length the parser asked for, in order. Lets a test assert the regrow path ran. */
    val requestedLengths: MutableList<Int> = mutableListOf()

    override val sizeBytes: Long get() = bytes.size.toLong()

    override suspend fun prefix(length: Int): ByteArray {
        requestedLengths += length
        return bytes.copyOf(minOf(length, bytes.size))
    }

    override fun close() = Unit
}
