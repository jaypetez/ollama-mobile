package io.github.jaypetez.ollamamobile.server

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully

/*
 * Framing. Both formats are written as raw bytes rather than through a
 * higher-level plugin, because both are defined by their delimiters and a
 * plugin that "helpfully" buffers or re-encodes changes the bytes on the wire.
 * A client that reads a line at a time cannot recover from a missing newline.
 */

/** `application/x-ndjson` — what Ollama labels its `/api` streams. */
val NdjsonContentType: ContentType = ContentType("application", "x-ndjson")

/**
 * `text/event-stream`, charset spelled out.
 *
 * Some SSE clients refuse a frame whose media type carries no charset, and the
 * OpenAI SDKs decode as UTF-8 unconditionally, so saying so costs nothing and
 * removes a class of "works in curl, not in the SDK" bug.
 */
val SseContentType: ContentType = ContentType.Text.EventStream.withCharset(Charsets.UTF_8)

/**
 * Writes one NDJSON record: the JSON, then exactly one `\n`, then a flush.
 *
 * The flush is the point. Without it CIO coalesces records into whatever fills
 * a buffer, and a streaming client shows nothing until the generation ends —
 * which is indistinguishable from a hang on a slow model.
 */
suspend fun ByteWriteChannel.writeNdjsonLine(json: String) {
    writeFully((json + "\n").toByteArray(Charsets.UTF_8))
    flush()
}

/** Writes one SSE frame: `data: {json}\n\n`. */
suspend fun ByteWriteChannel.writeSseData(json: String) {
    writeFully(("data: " + json + "\n\n").toByteArray(Charsets.UTF_8))
    flush()
}

/**
 * The terminal `data: [DONE]\n\n` frame.
 *
 * Not optional and not decorative: the OpenAI SDKs treat a stream that ends
 * without it as truncated and raise, so omitting it turns every successful
 * generation into a client-side error.
 */
suspend fun ByteWriteChannel.writeSseDone() {
    writeFully(SSE_DONE_FRAME.toByteArray(Charsets.UTF_8))
    flush()
}

const val SSE_DONE_FRAME: String = "data: [DONE]\n\n"
