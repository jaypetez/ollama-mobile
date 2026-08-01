package io.github.jaypetez.ollamamobile.feature.benchmark

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.BuildConfig
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.ml.BackendPolicy
import io.github.jaypetez.ollamamobile.ml.BackendQuarantine
import io.github.jaypetez.ollamamobile.ml.DeviceCapabilitiesProbe
import io.github.jaypetez.ollamamobile.ml.ThermalMonitor
import io.github.jaypetez.ollamamobile.ml.ThermalSnapshot
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.SamplingParams
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Progress emitted while a run is in flight, so the screen can show something truthful. */
public sealed interface BenchmarkProgress {
    public data class Started(
        val totalCells: Int,
    ) : BenchmarkProgress

    public data class Cell(
        val index: Int,
        val total: Int,
        val label: String,
    ) : BenchmarkProgress

    public data class Repetition(
        val cellLabel: String,
        val index: Int,
        val total: Int,
    ) : BenchmarkProgress

    public data class Finished(
        val document: BenchmarkDocument,
    ) : BenchmarkProgress

    public data class Failed(
        val message: String,
    ) : BenchmarkProgress
}

/**
 * How the caller claims a "cold" load was achieved. Recorded, never verified.
 *
 * A cold measurement that is secretly warm is wrong by an order of magnitude and
 * is the most common way a load-time number ends up meaningless. The harness
 * cannot drop the page cache from inside the app, so the honest thing is to
 * record the claim and let a reader judge it.
 */
public enum class LoadCacheState {
    /** First load in this process, after a fresh install or a reboot. */
    CLAIMED_COLD,

    /** The file was already in page cache. */
    WARM,

    /** Nobody said. Treat load times in this document as uninterpretable. */
    UNKNOWN,
}

/**
 * Runs the inference sweeps and produces a [BenchmarkDocument].
 *
 * ## What this measures, honestly
 *
 * With `-Pollama.nativeSource=none` — the project default — `StubLlamaEngine` is
 * bound. The runner still completes and still writes a well-formed document, and
 * [BenchmarkEnvironment.nativeEnabled] is `false` in it. **Those numbers measure
 * the harness, not inference.** That is deliberate: a harness that only runs in
 * the configuration nobody uses by default is a harness that is broken the first
 * time it matters. It is also why `native_enabled` is a required field rather
 * than an optional one — a consumer that reads a throughput figure without
 * checking it is reading noise.
 *
 * ## What has never happened
 *
 * This has never produced a number on arm64 hardware. There is no such device in
 * this project and none is planned. Every figure it can currently produce comes
 * from an x86_64 emulator running the stub engine.
 */
