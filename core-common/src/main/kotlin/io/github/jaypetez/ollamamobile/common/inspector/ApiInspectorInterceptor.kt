package io.github.jaypetez.ollamamobile.common.inspector

import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * Captures traffic into [ApiInspector] for the developer-tools screen.
 *
 * Registered as an application interceptor so what it records is the request
 * the app made, not the request after OkHttp added `Accept-Encoding`, a cookie
 * and a `Content-Length`. When you are debugging "why did the server reject my
 * call", the app's own view is the one you want.
 */
@Singleton
class ApiInspectorInterceptor
    @Inject
    constructor(
        private val inspector: ApiInspector,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!inspector.enabled) return chain.proceed(request)

            val startedAt = System.currentTimeMillis()
            val started = capture(request, startedAt)
            inspector.record(started)

            val response = try {
                chain.proceed(request)
            } catch (failure: IOException) {
                inspector.record(
                    started.copy(
                        durationMillis = System.currentTimeMillis() - startedAt,
                        failure = "${failure::class.java.simpleName}: ${failure.message}",
                    ),
                )
                throw failure
            }

            inspector.record(started.merge(response, durationMillis = System.currentTimeMillis() - startedAt))
            return response
        }

        private fun capture(request: Request, startedAt: Long): ApiExchange {
            val body = request.body
            val preview = requestBodyPreview(request)
            return ApiExchange(
                id = inspector.nextExchangeId(),
                startedAtMillis = startedAt,
                method = request.method,
                url = request.url.toString(),
                requestHeaders = redact(request.headers),
                requestBodyPreview = preview?.text,
                requestBodyBytes = body?.contentLength()?.takeIf { it >= 0 },
                requestBodyTruncated = preview?.truncated == true,
            )
        }

        private fun ApiExchange.merge(response: Response, durationMillis: Long): ApiExchange {
            val preview = responseBodyPreview(response)
            return copy(
                protocol = response.protocol.toString(),
                statusCode = response.code,
                statusMessage = response.message,
                responseHeaders = redact(response.headers),
                responseBodyPreview = preview.text,
                responseBodyTruncated = preview.truncated,
                responseBodyOmittedReason = preview.omittedReason,
                durationMillis = durationMillis,
            )
        }

        private fun redact(headers: Headers): List<InspectedHeader> =
            (0 until headers.size).map { index ->
                ApiInspector.redact(headers.name(index), headers.value(index))
            }

        private fun requestBodyPreview(request: Request): BodyPreview? {
            val body = request.body ?: return null
            // A one-shot or duplex body can be read exactly once, and that once
            // belongs to the network. Buffering it here would send an empty
            // request — an inspector that changes the traffic it inspects.
            if (body.isOneShot() || body.isDuplex()) return null
            return try {
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readPreview(body.contentType()?.charset(), MAX_BODY_CHARS)
            } catch (_: IOException) {
                null
            }
        }

        private fun responseBodyPreview(response: Response): BodyPreview {
            val contentType = response.body.contentType()
            val mediaType = "${contentType?.type}/${contentType?.subtype}".lowercase()
            // THE TRAP: `peekBody(n)` blocks until it has n bytes or the stream
            // ends. An SSE or NDJSON response never ends, so peeking one would
            // hang the call forever — the inspector would deadlock exactly the
            // feature it exists to debug. Streaming responses are therefore
            // recorded by their headers alone.
            if (mediaType in STREAMING_MEDIA_TYPES) {
                return BodyPreview(text = null, truncated = false, omittedReason = "streaming response")
            }
            return try {
                val peeked = response.peekBody(MAX_BODY_CHARS.toLong())
                BodyPreview(
                    text = peeked.string().take(MAX_BODY_CHARS),
                    truncated = (response.body.contentLength() > MAX_BODY_CHARS) ||
                        peeked.contentLength() >= MAX_BODY_CHARS,
                )
            } catch (failure: IOException) {
                BodyPreview(text = null, truncated = false, omittedReason = failure.message ?: "unreadable body")
            }
        }

        private fun Buffer.readPreview(charset: Charset?, limit: Int): BodyPreview {
            val truncated = size > limit
            val slice = if (truncated) readByteString(limit.toLong()) else readByteString()
            return BodyPreview(
                text = slice.string(charset ?: StandardCharsets.UTF_8),
                truncated = truncated,
            )
        }

        private data class BodyPreview(
            val text: String?,
            val truncated: Boolean,
            val omittedReason: String? = null,
        )

        companion object {
            /**
             * A few kilobytes: enough to see the prompt and the first tokens of
             * a reply, small enough that a hundred captured exchanges cannot
             * meaningfully grow the heap.
             */
            const val MAX_BODY_CHARS: Int = 8 * 1024

            private val STREAMING_MEDIA_TYPES = setOf(
                "text/event-stream",
                "application/x-ndjson",
                "application/x-jsonlines",
                "application/octet-stream",
            )
        }
    }
