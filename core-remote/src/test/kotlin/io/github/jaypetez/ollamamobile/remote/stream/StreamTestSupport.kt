package io.github.jaypetez.ollamamobile.remote.stream

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

/*
 * Shared fixtures for the two streaming parsers.
 *
 * Split cases are exercised two ways on purpose:
 *
 *  * DripSource hands out a fixed, tiny number of bytes per read, so the test
 *    is deterministic and genuinely fails if the line buffering is removed; and
 *  * chunkedStreamingResponse serves the same body over a real socket with
 *    HTTP chunked encoding *and* byte throttling, so the split is produced by
 *    the transport rather than by the fixture.
 *
 * Neither is sufficient alone: the first proves the algorithm, the second
 * proves it survives OkHttp.
 */

internal const val NDJSON_MEDIA_TYPE = "application/x-ndjson"
internal const val SSE_MEDIA_TYPE = "text/event-stream"

/** Bytes per throttling period. Small enough to guarantee partial reads, large enough to stay quick. */
private const val THROTTLE_BYTES = 24L
private const val THROTTLE_PERIOD_MILLIS = 5L

/** Bytes per read in the deterministic fixture. Deliberately not a token or line boundary. */
internal const val DRIP_BYTES = 7

/**
 * A [Source] that yields at most [chunkBytes] per read.
 *
 * This is what a slow network looks like to Okio, minus the timing. Records
 * [closed] so the cancellation path can be asserted.
 */
internal class DripSource(
    text: String,
    private val chunkBytes: Int = DRIP_BYTES,
) : Source {
    private val remaining = Buffer().writeUtf8(text)

    var closed: Boolean = false
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (remaining.exhausted()) return -1L
        return remaining.read(sink, minOf(byteCount, chunkBytes.toLong()))
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
        remaining.clear()
    }
}

/** Wraps [source] as a response body of [contentType], without a server. */
internal fun Source.asBody(contentType: String): ResponseBody =
    buffer().asResponseBody(contentType.toMediaType(), -1L)

/** A whole body in memory, for the cases where the split does not matter. */
internal fun bodyOf(text: String, contentType: String): ResponseBody =
    Buffer().writeUtf8(text).asResponseBody(contentType.toMediaType(), text.toByteArray().size.toLong())

/**
 * A chunked, throttled response — the transport-level version of [DripSource].
 *
 * `chunkedBody` frames the body so the server writes and flushes it in pieces;
 * `throttleBody` then forces those pieces to reach the client separately
 * instead of being coalesced into one segment by the kernel.
 */
internal fun chunkedStreamingResponse(body: String, contentType: String, chunkSize: Int = 13): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", contentType)
        .chunkedBody(body, chunkSize)
        .throttleBody(THROTTLE_BYTES, THROTTLE_PERIOD_MILLIS, TimeUnit.MILLISECONDS)
        .build()

/** A plain 200 with the whole body at once. */
internal fun wholeResponse(body: String, contentType: String): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", contentType)
        .body(body)
        .build()

/**
 * A client for streaming reads.
 *
 * `readTimeout = 0` is trap 8: the gap between the request and the first token
 * is a model thinking, and it can legitimately be minutes. The default deadline
 * would kill the call and look to the user like the model gave up. The tests do
 * not run long enough to need it, but they should be built the way production
 * is or they are not testing production.
 */
internal fun streamingClient(): OkHttpClient = OkHttpClient
    .Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

/** Issues a GET and returns the (still open) body for the parser to consume. */
internal fun OkHttpClient.streamBody(server: MockWebServer, path: String = "/api/chat"): ResponseBody =
    newCall(Request.Builder().url(server.url(path)).build()).execute().body
