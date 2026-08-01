package io.github.jaypetez.ollamamobile.feature.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.testing.FakeLlamaEngine
import io.github.jaypetez.ollamamobile.ml.BackendQuarantine
import io.github.jaypetez.ollamamobile.ml.DeviceCapabilitiesProbe
import io.github.jaypetez.ollamamobile.ml.ThermalMonitor
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End to end: a run on a Robolectric "device" must produce a well-formed result
 * document.
 *
 * This is the test the emulator requirement reduces to on the host. It does not
 * assert any *value* — it cannot, and asserting one would be inventing a
 * measurement. It asserts the shape: every configuration present, every
 * repetition carrying its thermal state, the environment stating that inference
 * was not measured, and the whole thing surviving a JSON round trip.
 */
@RunWith(RobolectricTestRunner::class)
class BenchmarkRunnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = BenchmarkResultStore(context)
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        modelFile = File(context.filesDir, "models/tiny-test-q4_k_m.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(size = 1_024))
        }
    }

    @Test
    fun `a run produces a document with one result per cell`() = runTest {
        val runner = runner()
        val cells = listOf(cell("threads=1", threads = 1), cell("threads=2", threads = 2))

        val document = runToCompletion(runner, cells)

        assertThat(document.results).hasSize(2)
        assertThat(document.results.map { it.configuration.label })
            .containsExactly("threads=1", "threads=2")
            .inOrder()
    }

    @Test
    fun `every repetition records thermal state at both ends`() = runTest {
        val document = runToCompletion(runner(), listOf(cell("threads=1", threads = 1)))

        val repetitions = document.results.single().repetitions
        assertThat(repetitions).hasSize(REPETITIONS)
        repetitions.forEach { repetition ->
            assertThat(repetition.thermalBefore).isNotEmpty()
            assertThat(repetition.thermalAfter).isNotEmpty()
        }
    }

    @Test
    fun `the environment states that inference was not measured`() = runTest {
        // The whole point of the field. A consumer that reads a throughput
        // number without checking it is reading noise.
        val document = runToCompletion(
            runner(engine = FakeLlamaEngine(isAvailable = false)),
            listOf(cell("threads=1", threads = 1)),
        )

        assertThat(document.environment.nativeEnabled).isFalse()
        assertThat(document.environment.caveat).contains("no arm64 test device")
    }

    @Test
    fun `the document survives serialisation`() = runTest {
        val document = runToCompletion(runner(), listOf(cell("threads=1", threads = 1)))

        assertThat(store.decode(store.encode(document))).isEqualTo(document)
    }

    @Test
    fun `a run with the stub engine still writes both files`() = runTest {
        val document = runToCompletion(runner(), listOf(cell("threads=1", threads = 1)))

        val written = store.write(document)

        assertThat(written?.exists()).isTrue()
        assertThat(written?.parentFile?.resolve(BenchmarkResultStore.FLAT_FILE_NAME)?.exists())
            .isTrue()
    }

    @Test
    fun `an engine that cannot generate yields failed repetitions rather than no document`() =
        runTest {
            // "This configuration would not run" is itself a result, and a
            // partial document is worth far more than none.
            val document = runToCompletion(
                runner(engine = FakeLlamaEngine(isAvailable = false)),
                listOf(cell("threads=1", threads = 1)),
            )

            val summary = document.results.single().summary
            assertThat(summary.tokenGenerationTokensPerSecondMedian).isNull()
            assertThat(document.results.single().repetitions).hasSize(REPETITIONS)
        }

    @Test
    fun `available models finds gguf files in the models directory`() {
        assertThat(runner().availableModels().map { it.displayName })
            .contains("tiny-test-q4_k_m.gguf")
    }

    @Test
    fun `the quantisation is inferred from the file name`() {
        val model = runner().availableModels().single { it.displayName.contains("tiny-test") }

        assertThat(model.quantization?.label).isEqualTo("Q4_K_M")
    }

    private suspend fun runToCompletion(
        runner: BenchmarkRunner,
        cells: List<BenchmarkCell>,
    ): BenchmarkDocument {
        val progress = runner.run(cells).toList()
        return progress.filterIsInstance<BenchmarkProgress.Finished>().single().document
    }

    private fun runner(engine: FakeLlamaEngine = FakeLlamaEngine()) = BenchmarkRunner(
        context = context,
        engine = engine,
        capabilitiesProbe = DeviceCapabilitiesProbe(context),
        thermalMonitor = ThermalMonitor(context),
        energySampler = EnergySampler(context),
        quarantine = BackendQuarantine(File(context.filesDir, BackendQuarantine.FILE_NAME)),
    )

    private fun cell(label: String, threads: Int) = BenchmarkCell(
        label = label,
        model = BenchmarkModel(modelFile),
        contextTokens = 512,
        batchTokens = 128,
        ubatchTokens = 128,
        threads = threads,
        kvCacheTypeK = "f16",
        kvCacheTypeV = "f16",
        promptTokens = 8,
        generateTokens = 4,
        warmupIterations = 0,
        repetitions = REPETITIONS,
        seed = 1L,
    )

    private companion object {
        const val REPETITIONS = 2
    }
}