@Singleton
public class BenchmarkRunner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val engine: LlamaEngine,
        private val capabilitiesProbe: DeviceCapabilitiesProbe,
        private val thermalMonitor: ThermalMonitor,
        private val energySampler: EnergySampler,
        private val quarantine: BackendQuarantine,
    ) {
        /**
         * Clocks as mutable properties rather than defaulted constructor
         * parameters: Dagger does not see Kotlin default arguments, so a
         * `() -> Long = ...` in an `@Inject` constructor asks the graph for a
         * `Function0<Long>` binding that does not exist and fails the build.
         */
        private val clock: () -> Long = System::currentTimeMillis
        private val nanoClock: () -> Long = System::nanoTime

        /**
         * Runs every cell in order and emits progress.
         *
         * A cell that throws does not abort the run: its repetitions carry a
         * `failure` string and the remaining cells still execute. A partial
         * document is worth far more than none, and "this configuration OOMed"
         * is itself a result.
         */
        public fun run(
            cells: List<BenchmarkCell>,
            cacheState: LoadCacheState = LoadCacheState.UNKNOWN,
        ): Flow<BenchmarkProgress> = flow {
            emit(BenchmarkProgress.Started(cells.size))
            val environment = describeEnvironment()
            val results = mutableListOf<BenchmarkResult>()

            cells.forEachIndexed { index, cell ->
                emit(BenchmarkProgress.Cell(index + 1, cells.size, cell.label))
                results += runCell(cell, cacheState) { repetition ->
                    emit(BenchmarkProgress.Repetition(cell.label, repetition, cell.repetitions))
                }
            }

            emit(
                BenchmarkProgress.Finished(
                    BenchmarkDocument(environment = environment, results = results),
                ),
            )
        }

        private suspend inline fun runCell(
            cell: BenchmarkCell,
            cacheState: LoadCacheState,
            onRepetition: (Int) -> Unit,
        ): BenchmarkResult {
            val model = modelRefFor(cell)
            val spec = ModelLoadSpec(
                model = model,
                path = cell.model.file.absolutePath,
                contextTokens = cell.contextTokens,
                threads = cell.threads,
                batchTokens = cell.batchTokens,
            )

            val coldLoadNanos = timeLoad(spec)
            // The second load is served largely from page cache. Both are real
            // and they measure different things, so both are reported.
            engine.unload()
            val warmLoadNanos = timeLoad(spec)

            val prompt = syntheticPrompt(cell)
            repeat(cell.warmupIterations) { runCatching { measureOnce(cell, model, prompt, index = -1) } }

            val repetitions = (0 until cell.repetitions).map { index ->
                onRepetition(index + 1)
                measureOnce(cell, model, prompt, index)
            }

            engine.unload()

            return BenchmarkResult(
                configuration = cell.toConfiguration(model.id.value),
                loadColdNanos = coldLoadNanos,
                loadWarmNanos = warmLoadNanos,
                loadCacheState = cacheState.name,
                repetitions = repetitions,
                summary = BenchmarkMetrics.summarise(repetitions),
            )
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun timeLoad(spec: ModelLoadSpec): Long? = try {
            val started = nanoClock()
            engine.load(spec)
            nanoClock() - started
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

        @Suppress("TooGenericExceptionCaught", "LongMethod")
        private suspend fun measureOnce(
            cell: BenchmarkCell,
            model: ModelRef,
            prompt: String,
            index: Int,
        ): BenchmarkRepetition {
            val thermalBefore = thermalMonitor.snapshot()
            val energyBefore = energySampler.read()

            var promptTokens: Int? = null
            var generatedTokens = 0
            var firstTokenNanos: Long? = null
            var promptProcessingNanos: Long? = null
            var generationNanos: Long? = null
            var failure: String? = null

            val request = InferenceRequest(
                model = model,
                messages = listOf(InferenceMessage.user(prompt)),
                sampling = SamplingParams(
                    // Greedy, with a fixed seed. Two runs of the same cell must
                    // process the same prompt and sample the same continuation;
                    // otherwise run-to-run variance includes "the model said
                    // something longer this time", which is not a performance
                    // difference.
                    temperature = 0.0,
                    seed = cell.seed,
                    numPredict = cell.generateTokens,
                    numCtx = cell.contextTokens,
                ),
            )

            val started = nanoClock()
            try {
                engine.generate(request).collect { event ->
                    when (event) {
                        is InferenceEvent.Token -> {
                            if (firstTokenNanos == null) {
                                // Measured at the application boundary, not
                                // inside the engine: the JNI transition and the
                                // coroutine dispatch are part of what a user
                                // waits for.
                                firstTokenNanos = nanoClock() - started
                            }
                            generatedTokens++
                        }

                        is InferenceEvent.Stats -> {
                            promptTokens = event.stats.promptTokens ?: promptTokens
                            promptProcessingNanos = event.stats.promptEvalNanos
                            generationNanos = event.stats.evalNanos
                            event.stats.completionTokens?.let { generatedTokens = it }
                        }

                        is InferenceEvent.Failed -> {
                            failure = event.error::class.simpleName
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failure = error::class.simpleName
            }
            val elapsed = nanoClock() - started

            val thermalAfter = thermalMonitor.snapshot()
            val energyAfter = energySampler.read()

            return BenchmarkRepetition(
                index = index,
                promptTokens = promptTokens,
                generatedTokens = generatedTokens.takeIf { it > 0 },
                promptProcessingNanos = promptProcessingNanos,
                // When the engine reports no eval duration, fall back to
                // wall-clock minus time-to-first-token: coarser, but it is the
                // generation phase and not the whole call, which is the
                // distinction that matters.
                generationNanos = generationNanos ?: firstTokenNanos?.let { elapsed - it },
                timeToFirstTokenNanos = firstTokenNanos,
                thermalBefore = thermalBefore.status.name,
                thermalAfter = thermalAfter.status.name,
                throttled = thermalBefore.status.isThrottling || thermalAfter.status.isThrottling,
                thermalHeadroomBefore = thermalBefore.headroom,
                thermalHeadroomAfter = thermalAfter.headroom,
                energyMilliJoules = energySampler.energyMilliJoules(energyBefore, energyAfter),
                peakRssBytes = ProcStatus.peakRssBytes(),
                failure = failure,
            )
        }

        /**
         * A deterministic prompt of roughly [BenchmarkCell.promptTokens] tokens.
         *
         * Deliberately synthetic and deliberately dull. Real text would make the
         * measurement depend on the tokenizer's treatment of that specific text,
         * which varies by model and would make two models incomparable for a
         * reason that has nothing to do with speed. The word list is fixed and
         * the sequence is seeded, so the prompt is byte-identical across runs.
         *
         * Token count is approximate: one short ASCII word is usually one token,
         * but no tokenizer guarantees it. The measured `prompt_tokens` in the
         * result is what the engine actually counted; this is only how much text
         * to hand it.
         */
        private fun syntheticPrompt(cell: BenchmarkCell): String {
            val random = kotlin.random.Random(cell.seed)
            return (0 until cell.promptTokens)
                .joinToString(separator = " ") { PROMPT_WORDS[random.nextInt(PROMPT_WORDS.size)] }
        }

        private fun modelRefFor(cell: BenchmarkCell): ModelRef = ModelRef(
            id = ModelId(cell.model.file.name),
            displayName = cell.model.displayName,
            name = cell.model.file.nameWithoutExtension,
            origin = ModelOrigin.Local(path = cell.model.file.absolutePath),
            quantization = cell.model.quantization,
            sizeBytes = cell.model.file
                .length()
                .takeIf { it > 0L },
            contextLength = cell.contextTokens,
        )

        /** Everything needed to interpret the results, captured once per run. */
        public fun describeEnvironment(): BenchmarkEnvironment {
            val capabilities = capabilitiesProbe.capabilities()
            val choice = BackendPolicy.select(capabilities, quarantine.quarantinedVariants())
            return BenchmarkEnvironment(
                startedAtEpochMillis = clock(),
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                soc = socModel(),
                abi = capabilities.abi,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                isEmulator = isEmulator(),
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                appBuildType = BuildConfig.BUILD_TYPE,
                nativeSource = NATIVE_SOURCE_UNKNOWN,
                nativeEnabled = engine.isAvailable,
                llamaCppRevision = null,
                cpuFeatures = capabilities.features.map { it.name }.sorted(),
                ggmlCpuVariant = choice.variant.libraryName,
                totalCores = capabilities.topology.totalCores,
                performanceCores = capabilities.topology.performanceCores,
                totalRamBytes = capabilities.totalRamBytes,
                memAvailableBytesAtStart = ProcStatus.memAvailableBytes(),
                isLowRamDevice = capabilities.isLowRamDevice,
                caveat = CAVEAT,
            )
        }

        /** GGUF files the harness can see, in the directory the app stores models in. */
        public fun availableModels(): List<BenchmarkModel> =
            File(context.filesDir, MODELS_DIRECTORY)
                .listFiles { file -> file.isFile && file.name.endsWith(GGUF_SUFFIX, ignoreCase = true) }
                .orEmpty()
                .sortedBy { it.name }
                .map(::BenchmarkModel)

        private fun socModel(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) socModelApi31() else Build.HARDWARE

        @RequiresApi(Build.VERSION_CODES.S)
        private fun socModelApi31(): String = Build.SOC_MODEL

        /**
         * Emulator detection, by fingerprint.
         *
         * Not perfect and does not need to be: it decides whether the document
         * carries the emulator caveat, and a false positive costs a warning that
         * was already true in spirit.
         */
        private fun isEmulator(): Boolean =
            Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                Build.MODEL.contains("sdk", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true)

        public companion object {
            /**
             * Travels inside every document, as a field rather than a comment,
             * because someone will copy a table out of a job summary.
             */
            public const val CAVEAT: String =
                "Relative regression signal only. This project has no arm64 test device; " +
                    "CI measures an x86_64 emulator on a shared runner. Not device performance. " +
                    "Check native_enabled before reading any throughput field."

            /**
             * The build does not currently surface `-Pollama.nativeSource` to
             * `:app`'s BuildConfig — only `:core-llm` has that field, and this
             * module may not read it. `native_enabled` carries the load-bearing
             * half of the answer.
             */
            private const val NATIVE_SOURCE_UNKNOWN = "unknown"

            private const val MODELS_DIRECTORY = "models"
            private const val GGUF_SUFFIX = ".gguf"

            private val PROMPT_WORDS = listOf(
                "the",
                "quick",
                "brown",
                "fox",
                "jumps",
                "over",
                "lazy",
                "dog",
                "and",
                "then",
                "runs",
                "back",
                "across",
                "open",
                "field",
                "again",
            )
        }
    }
