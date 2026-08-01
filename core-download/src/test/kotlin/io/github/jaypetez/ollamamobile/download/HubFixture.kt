package io.github.jaypetez.ollamamobile.download

import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers

/**
 * A stand-in for huggingface.co **and** its CDN, on one socket.
 *
 * The two are modelled separately on purpose, because their difference is the
 * whole subject of [ModelTransferResumeTest]:
 *
 *  * `/{repo}/resolve/{rev}/{file}` answers **302** and carries [lfsEtag] in
 *    both `ETag` and `X-Linked-Etag`. That value is the LFS object's SHA-256.
 *  * `/cdn/{file}?sig=N` answers with the bytes and carries [CDN_ETAG], an
 *    entirely different, MD5-derived, multipart-style tag.
 *
 * The CDN also behaves the way RFC 9110 requires and a real CDN does: given an
 * `If-Range` that does not match its own validator, it **ignores `Range` and
 * returns 200 with the whole file**. That is what turns the ETag mix-up into a
 * silent restart rather than an error, and it is what makes the regression test
 * fail if anyone reintroduces it.
 */
internal class HubFixture(
    private val content: ByteArray,
    private val repo: String = "acme/tiny-gguf",
    private val revision: String = "main",
    val fileName: String = "tiny.gguf",
) {
    val server: MockWebServer = MockWebServer()

    /** Every request the fixture saw, in order. */
    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    /** The LFS digest, which is also what the redirect advertises as its ETag. */
    val lfsEtag: String = "\"${sha256(content)}\""

    val sha256: String = sha256(content)

    /** Bumped by [expireSignedUrl]; the CDN rejects any older signature. */
    private var signature = 1

    /** How many bytes a first, interrupted attempt delivers before the socket ends. */
    var truncateAfter: Int? = null

    /** Set to make the CDN ignore `Range` entirely and answer 200, whatever the validator. */
    var cdnIgnoresRange: Boolean = false

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                return when {
                    path == resolvePath -> redirect()
                    path == cdnPath -> cdn(request)
                    else -> MockResponse.Builder().code(HTTP_NOT_FOUND).build()
                }
            }
        }
        server.start()
    }

    fun close() {
        server.close()
    }

    /** The `/resolve` URL the app asks for; the same one a resolver would build. */
    fun resolveUrl(): String = server.url(resolvePath).toString()

    /** Invalidates the signature, so the stored CDN URL starts answering 403. */
    fun expireSignedUrl() {
        signature++
    }

    fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.url.encodedPath == path }

    val cdnRequests: List<RecordedRequest> get() = requestsTo(cdnPath)

    val resolveRequests: List<RecordedRequest> get() = requestsTo(resolvePath)

    private val resolvePath = "/$repo/resolve/$revision/$fileName"
    private val cdnPath = "/cdn/$fileName"

    private fun redirect(): MockResponse = MockResponse
        .Builder()
        .code(HTTP_FOUND)
        .setHeader("Location", "$cdnPath?sig=$signature")
        // Both of these are the LFS SHA-256. Neither is a CDN validator, and
        // neither may ever reach an If-Range header.
        .setHeader("ETag", lfsEtag)
        .setHeader("X-Linked-Etag", lfsEtag)
        .setHeader("X-Linked-Size", content.size.toString())
        .build()

    private fun cdn(request: RecordedRequest): MockResponse {
        val presented = request.url.queryParameter("sig")?.toIntOrNull()
        if (presented != signature) {
            // What CloudFront does with an expired signature.
            return MockResponse
                .Builder()
                .code(HTTP_FORBIDDEN)
                .body("Request has expired")
                .build()
        }

        val range = request.headers["Range"]
        val ifRange = request.headers["If-Range"]
        val validatorMatches = ifRange == null || ifRange == CDN_ETAG

        if (range == null || cdnIgnoresRange || !validatorMatches) return whole()

        val from = RANGE
            .find(range)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: return whole()
        val slice = content.copyOfRange(from, content.size)
        val contentRange = "bytes $from-${content.size - 1}/${content.size}"
        val limit = truncateAfter
        return if (limit != null && limit < slice.size) {
            truncatedResponse(
                code = HTTP_PARTIAL_CONTENT,
                fullLength = slice.size.toLong(),
                sent = slice.copyOf(limit),
                headers = cdnHeaders(),
                contentRange = contentRange,
            )
        } else {
            fullResponse(HTTP_PARTIAL_CONTENT, slice, cdnHeaders(), contentRange)
        }
    }

    private fun whole(): MockResponse {
        val limit = truncateAfter
        return if (limit != null && limit < content.size) {
            truncatedResponse(
                code = HTTP_OK,
                fullLength = content.size.toLong(),
                sent = content.copyOf(limit),
                headers = cdnHeaders(),
            )
        } else {
            fullResponse(HTTP_OK, content, cdnHeaders())
        }
    }

    private fun cdnHeaders(): Headers = Headers.headersOf(
        "ETag",
        CDN_ETAG,
        "Last-Modified",
        LAST_MODIFIED,
        "Accept-Ranges",
        "bytes",
    )

    companion object {
        /** Nothing like the LFS digest, which is exactly the point. */
        const val CDN_ETAG: String = "\"3f7a1c2e9b0d4f6a8c1e2d3b4a5f6071-3\""
        const val LAST_MODIFIED: String = "Wed, 21 Oct 2026 07:28:00 GMT"

        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_FOUND = 302
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404

        private val RANGE = Regex("""bytes=(\d+)-""")
    }
}
