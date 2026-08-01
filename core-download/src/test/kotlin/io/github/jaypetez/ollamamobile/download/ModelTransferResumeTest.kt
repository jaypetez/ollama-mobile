package io.github.jaypetez.ollamamobile.download

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** 96 KiB: bigger than the 64 KiB transfer buffer, so a resume splices mid-buffer. */
private const val MODEL_BYTES = 96 * 1024

/** Where the first attempt is cut off. Deliberately not a buffer boundary. */
private const val FIRST_ATTEMPT_BYTES = 40_000

/**
 * The resume path, against a real socket.
 *
 * Every test here fails if the corresponding handling is removed from
 * [ModelTransfer], and none of them can be replaced by a happy-path integration
 * test: the failure they cover is *silent*. A download that restarts from zero
 * on every resume produces a correct file and a correct hash. It just takes four
 * times as long, and the bug report says "downloads are slow".
 *
 * `runBlocking` rather than `runTest`: these exercise real I/O against
 * MockWebServer, where virtual time would only make the delays lie.
 */
@RunWith(JUnit4::class)
class ModelTransferResumeTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val content = modelBytes(MODEL_BYTES)
    private lateinit var hub: HubFixture
    private lateinit var partFile: File
    private val transfer = testTransfer()

    @Before
    fun setUp() {
        hub = HubFixture(content)
        hub.start()
        partFile = File(temporaryFolder.newFolder("downloads"), "tiny.gguf.part")
    }

    @After
    fun tearDown() {
        hub.close()
    }

    private fun spec() = TransferSpec(
        fileName = hub.fileName,
        partFile = partFile,
        expectedSizeBytes = content.size.toLong(),
        expectedSha256 = hub.sha256,
        originLabel = "acme/tiny-gguf",
    )

    private fun resolver() = RecordingResolver(hub.resolveUrl())

    /** Runs one interrupted attempt, leaving [FIRST_ATTEMPT_BYTES] on disk. */
    private fun interruptedFirstAttempt(resolver: DownloadUrlResolver) {
        hub.truncateAfter = FIRST_ATTEMPT_BYTES
        val failure = runCatching { runBlocking { transfer.download(spec(), resolver) } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(DownloadException::class.java)
        assertThat(partFile.length()).isEqualTo(FIRST_ATTEMPT_BYTES.toLong())
        hub.truncateAfter = null
    }

    // ---------------------------------------------------------------- resuming

    @Test
    fun `a resumed download sends the right Range and If-Range and produces a byte-identical file`() {
        val resolver = resolver()
        interruptedFirstAttempt(resolver)

        val outcome = runBlocking { transfer.download(spec(), resolver) }

        val resumed = hub.cdnRequests.last()
        assertThat(resumed.headers["Range"]).isEqualTo("bytes=$FIRST_ATTEMPT_BYTES-")
        assertThat(resumed.headers["If-Range"]).isEqualTo(HubFixture.CDN_ETAG)

        assertThat(outcome.resumedFromBytes).isEqualTo(FIRST_ATTEMPT_BYTES.toLong())
        assertThat(outcome.restartedFromZero).isFalse()
        assertThat(outcome.sha256).isEqualTo(hub.sha256)
        assertThat(partFile.readBytes()).isEqualTo(content)
    }

    @Test
    fun `the resume validator is the CDN's own ETag and never the redirect's X-Linked-Etag`() {
        // This is the regression guard for the single most damaging bug on this
        // path. The redirect from huggingface.co carries an ETag — and it is the
        // LFS SHA-256, which the CDN has never heard of. Sending it as If-Range
        // makes the CDN ignore Range and answer 200, so every resume silently
        // restarts from zero. The fixture's CDN enforces exactly that behaviour,
        // so reintroducing the bug turns the assertions below red rather than
        // merely making the download slower.
        val resolver = resolver()
        interruptedFirstAttempt(resolver)

        val sidecar = ModelTransfer.sidecarOf(partFile)
        assertThat(sidecar.isFile).isTrue()
        val stored = sidecar.readText()
        assertThat(stored).contains(HubFixture.CDN_ETAG.trim('"'))
        assertThat(stored).doesNotContain(hub.lfsEtag.trim('"'))

        val outcome = runBlocking { transfer.download(spec(), resolver) }

        val ifRange = hub.cdnRequests.last().headers["If-Range"]
        assertThat(ifRange).isEqualTo(HubFixture.CDN_ETAG)
        assertThat(ifRange).isNotEqualTo(hub.lfsEtag)
        assertThat(outcome.ifRangeSent).isNotEqualTo(hub.lfsEtag)

        // The proof that the validator matched: the CDN answered 206 and only
        // the missing bytes crossed the wire.
        assertThat(outcome.restartedFromZero).isFalse()
        assertThat(outcome.resumedFromBytes).isEqualTo(FIRST_ATTEMPT_BYTES.toLong())
        assertThat(partFile.readBytes()).isEqualTo(content)
    }

    @Test
    fun `a CDN answering 200 instead of 206 restarts cleanly instead of appending`() {
        val resolver = resolver()
        interruptedFirstAttempt(resolver)

        // The server now ignores Range altogether, which is what a 200 to a
        // ranged request means. Appending that body to the partial file would
        // produce a file of 40 000 + 98 304 bytes that fails its hash with no
        // indication why.
        hub.cdnIgnoresRange = true
        val outcome = runBlocking { transfer.download(spec(), resolver) }

        assertThat(outcome.restartedFromZero).isTrue()
        assertThat(outcome.bytesWritten).isEqualTo(content.size.toLong())
        assertThat(partFile.length()).isEqualTo(content.size.toLong())
        assertThat(partFile.readBytes()).isEqualTo(content)
        assertThat(outcome.sha256).isEqualTo(hub.sha256)
    }

    @Test
    fun `an expired signed URL is re-resolved and the transfer continues from where it stopped`() {
        val resolver = resolver()
        interruptedFirstAttempt(resolver)
        val resolvesBefore = hub.resolveRequests.size

        // A download paused overnight holds a CloudFront URL whose signature has
        // since lapsed. The stored URL now answers 403 for ever; only going back
        // to the origin recovers.
        hub.expireSignedUrl()
        val outcome = runBlocking { transfer.download(spec(), resolver) }

        assertThat(outcome.urlReissued).isTrue()
        assertThat(hub.resolveRequests.size).isGreaterThan(resolvesBefore)

        // The freshly signed URL is a different resource, so nothing is known
        // about its validator yet and no If-Range may be sent against it. A bare
        // Range is still correct — a 200 answer is handled as "start over".
        val retry = hub.cdnRequests.last()
        assertThat(retry.headers["Range"]).isEqualTo("bytes=$FIRST_ATTEMPT_BYTES-")
        assertThat(retry.headers["If-Range"]).isNull()

        assertThat(outcome.restartedFromZero).isFalse()
        assertThat(partFile.readBytes()).isEqualTo(content)
    }

    // -------------------------------------------------------------- integrity

    @Test
    fun `a sha256 mismatch fails with a typed error and leaves no usable file`() {
        val wrong = spec().copy(expectedSha256 = "0".repeat(64))

        val failure = runCatching { runBlocking { transfer.download(wrong, resolver()) } }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DownloadException::class.java)
        val error = (failure as DownloadException).error
        assertThat(error).isInstanceOf(DownloadError.IntegrityMismatch::class.java)
        assertThat((error as DownloadError.IntegrityMismatch).actualSha256).isEqualTo(hub.sha256)

        // A GGUF with a flipped bit often loads and produces subtly wrong output
        // rather than failing, so the bytes must not survive to be "used anyway".
        assertThat(partFile.exists()).isFalse()
        assertThat(ModelTransfer.sidecarOf(partFile).exists()).isFalse()
    }

    @Test
    fun `a total length that disagrees with the catalogue fails before the bytes are spent`() {
        val lying = spec().copy(expectedSizeBytes = content.size + 1L, expectedSha256 = null)

        val failure = runCatching { runBlocking { transfer.download(lying, resolver()) } }.exceptionOrNull()

        val error = (failure as DownloadException).error
        assertThat(error).isInstanceOf(DownloadError.SizeMismatch::class.java)
        assertThat(partFile.length()).isEqualTo(0L)
    }

    @Test
    fun `a part file already at full length is verified rather than re-requested`() {
        partFile.parentFile?.mkdirs()
        partFile.writeBytes(content)

        val outcome = runBlocking { transfer.download(spec(), resolver()) }

        assertThat(outcome.sha256).isEqualTo(hub.sha256)
        assertThat(hub.requests).isEmpty()
    }

    @Test
    fun `an over-long part file is discarded rather than resumed from`() {
        partFile.parentFile?.mkdirs()
        partFile.writeBytes(content + modelBytes(1024, seed = 99))
        // A sidecar that would otherwise make the transfer try to continue from
        // the end of a file that is already too long.
        ModelTransfer
            .sidecarOf(partFile)
            .writeText("""{"url":"${hub.resolveUrl()}","validator":${'"'}bogus${'"'}}""")

        val outcome = runBlocking { transfer.download(spec(), resolver()) }

        assertThat(outcome.bytesWritten).isEqualTo(content.size.toLong())
        assertThat(partFile.readBytes()).isEqualTo(content)
    }
}
