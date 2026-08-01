package io.github.jaypetez.ollamamobile.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jaypetez.ollamamobile.llm.internal.BackendMode
import io.github.jaypetez.ollamamobile.llm.internal.CrashSentinel
import io.github.jaypetez.ollamamobile.llm.internal.LlamaBridge
import io.github.jaypetez.ollamamobile.llm.internal.NativeLibraryLoader
import io.github.jaypetez.ollamamobile.llm.internal.NativeStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the native library load, bind and answer at all?
 *
 * ## What this proves and what it does not
 *
 * It proves the four things that are only observable with a real Android
 * runtime and a real `.so`: `System.loadLibrary` resolves the transitive
 * `libllama.so`/`libggml*.so`; `JNI_OnLoad` found
 * `io/github/jaypetez/ollamamobile/llm/internal/LlamaBridge` and
 * `RegisterNatives` bound every entry in the table; ggml's directory scan finds
 * backend variants in `nativeLibraryDir`; and a bad model path fails as a
 * status rather than as a SIGSEGV.
 *
 * It proves nothing about inference. There is no model file on the device and
 * no arm64 hardware in this project, so token throughput, CPU-variant selection
 * and KleidiAI dispatch remain unverified — see docs/verification-status.md.
 *
 * ## The guard
 *
 * `assumeTrue(BuildConfig.NATIVE_ENABLED)` skips the whole class in the default
 * `-Pollama.nativeSource=none` build. A *failing* test there would be wrong:
 * that build deliberately contains no native code, and it is the configuration
 * CI runs so it never needs an NDK.
 */
@RunWith(AndroidJUnit4::class)
class JniSmokeTest {
    private lateinit var loader: NativeLibraryLoader
    private lateinit var sentinelFile: File

    @Before
    fun skipWithoutNativeCode() {
        assumeTrue(
            "Built with -Pollama.nativeSource=none; there is no native library to smoke.",
            BuildConfig.NATIVE_ENABLED,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A private sentinel file, so the test cannot put a real installation
        // into safe mode and cannot be affected by one.
        sentinelFile = File(context.cacheDir, "jni-smoke-sentinel").also { it.delete() }
        loader = NativeLibraryLoader(
            backendApi = LlamaBridge,
            sentinel = CrashSentinel(sentinelFile),
            directoryProvider = { context.applicationInfo.nativeLibraryDir },
            abiProvider = {
                android.os.Build.SUPPORTED_ABIS
                    .first()
            },
            nativeEnabled = true,
        )
    }

    @Test
    fun theLibraryLoadsAndRegistersAtLeastOneBackend() {
        val status = loader.status

        assertTrue("Native load failed: $status", status is NativeStatus.Ready)
        val ready = status as NativeStatus.Ready
        assertEquals(BackendMode.FULL_SCAN, ready.mode)
        assertTrue("No ggml backend was registered", ready.backends.isNotEmpty())
    }

    @Test
    fun theCpuBackendIsAmongThem() {
        assumeTrue(loader.status is NativeStatus.Ready)
        val ready = loader.status as NativeStatus.Ready

        assertTrue(
            "Expected a CPU backend, got ${ready.backends}",
            ready.backends.any { it.contains("CPU", ignoreCase = true) },
        )
    }

    @Test
    fun systemInfoIsReported() {
        // Round-trips a jstring out of native code, which also demonstrates
        // that RegisterNatives bound a method with a non-void return.
        assumeTrue(loader.status is NativeStatus.Ready)

        assertTrue(LlamaBridge.nativeSystemInfo().isNotBlank())
    }

    @Test
    fun aMissingModelFileFailsAsAStatusRatherThanACrash() {
        assumeTrue(loader.status is NativeStatus.Ready)

        val handle = LlamaBridge.nativeCreateSession(
            modelPath = "/definitely/not/a/model.gguf",
            contextTokens = 512,
            threads = 2,
            batchTokens = 128,
            embeddingMode = false,
            useMmap = true,
        )

        assertEquals("A missing file must not produce a live handle", 0L, handle)
        assertNotNull("The failure must carry a message", LlamaBridge.nativeLastError(0L))
    }

    @Test
    fun callsAgainstAnUnknownHandleAreRejectedRatherThanDereferenced() {
        // The reason handles are registry keys and not pointers.
        assumeTrue(loader.status is NativeStatus.Ready)

        assertEquals(0, LlamaBridge.nativeContextSize(0x5EEDL))
        assertEquals(-1, LlamaBridge.nativeTokenCount(0x5EEDL, "hello"))
        LlamaBridge.nativeRequestAbort(0x5EEDL)
        LlamaBridge.nativeDestroySession(0x5EEDL)
    }

    @Test
    fun theSentinelIsNotLeftBehindByLoading() {
        // Loading alone must not arm it: nothing has decoded yet, so a crash
        // here would not be a backend crash.
        assumeTrue(loader.status is NativeStatus.Ready)

        assertTrue("Loading must not arm the sentinel", !sentinelFile.exists())
    }
}
