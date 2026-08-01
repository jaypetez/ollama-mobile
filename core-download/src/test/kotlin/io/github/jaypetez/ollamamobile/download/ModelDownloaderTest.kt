package io.github.jaypetez.ollamamobile.download

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.ModelId
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Small, because these tests are about the sequence rather than the throughput. */
private const val SHARD_BYTES = 8 * 1024

/**
 * The whole sequence: pre-flight, transfer, publish.
 *
 * Robolectric because [ModelStorage] needs a real `filesDir`; MockWebServer
 * because the transfer under test is the real one, over a real socket.
 */
@RunWith(RobolectricTestRunner::class)
class ModelDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var storage: ModelStorage
    private val served = mutableMapOf<String, ByteArray>()
    private val requestedPaths = CopyOnWriteArrayList<String>()

    /** Names that answer 404 however many times they are asked for. */
    private val missing = mutableSetOf<String>()

    @Before
    fun setUp() {
        storage = ModelStorage(ApplicationProvider.getApplicationContext())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.url.pathSegments.last()
                requestedPaths += name
                val body = served[name]
                return when {
                    name in missing || body == null -> MockResponse.Builder().code(404).build()

                    else -> fullResponse(
                        code = 200,
                        bytes = body,
                        headers = Headers.headersOf("ETag", "\"$name-etag\"", "Accept-Ranges", "bytes"),
                    )
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun resolver() = object : DownloadUrlResolver {
        override suspend fun resolve(fileName: String): ResolvedDownload =
            ResolvedDownload(url = server.url("/files/$fileName").toString())
    }

    private fun downloader(quota: StorageQuotaManager = UnlimitedQuota()) = ModelDownloader(
        transfer = testTransfer(),
        storage = storage,
        quota = quota,
        // Never consulted: every call below passes an explicit resolver, which
        // is what keeps this test free of the Hub client and its DI graph.
        resolvers = mockk(),
    )

    private fun shardedRequest(shardCount: Int = 2): DownloadRequest {
        val files = (1..shardCount).map { index ->
            val name = ShardedModelResolver.nameOf("big-model-Q4_K_M", index, shardCount)
            val bytes = ggufBytes(SHARD_BYTES * index, seed = index)
            served[name] = bytes
            RemoteFile(fileName = name, sizeBytes = bytes.size.toLong(), sha256 = sha256(bytes))
        }
        return DownloadRequest(
            modelId = ModelId("hf:acme/big:${files.first().fileName}"),
            displayName = "Big Model",
            source = DownloadSource.HuggingFace(repo = "acme/big", revision = "deadbeef"),
            files = files,
        )
    }

    // ----------------------------------------------------------------- shards

    @Test
    fun `a two-shard set downloads as one unit and is only usable once complete`() {
        val request = shardedRequest()
        val storageDir = request.source.storageDir

        // Part two is missing from the server, so the set cannot complete.
        missing += request.files[1].fileName
        val partial = runBlocking { downloader().download(request, resolver()) }

        assertThat(partial).isInstanceOf(DownloadResult.Failed::class.java)
        // The first shard did transfer, and it is kept as a resume point — but it
        // is under downloads/, not models/, so nothing can mistake it for a model.
        assertThat(storage.partFile(storageDir, request.files[0].fileName).isFile).isTrue()
        assertThat(storage.isInstalled(storageDir)).isFalse()
        assertThat(storage.installedFile(storageDir, request.files[0].fileName).exists()).isFalse()
        assertThat(storage.metadataFile(storageDir).exists()).isFalse()

        // The second shard appears; the retry resumes rather than restarting.
        missing.clear()
        requestedPaths.clear()
        val complete = runBlocking { downloader().download(request, resolver()) }

        assertThat(complete).isInstanceOf(DownloadResult.Completed::class.java)
        assertThat(requestedPaths).containsExactly(request.files[1].fileName)
        assertThat(storage.isInstalled(storageDir)).isTrue()

        val metadata = storage.installedMetadata(storageDir)!!
        assertThat(metadata.files.map { it.fileName })
            .containsExactly(request.files[0].fileName, request.files[1].fileName)
            .inOrder()
        // The loader is handed shard one; the parts are separate GGUF containers
        // and must never be concatenated.
        assertThat(metadata.primaryFileName).isEqualTo(request.files[0].fileName)
        assertThat(storage.primaryFilePath(storageDir)).endsWith(request.files[0].fileName)
        // And the shards keep their original names next to each other, which is
        // how llama.cpp finds the siblings.
        request.files.forEach { assertThat(storage.installedFile(storageDir, it.fileName).isFile).isTrue() }
    }

    @Test
    fun `progress is reported across the whole set rather than per shard`() {
        val request = shardedRequest()
        val seen = mutableListOf<Long>()

        runBlocking { downloader().download(request, resolver()) { seen += it.bytesDownloaded } }

        // Monotonic, and ending at the sum of both shards. A per-shard figure
        // would go back to zero between parts.
        assertThat(seen).isInOrder()
        assertThat(seen.last()).isEqualTo(request.totalBytes)
    }

    // ------------------------------------------------------------------ quota

    @Test
    fun `quota pre-flight refuses before a single byte is transferred`() {
        val request = shardedRequest(shardCount = 1)
        val quota = ExhaustedQuota(allocatable = 1024)

        val result = runBlocking { downloader(quota).download(request, resolver()) }

        assertThat(quota.consulted).isTrue()
        val error = (result as DownloadResult.Failed).error
        assertThat(error).isInstanceOf(DownloadError.InsufficientStorage::class.java)
        // The whole point: nothing was asked of the server, and nothing is on disk.
        assertThat(requestedPaths).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(storage.partFile(request.source.storageDir, request.files[0].fileName).exists()).isFalse()
    }

    @Test
    fun `pre-flight budgets only the bytes still missing`() {
        val request = shardedRequest(shardCount = 1)
        val alreadyHave = 3_000L
        storage.partFile(request.source.storageDir, request.files[0].fileName).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(alreadyHave.toInt()))
        }
        val quota = UnlimitedQuota()

        runBlocking { downloader(quota).download(request, resolver()) }

        assertThat(quota.lastRequestedBytes).isEqualTo(request.totalBytes!! - alreadyHave)
    }

    @Test
    fun `an already-installed model is not downloaded again`() {
        val request = shardedRequest(shardCount = 1)
        runBlocking { downloader().download(request, resolver()) }
        requestedPaths.clear()

        val second = runBlocking { downloader().download(request, resolver()) }

        assertThat(second).isInstanceOf(DownloadResult.AlreadyInstalled::class.java)
        assertThat(requestedPaths).isEmpty()
    }

    // --------------------------------------------------------------- integrity

    @Test
    fun `a body that is not a GGUF is refused rather than published`() {
        val name = "not-really.gguf"
        val html = "<html><body>502 Bad Gateway</body></html>".toByteArray()
        served[name] = html
        val request = DownloadRequest(
            modelId = ModelId("hf:acme/x:$name"),
            displayName = "Not a model",
            source = DownloadSource.HuggingFace(repo = "acme/x"),
            files = listOf(RemoteFile(fileName = name, sizeBytes = html.size.toLong(), sha256 = sha256(html))),
        )

        val result = runBlocking { downloader().download(request, resolver()) }

        assertThat((result as DownloadResult.Failed).error).isInstanceOf(DownloadError.NotAGguf::class.java)
        assertThat(storage.isInstalled(request.source.storageDir)).isFalse()
    }
}

/** Bytes that begin with the GGUF magic, so [ModelStorage] accepts them. */
internal fun ggufBytes(size: Int, seed: Int = 1): ByteArray =
    byteArrayOf(0x47, 0x47, 0x55, 0x46) + modelBytes(size - 4, seed)
