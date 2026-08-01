package io.github.jaypetez.ollamamobile.feature.models

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.engine.ModelLifecycleManager
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRecord
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The model manager, in the two build shapes that matter.
 *
 * The honesty requirement is the reason most of these exist: on the default
 * `-Pollama.nativeSource=none` build there is no engine, and the difference
 * between "you have not downloaded anything" and "this build cannot run
 * anything" has to survive into the state the screen renders. An assertion that
 * only checks the list is empty would pass for both and catch neither.
 */
@RunWith(JUnit4::class)
class ModelsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val modelId = ModelId("hf:Qwen/Qwen3-1.7B-GGUF:qwen3-1.7b-q4_k_m.gguf")
    private val ref = ModelRef(
        id = modelId,
        displayName = "Qwen3 1.7B",
        name = "qwen3-1.7b-q4_k_m.gguf",
        origin = ModelOrigin.Local("/data/models/qwen3-1.7b-q4_k_m.gguf"),
        parameterCount = 1_700_000_000L,
        quantization = Quantization.Q4_K_M,
        sizeBytes = 1_200_000_000L,
        contextLength = 32_768,
    )

    private fun record(verdict: MemoryVerdict = fits) = LocalModelRecord(
        ref = ref,
        path = "/data/models/qwen3-1.7b-q4_k_m.gguf",
        sizeBytes = 1_200_000_000L,
        origin = "huggingface.co/Qwen/Qwen3-1.7B-GGUF",
        downloadedAtMillis = 1L,
        verdict = verdict,
        architecture = "qwen3",
        budgetedContextLength = 4096,
    )

    private val fits = MemoryVerdict.Fits(headroomBytes = 2L * 1024 * 1024 * 1024)
    private val refuse = MemoryVerdict.Refuse(
        requiredBytes = 6L * 1024 * 1024 * 1024,
        availableBytes = 1L * 1024 * 1024 * 1024,
        reason = "Choose a smaller quantisation of this model, or a smaller model.",
    )

    private val models = MutableStateFlow<List<LocalModelRecord>>(emptyList())
    private val resident = MutableStateFlow<ModelRef?>(null)

    private val localModels = mockk<LocalModelRepository>(relaxUnitFun = true)
    private val lifecycle = mockk<ModelLifecycleManager>(relaxUnitFun = true)

    /**
     * Builds the view model **and subscribes to its state**.
     *
     * `uiState` shares `WhileSubscribed`, which is right in production and means
     * that a test reading `.value` without a collector would assert against the
     * initial placeholder for ever. The background collector is what makes the
     * combine actually run.
     */
    @Before
    fun setUp() {
        every { localModels.models } returns models
        every { localModels.engineAvailable } returns true
        every { lifecycle.resident } returns resident
        coEvery { localModels.refresh() } answers { models.value }
        coEvery { localModels.delete(any()) } returns AppResult.Success(Unit)
        coEvery { lifecycle.ensureLoaded(any()) } returns ref
    }

    private fun TestScope.viewModel(engineAvailable: Boolean = true): ModelsViewModel {
        // Only the build shape is overridden here. Everything else is stubbed
        // in setUp, so a test can replace one behaviour before building the
        // subject without the helper putting the default back.
        every { localModels.engineAvailable } returns engineAvailable
        return ModelsViewModel(localModels = localModels, lifecycle = lifecycle).also { subject ->
            backgroundScope.launch(mainDispatcherRule.dispatcher) { subject.uiState.collect { } }
        }
    }

    // -----------------------------------------------------------------------
    // The honesty requirement
    // -----------------------------------------------------------------------

    @Test
    fun `a build with no engine says so, and does not merely show an empty list`() = runTest {
        val subject = viewModel(engineAvailable = false)
        advanceUntilIdle()

        val state = subject.uiState.value
        assertThat(state.engineAvailable).isFalse()
        // Both facts are separately available, so the screen can say which of
        // the two empty states it is looking at.
        assertThat(state.isEmpty).isTrue()
    }

    @Test
    fun `a build with an engine and nothing installed is a different state`() = runTest {
        val subject = viewModel(engineAvailable = true)
        advanceUntilIdle()

        val state = subject.uiState.value
        assertThat(state.engineAvailable).isTrue()
        assertThat(state.isEmpty).isTrue()
    }

    @Test
    fun `models left on disk by a native build are still listed when there is no engine`() = runTest {
        // The rows exist and must not silently vanish; the screen disables the
        // load affordance instead, which is what `engineAvailable` is for.
        models.value = listOf(record())
        val subject = viewModel(engineAvailable = false)
        advanceUntilIdle()

        assertThat(subject.uiState.value.models).hasSize(1)
        assertThat(subject.uiState.value.engineAvailable).isFalse()
    }

    // -----------------------------------------------------------------------
    // The memory verdict
    // -----------------------------------------------------------------------

    @Test
    fun `a refused model is visibly refusable rather than silently loadable`() = runTest {
        models.value = listOf(record(verdict = refuse))
        val subject = viewModel()
        advanceUntilIdle()

        val row = subject.uiState.value.models
            .single()
        assertThat(row.verdict.kind).isEqualTo(VerdictKind.REFUSE)
        assertThat(row.loadable).isFalse()
        // And the reason is on the row, with the shortfall in it, rather than
        // appearing only after a failed load.
        assertThat(row.verdict.explanation).contains("short")
    }

    @Test
    fun `a tight verdict is a warning, not a refusal`() = runTest {
        models.value = listOf(record(verdict = MemoryVerdict.Tight(headroomBytes = 1_000L, reason = "close")))
        val subject = viewModel()
        advanceUntilIdle()

        val row = subject.uiState.value.models
            .single()
        assertThat(row.verdict.kind).isEqualTo(VerdictKind.TIGHT)
        assertThat(row.loadable).isTrue()
    }

    // -----------------------------------------------------------------------
    // Load, unload, delete
    // -----------------------------------------------------------------------

    @Test
    fun `the resident model is marked`() = runTest {
        models.value = listOf(record())
        resident.value = ref
        val subject = viewModel()
        advanceUntilIdle()

        assertThat(
            subject.uiState.value.models
                .single()
                .resident,
        ).isTrue()
        assertThat(subject.uiState.value.residentModelId).isEqualTo(modelId)
    }

    @Test
    fun `loading a model goes through the lifecycle manager`() = runTest {
        models.value = listOf(record())
        val subject = viewModel()
        advanceUntilIdle()

        subject.load(modelId)
        advanceUntilIdle()

        coVerify(exactly = 1) { lifecycle.ensureLoaded(modelId) }
    }

    @Test
    fun `a refused load surfaces the shortfall rather than a generic failure`() = runTest {
        models.value = listOf(record())
        coEvery { lifecycle.ensureLoaded(any()) } throws AppErrorException(
            AppError.Model.InsufficientMemory(verdict = refuse),
        )
        val subject = viewModel()
        advanceUntilIdle()

        subject.load(modelId)
        advanceUntilIdle()

        val message = subject.uiState.value.message
        assertThat(message?.messageRes).isEqualTo(R.string.models_error_memory)
        assertThat(message?.detail).contains("short")
    }

    @Test
    fun `a load attempted with no engine reports the build, not a missing file`() = runTest {
        models.value = listOf(record())
        coEvery { lifecycle.ensureLoaded(any()) } throws AppErrorException(AppError.Engine.NotAvailable())
        val subject = viewModel(engineAvailable = false)
        advanceUntilIdle()

        subject.load(modelId)
        advanceUntilIdle()

        assertThat(
            subject.uiState.value.message
                ?.messageRes,
        ).isEqualTo(R.string.models_error_no_engine)
    }

    @Test
    fun `deleting asks first and does nothing until it is confirmed`() = runTest {
        models.value = listOf(record())
        val subject = viewModel()
        advanceUntilIdle()

        subject.requestDelete(modelId)
        advanceUntilIdle()
        assertThat(
            subject.uiState.value.pendingDelete
                ?.id,
        ).isEqualTo(modelId)
        coVerify(exactly = 0) { localModels.delete(any()) }

        subject.confirmDelete()
        advanceUntilIdle()
        coVerify(exactly = 1) { localModels.delete(modelId) }
    }

    @Test
    fun `dismissing the confirmation deletes nothing`() = runTest {
        models.value = listOf(record())
        val subject = viewModel()
        advanceUntilIdle()

        subject.requestDelete(modelId)
        subject.dismissDelete()
        subject.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { localModels.delete(any()) }
        assertThat(subject.uiState.value.pendingDelete).isNull()
    }

    @Test
    fun `a failed delete says so instead of quietly leaving the model in the list`() = runTest {
        models.value = listOf(record())
        coEvery { localModels.delete(any()) } returns AppResult.Failure(
            AppError.Storage.Io(message = "Could not delete Qwen3 1.7B."),
        )
        val subject = viewModel()
        advanceUntilIdle()

        subject.requestDelete(modelId)
        subject.confirmDelete()
        advanceUntilIdle()

        assertThat(
            subject.uiState.value.message
                ?.isError,
        ).isTrue()
    }

    // -----------------------------------------------------------------------
    // Projection
    // -----------------------------------------------------------------------

    @Test
    fun `the row carries size, quantisation, parameter count and context`() = runTest {
        models.value = listOf(record())
        val subject = viewModel()
        advanceUntilIdle()

        val row = subject.uiState.value.models
            .single()
        assertThat(row.quantizationLabel).isEqualTo("Q4_K_M")
        assertThat(row.parameterLabel).isEqualTo("1.7B")
        assertThat(row.contextLabel).isEqualTo("32K")
        assertThat(row.sizeLabel).contains("GiB")
    }

    @Test
    fun `a model whose converter wrote no parameter count claims none`() = runTest {
        // Null rather than "unknown": a row that says "unknown parameters" is
        // noise, and a fabricated number would be worse.
        models.value = listOf(record().copy(ref = ref.copy(parameterCount = null)))
        val subject = viewModel()
        advanceUntilIdle()

        assertThat(
            subject.uiState.value.models
                .single()
                .parameterLabel,
        ).isNull()
    }
}
