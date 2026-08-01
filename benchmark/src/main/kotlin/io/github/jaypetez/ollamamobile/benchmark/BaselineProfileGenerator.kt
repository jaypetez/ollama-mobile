package io.github.jaypetez.ollamamobile.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a baseline profile by exercising the app's cold-start path.
 *
 * A baseline profile lists the classes and methods to compile ahead of time at
 * install, which removes the interpreter and JIT from the first run of those
 * paths. Its effect is real and measurable — [StartupBenchmark] measures it, by
 * comparing `CompilationMode.None` against `CompilationMode.Partial`.
 *
 * ## Two honest caveats
 *
 * The profile produced by running this on an **x86_64 emulator** is still valid
 * for arm64: a profile names methods, not machine code. What is *not* valid is
 * any claim about how much it helps, because that is measured on the same
 * emulator that produced it.
 *
 * The journey below is deliberately shallow — launch and settle. A deeper
 * journey (open a conversation, stream a reply) would produce a better profile,
 * but it needs a configured server or a loaded model, and a generator that
 * silently produces a *shorter* profile when that setup is missing is worse than
 * one that never had it: the profile would quietly get worse and nothing would
 * fail.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        const val TARGET_PACKAGE = "io.github.jaypetez.ollamamobile"
    }
}
