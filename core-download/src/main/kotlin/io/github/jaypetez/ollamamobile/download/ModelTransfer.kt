package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.model.AppError
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

/** A URL that is valid *right now*, plus whatever headers its origin needs. */
public data class ResolvedDownload(
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
)

/**
 * Issues a download URL, and re-issues it when the previous one expires.
 *
 * The re-issue is not an edge case. Hugging Face's `/resolve` endpoint redirects
 * to a **signed** CloudFront URL with a short lifetime, so a download that is
 * paused overnight and resumed in the morning is holding a URL that will answer
 * 403 forever. Only something that can go back to the origin recovers from that,
 * hence this seam rather than a plain URL string.
 */
public interface DownloadUrlResolver {
    /** A fresh URL for [fileName]. May perform network I/O. */
    public suspend fun resolve(fileName: String): ResolvedDownload

    /**
     * Headers to attach when re-requesting a *stored* [url] directly.
     *
     * Implementations must return credentials only for hosts they own. A
     * resumed request goes straight to the CDN, and attaching a bearer token to
     * a pre-signed CloudFront URL both leaks the token and makes the request
     * fail — AWS rejects a request carrying two authentication mechanisms.
     */
    public suspend fun headersFor(url: String): Map<String, String> = emptyMap()
}

/** What to fetch, and where to put it. */
public data class TransferSpec(
    public val fileName: String,
    /** The `.part` file. Its length on disk is the only durable record of progress. */
    public val partFile: File,
    public val expectedSizeBytes: Long? = null,
    /** The LFS SHA-256. The single correctness guarantee; never a CDN `ETag`. */
    public val expectedSha256: String? = null,
    /** Only used to phrase errors: a repo id, or the host of a pasted URL. */
    public val originLabel: String = "",
)

/** What happened. The diagnostic fields are also what the resume tests assert on. */
public data class TransferOutcome(
    public val fileName: String,
    public val bytesWritten: Long,
    public val sha256: String,
    /** How many bytes this call was able to keep from a previous attempt. */
    public val resumedFromBytes: Long,
    /** True when a server answered 200 to a ranged request and the file began again from zero. */
    public val restartedFromZero: Boolean,
    /** The `If-Range` value actually put on the wire, or null when none was sent. */
    public val ifRangeSent: String? = null,
    /** True when a stored signed URL had expired and the origin was asked for a new one. */
    public val urlReissued: Boolean = false,
)

/**
 * The validator sidecar, written next to the `.part` file.
 *
 * ## Why this file exists — the ETag trap
 *
 * `https://huggingface.co/<repo>/resolve/<rev>/<file>` answers **302**, and that
 * redirect carries an `ETag` (and an `X-Linked-Etag`) which is the LFS object's
 * SHA-256. It is the obvious validator to grab, because it came from the URL
 * that was requested. It is the wrong one.
 *
 * The bytes come from a *different* host. The CDN generates its own `ETag` —
 * typically an MD5-derived multipart tag — with nothing in common with the LFS
 * digest. Send the redirect's value as `If-Range` and the CDN does exactly what
 * RFC 9110 requires of a non-matching validator: it **ignores the `Range` header
 * and returns 200 with the entire file**.
 *
 * There is no error, no warning and no log line. Every resume silently restarts
 * from byte zero. On a fast link during development nobody notices; on a user's
 * phone a 4 GB model interrupted three times transfers 16 GB, and the bug report
 * says "downloads are slow".
 *
 * So the validator recorded here is taken from the **final** response — the one
 * that actually carried bytes — and it is stored together with the **final** URL
 * it is valid against, because a validator detached from its resource is
 * meaningless.
 *
 * @property url the post-redirect URL the bytes came from.
 * @property validator the CDN's own `ETag` or `Last-Modified`. Never the LFS digest.
 * @property validatorHeader which of the two [validator] is, for the log.
 * @property totalBytes the `Content-Range` total, cross-checked on every resume.
 */
@Serializable
internal data class ResumeState(
    val url: String,
    val validator: String? = null,
    val validatorHeader: String? = null,
    val totalBytes: Long? = null,
)

/** [ModelTransfer.stream]'s two outputs. */
private data class StreamResult(
    val bytesOnDisk: Long,
    val sha256: String,
)

