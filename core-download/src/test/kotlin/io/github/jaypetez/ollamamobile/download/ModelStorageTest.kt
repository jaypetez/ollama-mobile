package io.github.jaypetez.ollamamobile.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.ModelId
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The on-disk contract: internal storage, `.part` outside `models/`, marker last. */
@RunWith(RobolectricTestRunner::class)
class ModelStorageTest {
    private lateinit var context: Context
    private lateinit var storage: ModelStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = ModelStorage(context)
    }

    private fun request(fileName: String = "m-q4_k_m.gguf"): DownloadRequest = DownloadRequest(
        modelId = ModelId("hf:acme/model:$fileName"),
        displayName = "Model",
        source = DownloadSource.HuggingFace(repo = "acme/model", revision = "deadbeef"),
        files = listOf(RemoteFile(fileName = fileName, sizeBytes = 64, sha256 = null)),
    )

    private fun writePart(request: DownloadRequest, bytes: ByteArray = ggufBytes(64)) {
        val part = storage.partFile(request.source.storageDir, request.files.first().fileName)
        part.parentFile?.mkdirs()
        part.writeBytes(bytes)
    }

    @Test
    fun `everything lives under filesDir, never external storage`() {
        // FUSE-backed external storage resolves mmap page faults through a
        // userspace daemon, which is the pathological case for llama.cpp.
        assertThat(storage.modelsRoot.absolutePath).startsWith(context.filesDir.absolutePath)
        assertThat(storage.downloadsRoot.absolutePath).startsWith(context.filesDir.absolutePath)
        assertThat(storage.modelsRoot.name).isEqualTo("models")
        assertThat(storage.downloadsRoot.name).isEqualTo("downloads")
    }

    @Test
    fun `in-flight bytes are outside the models tree`() {
        val request = request()
        val part = storage.partFile(request.source.storageDir, "m-q4_k_m.gguf")

        assertThat(part.name).endsWith(".part")
        assertThat(part.absolutePath).startsWith(storage.downloadsRoot.absolutePath)
        assertThat(part.absolutePath).doesNotContain(storage.modelsRoot.absolutePath)
    }

    @Test
    fun `the layout is models slash repo slash file`() {
        val installed = storage.installedFile("acme/model", "m-q4_k_m.gguf")
        val relative = installed.relativeTo(storage.modelsRoot).path.replace('\\', '/')

        assertThat(relative).isEqualTo("acme/model/m-q4_k_m.gguf")
    }

    @Test
    fun `publishing moves the file and writes the marker last`() {
        val request = request()
        writePart(request)

        val metadata = storage.publish(request, digests = mapOf("m-q4_k_m.gguf" to "abc"))

        assertThat(storage.installedFile(request.source.storageDir, "m-q4_k_m.gguf").isFile).isTrue()
        assertThat(storage.partFile(request.source.storageDir, "m-q4_k_m.gguf").exists()).isFalse()
        assertThat(storage.isInstalled(request.source.storageDir)).isTrue()
        assertThat(metadata.repo).isEqualTo("acme/model")
        assertThat(metadata.revision).isEqualTo("deadbeef")
        assertThat(metadata.files.single().sha256).isEqualTo("abc")
    }

    @Test
    fun `a directory with no marker is not an installed model`() {
        val request = request()
        writePart(request)
        storage.publish(request, digests = emptyMap())
        assertThat(storage.isInstalled(request.source.storageDir)).isTrue()

        // Losing the marker must read as "incomplete", not as a usable model,
        // because the marker is the only atomic commit point a multi-shard set
        // has.
        storage.metadataFile(request.source.storageDir).delete()

        assertThat(storage.isInstalled(request.source.storageDir)).isFalse()
        assertThat(storage.primaryFilePath(request.source.storageDir)).isNull()
    }

    @Test
    fun `a marker whose file no longer matches is not trusted`() {
        val request = request()
        writePart(request)
        storage.publish(request, digests = emptyMap())

        // A truncated file has to read as "not installed" rather than as a model
        // that fails inside ggml.
        storage.installedFile(request.source.storageDir, "m-q4_k_m.gguf").writeBytes(ggufBytes(16))

        assertThat(storage.installedMetadata(request.source.storageDir)).isNull()
    }

    @Test
    fun `publishing something that is not a GGUF is refused`() {
        val request = request()
        writePart(request, bytes = "<html>502</html>".toByteArray())

        val failure = runCatching { storage.publish(request, digests = emptyMap()) }.exceptionOrNull()

        assertThat((failure as DownloadException).error).isInstanceOf(DownloadError.NotAGguf::class.java)
        assertThat(storage.isInstalled(request.source.storageDir)).isFalse()
    }

    @Test
    fun `reverification from disk catches a hash that does not match`() {
        val bytes = ggufBytes(64)
        val request = request().let { base ->
            base.copy(files = listOf(base.files.first().copy(sha256 = "0".repeat(64))))
        }
        writePart(request, bytes)

        val failure = runCatching {
            storage.publish(request, digests = emptyMap(), reverifyFromDisk = true)
        }.exceptionOrNull()

        assertThat((failure as DownloadException).error)
            .isInstanceOf(DownloadError.IntegrityMismatch::class.java)
    }

    @Test
    fun `path traversal in a repository id or file name is refused`() {
        // Repository ids and pasted URLs are attacker-influenced strings that end
        // up as path segments.
        listOf("..", "acme/../../etc", "C:/windows").forEach { evil ->
            assertThat(runCatching { ModelStorage.safeRelativePath(evil) }.isFailure).isTrue()
        }
        listOf("../escape.gguf", "sub/dir.gguf", "..").forEach { evil ->
            assertThat(runCatching { ModelStorage.safeFileName(evil) }.isFailure).isTrue()
        }
        assertThat(ModelStorage.safeRelativePath("acme/model/")).isEqualTo("acme/model")
    }

    @Test
    fun `deleting a model removes the directory and any partial bytes`() {
        val request = request()
        writePart(request)
        storage.publish(request, digests = emptyMap())
        File(storage.downloadDir(request.source.storageDir), "leftover.part").apply {
            parentFile?.mkdirs()
            writeText("x")
        }

        assertThat(storage.delete(request.source.storageDir)).isTrue()

        assertThat(storage.modelDir(request.source.storageDir).exists()).isFalse()
        assertThat(storage.downloadDir(request.source.storageDir).exists()).isFalse()
    }
}
