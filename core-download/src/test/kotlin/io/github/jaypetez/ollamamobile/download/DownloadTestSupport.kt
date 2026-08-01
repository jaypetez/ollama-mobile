package io.github.jaypetez.ollamamobile.download

import java.io.File
import java.security.MessageDigest
import java.time.Duration
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer

/**
 * Shared fixtures for the download tests.
 *
 * These construct an `OkHttpClient` directly, which production code in this
 * repository is forbidden to do. That is deliberate and is why the Konsist rule
 * in `:core-common` scopes itself to `src/main`: a test needs its own timeouts,
 * and it must not be able to reach the app's real one.
 */
internal fun testOkHttpClient(): OkHttpClient = OkHttpClient
    .Builder()
    .connectTimeout(Duration.ofSeconds(5))
    .readTimeout(Duration.ofSeconds(5))
    .callTimeout(Duration.ZERO)
    .build()

internal fun testTransfer(client: OkHttpClient = testOkHttpClient()): ModelTransfer =
    ModelTransfer(client = client, io = Dispatchers.IO)

/** Deterministic pseudo-random bytes; a repeating pattern would hide an off-by-one splice. */
internal fun modelBytes(size: Int, seed: Int = 7): ByteArray = Random(seed).nextBytes(size)

internal fun sha256(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

internal fun sha256(file: File): String = sha256(file.readBytes())

/** A resolver that always returns the same URL and records how often it was asked. */
internal class RecordingResolver(
    private val urls: List<String>,
    private val headers: Map<String, String> = emptyMap(),
) : DownloadUrlResolver {
    val resolveCount: Int get() = calls
    private var calls = 0

    constructor(url: String, headers: Map<String, String> = emptyMap()) : this(listOf(url), headers)

    override suspend fun resolve(fileName: String): ResolvedDownload {
        val url = urls[minOf(calls, urls.lastIndex)]
        calls++
        return ResolvedDownload(url = url, headers = headers)
    }

    override suspend fun headersFor(url: String): Map<String, String> = headers
}

/**
 * A body that is shorter than the `Content-Length` it advertises, followed by
 * the socket closing.
 *
 * This is how an interrupted transfer is simulated without racing a timer: the
 * client reads what arrives, the stream ends early, and OkHttp raises the same
 * truncation error a dropped connection produces — leaving exactly [sent] bytes
 * in the `.part` file. The explicit close matters: without it the server keeps
 * the connection alive and the client waits out its read timeout instead, which
 * turns a one-millisecond test into a five-second one.
 */
internal fun truncatedResponse(
    code: Int,
    fullLength: Long,
    sent: ByteArray,
    headers: Headers = Headers.headersOf(),
    contentRange: String? = null,
): MockResponse {
    val builder = MockResponse
        .Builder()
        .code(code)
        .headers(headers)
        .body(Buffer().write(sent))
    contentRange?.let { builder.setHeader("Content-Range", it) }
    // Set last: `body()` writes its own Content-Length, and the lie is the point.
    builder.setHeader("Content-Length", fullLength.toString())
    builder.onResponseEnd(SocketEffect.CloseSocket(closeSocket = true, shutdownOutput = true, shutdownInput = false))
    return builder.build()
}

internal fun fullResponse(
    code: Int,
    bytes: ByteArray,
    headers: Headers = Headers.headersOf(),
    contentRange: String? = null,
): MockResponse {
    val builder = MockResponse
        .Builder()
        .code(code)
        .headers(headers)
        .body(Buffer().write(bytes))
    contentRange?.let { builder.setHeader("Content-Range", it) }
    return builder.build()
}

/** A quota manager that says yes to everything. */
internal class UnlimitedQuota : StorageQuotaManager {
    var lastRequestedBytes: Long? = null
        private set

    override suspend fun preflight(requiredBytes: Long): QuotaDecision {
        lastRequestedBytes = requiredBytes
        return QuotaDecision.Granted(reservedBytes = requiredBytes)
    }

    override suspend fun allocatableBytes(): Long = Long.MAX_VALUE
}

/** A quota manager that refuses everything, and records that it was consulted. */
internal class ExhaustedQuota(
    private val allocatable: Long = 0,
) : StorageQuotaManager {
    var consulted = false
        private set

    override suspend fun preflight(requiredBytes: Long): QuotaDecision {
        consulted = true
        return QuotaDecision.Refused(requiredBytes = requiredBytes, allocatableBytes = allocatable)
    }

    override suspend fun allocatableBytes(): Long = allocatable
}
