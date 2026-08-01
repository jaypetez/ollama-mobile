package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ModelId
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * What a probe of a pasted URL found.
 *
 * @property supportsRanges whether the server advertised `Accept-Ranges: bytes`.
 *   When false, an interrupted download of this URL has to start again from
 *   zero, and the UI should say so before a user commits to four gigabytes over
 *   a hotel connection.
 * @property sha256 always null. A pasted URL comes with no integrity reference
 *   at all, which is a real difference in guarantee from a catalogue entry and
 *   is stated rather than papered over.
 */
public data class CustomUrlModel(
    public val url: String,
    public val fileName: String,
    public val sizeBytes: Long? = null,
    public val supportsRanges: Boolean = false,
    public val storageDir: String,
    public val host: String,
    public val sha256: String? = null,
) {
    public fun toSource(): DownloadSource.CustomUrl = DownloadSource.CustomUrl(
        url = url,
        storageDir = storageDir,
        originLabel = host,
    )

    public fun toRequest(
        displayName: String = fileName,
        requireUnmeteredNetwork: Boolean = true,
        requireStorageNotLow: Boolean = true,
    ): DownloadRequest = DownloadRequest(
        modelId = ModelId("url:$storageDir/$fileName"),
        displayName = displayName,
        source = toSource(),
        files = listOf(RemoteFile(fileName = fileName, sizeBytes = sizeBytes, sha256 = null)),
        requireUnmeteredNetwork = requireUnmeteredNetwork,
        requireStorageNotLow = requireStorageNotLow,
    )
}

/**
 * The same download flow, for a URL the user pasted.
 *
 * Everything downstream of here is identical to a catalogue download —
 * [ModelTransfer] does the ranges, the resumption and the GGUF magic check — so
 * this type's whole job is to turn an arbitrary string into the same inputs a
 * catalogue entry produces, and to be honest about the two things it cannot
 * supply: a SHA-256, and any assurance about who is being downloaded from.
 */
