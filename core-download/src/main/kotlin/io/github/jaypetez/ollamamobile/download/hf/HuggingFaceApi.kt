package io.github.jaypetez.ollamamobile.download.hf

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.download.DownloadError
import io.github.jaypetez.ollamamobile.download.DownloadHttpErrors
import io.github.jaypetez.ollamamobile.download.DownloadJson
import io.github.jaypetez.ollamamobile.download.DownloadRequest
import io.github.jaypetez.ollamamobile.download.DownloadSource
import io.github.jaypetez.ollamamobile.download.DownloadUrlResolver
import io.github.jaypetez.ollamamobile.download.RemoteFile
import io.github.jaypetez.ollamamobile.download.ResolvedDownload
import io.github.jaypetez.ollamamobile.download.ShardedModelResolver
import io.github.jaypetez.ollamamobile.download.asException
import io.github.jaypetez.ollamamobile.download.awaitResponse
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GgufMetadata
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.storage.gguf.GgufHeaderParser
import io.github.jaypetez.ollamamobile.storage.gguf.HttpRangeGgufSource
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** The Hub's origin. A binding so tests can point the client at a local server. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class HuggingFaceBaseUrl

/**
 * Supplies the user's Hugging Face access token, or null when they have not
 * given one.
 *
 * A seam rather than a direct `SecretsStore` call, because `:core-download` must
 * not reach into the Keystore itself: the token is entered in Settings, stored
 * by `:core-storage`, and bound into this graph at assembly. See
 * `DownloadModule`.
 */
public fun interface HuggingFaceTokenProvider {
    /** The raw `hf_…` token. Never logged, never written to a sidecar. */
    public suspend fun token(): String?
}

/**
 * The read-only slice of the Hugging Face Hub API this app needs.
 *
 * Three jobs: find GGUF models, list what is in a repository with each file's
 * real size and SHA-256, and turn a filename into a URL. Everything else about
 * a download — ranges, resumption, integrity — is [io.github.jaypetez.ollamamobile.download.ModelTransfer]'s.
 *
 * ## Gating
 *
 * A gated repository answers 401 (or 403) with `X-Error-Code: GatedRepo`, and it
 * does so **even for a valid token** until the account behind that token has
 * accepted the model's terms on the website. Treating it as an authentication
 * failure sends the user round a sign-in loop that cannot succeed, so it maps to
 * [DownloadError.GatedRepo] and the UI offers the licence page instead. See
 * `DownloadHttpErrors`.
 */
