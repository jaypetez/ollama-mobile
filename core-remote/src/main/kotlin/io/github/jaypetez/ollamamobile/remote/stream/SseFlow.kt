package io.github.jaypetez.ollamamobile.remote.stream

import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.ResponseBody

/** The literal payload that ends an OpenAI-compatible stream. Not JSON, never emitted. */
const val SSE_DONE: String = "[DONE]"

private const val DATA_FIELD = "data"

/**
 * Parses a `text/event-stream` body — the `/v1` streaming format — into a
 * [Flow] of decoded objects.
 *
 * SSE is deceptively simple and has five ways to go wrong, all handled here:
 *
 *  * **Frames split across reads.** A frame is `data: {...}` followed by a
 *    blank line, and TCP will happily deliver half of it. Lines are buffered by
 *    [consumeLines] and frames are accumulated until their blank-line
 *    terminator, so one logical event comes out however the bytes arrived.
 *  * **Multi-line `data:`.** The spec allows a payload to be spread over
 *    several `data:` lines, joined with newlines. Emitting each line as its own
 *    event would produce fragments of JSON.
 *  * **Comment lines.** A line beginning with `:` is a comment, and a bare `:`
 *    is the conventional keep-alive. Feeding one to the JSON parser is an error
 *    on a healthy connection.
 *  * **`data: [DONE]`.** The terminator is a literal, not JSON. Decoding it
 *    throws; emitting it as a string leaks a sentinel into typed output.
 *  * **CRLF.** The spec permits CRLF, LF or CR line endings, and a stray `\r`
 *    left on the end of a payload makes it invalid JSON.
 *
 * Like [asNdjsonFlow] this closes the body on every exit path, surfaces a
 * `{"error": ...}` payload as a typed failure, never silently drops an
 * undecodable frame, and needs the call to have been made with
 * `readTimeout = 0`.
 */
fun <T> ResponseBody.asSseFlow(deserializer: DeserializationStrategy<T>, json: Json = RemoteJson): Flow<T> = flow {
    use { body ->
        val data = StringBuilder()

        // Returns false once the terminator is seen, which stops the read loop
        // rather than waiting for a server that may hold the socket open after
        // saying [DONE].
        suspend fun dispatch(): Boolean {
            // The spec appends a newline after every data line, so the frame
            // carries one trailing separator that is not part of the payload.
            val payload = data.toString().removeSuffix("\n")
            data.setLength(0)
            if (payload.isEmpty()) return true
            if (payload.trim() == SSE_DONE) return false
            emit(decodeStreamPayload(payload, deserializer, json))
            return true
        }

        body.source().consumeLines { rawLine ->
            // consumeLines splits on LF; a CRLF stream leaves the CR behind.
            val line = rawLine.removeSuffix("\r")
            when {
                line.isEmpty() -> {
                    dispatch()
                }

                line.startsWith(':') -> {
                    true
                }

                else -> {
                    accumulate(line, data)
                    true
                }
            }
        }
        // A body that ends without the blank line that would have terminated
        // the last frame still owes us that frame. After [DONE] the accumulator
        // is empty, so this is a no-op on a well-formed stream.
        dispatch()
    }
}

/** As [asSseFlow], with the serializer taken from the reified type. */
inline fun <reified T> ResponseBody.asSseFlow(json: Json = RemoteJson): Flow<T> = asSseFlow(serializer<T>(), json)

/**
 * Applies one field line to the frame under construction.
 *
 * Only `data` is kept. `event`, `id` and `retry` are parsed off so they cannot
 * be mistaken for payload, but the OpenAI-compatible surface does not use them
 * and the app has nothing to do with a reconnection id it never reconnects
 * with.
 */
private fun accumulate(line: String, data: StringBuilder) {
    val separator = line.indexOf(':')
    val field = if (separator == -1) line else line.substring(0, separator)
    if (field != DATA_FIELD) return

    val rawValue = if (separator == -1) "" else line.substring(separator + 1)
    // Exactly one leading space is part of the framing, not of the value:
    // "data: {}" and "data:{}" carry the same payload, but "data:  {}" carries
    // a payload that starts with a space.
    val value = rawValue.removePrefix(" ")
    data.append(value).append('\n')
}