@Singleton
public class CustomUrlSource
    @Inject
    constructor(
        /** The one shared client, so the LAN-only and offline policies still apply. */
        private val client: OkHttpClient,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Parses and probes [rawUrl].
         *
         * The probe is a `HEAD`, falling back to a one-byte `GET` because plenty
         * of object stores and file servers do not implement `HEAD` — and a
         * `Range: bytes=0-0` answered with 206 is also the most direct proof that
         * the server will honour a resume.
         */
        public suspend fun inspect(rawUrl: String): CustomUrlModel = withContext(io) {
            val url = parse(rawUrl)
            val probe = probe(url)
            val fileName = fileNameOf(url, probe.contentDisposition)
            CustomUrlModel(
                url = url.toString(),
                fileName = fileName,
                sizeBytes = probe.sizeBytes,
                supportsRanges = probe.supportsRanges,
                storageDir = storageDirFor(url, fileName),
                host = url.host,
            )
        }

        /**
         * A resolver that always hands back the same URL.
         *
         * A pasted URL has no origin to re-issue from, so an expiring signed URL
         * simply fails — correctly — rather than being retried into a loop.
         * Nothing here has credentials to attach either, so `headersFor` is left
         * at its empty default.
         */
        public fun resolverFor(source: DownloadSource.CustomUrl): DownloadUrlResolver =
            object : DownloadUrlResolver {
                override suspend fun resolve(fileName: String): ResolvedDownload =
                    ResolvedDownload(url = source.url)
            }

        private fun parse(rawUrl: String): HttpUrl {
            val url = rawUrl.trim().toHttpUrlOrNull()
                ?: throw DownloadError
                    .Transport(AppError.Unexpected(message = "That is not a valid http or https URL."))
                    .asException()
            // `toHttpUrlOrNull` already rejects every other scheme, but saying so
            // explicitly keeps the guarantee visible next to the thing that
            // depends on it: everything downstream assumes an HTTP transport.
            require(url.scheme == "http" || url.scheme == "https") { "Only http and https URLs can be downloaded." }
            return url
        }

        private data class Probe(
            val sizeBytes: Long?,
            val supportsRanges: Boolean,
            val contentDisposition: String?,
        )

        private suspend fun probe(url: HttpUrl): Probe {
            val head = try {
                execute(
                    Request
                        .Builder()
                        .url(url)
                        .head()
                        .build(),
                )
            } catch (e: DownloadException) {
                // Plenty of file servers and object stores answer HEAD with 405
                // or nothing useful. That is not a reason to refuse the URL, so
                // the failure is noted and the ranged GET decides.
                Timber.d(e, "HEAD is not supported by %s; probing with a one-byte range instead.", url.host)
                null
            }
            if (head != null && head.first) return head.second

            val ranged = execute(
                Request
                    .Builder()
                    .url(url)
                    .header(ModelTransfer.HEADER_RANGE, "bytes=0-0")
                    .header(ModelTransfer.HEADER_ACCEPT_ENCODING, "identity")
                    .build(),
            )
            if (!ranged.first) {
                throw DownloadError
                    .NotFound(what = url.encodedPath.substringAfterLast('/').ifEmpty { url.toString() })
                    .asException()
            }
            return ranged.second
        }

        private suspend fun execute(request: Request): Pair<Boolean, Probe> {
            val response = try {
                client.newCall(request).awaitResponse()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                throw DownloadHttpErrors.fromThrowable(e).asException()
            }
            return response.use {
                val total = ContentRange.parse(it.header(ModelTransfer.HEADER_CONTENT_RANGE))?.total
                    ?: it.header(HEADER_CONTENT_LENGTH)?.toLongOrNull()
                it.isSuccessful to Probe(
                    sizeBytes = total?.takeIf { size -> size > 0 },
                    supportsRanges = it.code == HTTP_PARTIAL_CONTENT ||
                        it.header(HEADER_ACCEPT_RANGES)?.contains("bytes", ignoreCase = true) == true,
                    contentDisposition = it.header(HEADER_CONTENT_DISPOSITION),
                )
            }
        }

        private companion object {
            const val HEADER_ACCEPT_RANGES = "Accept-Ranges"
            const val HEADER_CONTENT_DISPOSITION = "Content-Disposition"
            const val HEADER_CONTENT_LENGTH = "Content-Length"
            const val HTTP_PARTIAL_CONTENT = 206
            const val DIR_HASH_CHARS = 8
            const val FALLBACK_FILE_NAME = "model.gguf"

            private val FILENAME_STAR = Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE)
            private val FILENAME_PLAIN = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            private val UNSAFE = Regex("""[^A-Za-z0-9._-]""")

            /**
             * The last path segment, or what `Content-Disposition` says.
             *
             * The name matters beyond cosmetics: a shard keeps its
             * `-00001-of-00003.gguf` suffix or llama.cpp cannot find its
             * siblings. So the URL's own name wins, and the header is only a
             * fallback for the `/download?id=…` shape of URL.
             */
            fun fileNameOf(url: HttpUrl, contentDisposition: String?): String {
                val fromPath = url.pathSegments
                    .lastOrNull()
                    .orEmpty()
                    .substringBefore('?')
                if (fromPath.isNotBlank() && fromPath.contains('.')) return sanitise(fromPath)
                val fromHeader = contentDisposition?.let { header ->
                    FILENAME_STAR.find(header)?.groupValues?.get(1)
                        ?: FILENAME_PLAIN.find(header)?.groupValues?.get(1)
                }
                return sanitise(fromHeader?.takeIf { it.isNotBlank() } ?: FALLBACK_FILE_NAME)
            }

            fun sanitise(name: String): String = name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
                .replace(UNSAFE, "_")
                .trim('.', '_')
                .ifEmpty { FALLBACK_FILE_NAME }

            /**
             * `custom/<host>/<name>-<hash>`.
             *
             * The hash is over the full URL, so two files with the same basename
             * from different paths do not land in the same directory and
             * overwrite each other — which would otherwise present as a model
             * that silently changed.
             */
            fun storageDirFor(url: HttpUrl, fileName: String): String {
                val digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(url.toString().toByteArray(Charsets.UTF_8))
                    .toHexString()
                    .take(DIR_HASH_CHARS)
                val stem = fileName.substringBeforeLast('.').ifEmpty { "model" }
                return "custom/${sanitise(url.host)}/$stem-$digest"
            }
        }
    }