@Singleton
public class HuggingFaceApi
    @Inject
    constructor(
        /** The one shared client. Never construct another; see `:core-common`. */
        private val client: OkHttpClient,
        private val tokens: HuggingFaceTokenProvider,
        @param:HuggingFaceBaseUrl private val baseUrl: String,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Models that ship GGUF, most-downloaded first.
         *
         * `filter=gguf` is applied server-side. Filtering client-side instead
         * would mean paging through the whole of the Hub to find the handful of
         * repositories that are relevant, on a phone, over the user's data.
         */
        public suspend fun searchGgufModels(
            query: String,
            limit: Int = DEFAULT_SEARCH_LIMIT,
        ): List<HfModelInfo> = withContext(io) {
            val url = apiUrl("api/models")
                .newBuilder()
                .addQueryParameter("search", query)
                .addQueryParameter("filter", GGUF_FILTER)
                .addQueryParameter("sort", "downloads")
                .addQueryParameter("direction", "-1")
                .addQueryParameter("limit", limit.coerceIn(1, MAX_SEARCH_LIMIT).toString())
                // Without this the response omits `siblings`, and a search hit
                // with no file list cannot be turned into a download.
                .addQueryParameter("full", "true")
                .build()
            val body = get(url.toString(), repo = query, what = "the model search")
            decode(body) { DownloadJson.decodeFromString(ListSerializer(HfModelInfo.serializer()), it) }
                .filter { it.hasGguf }
        }

        /** Everything the Hub knows about one repository at one revision. */
        public suspend fun modelInfo(
            repo: String,
            revision: String = DownloadSource.HuggingFace.DEFAULT_REVISION,
        ): HfModelInfo = withContext(io) {
            val url = apiUrl("api/models/$repo/revision/${encode(revision)}")
            val body = get(url.toString(), repo = repo, what = repo)
            decode(body) { DownloadJson.decodeFromString(HfModelInfo.serializer(), it) }
        }

        /**
         * Every file in the repository, with its real size and SHA-256.
         *
         * Paginated by the Hub with `Link: …; rel="next"`, which is followed up
         * to [MAX_TREE_PAGES]. A repository with more pages than that is not one
         * this app should be downloading from.
         */
        public suspend fun listFiles(
            repo: String,
            revision: String = DownloadSource.HuggingFace.DEFAULT_REVISION,
        ): List<HfTreeEntry> = withContext(io) {
            var next: String? = apiUrl("api/models/$repo/tree/${encode(revision)}")
                .newBuilder()
                .addQueryParameter("recursive", "1")
                .build()
                .toString()
            val entries = mutableListOf<HfTreeEntry>()
            var page = 0
            while (next != null && page < MAX_TREE_PAGES) {
                val (body, link) = getWithLink(next, repo = repo, what = "$repo tree")
                entries += decode(body) {
                    DownloadJson.decodeFromString(ListSerializer(HfTreeEntry.serializer()), it)
                }
                next = nextLink(link)
                page++
            }
            entries.filter { it.isFile }
        }

        /** The GGUF files of a repository, as download inputs. */
        public suspend fun listGgufFiles(
            repo: String,
            revision: String = DownloadSource.HuggingFace.DEFAULT_REVISION,
        ): List<RemoteFile> = listFiles(repo, revision)
            .filter { it.isGguf }
            .map { RemoteFile(fileName = it.path, sizeBytes = it.contentSize, sha256 = it.sha256) }

        /**
         * The download URL for one file.
         *
         * Prefer a commit SHA for [revision]. `main` is a moving pointer: the
         * bytes behind it can change between the day a hash was recorded and the
         * day a user downloads, and the mismatch is indistinguishable from
         * corruption.
         *
         * This URL **redirects** to a signed CDN URL. That redirect is where the
         * most damaging bug in the whole download path lives — see
         * [io.github.jaypetez.ollamamobile.download.ResumeState].
         */
        public fun resolveUrl(repo: String, revision: String, fileName: String): String = apiUrl(
            "$repo/resolve/${encode(revision)}/${fileName.split('/').joinToString("/") { encode(it) }}",
        ).toString()

        /**
         * A resolver bound to one repository, for [io.github.jaypetez.ollamamobile.download.ModelTransfer].
         *
         * [DownloadUrlResolver.headersFor] returns the bearer token only for Hub
         * URLs. A resumed request goes straight to the CDN, and attaching an
         * `Authorization` header to a pre-signed URL both leaks the token and
         * makes the request fail.
         */
        public fun resolverFor(source: DownloadSource.HuggingFace): DownloadUrlResolver =
            object : DownloadUrlResolver {
                override suspend fun resolve(fileName: String): ResolvedDownload = ResolvedDownload(
                    url = resolveUrl(source.repo, source.revision, fileName),
                    headers = authHeaders(),
                )

                override suspend fun headersFor(url: String): Map<String, String> =
                    if (isHubUrl(url)) authHeaders() else emptyMap()
            }

        /**
         * Turns "this repo, this file" into a complete unit of work.
         *
         * This is where a catalogue entry with a null size and a null hash gets
         * both, and where a request for one shard quietly becomes a request for
         * the whole set — see [ShardedModelResolver]. A shard set with a part
         * missing fails here, before any bytes move, rather than after four of
         * five parts have transferred.
         */
        public suspend fun downloadRequest(
            modelId: ModelId,
            displayName: String,
            source: DownloadSource.HuggingFace,
            fileName: String,
            requireUnmeteredNetwork: Boolean = true,
            requireStorageNotLow: Boolean = true,
        ): DownloadRequest {
            val available = listGgufFiles(source.repo, source.revision)
            val requested = available.firstOrNull { it.fileName == fileName }
                ?: throw DownloadError.NotFound(what = "${source.repo}/$fileName").asException()
            return DownloadRequest(
                modelId = modelId,
                displayName = displayName,
                source = source,
                files = ShardedModelResolver.expand(requested, available),
                requireUnmeteredNetwork = requireUnmeteredNetwork,
                requireStorageNotLow = requireStorageNotLow,
            )
        }

        /**
         * GGUF metadata for one file, Hub-first.
         *
         * The Hub parses headers server-side and publishes the result, which is
         * one small JSON response instead of a chain of range requests that grow
         * to megabytes on a file with a large chat template. It is only used when
         * it is **unambiguous** which file the Hub described: the endpoint
         * publishes a single `gguf` object per repository, and a repository that
         * offers eight quantisations of the same model is describing one of them,
         * not all eight. In that case, and when the Hub has nothing, this falls
         * back to reading the header over range requests.
         */
        public suspend fun headerMetadata(
            repo: String,
            revision: String,
            fileName: String,
            parser: GgufHeaderParser = GgufHeaderParser(),
        ): GgufMetadata = hubMetadata(repo, revision, fileName) ?: rangeReadMetadata(repo, revision, fileName, parser)

        /** Null when the Hub has no `gguf` object, or when it may describe a different file. */
        public suspend fun hubMetadata(repo: String, revision: String, fileName: String): GgufMetadata? {
            val info = runCatching { modelInfo(repo, revision) }.getOrNull() ?: return null
            val gguf = info.gguf ?: return null
            val ggufFiles = info.ggufFileNames
            val describesThisFile = ggufFiles.size == 1 ||
                // A shard set is one model, so a single `gguf` object describing
                // it is unambiguous even though there are several files.
                ShardedModelResolver.expectedSiblings(fileName).containsAll(ggufFiles)
            if (!describesThisFile) return null
            return gguf.toGgufMetadata()
        }

        /**
         * Reads the header itself with `Range: bytes=0-n`, growing the window
         * only when the parser genuinely runs out of bytes.
         *
         * Takes a `Call.Factory` derived from the shared client rather than a new
         * client, so the network policy, the inspector and the LAN guard all
         * still apply.
         */
        public suspend fun rangeReadMetadata(
            repo: String,
            revision: String,
            fileName: String,
            parser: GgufHeaderParser = GgufHeaderParser(),
        ): GgufMetadata {
            val headers = authHeaders()
            val factory = Call.Factory { request ->
                val authorised = request.newBuilder().apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }
                client.newCall(authorised.build())
            }
            return HttpRangeGgufSource(resolveUrl(repo, revision, fileName), factory).use { source ->
                parser.parse(source)
            }
        }

        // ------------------------------------------------------------- plumbing

        private suspend fun authHeaders(): Map<String, String> =
            tokens
                .token()
                ?.takeIf { it.isNotBlank() }
                ?.let { mapOf("Authorization" to "Bearer $it") }
                .orEmpty()

        private fun apiUrl(path: String) = "${baseUrl.trimEnd('/')}/$path".toHttpUrl()

        private fun isHubUrl(url: String): Boolean {
            val host = url.toHttpUrlOrNull()?.host ?: return false
            val hubHost = baseUrl.toHttpUrlOrNull()?.host ?: return false
            return host == hubHost
        }

        private suspend fun get(url: String, repo: String, what: String): String =
            getWithLink(url, repo, what).first

        private suspend fun getWithLink(url: String, repo: String, what: String): Pair<String, String?> {
            val builder = Request.Builder().url(url)
            authHeaders().forEach { (name, value) -> builder.header(name, value) }
            val response = try {
                client.newCall(builder.build()).awaitResponse()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                throw DownloadHttpErrors.fromThrowable(e).asException()
            }
            return response.use {
                val body = runCatching { it.body.string() }.getOrNull().orEmpty()
                if (!it.isSuccessful) {
                    throw DownloadHttpErrors.fromResponse(it, repo, what, body).asException()
                }
                body to it.header(HEADER_LINK)
            }
        }

        private fun <T> decode(body: String, block: (String) -> T): T = try {
            block(body)
        } catch (e: SerializationException) {
            throw DownloadError
                .Transport(
                    AppError.Unexpected(
                        message = "The Hub sent a response this client could not parse.",
                        cause = e,
                    ),
                ).asException()
        }

        private companion object {
            const val DEFAULT_SEARCH_LIMIT = 25
            const val MAX_SEARCH_LIMIT = 100
            const val MAX_TREE_PAGES = 20
            const val GGUF_FILTER = "gguf"
            const val HEADER_LINK = "Link"

            private val NEXT_LINK = Regex("""<([^>]+)>\s*;\s*rel="next"""")

            fun nextLink(header: String?): String? = header?.let { NEXT_LINK.find(it)?.groupValues?.get(1) }

            /**
             * Percent-encodes one path segment.
             *
             * `HttpUrl` would happily accept a raw segment, but a filename with a
             * `+` or a space in it — both occur in real GGUF uploads — resolves to
             * a different object once a CDN has normalised it.
             */
            fun encode(segment: String): String = java.net.URLEncoder
                .encode(segment, Charsets.UTF_8.name())
                .replace("+", "%20")
        }
    }
