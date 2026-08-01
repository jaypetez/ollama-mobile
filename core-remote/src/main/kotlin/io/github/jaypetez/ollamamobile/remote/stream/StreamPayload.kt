package io.github.jaypetez.ollamamobile.remote.stream

import io.github.jaypetez.ollamamobile.model.asException
import io.github.jaypetez.ollamamobile.remote.RemoteError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okio.Buffer
import okio.BufferedSource

/*
 * The two things the NDJSON and SSE parsers must both get right, in one place.
 *
 * Neither is interesting on its own; both are the difference between a parser
 * that works on localhost and one that works over Wi-Fi.
 */

/** One newline. Compared as a byte so the scan never has to decode UTF-8 first. */
private const val LINE_FEED: Byte = '\n'.code.toByte()

/**
 * How much to ask for per read. Okio hands back whatever has arrived rather
 * than waiting to fill this, so a larger number costs nothing and a smaller one
 * would only add syscalls.
 */
private const val READ_CHUNK_BYTES: Long = 8_192L

/**
 * Reads [this] to exhaustion, invoking [onLine] once per complete line.
 *
 * **The buffering is the point.** TCP splits a response at arbitrary byte
 * offsets, which for a token stream means routinely mid-object and even
 * mid-token: `{"response":"hel` arrives, then `lo"}\n`. A parser that decodes
 * whatever each read returned fails on exactly that, and it fails only over a
 * real network, because a localhost test writes the whole body in one segment.
 * So incomplete bytes stay buffered until a newline shows up.
 *
 * The scan is byte-oriented rather than string-oriented for the same class of
 * reason one level down: a multi-byte UTF-8 character can also straddle a read,
 * and decoding each chunk to a `String` as it arrives replaces the halves with
 * replacement characters. Bytes are only decoded once a whole line is present.
 *
 * The trailing newline is not required — a body that ends without one still
 * yields its last line.
 *
 * [onLine] returns false to stop reading early; the caller is then responsible
 * for closing the body, which the `use` in each flow does.
 */
internal suspend fun BufferedSource.consumeLines(onLine: suspend (String) -> Boolean) {
    val pending = Buffer()
    while (true) {
        // Blocking reads cannot be interrupted by cancellation, so the flag is
        // checked between them. The socket itself is closed by the `use` block
        // unwinding, which is what actually unblocks a read in progress.
        currentCoroutineContext().ensureActive()
        if (read(pending, READ_CHUNK_BYTES) == -1L) break
        while (true) {
            val newline = pending.indexOf(LINE_FEED)
            if (newline == -1L) break
            val line = pending.readUtf8(newline)
            pending.skip(1)
            if (!onLine(line)) return
        }
    }
    if (pending.size > 0L) onLine(pending.readUtf8())
}

/**
 * Decodes one JSON payload from a stream, or throws a typed failure.
 *
 * Two failure modes, both of which must be loud:
 *
 *  * **A top-level `error` key.** Ollama reports a mid-stream failure as an
 *    ordinary NDJSON line inside a response that already returned HTTP 200 —
 *    the headers went out before anything went wrong, so there is no status
 *    code left to use. A parser that only decodes into its happy-path DTO gets
 *    an object with every field defaulted, `done` false, and no indication that
 *    anything happened: the answer simply stops, and the user is told nothing.
 *  * **An undecodable payload.** Skipping it would present a truncated answer
 *    as a complete one.
 */
internal fun <T> decodeStreamPayload(payload: String, deserializer: DeserializationStrategy<T>, json: Json): T {
    val element = try {
        json.parseToJsonElement(payload)
    } catch (e: SerializationException) {
        throw RemoteError.malformedPayload(payload, e).asException()
    }

    val obj = element as? JsonObject ?: throw RemoteError.malformedPayload(payload).asException()

    val error = obj["error"]
    if (error != null && error != JsonNull) {
        throw RemoteError.fromStreamPayload(payload).asException()
    }

    return try {
        json.decodeFromJsonElement(deserializer, element)
    } catch (e: SerializationException) {
        throw RemoteError.malformedPayload(payload, e).asException()
    }
}
