package io.github.jaypetez.ollamamobile.llm.internal

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Load order and the safe-mode fallback, with the JNI calls replaced by a
 * recorder.
 *
 * Nothing here loads a `.so`. That is the point: the decisions this class makes
 * are the ones that determine whether a device with a bad kernel dispatch gets
 * a working app or an unbreakable crash loop, and none of them are decisions
 * about arithmetic.
 */
class NativeLibraryLoaderTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private class RecordingBackendApi(
        private val backends: List<String> = listOf("CPU (armv8.6 i8mm)"),
        private val loadBackendSucceeds: Boolean = true,
        private val linkError: String? = null,
    ) : NativeBackendApi {
        val calls = mutableListOf<String>()

        override fun loadLibrary(name: String) {
            calls += "loadLibrary($name)"
            linkError?.let { throw UnsatisfiedLinkError(it) }
        }

        override fun nativeBackendInit() {
            calls += "backendInit"
        }

        override fun nativeLoadBackendsFromPath(directory: String): Int {
            calls += "scan($directory)"
            return backends.size
        }

        override fun nativeLoadBackend(path: String): Boolean {
            calls += "loadOne($path)"
            return loadBackendSucceeds
        }

        override fun nativeBackendNames(): Array<String> = backends.toTypedArray()

        override fun nativeSystemInfo(): String = "test system info"
    }

    private fun loader(
        api: NativeBackendApi,
        sentinel: CrashSentinel = CrashSentinel(File(temporaryFolder.root, "sentinel")),
        abi: String = "arm64-v8a",
        nativeEnabled: Boolean = true,
    ) = NativeLibraryLoader(
        backendApi = api,
        sentinel = sentinel,
        directoryProvider = { "/data/app/lib/arm64" },
        abiProvider = { abi },
        nativeEnabled = nativeEnabled,
    )

    @Test
    fun `the library is loaded before the backend is initialised, and before the scan`() {
        // Order is load-library, init, discover. Initialising before
        // JNI_OnLoad has run would call an unbound method.
        val api = RecordingBackendApi()

        loader(api).status

        assertThat(api.calls)
            .containsExactly(
                "loadLibrary(ollamamobile_llm)",
                "backendInit",
                "scan(/data/app/lib/arm64)",
            ).inOrder()
    }

    @Test
    fun `a clean start scans every backend variant`() {
        val status = loader(RecordingBackendApi()).status as NativeStatus.Ready

        assertThat(status.mode).isEqualTo(BackendMode.FULL_SCAN)
        assertThat(status.attempt).isEqualTo(1)
        assertThat(status.backends).containsExactly("CPU (armv8.6 i8mm)")
    }

    @Test
    fun `a surviving sentinel loads only the baseline variant, by name`() {
        val sentinel = CrashSentinel(File(temporaryFolder.root, "sentinel"))
        sentinel.arm(SentinelRecord(1, BackendMode.FULL_SCAN, "CPU (i8mm)"))
        val api = RecordingBackendApi()

        val status = loader(api, sentinel).status as NativeStatus.Ready

        assertThat(status.mode).isEqualTo(BackendMode.SAFE_BASELINE)
        assertThat(api.calls).contains("loadOne(/data/app/lib/arm64/libggml-cpu-android_armv8.0_1.so)")
        assertThat(api.calls).doesNotContain("scan(/data/app/lib/arm64)")
    }

    @Test
    fun `a second crash disables native inference without touching the library`() {
        val sentinel = CrashSentinel(File(temporaryFolder.root, "sentinel"))
        sentinel.arm(SentinelRecord(2, BackendMode.SAFE_BASELINE, "CPU"))
        val api = RecordingBackendApi()

        val status = loader(api, sentinel).status

        assertThat(status).isInstanceOf(NativeStatus.Unavailable::class.java)
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `the sentinel is left in place when native inference is disabled`() {
        // Clearing it would re-enable native code next launch and resume the
        // crash loop, which is the failure this whole mechanism exists to stop.
        val file = File(temporaryFolder.root, "sentinel")
        val sentinel = CrashSentinel(file)
        sentinel.arm(SentinelRecord(2, BackendMode.SAFE_BASELINE, "CPU"))

        loader(RecordingBackendApi(), sentinel).status

        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `an unknown ABI falls back to the scan rather than having no backend`() {
        val sentinel = CrashSentinel(File(temporaryFolder.root, "sentinel"))
        sentinel.arm(SentinelRecord(1, BackendMode.FULL_SCAN, "CPU"))
        val api = RecordingBackendApi()

        val status = loader(api, sentinel, abi = "riscv64").status as NativeStatus.Ready

        assertThat(status.mode).isEqualTo(BackendMode.FULL_SCAN)
        assertThat(api.calls).contains("scan(/data/app/lib/arm64)")
    }

    @Test
    fun `a baseline variant that will not load falls back to the scan`() {
        val sentinel = CrashSentinel(File(temporaryFolder.root, "sentinel"))
        sentinel.arm(SentinelRecord(1, BackendMode.FULL_SCAN, "CPU"))
        val api = RecordingBackendApi(loadBackendSucceeds = false)

        val status = loader(api, sentinel).status as NativeStatus.Ready

        assertThat(status.mode).isEqualTo(BackendMode.FULL_SCAN)
    }

    @Test
    fun `an UnsatisfiedLinkError becomes a typed error rather than a crash`() {
        val api = RecordingBackendApi(linkError = "dlopen failed: wrong ELF class")

        val status = loader(api).status

        assertThat(status).isInstanceOf(NativeStatus.Unavailable::class.java)
        assertThat((status as NativeStatus.Unavailable).error.message).contains("wrong ELF class")
    }

    @Test
    fun `a library that loads but registers no backend is not Ready`() {
        // Reported honestly rather than as a Ready engine that fails on the
        // first decode with something unrelated-looking.
        val status = loader(RecordingBackendApi(backends = emptyList())).status

        assertThat(status).isInstanceOf(NativeStatus.Unavailable::class.java)
    }

    @Test
    fun `a build with no native code never touches the library`() {
        val api = RecordingBackendApi()

        val status = loader(api, nativeEnabled = false).status

        assertThat(status).isInstanceOf(NativeStatus.Unavailable::class.java)
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `arming records the attempt and backend the loader actually chose`() {
        val file = File(temporaryFolder.root, "sentinel")
        val subject = loader(RecordingBackendApi(), CrashSentinel(file))

        subject.armSentinel()

        assertThat(CrashSentinel(file).read())
            .isEqualTo(SentinelRecord(1, BackendMode.FULL_SCAN, "CPU (armv8.6 i8mm)"))
    }

    @Test
    fun `disarming clears it, so the next launch sees a clean start`() {
        val file = File(temporaryFolder.root, "sentinel")
        val subject = loader(RecordingBackendApi(), CrashSentinel(file))
        subject.armSentinel()

        subject.disarmSentinel()

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `disarming without arming does not delete a record another run left`() {
        val file = File(temporaryFolder.root, "sentinel")
        val sentinel = CrashSentinel(file)
        sentinel.arm(SentinelRecord(2, BackendMode.SAFE_BASELINE, "CPU"))
        val subject = loader(RecordingBackendApi(), sentinel)

        subject.disarmSentinel()

        assertThat(file.exists()).isTrue()
    }
}
