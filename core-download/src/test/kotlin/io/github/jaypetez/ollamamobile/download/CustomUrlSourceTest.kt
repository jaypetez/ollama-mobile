package io.github.jaypetez.ollamamobile.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * A pasted URL, put through the same pipeline as a catalogue entry.
 *
 * The two things it cannot supply — a SHA-256, and any assurance about who is
 * being downloaded from — are asserted rather than hidden.
 */
@RunWith(JUnit4::class)
class CustomUrlSourceTest {
    private val server = MockWebServer()
    private val source = CustomUrlSource(testOkHttpClient(), Dispatchers.IO)

    @After
    fun tearDown() {
        server.close()
    }

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
        server.start()
    }

    @Test
    fun `a HEAD that answers gives the size and the range capability`() {
        serve {
            MockResponse
                .Builder()
                .setHeader("Content-Length", "1073741824")
                .setHeader("Accept-Ranges", "bytes")
                .build()
        }

        val model = runBlocking { source.inspect(server.url("/models/tiny-Q4_K_M.gguf").toString()) }

        assertThat(model.fileName).isEqualTo("tiny-Q4_K_M.gguf")
        assertThat(model.sizeBytes).isEqualTo(1_073_741_824L)
        assertThat(model.supportsRanges).isTrue()
        // No integrity reference exists for a pasted URL, and inventing one would
        // be worse than admitting it.
        assertThat(model.sha256).isNull()
    }

    @Test
    fun `a server that refuses HEAD is probed with a one-byte range instead`() {
        var sawHead = false
        serve { request ->
            if (request.method == "HEAD") {
                sawHead = true
                MockResponse.Builder().code(405).build()
            } else {
                MockResponse
                    .Builder()
                    .code(206)
                    .setHeader("Content-Range", "bytes 0-0/2048")
                    .body("x")
                    .build()
            }
        }

        val model = runBlocking { source.inspect(server.url("/m.gguf").toString()) }

        assertThat(sawHead).isTrue()
        assertThat(model.sizeBytes).isEqualTo(2048L)
        // A 206 to the probe is the most direct proof there is that a resume will
        // work against this server.
        assertThat(model.supportsRanges).isTrue()
    }

    @Test
    fun `a URL with no filename falls back to Content-Disposition`() {
        serve {
            MockResponse
                .Builder()
                .setHeader("Content-Length", "10")
                .setHeader("Content-Disposition", "attachment; filename=\"real-name-Q4_K_M.gguf\"")
                .build()
        }

        val model = runBlocking { source.inspect(server.url("/download").toString()) }

        assertThat(model.fileName).isEqualTo("real-name-Q4_K_M.gguf")
    }

    @Test
    fun `two URLs with the same basename land in different directories`() {
        serve { MockResponse.Builder().setHeader("Content-Length", "10").build() }

        val first = runBlocking { source.inspect(server.url("/a/model.gguf").toString()) }
        val second = runBlocking { source.inspect(server.url("/b/model.gguf").toString()) }

        // Sharing a directory would let one silently overwrite the other, which
        // presents as a model that changed on its own.
        assertThat(first.storageDir).isNotEqualTo(second.storageDir)
        assertThat(first.storageDir).startsWith("custom/")
    }

    @Test
    fun `a request survives the round trip to a DownloadRequest`() {
        serve { MockResponse.Builder().setHeader("Content-Length", "4096").build() }

        val request = runBlocking { source.inspect(server.url("/m.gguf").toString()) }.toRequest()

        assertThat(request.files.single().sha256).isNull()
        assertThat(request.totalBytes).isEqualTo(4096L)
        assertThat(request.source).isInstanceOf(DownloadSource.CustomUrl::class.java)
        assertThat(ModelStorage.safeRelativePath(request.source.storageDir)).isEqualTo(request.source.storageDir)
    }

    @Test
    fun `a non-http URL is refused outright`() {
        listOf("file:///etc/passwd", "ftp://example.com/m.gguf", "not a url").forEach { bad ->
            val failure = runCatching { runBlocking { source.inspect(bad) } }.exceptionOrNull()
            assertThat(failure).isNotNull()
        }
    }
}
