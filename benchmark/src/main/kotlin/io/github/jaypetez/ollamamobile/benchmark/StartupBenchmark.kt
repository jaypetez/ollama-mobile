package io.github.jaypetez.ollamamobile.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold and warm start of the application.
 *
 * ## Read this before quoting anything from here
 *
 * Nothing in this module has ever run on arm64 hardware. This project has no
 * physical ARM device and none is planned, so every figure it can produce comes
 * from an x86_64 emulator on a shared CI runner. That is a **relative regression
 * signal on identical infrastructure** and nothing else: it is not device
 * performance, it cannot be compared to anyone's phone, and no result from it
 * ships with the app.
 *
 * ## Why startup, when this project is about inference
 *
 * Because startup is the part an emulator can measure honestly. Application
 * class work, Hilt graph construction, WorkManager initialisation, the first
 * frame — those cost the same shape of work on any ABI, and a regression in them
 * shows up here. Inference throughput does not: the nightly job runs with
 * `-Pollama.nativeSource=none`, so `StubLlamaEngine` is bound and there is no
 * llama.cpp in the APK at all.
 *
 * [CompilationMode.None] and [CompilationMode.Partial] are measured separately.
 * The gap between them is what a baseline profile is worth, which is the only
 * way to know whether [BaselineProfileGenerator]'s output is doing anything.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupNoCompilation() = measureStartup(
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
    )

    @Test
    fun coldStartupWithBaselineProfile() = measureStartup(
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
    )

    @Test
    fun warmStartup() = measureStartup(
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
    )

    private fun measureStartup(
        compilationMode: CompilationMode,
        startupMode: StartupMode,
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        // Five, not one. A single iteration on a shared virtualised runner
        // measures which neighbour was busy. The median of five is still noisy
        // and is at least a distribution.
        iterations = ITERATIONS,
        startupMode = startupMode,
        compilationMode = compilationMode,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val TARGET_PACKAGE = "io.github.jaypetez.ollamamobile"
        const val ITERATIONS = 5
    }
}
