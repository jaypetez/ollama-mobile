package io.github.jaypetez.ollamamobile.download

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.storage.dao.ModelDao
import io.github.jaypetez.ollamamobile.storage.entity.ModelEntity
import io.github.jaypetez.ollamamobile.storage.entity.ModelOriginColumn
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * The three sweeps, and the line between the two that run automatically and the
 * one that must never run unasked.
 */
@RunWith(RobolectricTestRunner::class)
class ModelGarbageCollectorTest {
    private lateinit var storage: ModelStorage
    private val dao: ModelDao = mockk(relaxed = true)

    @Before
    fun setUp() {
        storage = ModelStorage(ApplicationProvider.getApplicationContext())
        every { dao.observeAll() } returns flowOf(emptyList())
    }

    private fun collector() = ModelGarbageCollector(storage, dao, Dispatchers.IO)

    private fun writePart(storageDir: String, fileName: String, bytes: Int = 1024): File =
        storage.partFile(storageDir, fileName).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    private fun install(storageDir: String, modelId: String, downloadedAt: Long): File {
        val request = DownloadRequest(
            modelId = ModelId(modelId),
            displayName = "Model",
            source = DownloadSource.HuggingFace(repo = storageDir),
            files = listOf(RemoteFile(fileName = "m.gguf", sizeBytes = 64)),
        )
        storage.partFile(storageDir, "m.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(ggufBytes(64))
        }
        storage.publish(request, digests = emptyMap(), nowMillis = downloadedAt)
        return storage.modelDir(storageDir)
    }

    @Test
    fun `a part file with no live work request is swept and one with is kept`() {
        val orphan = writePart("acme/abandoned", "m.gguf")
        val live = writePart("acme/running", "m.gguf")

        val report = runBlocking { collector().sweepOrphanedDownloads(setOf("acme/running")) }

        assertThat(orphan.exists()).isFalse()
        assertThat(live.exists()).isTrue()
        assertThat(report.bytesReclaimed).isEqualTo(1024)
    }

    @Test
    fun `a part file older than the threshold goes even while its work is live`() {
        val stale = writePart("acme/running", "m.gguf")
        stale.setLastModified(System.currentTimeMillis() - 30 * DAY_MILLIS)

        runBlocking { collector().sweepOrphanedDownloads(setOf("acme/running")) }

        // A resume point a month old has outlived its signed URL and usually the
        // revision it was fetched from; the bytes are recoverable from the network.
        assertThat(stale.exists()).isFalse()
    }

    @Test
    fun `a resume sidecar is swept with its part file`() {
        val orphan = writePart("acme/abandoned", "m.gguf")
        val sidecar = ModelTransfer.sidecarOf(orphan).apply { writeText("{}") }

        runBlocking { collector().sweepOrphanedDownloads(emptySet()) }

        assertThat(sidecar.exists()).isFalse()
    }

    @Test
    fun `a model directory with no database row is removed`() {
        val now = System.currentTimeMillis()
        install("acme/unknown", "hf:acme/unknown:m.gguf", downloadedAt = now - DAY_MILLIS)

        val report = runBlocking { collector().sweepUnknownModels(nowMillis = now) }

        assertThat(storage.isInstalled("acme/unknown")).isFalse()
        assertThat(report.bytesReclaimed).isGreaterThan(0L)
    }

    @Test
    fun `a model that was just published survives the grace period`() {
        val now = System.currentTimeMillis()
        install("acme/fresh", "hf:acme/fresh:m.gguf", downloadedAt = now)

        runBlocking { collector().sweepUnknownModels(nowMillis = now) }

        // The repository writes the row after publishing the files; deleting in
        // that window would make a successful download vanish.
        assertThat(storage.isInstalled("acme/fresh")).isTrue()
    }

    @Test
    fun `a model with a database row is left alone`() {
        val now = System.currentTimeMillis()
        install("acme/known", "hf:acme/known:m.gguf", downloadedAt = now - DAY_MILLIS)
        every { dao.observeAll() } returns flowOf(listOf(entity("hf:acme/known:m.gguf", "acme/known")))

        runBlocking { collector().sweepUnknownModels(nowMillis = now) }

        assertThat(storage.isInstalled("acme/known")).isTrue()
    }

    @Test
    fun `eviction is least-recently-used, skips favourites and never runs unasked`() {
        val now = System.currentTimeMillis()
        install("acme/old", "old", downloadedAt = now)
        install("acme/new", "new", downloadedAt = now)
        install("acme/fav", "fav", downloadedAt = now)
        every { dao.observeAll() } returns flowOf(
            listOf(
                entity("new", "acme/new", lastUsedAt = now),
                entity("old", "acme/old", lastUsedAt = now - 10 * DAY_MILLIS),
                entity("fav", "acme/fav", lastUsedAt = 0L, favourite = true),
            ),
        )
        coJustRun { dao.deleteById(any()) }

        // Asking for one byte evicts exactly one model: the least recently used.
        val report = runBlocking { collector().evictLeastRecentlyUsed(bytesToFree = 1) }

        assertThat(report.removedPaths).hasSize(1)
        assertThat(storage.isInstalled("acme/old")).isFalse()
        assertThat(storage.isInstalled("acme/new")).isTrue()
        // A favourite is never a candidate, however long it has sat unused.
        assertThat(storage.isInstalled("acme/fav")).isTrue()
        coVerify(exactly = 1) { dao.deleteById("old") }
    }

    @Test
    fun `eviction of zero bytes deletes nothing`() {
        val report = runBlocking { collector().evictLeastRecentlyUsed(bytesToFree = 0) }

        assertThat(report.isEmpty).isTrue()
    }

    private fun entity(
        id: String,
        storageDir: String,
        lastUsedAt: Long? = null,
        favourite: Boolean = false,
    ): ModelEntity = ModelEntity(
        id = id,
        displayName = id,
        name = "m.gguf",
        originType = ModelOriginColumn.LOCAL,
        localPath = storage.installedFile(storageDir, "m.gguf").absolutePath,
        lastUsedAt = lastUsedAt,
        favourite = favourite,
    )
}
