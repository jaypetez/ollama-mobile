package io.github.jaypetez.ollamamobile.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.FakeClock
import io.github.jaypetez.ollamamobile.download.CompatibilityChecker
import io.github.jaypetez.ollamamobile.download.CompatibilityReport
import io.github.jaypetez.ollamamobile.download.DownloadRequest
import io.github.jaypetez.ollamamobile.download.DownloadSource
import io.github.jaypetez.ollamamobile.download.ModelStorage
import io.github.jaypetez.ollamamobile.download.RemoteFile
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.llm.testing.FakeLlamaEngine
import io.github.jaypetez.ollamamobile.model.GgufMetadata
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.storage.MemoryEstimator
import io.github.jaypetez.ollamamobile.storage.OllamaDatabase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The on-device model library, against a real filesystem.
 *
 * Robolectric with real files rather than a mocked `ModelStorage`, because the
 * properties being asserted are properties of the layout: that an incomplete
 * directory is not offered as a model, that a delete removes the files as well
 * as the row, and that the rows the router reads actually appear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalModelRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock()

    private lateinit var database: OllamaDatabase
    private lateinit var storage: ModelStorage

    private val compatibility = mockk<CompatibilityChecker>()
    private val memoryEstimator = mockk<MemoryEstimator>()

    private val metadata = GgufMetadata(
        architecture = "qwen3",
        name = "Qwen3 1.7B",
        parameterCount = 1_700_000_000L,
        contextLength = 32_768,
    )

    private val fits = MemoryVerdict.Fits(headroomBytes = 2L * 1024 * 1024 * 1024)

    @Before
    fun setUp() {
        database = OllamaDatabase.buildInMemory(context)
        storage = ModelStorage(context)
        coEvery { compatibility.checkLocalFile(any(), any(), any()) } returns CompatibilityReport(
            metadata = metadata,
            verdict = fits,
            quantization = Quantization.Q4_K_M,
            contextLength = 4096,
        )
    }

    @After
    fun tearDown() {
        database.close()
        storage.modelsRoot.deleteRecursively()
        storage.downloadsRoot.deleteRecursively()
    }

    private fun repository(engine: FakeLlamaEngine = FakeLlamaEngine()) = LocalModelRepository(
        context = context,
        storage = storage,
        compatibility = compatibility,
        memoryEstimator = memoryEstimator,
        modelDao = database.modelDao(),
        engine = engine,
        clock = clock,
        io = dispatcher,
    )

    /** Publishes a model the way a finished download would, marker and all. */
    private fun install(
        modelId: String = MODEL_ID,
        fileName: String = "qwen3-1.7b-q4_k_m.gguf",
        storageDir: String = "Qwen/Qwen3-1.7B-GGUF",
    ) {
        val part = storage.partFile(storageDir, fileName)
        part.parentFile?.mkdirs()
        // The first four bytes have to be the GGUF magic: `publish` checks them,
        // which is the cheap guard against a CDN error page wearing a .gguf name.
        part.writeBytes(GGUF_MAGIC + ByteArray(PADDING_BYTES))
        storage.publish(
            request = DownloadRequest(
                modelId = ModelId(modelId),
                displayName = "Qwen3 1.7B",
                source = DownloadSource.HuggingFace(repo = storageDir),
                files = listOf(RemoteFile(fileName = fileName, sizeBytes = part.length())),
            ),
            digests = emptyMap(),
            nowMillis = clock.nowMillis(),
        )
    }

    @Test
    fun `a published model is discovered, with its size and quantisation`() = runTest(dispatcher) {
        install()

        val records = repository().refresh()

        val record = records.single()
        assertThat(record.id).isEqualTo(ModelId(MODEL_ID))
        assertThat(record.quantization).isEqualTo(Quantization.Q4_K_M)
        assertThat(record.parameterCount).isEqualTo(1_700_000_000L)
        assertThat(record.sizeBytes).isGreaterThan(0L)
        assertThat(record.ref.origin).isInstanceOf(ModelOrigin.Local::class.java)
    }

    @Test
    fun `the scan writes the local rows the router reads`() = runTest(dispatcher) {
        // This is the wiring change the router's local branch was written in
        // anticipation of; without it `ModelRepository.localModels` stays empty
        // and no Local target can ever be produced.
        install()

        repository().refresh()

        assertThat(database.modelDao().find(MODEL_ID)).isNotNull()
    }

    @Test
    fun `a directory with no completion marker is not offered as a model`() = runTest(dispatcher) {
        // An interrupted download must never be discoverable as something
        // loadable: the bytes are there and the file is short.
        val part = storage.partFile("Qwen/Qwen3-1.7B-GGUF", "qwen3-1.7b-q4_k_m.gguf")
        part.parentFile?.mkdirs()
        part.writeBytes(GGUF_MAGIC)

        assertThat(repository().refresh()).isEmpty()
    }

    @Test
    fun `a model whose file was removed behind our back disappears from the list and the table`() =
        runTest(dispatcher) {
            install()
            val subject = repository()
            subject.refresh()

            storage.modelDir("Qwen/Qwen3-1.7B-GGUF").deleteRecursively()
            val after = subject.refresh()

            assertThat(after).isEmpty()
            assertThat(database.modelDao().find(MODEL_ID)).isNull()
        }

    @Test
    fun `deleting removes the files and the row`() = runTest(dispatcher) {
        install()
        val subject = repository()
        subject.refresh()

        val result = subject.delete(ModelId(MODEL_ID))

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(storage.isInstalled("Qwen/Qwen3-1.7B-GGUF")).isFalse()
        assertThat(database.modelDao().find(MODEL_ID)).isNull()
    }

    @Test
    fun `deleting the resident model unloads it first`() = runTest(dispatcher) {
        // Deleting the file an mmap points at leaves the mapping valid and the
        // directory entry gone: the model would keep answering from a file the
        // user believes they deleted.
        install()
        val engine = FakeLlamaEngine()
        val subject = repository(engine)
        val record = subject.refresh().single()
        engine.load(ModelLoadSpec(model = record.ref, path = record.path))

        subject.delete(record.id)

        assertThat(engine.unloadCount).isEqualTo(1)
        assertThat(engine.loadedModel.value).isNull()
    }

    @Test
    fun `deleting something that is not there is a failure, not a silent success`() = runTest(dispatcher) {
        val result = repository().delete(ModelId("nothing"))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `engineAvailable follows the engine binding, not the contents of the directory`() = runTest(dispatcher) {
        install()

        // A stub-engine build with models on disk: the list is not empty and
        // nothing on it is loadable.
        val stubbed = repository(FakeLlamaEngine.unavailable())
        assertThat(stubbed.refresh()).hasSize(1)
        assertThat(stubbed.engineAvailable).isFalse()

        assertThat(repository(FakeLlamaEngine()).engineAvailable).isTrue()
    }

    @Test
    fun `an import refuses a file that is not a gguf before copying anything`() = runTest(dispatcher) {
        val result = repository().importGguf(android.net.Uri.parse("content://test/notes.txt"), "notes.txt")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(storage.downloadsRoot.exists()).isFalse()
    }

    @Test
    fun `the resident model is reported straight from the engine`() = runTest(dispatcher) {
        val engine = FakeLlamaEngine()
        val subject = repository(engine)
        val ref = ModelRef(
            id = ModelId(MODEL_ID),
            displayName = "Qwen3 1.7B",
            name = "qwen3-1.7b-q4_k_m.gguf",
            origin = ModelOrigin.Local("/data/models/x.gguf"),
        )

        engine.load(ModelLoadSpec(model = ref, path = "/data/models/x.gguf"))

        assertThat(subject.resident.value).isEqualTo(ref)
    }

    private companion object {
        const val MODEL_ID = "hf:Qwen/Qwen3-1.7B-GGUF:qwen3-1.7b-q4_k_m.gguf"
        const val PADDING_BYTES = 64

        /** "GGUF" in ASCII. */
        val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
