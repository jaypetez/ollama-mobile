package io.github.jaypetez.ollamamobile.download.hf

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.download.DownloadError
import io.github.jaypetez.ollamamobile.download.DownloadException
import io.github.jaypetez.ollamamobile.download.DownloadSource
import io.github.jaypetez.ollamamobile.download.testOkHttpClient
import io.github.jaypetez.ollamamobile.model.ModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private const val REPO = "google/gemma-3-1b-it-qat-q4_0-gguf"

/**
 * The Hub API, against a real socket.
 *
 * The gated-repository case is the one that matters most and the one that is
 * easiest to get subtly wrong: the Hub answers **401 even for a valid token**
 * until the account has accepted the model's terms, so a client that reads 401
 * as "sign in" sends the user round a loop that cannot terminate.
 */
@RunWith(JUnit4::class)
class HuggingFaceApiTest {
    private lateinit var server: MockWebServer
    private var token: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(): HuggingFaceApi {
        server.start()
        return HuggingFaceApi(
            client = testOkHttpClient(),
            tokens = { token },
            baseUrl = server.url("/").toString().trimEnd('/'),
            io = Dispatchers.IO,
        )
    }

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
    }

    @Test
    fun `a gated repo 401 maps to the typed licence error, not to an authentication failure`() {
        respond {
            MockResponse
                .Builder()
                .code(401)
                .setHeader("X-Error-Code", "GatedRepo")
                .body("""{"error":"Access to model $REPO is restricted."}""")
                .build()
        }
        val api = api()

        val failure = runCatching { runBlocking { api.listFiles(REPO) } }.exceptionOrNull()

        val error = (failure as DownloadException).error
        assertThat(error).isInstanceOf(DownloadError.GatedRepo::class.java)
        val gated = error as DownloadError.GatedRepo
        assertThat(gated.repo).isEqualTo(REPO)
        assertThat(gated.licenceUrl).isEqualTo("https://huggingface.co/$REPO")
        assertThat(gated.message).contains("accept its licence")
    }

    @Test
    fun `a gated repo still reports GatedRepo when a token was supplied`() {
        // The whole point of the distinction: a perfectly good token does not
        // make a gated repository downloadable.
        token = "hf_a_valid_looking_token"
        respond {
            MockResponse
                .Builder()
                .code(403)
                .setHeader("X-Error-Code", "GatedRepo")
                .build()
        }
        val api = api()

        val failure = runCatching { runBlocking { api.listFiles(REPO) } }.exceptionOrNull()

        assertThat((failure as DownloadException).error).isInstanceOf(DownloadError.GatedRepo::class.java)
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer $token")
    }

    @Test
    fun `a private repo reports authentication rather than a missing repository`() {
        respond {
            MockResponse
                .Builder()
                .code(401)
                .setHeader("X-Error-Code", "RepoNotFound")
                .build()
        }
        val api = api()

        val failure = runCatching { runBlocking { api.listFiles("someone/private") } }.exceptionOrNull()

        assertThat((failure as DownloadException).error)
            .isInstanceOf(DownloadError.AuthenticationRequired::class.java)
    }

    @Test
    fun `the tree API yields the LFS sha256 and the real content size`() {
        respond {
            MockResponse
                .Builder()
                .body(
                    """
                    [
                      {"type":"file","path":".gitattributes","size":1519,"oid":"abc"},
                      {"type":"file","path":"model-q4_k_m.gguf","size":135,"oid":"pointer-sha1",
                       "lfs":{"oid":"aa11bb22","size":1073741824,"pointerSize":135}}
                    ]
                    """.trimIndent(),
                ).build()
        }
        val api = api()

        val files = runBlocking { api.listGgufFiles("acme/model") }

        assertThat(files).hasSize(1)
        // `size` on the entry is the pointer file's length; `lfs.size` is the
        // model's. Using the wrong one makes a gigabyte look like 135 bytes.
        assertThat(files.first().sizeBytes).isEqualTo(1_073_741_824L)
        // And `oid` on the entry is a git SHA-1 of the pointer, never an
        // integrity reference for the content.
        assertThat(files.first().sha256).isEqualTo("aa11bb22")
    }

    @Test
    fun `a sharded request expands to the whole set`() {
        respond {
            MockResponse
                .Builder()
                .body(
                    """
                    [
                      {"type":"file","path":"big-00001-of-00002.gguf","size":10,
                       "lfs":{"oid":"one","size":100}},
                      {"type":"file","path":"big-00002-of-00002.gguf","size":10,
                       "lfs":{"oid":"two","size":200}}
                    ]
                    """.trimIndent(),
                ).build()
        }
        val api = api()

        val request = runBlocking {
            api.downloadRequest(
                modelId = ModelId("hf:acme/model:big-00001-of-00002.gguf"),
                displayName = "Big",
                source = DownloadSource.HuggingFace(repo = "acme/model"),
                fileName = "big-00001-of-00002.gguf",
            )
        }

        assertThat(request.files.map { it.fileName })
            .containsExactly("big-00001-of-00002.gguf", "big-00002-of-00002.gguf")
            .inOrder()
        assertThat(request.totalBytes).isEqualTo(300L)
        assertThat(request.primaryFileName).isEqualTo("big-00001-of-00002.gguf")
    }

    @Test
    fun `a shard set with a missing part is refused before anything is downloaded`() {
        respond {
            MockResponse
                .Builder()
                .body(
                    """
                    [{"type":"file","path":"big-00001-of-00003.gguf","size":10,"lfs":{"oid":"one","size":100}}]
                    """.trimIndent(),
                ).build()
        }
        val api = api()

        val failure = runCatching {
            runBlocking {
                api.downloadRequest(
                    modelId = ModelId("hf:acme/model:big-00001-of-00003.gguf"),
                    displayName = "Big",
                    source = DownloadSource.HuggingFace(repo = "acme/model"),
                    fileName = "big-00001-of-00003.gguf",
                )
            }
        }.exceptionOrNull()

        val error = (failure as DownloadException).error
        assertThat(error).isInstanceOf(DownloadError.IncompleteShardSet::class.java)
        assertThat((error as DownloadError.IncompleteShardSet).missing)
            .containsExactly("big-00002-of-00003.gguf", "big-00003-of-00003.gguf")
    }

    @Test
    fun `search filters to GGUF server-side and drops hits with no GGUF file`() {
        respond { request ->
            assertThat(request.url.queryParameter("filter")).isEqualTo("gguf")
            assertThat(request.url.queryParameter("full")).isEqualTo("true")
            MockResponse
                .Builder()
                .body(
                    """
                    [
                      {"id":"acme/with-gguf","siblings":[{"rfilename":"m-q4_k_m.gguf"}]},
                      {"id":"acme/no-gguf","siblings":[{"rfilename":"README.md"}]}
                    ]
                    """.trimIndent(),
                ).build()
        }
        val api = api()

        val results = runBlocking { api.searchGgufModels("qwen") }

        assertThat(results.map { it.id }).containsExactly("acme/with-gguf")
    }

    @Test
    fun `the Hub's precomputed metadata is used only when it can only describe this file`() {
        respond {
            MockResponse
                .Builder()
                .body(
                    """
                    {"id":"acme/model",
                     "siblings":[{"rfilename":"m-q4_k_m.gguf"},{"rfilename":"m-q8_0.gguf"}],
                     "gguf":{"total":1500000000,"architecture":"qwen3","context_length":32768}}
                    """.trimIndent(),
                ).build()
        }
        val api = api()

        // Two quantisations, one `gguf` object: the Hub is describing one of
        // them and nothing says which, so trusting it would attach one file's
        // parameter count to the other.
        val ambiguous = runBlocking { api.hubMetadata("acme/model", "main", "m-q8_0.gguf") }
        assertThat(ambiguous).isNull()
    }

    @Test
    fun `the Hub's precomputed metadata is used when the repository holds one GGUF`() {
        respond {
            MockResponse
                .Builder()
                .body(
                    """
                    {"id":"acme/model",
                     "siblings":[{"rfilename":"m-q4_k_m.gguf"},{"rfilename":"README.md"}],
                     "gguf":{"total":1500000000,"architecture":"qwen3","context_length":32768}}
                    """.trimIndent(),
                ).build()
        }
        val api = api()

        val metadata = runBlocking { api.hubMetadata("acme/model", "main", "m-q4_k_m.gguf") }

        assertThat(metadata).isNotNull()
        assertThat(metadata?.architecture).isEqualTo("qwen3")
        assertThat(metadata?.parameterCount).isEqualTo(1_500_000_000L)
        assertThat(metadata?.contextLength).isEqualTo(32_768)
    }

    @Test
    fun `resolve builds the documented URL and percent-encodes the file name`() {
        server.start()
        val api = HuggingFaceApi(
            client = testOkHttpClient(),
            tokens = { null },
            baseUrl = "https://huggingface.co",
            io = Dispatchers.IO,
        )

        val url = api.resolveUrl("acme/model", "deadbeef", "a model +1.gguf")

        assertThat(url).isEqualTo("https://huggingface.co/acme/model/resolve/deadbeef/a%20model%20%2B1.gguf")
    }

    @Test
    fun `a resumed CDN request carries no bearer token`() {
        token = "hf_secret"
        server.start()
        val api = HuggingFaceApi(
            client = testOkHttpClient(),
            tokens = { token },
            baseUrl = "https://huggingface.co",
            io = Dispatchers.IO,
        )
        val resolver = api.resolverFor(DownloadSource.HuggingFace(repo = "acme/model"))

        val hub = runBlocking { resolver.headersFor("https://huggingface.co/acme/model/resolve/main/m.gguf") }
        val cdn = runBlocking { resolver.headersFor("https://cdn-lfs.hf.co/repos/aa/bb/model.gguf?sig=1") }

        assertThat(hub).containsKey("Authorization")
        // Attaching an Authorization header to a pre-signed URL leaks the token
        // and makes the request fail: AWS rejects two authentication mechanisms
        // on one request.
        assertThat(cdn).isEmpty()
    }
}