/**
 * Moves the bytes, and knows how to pick up where it left off.
 *
 * Resumption is not an optimisation here, it is the feature: a multi-gigabyte
 * transfer to a phone *will* be interrupted. What makes it hard is that the
 * common failure is silent — see [ResumeState] — so the rules below are asserted
 * by tests rather than trusted:
 *
 *  * Progress is read from the `.part` file's length, never from a counter in
 *    memory. The process will be killed; the file length is the only durable
 *    truth.
 *  * A 206 is checked, not assumed: the `Content-Range` start must equal what
 *    was asked for, and its total must equal the expected size.
 *  * A **200 answer to a ranged request means start over**, never append. A full
 *    body appended to a partial file produces a too-long file that fails its
 *    hash with no indication why.
 *  * The SHA-256 from the Hub's LFS metadata is the correctness guarantee. The
 *    validator dance only saves bandwidth.
 */
@Singleton
public class ModelTransfer
    @Inject
    constructor(
        /**
         * The one shared client, injected. Building a second one here would be a
         * second path to the network that `LanOnlyGuard` does not police, and a
         * Konsist test in `:core-common` fails the build over it.
         */
        private val client: OkHttpClient,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Downloads (or continues downloading) one file into [TransferSpec.partFile].
         *
         * [onProgress] receives the total bytes *on disk*, so it is monotonic
         * across a resume and can be summed across a shard set.
         */
        public suspend fun download(
            spec: TransferSpec,
            resolver: DownloadUrlResolver,
            onProgress: suspend (bytesOnDisk: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        ): TransferOutcome = withContext(io) { transfer(spec, resolver, onProgress) }

        private suspend fun transfer(
            spec: TransferSpec,
            resolver: DownloadUrlResolver,
            onProgress: suspend (Long, Long?) -> Unit,
        ): TransferOutcome {
            spec.partFile.parentFile?.mkdirs()
            val sidecar = sidecarOf(spec.partFile)
            var state = readState(sidecar)
            var reissued = false
            var restarted = false

            alreadyComplete(spec, sidecar)?.let { return it }

            repeat(MAX_ATTEMPTS) {
                val have = startOffset(spec)
                val plan = plan(spec, resolver, state, have)
                val response = execute(plan, spec)
                val decision = decide(response, spec, plan)
                if (decision is Decision.StaleUrl) {
                    Timber.i("The stored URL for %s expired; asking the origin for a new one.", spec.fileName)
                    response.close()
                    state = null
                    reissued = true
                    return@repeat
                }
                val append = decision is Decision.Append
                if (!append && have > 0) restarted = true
                writeState(sidecar, decision.state)
                val result = response.use {
                    stream(it, spec, from = if (append) have else 0L, total = decision.totalBytes, onProgress)
                }
                return finish(
                    spec = spec,
                    sidecar = sidecar,
                    result = result,
                    resumedFrom = if (append) have else 0L,
                    restarted = restarted,
                    ifRangeSent = plan.ifRange,
                    reissued = reissued,
                )
            }
            throw DownloadError
                .Transport(
                    AppError.Network.Unreachable(
                        message = "The server kept refusing to continue ${spec.fileName} after $MAX_ATTEMPTS tries.",
                    ),
                ).asException()
        }

        // ------------------------------------------------------------ planning

        private data class Plan(
            val request: Request,
            val rangeFrom: Long?,
            val ifRange: String?,
            /** True when the request went to a *stored* URL rather than a freshly issued one. */
            val reusingStoredUrl: Boolean,
        )

        private suspend fun plan(
            spec: TransferSpec,
            resolver: DownloadUrlResolver,
            state: ResumeState?,
            have: Long,
        ): Plan {
            val reuse = have > 0 && state != null
            val url: String
            val headers: Map<String, String>
            if (reuse) {
                url = state.url
                headers = resolver.headersFor(state.url)
            } else {
                val resolved = resolver.resolve(spec.fileName)
                url = resolved.url
                headers = resolved.headers
            }
            // Only a validator that came from the very URL being re-requested may
            // be used. Without one a bare Range is still correct: a 200 answer is
            // handled as "start over", not as data to append.
            val ifRange = if (reuse && have > 0) state.validator else null

            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            // Transparent gzip must be off. OkHttp adds `Accept-Encoding: gzip`
            // and silently inflates the body when it added the header itself —
            // which would make the bytes written bear no relation to the byte
            // offsets in Range and Content-Range, and turn every resume into
            // corruption. Setting the header explicitly disables that path.
            builder.header(HEADER_ACCEPT_ENCODING, IDENTITY)
            if (have > 0) {
                builder.header(HEADER_RANGE, "bytes=$have-")
                ifRange?.let { builder.header(HEADER_IF_RANGE, it) }
            }
            return Plan(
                request = builder.build(),
                rangeFrom = have.takeIf { it > 0 },
                ifRange = ifRange?.takeIf { have > 0 },
                reusingStoredUrl = reuse,
            )
        }

        private suspend fun execute(plan: Plan, spec: TransferSpec): Response {
            val response = try {
                client.newCall(plan.request).awaitResponse()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                throw DownloadHttpErrors.fromThrowable(e).asException()
            }
            Timber.d(
                "%s -> HTTP %d (range=%s, if-range=%s)",
                spec.fileName,
                response.code,
                plan.rangeFrom?.toString() ?: "none",
                plan.ifRange ?: "none",
            )
            return response
        }

        // ----------------------------------------------------------- decisions

        private sealed interface Decision {
            val state: ResumeState?
            val totalBytes: Long?

            /** 206 with a matching `Content-Range`: continue where we left off. */
            data class Append(
                override val state: ResumeState,
                override val totalBytes: Long?,
            ) : Decision

            /** 200: the whole file is on its way, so any partial bytes are void. */
            data class Restart(
                override val state: ResumeState,
                override val totalBytes: Long?,
            ) : Decision

            /** The stored signed URL has expired. Go back to the origin. */
            data object StaleUrl : Decision {
                override val state: ResumeState? get() = null
                override val totalBytes: Long? get() = null
            }
        }

        private fun decide(response: Response, spec: TransferSpec, plan: Plan): Decision = when {
            response.code == HTTP_PARTIAL_CONTENT -> partial(response, spec, plan)

            response.code == HTTP_OK -> full(response, spec)

            // 403 and 410 against a URL stored earlier mean the signature
            // expired, not that access was lost — the same URL worked an hour
            // ago. Only reachable when the URL was reused, so a genuine 403 from
            // the origin still surfaces as an error.
            plan.reusingStoredUrl && response.code in STALE_URL_CODES -> Decision.StaleUrl

            else -> throw failure(response, spec).asException()
        }

        private fun failure(response: Response, spec: TransferSpec): DownloadError {
            val body = runCatching { response.body.string() }.getOrNull()
            val error = DownloadHttpErrors.fromResponse(response, spec.originLabel, spec.fileName, body)
            response.close()
            return error
        }

        private fun partial(response: Response, spec: TransferSpec, plan: Plan): Decision {
            val range = ContentRange.parse(response.header(HEADER_CONTENT_RANGE))
                ?: throw protocolFailure(response, "sent 206 with no usable Content-Range")
            // A 206 for a range other than the one asked for is possible through
            // a misbehaving proxy, and appending it would corrupt the file in a
            // way only the final hash would catch.
            if (range.start != (plan.rangeFrom ?: 0L)) {
                throw protocolFailure(
                    response,
                    "was asked for bytes from ${plan.rangeFrom ?: 0L} and answered from ${range.start}",
                )
            }
            val total = checkedTotal(spec, range.total, response)
            return Decision.Append(state = captureValidator(response, total), totalBytes = total)
        }

        private fun full(response: Response, spec: TransferSpec): Decision {
            val declared = response.body.contentLength().takeIf { it >= 0 }
            val total = checkedTotal(spec, declared, response)
            return Decision.Restart(state = captureValidator(response, total), totalBytes = total)
        }

        private fun protocolFailure(response: Response, what: String): DownloadException {
            val code = response.code
            response.close()
            return DownloadError
                .Transport(AppError.Network.Http(code = code, message = "The server $what."))
                .asException()
        }

        /**
         * Cross-checks the server's idea of the total length against the
         * catalogue's, on every response rather than only the first.
         *
         * A proxy serving a range request out of a stale or different object is
         * what this catches; without it the disagreement only surfaces as a hash
         * mismatch after the whole file has been transferred.
         */
        private fun checkedTotal(spec: TransferSpec, total: Long?, response: Response): Long? {
            val expected = spec.expectedSizeBytes
            if (expected != null && total != null && total != expected) {
                response.close()
                throw DownloadError
                    .SizeMismatch(fileName = spec.fileName, expectedBytes = expected, serverBytes = total)
                    .asException()
            }
            return total ?: expected
        }

        /**
         * Reads the validator off the response that carried the bytes.
         *
         * `response.request.url` is the URL **after** redirects, and
         * `response.headers` are that final response's headers.
         * `response.priorResponse` — the 302 from huggingface.co — is
         * deliberately never consulted: its `ETag` and [HEADER_LINKED_ETAG] hold
         * the LFS SHA-256, a value the CDN has never heard of. See [ResumeState].
         *
         * A weak `ETag` (`W/"…"`) is skipped rather than used: RFC 9110 permits
         * only a strong validator in `If-Range`, and a compliant server given a
         * weak one falls back to 200 — the exact silent restart this mechanism
         * exists to avoid.
         */
        private fun captureValidator(response: Response, total: Long?): ResumeState {
            val etag = response.header(HEADER_ETAG)?.takeUnless { it.startsWith(WEAK_ETAG_PREFIX) }
            val lastModified = response.header(HEADER_LAST_MODIFIED)
            val validator = etag ?: lastModified
            return ResumeState(
                url = response.request.url.toString(),
                validator = validator,
                validatorHeader = if (etag != null) HEADER_ETAG else lastModified?.let { HEADER_LAST_MODIFIED },
                totalBytes = total,
            )
        }

        // ------------------------------------------------------------ transfer

        private suspend fun stream(
            response: Response,
            spec: TransferSpec,
            from: Long,
            total: Long?,
            onProgress: suspend (Long, Long?) -> Unit,
        ): StreamResult {
            val digest = MessageDigest.getInstance(SHA_256)
            // The JDK cannot export a MessageDigest's intermediate state, so a
            // running digest cannot survive process death in a sidecar.
            // Re-reading the prefix already on disk is the honest alternative:
            // one sequential local read of the bytes we already have, and none
            // of the bytes we are about to write.
            if (from > 0) seedDigest(digest, spec.partFile, from)

            var onDisk = from
            var lastReported = from
            var interrupted: Throwable? = null
            RandomAccessFile(spec.partFile, "rw").use { out ->
                if (from == 0L) out.setLength(0)
                out.seek(from)
                try {
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            onDisk += read
                            if (onDisk - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = onDisk
                                onProgress(onDisk, total)
                            }
                        }
                    }
                } catch (e: IOException) {
                    // The connection dropped mid-body — a tunnel, a network
                    // switch, a CDN closing the socket. The bytes already written
                    // are perfectly good and are the resume point, so the file is
                    // flushed before the failure is reported rather than lost
                    // with it.
                    interrupted = e
                }
                out.fd.sync()
            }
            onProgress(onDisk, total)

            val expected = total ?: spec.expectedSizeBytes
            if (interrupted != null || (expected != null && onDisk < expected)) {
                throw DownloadError
                    .Transport(
                        AppError.Network.Unreachable(
                            message = "${spec.fileName} stopped at $onDisk of ${expected ?: "?"} bytes.",
                            cause = interrupted,
                        ),
                    ).asException()
            }
            return StreamResult(bytesOnDisk = onDisk, sha256 = digest.digest().toHexString())
        }

        private fun seedDigest(digest: MessageDigest, file: File, length: Long) {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
        }

        /**
         * A `.part` already at full length is not an error, it is a process that
         * was killed between the last byte and the verification. Hash it and
         * finish rather than issuing a zero-length range request the server will
         * answer 416 to.
         */
        private fun alreadyComplete(spec: TransferSpec, sidecar: File): TransferOutcome? {
            val expected = spec.expectedSizeBytes ?: return null
            if (!spec.partFile.isFile || spec.partFile.length() != expected) return null
            val digest = MessageDigest.getInstance(SHA_256)
            seedDigest(digest, spec.partFile, expected)
            return finish(
                spec = spec,
                sidecar = sidecar,
                result = StreamResult(bytesOnDisk = expected, sha256 = digest.digest().toHexString()),
                resumedFrom = expected,
                restarted = false,
                ifRangeSent = null,
                reissued = false,
            )
        }

        private fun finish(
            spec: TransferSpec,
            sidecar: File,
            result: StreamResult,
            resumedFrom: Long,
            restarted: Boolean,
            ifRangeSent: String?,
            reissued: Boolean,
        ): TransferOutcome {
            val expected = spec.expectedSha256
            if (expected != null && !expected.equals(result.sha256, ignoreCase = true)) {
                // Never keep a file that failed its hash and never offer to use
                // it anyway: a GGUF with a flipped bit often loads and produces
                // subtly wrong output rather than failing, which is worse than a
                // failed download.
                spec.partFile.delete()
                sidecar.delete()
                throw DownloadError
                    .IntegrityMismatch(
                        fileName = spec.fileName,
                        expectedSha256 = expected,
                        actualSha256 = result.sha256,
                    ).asException()
            }
            sidecar.delete()
            return TransferOutcome(
                fileName = spec.fileName,
                bytesWritten = result.bytesOnDisk,
                sha256 = result.sha256,
                resumedFromBytes = resumedFrom,
                restartedFromZero = restarted,
                ifRangeSent = ifRangeSent,
                urlReissued = reissued,
            )
        }

        // ------------------------------------------------------------- sidecar

        private fun startOffset(spec: TransferSpec): Long {
            val have = spec.partFile.takeIf { it.isFile }?.length() ?: 0L
            val expected = spec.expectedSizeBytes
            if (expected != null && have > expected) {
                // Longer than the file can possibly be: an earlier run appended a
                // full body onto a partial one. Nothing about those bytes is
                // trustworthy.
                Timber.w("%s is longer than it should be (%d > %d); starting again.", spec.fileName, have, expected)
                spec.partFile.delete()
                sidecarOf(spec.partFile).delete()
                return 0L
            }
            return have
        }

        private fun readState(sidecar: File): ResumeState? {
            if (!sidecar.isFile) return null
            return try {
                DownloadJson.decodeFromString(ResumeState.serializer(), sidecar.readText())
            } catch (e: SerializationException) {
                Timber.w(e, "Unreadable resume sidecar %s; resuming without a validator.", sidecar.name)
                null
            } catch (e: IOException) {
                Timber.w(e, "Could not read resume sidecar %s.", sidecar.name)
                null
            }
        }

        private fun writeState(sidecar: File, state: ResumeState?) {
            if (state == null) return
            try {
                sidecar.writeText(DownloadJson.encodeToString(ResumeState.serializer(), state))
            } catch (e: IOException) {
                // Losing the sidecar costs a bandwidth optimisation, not
                // correctness: the next attempt sends a bare Range instead.
                Timber.w(e, "Could not persist the resume validator for %s.", sidecar.name)
            }
        }

        public companion object {
            public const val RESUME_SUFFIX: String = ".resume"

            internal const val HEADER_RANGE = "Range"
            internal const val HEADER_IF_RANGE = "If-Range"
            internal const val HEADER_CONTENT_RANGE = "Content-Range"
            internal const val HEADER_ETAG = "ETag"
            internal const val HEADER_LAST_MODIFIED = "Last-Modified"
            internal const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"

            /**
             * The header the huggingface.co redirect carries the LFS digest in.
             *
             * Named here **only** so that a reader looking for it finds this
             * comment: it is never read as a resume validator, because it is the
             * SHA-256 of the content and no CDN will ever match an `If-Range`
             * against it. Integrity comes from the Hub's tree metadata instead.
             */
            internal const val HEADER_LINKED_ETAG = "X-Linked-Etag"

            private const val IDENTITY = "identity"
            private const val HTTP_OK = 200
            private const val HTTP_PARTIAL_CONTENT = 206
            private val STALE_URL_CODES = setOf(403, 410)

            private const val WEAK_ETAG_PREFIX = "W/"
            private const val SHA_256 = "SHA-256"
            private const val BUFFER_BYTES = 1 shl 16

            /** Coarse enough that WorkManager's progress writes do not dominate the transfer. */
            private const val PROGRESS_STEP_BYTES = 1L shl 20

            /**
             * Initial attempt, one re-issue of an expired URL, and one spare.
             * Bounded because "resolve, 403, resolve, 403" is a live-lock.
             */
            private const val MAX_ATTEMPTS = 4

            internal fun sidecarOf(partFile: File): File = File(partFile.path + RESUME_SUFFIX)
        }
    }
