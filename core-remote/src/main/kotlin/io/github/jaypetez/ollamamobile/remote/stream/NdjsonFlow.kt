package io.github.jaypetez.ollamamobile.remote.stream

import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.ResponseBody

/**
 * Parses an `application/x-ndjson` body — the `/api` streaming format,
 * one JSON object per line — into a [Flow] of decoded objects.
 *
 * Guarantees, each of which exists because its absence is a bug that only shows
 * up on a real network or a real failure:
 *
 *  * Partial lines are buffered across reads; see [consumeLines].
 *  * Blank lines are skipped. Some proxies insert them as keep-alives.
 *  * **Every** line is inspected for a top-level `error` key and turned into a
 *    typed [io.github.jaypetez.ollamamobile.model.AppErrorException]. Ollama
 *    reports mid-stream failures at HTTP 200; missing this makes a failure look
 *    like a short answer.
 *  * A line that cannot be decoded fails the flow rather than being dropped.
 *  * The body is closed on completion, on failure and on cancellation, and the
 *    read loop checks for cancellation between reads.
 *
 * The returned flow is cold and does blocking I/O on the collecting thread, so
 * collect it on an I/O dispatcher. Two caller obligations that are not
 * enforceable from here:
 *
 *  * the call must be made with `readTimeout = 0` (derive the streaming client
 *    with `client.newBuilder().readTimeout(Duration.ZERO).build()`), because a
 *    model can legitimately think for well over a minute before the first
 *    token; and
 *  * a non-2xx response must be turned into an error with
 *    [io.github.jaypetez.ollamamobile.remote.RemoteError.fromHttp] *before*
 *    reaching here — this parser assumes the status was already accepted.
 */
fun <T> ResponseBody.asNdjsonFlow(deserializer: DeserializationStrategy<T>, json: Json = RemoteJson): Flow<T> = flow {
    use { body ->
        body.source().consumeLines { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                emit(decodeStreamPayload(trimmed, deserializer, json))
            }
            true
        }
    }
}

/** As [asNdjsonFlow], with the serializer taken from the reified type. */
inline fun <reified T> ResponseBody.asNdjsonFlow(json: Json = RemoteJson): Flow<T> =
    asNdjsonFlow(serializer<T>(), json)
