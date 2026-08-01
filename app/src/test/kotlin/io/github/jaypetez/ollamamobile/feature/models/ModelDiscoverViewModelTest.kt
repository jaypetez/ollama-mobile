package io.github.jaypetez.ollamamobile.feature.models

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.download.CustomUrlModel
import io.github.jaypetez.ollamamobile.download.CustomUrlSource
import io.github.jaypetez.ollamamobile.download.DownloadProgress
import io.github.jaypetez.ollamamobile.download.DownloadRepository
import io.github.jaypetez.ollamamobile.download.DownloadStatus
import io.github.jaypetez.ollamamobile.download.catalog.CatalogEntry
import io.github.jaypetez.ollamamobile.download.catalog.ModelCatalog
import io.github.jaypetez.ollamamobile.download.catalog.ModelCatalogSource
import io.github.jaypetez.ollamamobile.download.hf.HuggingFaceApi
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Discovering and downloading, without a network.
 *
 * The interesting behaviour here is not the happy path — it is that a transfer
 * has four user-controllable states and that progress survives the screen being
 * left and reopened, because it is read back from WorkManager rather than from
 * anything this view model remembers.
 */
@RunWith(JUnit4::class)
class ModelDiscoverViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val entry = CatalogEntry(
        id = "qwen3-1.7b-q4_k_m",
        displayName = "Qwen3 1.7B Instruct",
        family = "qwen3",
        repo = "Qwen/Qwen3-1.7B-GGUF",
        file = "qwen3-1.7b-q4_k_m.gguf",
        quantization = "Q4_K_M",
        parameterCount = 1_700_000_000L,
        sizeBytes = 1_200_000_000L,
        sha256 = "abc123",
    )

    private val unverified = entry.copy(id = "unverified", sha256 = null, file = "unverified-q8_0.gguf")

    private val catalogSource = mockk<ModelCatalogSource>()
    private val huggingFace = mockk<HuggingFaceApi>()
    private val customUrls = mockk<CustomUrlSource>()
    private val downloads = mockk<DownloadRepository>(relaxUnitFun = true)
    private val localModels = mockk<LocalModelRepository>(relaxUnitFun = true)

    private val progress = MutableStateFlow(
        DownloadProgress(modelId = ModelId(entry.modelId.value), status = DownloadStatus.QUEUED),
    )

    @Before
    fun setUp() {
        coEvery { catalogSource.load() } returns ModelCatalog(
            schemaVersion = ModelCatalog.SCHEMA_VERSION,
            chatModels = listOf(entry, unverified),
        )
        every { localModels.models } returns MutableStateFlow(emptyList())
        every { localModels.engineAvailable } returns true
        coEvery { localModels.refresh() } returns emptyList()
        coEvery { downloads.isTracked(any()) } returns false
        coEvery { downloads.enqueue(any()) } answers { ModelId(entry.modelId.value) }
        coEvery { downloads.resume(any()) } returns true
        every { downloads.progress(any()) } returns progress
    }

    private fun viewModel() = ModelDiscoverViewModel(
        catalogSource = catalogSource,
        huggingFace = huggingFace,
        customUrls = customUrls,
        downloads = downloads,
        localModels = localModels,
    )

    @Test
    fun `the bundled catalogue is available with no network`() = runTest {
        val subject = viewModel()
        advanceUntilIdle()

        assertThat(subject.uiState.value.catalogue).hasSize(2)
        val first = subject.uiState.value.catalogue
            .first()
        assertThat(first.quantizationLabel).isEqualTo("Q4_K_M")
        assertThat(first.parameterLabel).isEqualTo("1.7B")
        assertThat(first.sizeLabel).isNotNull()
    }

    @Test
    fun `an entry with no published checksum says so`() = runTest {
        // A real reduction in guarantee: the transfer can only be checked
        // against its declared length and the GGUF magic.
        val subject = viewModel()
        advanceUntilIdle()

        assertThat(
            subject.uiState.value.catalogue
                .first { it.id == unverified.modelId.value }
                .hashVerified,
        ).isFalse()
    }

    @Test
    fun `a build with no engine is reported to the discover screen too`() = runTest {
        every { localModels.engineAvailable } returns false
        val subject = viewModel()
        advanceUntilIdle()

        assertThat(subject.uiState.value.engineAvailable).isFalse()
    }

    @Test
    fun `downloading enqueues the resolved request and starts reporting progress`() = runTest {
        coEvery { huggingFace.downloadRequest(any(), any(), any(), any(), any(), any()) } returns
            io.github.jaypetez.ollamamobile.download.DownloadRequest(
                modelId = ModelId(entry.modelId.value),
                displayName = entry.displayName,
                source = io.github.jaypetez.ollamamobile.download.DownloadSource
                    .HuggingFace(repo = entry.repo),
                files = listOf(
                    io.github.jaypetez.ollamamobile.download
                        .RemoteFile(fileName = entry.file, sizeBytes = 1L),
                ),
            )
        val subject = viewModel()
        advanceUntilIdle()
        val target = subject.uiState.value.catalogue
            .first { it.id == entry.modelId.value }

        subject.download(target)
        advanceUntilIdle()

        coVerify { downloads.enqueue(any()) }
        assertThat(subject.uiState.value.downloadFor(target)).isNotNull()
    }

    @Test
    fun `a running transfer offers pause and cancel but not resume`() = runTest {
        progress.value = DownloadProgress(
            modelId = ModelId(entry.modelId.value),
            status = DownloadStatus.RUNNING,
            bytesDownloaded = 100L,
            totalBytes = 1_000L,
        )
        val ui = DownloadUi.from(progress.value)

        assertThat(ui.canPause).isTrue()
        assertThat(ui.canCancel).isTrue()
        assertThat(ui.canResume).isFalse()
        assertThat(ui.fraction).isWithin(TOLERANCE).of(0.1f)
    }

    @Test
    fun `a paused transfer offers resume, and a failed one does too`() = runTest {
        val paused = DownloadUi.from(
            DownloadProgress(modelId = ModelId(entry.modelId.value), status = DownloadStatus.PAUSED),
        )
        val failed = DownloadUi.from(
            DownloadProgress(modelId = ModelId(entry.modelId.value), status = DownloadStatus.FAILED),
        )

        assertThat(paused.canResume).isTrue()
        // A failed transfer keeps its partial bytes, so "resume" is the honest
        // label rather than "retry from scratch".
        assertThat(failed.canResume).isTrue()
    }

    @Test
    fun `progress with no known total is indeterminate rather than sitting at zero`() = runTest {
        val ui = DownloadUi.from(
            DownloadProgress(
                modelId = ModelId(entry.modelId.value),
                status = DownloadStatus.RUNNING,
                bytesDownloaded = 4_096L,
                totalBytes = null,
            ),
        )

        assertThat(ui.fraction).isNull()
    }

    @Test
    fun `pause, resume and cancel all reach the repository`() = runTest {
        val subject = viewModel()
        advanceUntilIdle()
        val target = subject.uiState.value.catalogue
            .first { it.id == entry.modelId.value }

        subject.pause(target)
        subject.resume(target)
        subject.cancel(target)
        advanceUntilIdle()

        coVerify { downloads.pause(ModelId(target.id)) }
        coVerify { downloads.resume(ModelId(target.id)) }
        coVerify { downloads.cancel(ModelId(target.id), any()) }
    }

    @Test
    fun `cancelling clears the progress row rather than leaving a stale bar`() = runTest {
        val subject = viewModel()
        advanceUntilIdle()
        val target = subject.uiState.value.catalogue
            .first { it.id == entry.modelId.value }
        subject.resume(target)
        advanceUntilIdle()

        subject.cancel(target)
        advanceUntilIdle()

        assertThat(subject.uiState.value.downloadFor(target)).isNull()
    }

    @Test
    fun `a pasted URL is inspected before anything is transferred, and is marked unverified`() = runTest {
        coEvery { customUrls.inspect(any()) } returns CustomUrlModel(
            url = "https://example.com/model-q4_k_m.gguf",
            fileName = "model-q4_k_m.gguf",
            sizeBytes = 900_000_000L,
            supportsRanges = true,
            storageDir = "example.com/model",
            host = "example.com",
        )
        val subject = viewModel()
        advanceUntilIdle()

        subject.onCustomUrlChange("https://example.com/model-q4_k_m.gguf")
        subject.inspectCustomUrl()
        advanceUntilIdle()

        val preview = subject.uiState.value.customUrlPreview
        assertThat(preview).isNotNull()
        // A pasted URL never carries a checksum. Stated, not papered over.
        assertThat(preview?.hashVerified).isFalse()
        assertThat(preview?.sourceLabel).isEqualTo("example.com")
        coVerify(exactly = 0) { downloads.enqueue(any()) }
    }

    @Test
    fun `a completed download triggers a rescan so the model becomes loadable`() = runTest {
        val subject = viewModel()
        advanceUntilIdle()
        val target = subject.uiState.value.catalogue
            .first { it.id == entry.modelId.value }
        subject.resume(target)
        advanceUntilIdle()

        progress.value = DownloadProgress(
            modelId = ModelId(entry.modelId.value),
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = 1_000L,
            totalBytes = 1_000L,
        )
        advanceUntilIdle()

        // Without the rescan, finished bytes stay invisible until the manager
        // screen happens to be reopened.
        coVerify { localModels.refresh() }
        assertThat(
            subject.uiState.value.catalogue
                .first { it.id == target.id }
                .installed,
        ).isTrue()
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
