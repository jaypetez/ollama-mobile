package io.github.jaypetez.ollamamobile.data.engine

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.repository.AppSettings
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRecord
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.llm.testing.FakeLlamaEngine
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * What holds a model in memory, and the two independent things that let it go.
 *
 * Both are timing- or callback-driven and neither is observable from the UI, so
 * they are exactly the kind of behaviour that regresses silently: a keep-alive
 * that never fires costs a gigabyte of RAM for the rest of the process, and an
 * `onTrimMemory` that does nothing costs the user the conversation they were
 * watching.
 */
@RunWith(JUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ModelLifecycleManagerTest {
    private val modelId = ModelId("file:/models/qwen3-1.7b.gguf")
    private val ref = ModelRef(
        id = modelId,
        displayName = "Qwen3 1.7B",
        name = "qwen3-1.7b-q4_k_m.gguf",
        origin = ModelOrigin.Local("/data/models/qwen3-1.7b.gguf"),
    )
    private val record = LocalModelRecord(
        ref = ref,
        path = "/data/models/qwen3-1.7b.gguf",
        sizeBytes = 1_200_000_000L,
        origin = "huggingface.co/Qwen/Qwen3-1.7B-GGUF",
        downloadedAtMillis = 1L,
        verdict = MemoryVerdict.Fits(headroomBytes = 2L * 1024 * 1024 * 1024),
        architecture = "qwen3",
        budgetedContextLength = 4096,
    )

    private val activity = InferenceActivityTracker()

    private fun managerWith(
        engine: FakeLlamaEngine,
        scope: TestScope,
        keepAlive: String? = null,
        verdict: MemoryVerdict = record.verdict,
        installed: LocalModelRecord? = record,
    ): ModelLifecycleManager {
        val localModels = mockk<LocalModelRepository>()
        val settings = mockk<SettingsRepository>()
        coEvery { localModels.find(any()) } returns installed
        coEvery { localModels.verdictFor(any(), any()) } returns verdict
        coEvery { settings.current() } returns AppSettings(keepAlive = keepAlive)
        return ModelLifecycleManager(
            engine = engine,
            localModels = localModels,
            settings = settings,
            activity = activity,
            scope = scope,
        )
    }

    @Test
    fun `ensureLoaded loads the model and leaves it resident`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this)

        manager.ensureLoaded(modelId)

        assertThat(engine.loadedModel.value).isEqualTo(ref)
        assertThat(engine.loads.single().path).isEqualTo(record.path)
    }

    @Test
    fun `ensureLoaded on an already-resident model does not load it again`() = runTest {
        // The whole value of a warm model is that the second turn does not pay
        // the load; a manager that reloaded would make "warm" meaningless.
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this)

        manager.ensureLoaded(modelId)
        manager.ensureLoaded(modelId)

        assertThat(engine.loads).hasSize(1)
    }

    @Test
    fun `ensureLoaded refuses a model the memory estimate refuses, before opening the file`() = runTest {
        val engine = FakeLlamaEngine()
        val verdict = MemoryVerdict.Refuse(
            requiredBytes = 6L * 1024 * 1024 * 1024,
            availableBytes = 1L * 1024 * 1024 * 1024,
            reason = "Choose a smaller quantisation of this model, or a smaller model.",
        )
        val manager = managerWith(engine, this, verdict = verdict)

        val thrown = runCatching { manager.ensureLoaded(modelId) }.exceptionOrNull()

        assertThat((thrown as AppErrorException).error).isInstanceOf(AppError.Model.InsufficientMemory::class.java)
        // Never handed to the engine: an OOM kill teaches the user nothing.
        assertThat(engine.loads).isEmpty()
    }

    @Test
    fun `ensureLoaded on a build with no engine reports that, not a missing file`() = runTest {
        val manager = managerWith(FakeLlamaEngine.unavailable(), this)

        val thrown = runCatching { manager.ensureLoaded(modelId) }.exceptionOrNull()

        assertThat((thrown as AppErrorException).error).isInstanceOf(AppError.Engine.NotAvailable::class.java)
    }

    @Test
    fun `ensureLoaded on a model that is not installed is a model error`() = runTest {
        val manager = managerWith(FakeLlamaEngine(), this, installed = null)

        val thrown = runCatching { manager.ensureLoaded(modelId) }.exceptionOrNull()

        assertThat((thrown as AppErrorException).error).isInstanceOf(AppError.Model.NotFound::class.java)
    }

    // -----------------------------------------------------------------------
    // The keep-alive timer
    // -----------------------------------------------------------------------

    @Test
    fun `the model is unloaded once the keep-alive window passes with nothing running`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this, keepAlive = "5m")
        manager.ensureLoaded(modelId)

        manager.onGenerationFinished()
        advanceTimeBy(KeepAlive.parse("5m") + 1)
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isNull()
        assertThat(manager.lastUnloadReason.value).isEqualTo(UnloadReason.IDLE_TIMEOUT)
    }

    @Test
    fun `the model is still resident before the window expires`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this, keepAlive = "5m")
        manager.ensureLoaded(modelId)

        manager.onGenerationFinished()
        advanceTimeBy(KeepAlive.parse("5m") / 2)

        assertThat(engine.loadedModel.value).isEqualTo(ref)
    }

    @Test
    fun `a new turn cancels the pending unload`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this, keepAlive = "5m")
        manager.ensureLoaded(modelId)
        manager.onGenerationFinished()

        advanceTimeBy(KeepAlive.parse("5m") / 2)
        manager.onGenerationStarted()
        advanceTimeBy(KeepAlive.parse("5m"))
        advanceUntilIdle()

        // Unloading underneath a generation the user is watching is the one
        // outcome the timer must never produce.
        assertThat(engine.loadedModel.value).isEqualTo(ref)
    }

    @Test
    fun `a negative keep-alive keeps the model resident indefinitely`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this, keepAlive = "-1")
        manager.ensureLoaded(modelId)

        manager.onGenerationFinished()
        advanceTimeBy(DAYS_IN_MILLIS)
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isEqualTo(ref)
    }

    @Test
    fun `a zero keep-alive unloads as soon as the turn ends`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this, keepAlive = "0")
        manager.ensureLoaded(modelId)

        manager.onGenerationFinished()
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isNull()
    }

    @Test
    fun `the timer does not fire while another local turn is running`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this, keepAlive = "1m")
        manager.ensureLoaded(modelId)
        manager.onGenerationFinished()
        // A second conversation started while the first one's timer was armed.
        activity.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = {})

        advanceTimeBy(KeepAlive.parse("1m") + 1)
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isEqualTo(ref)
    }

    // -----------------------------------------------------------------------
    // Memory pressure
    // -----------------------------------------------------------------------

    @Test
    fun `onTrimMemory at RUNNING_LOW unloads the model`() = runTest {
        // MemoryEstimator can say Fits and the low-memory killer can still
        // disagree a minute later. Dropping the weights loses the answer in
        // flight; being killed loses the conversation.
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this)
        manager.ensureLoaded(modelId)

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isNull()
        assertThat(manager.lastUnloadReason.value).isEqualTo(UnloadReason.MEMORY_PRESSURE)
    }

    @Test
    fun `onTrimMemory below the threshold leaves the model alone`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this)
        manager.ensureLoaded(modelId)

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isEqualTo(ref)
    }

    @Test
    fun `onLowMemory unloads the model`() = runTest {
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this)
        manager.ensureLoaded(modelId)

        manager.onLowMemory()
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isNull()
    }

    @Test
    fun `memory pressure unloads even mid-generation`() = runTest {
        // Deliberate: a generation that dies because its model went away leaves
        // a partial answer and an explanation, which is strictly better than a
        // process kill that leaves neither.
        val engine = FakeLlamaEngine()
        val manager = managerWith(engine, this)
        manager.ensureLoaded(modelId)
        manager.onGenerationStarted()
        activity.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = {})

        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        advanceUntilIdle()

        assertThat(engine.loadedModel.value).isNull()
    }

    private companion object {
        const val DAYS_IN_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
