package io.github.jaypetez.ollamamobile.llm.internal

import io.github.jaypetez.ollamamobile.model.AppError

/** What the loader ended up with. */
internal sealed interface NativeStatus {
    data class Ready(
        val mode: BackendMode,
        /** Attempt number to write into the sentinel before the first decode. */
        val attempt: Int,
        /** Human-readable ggml device list, for the developer tools screen. */
        val backends: List<String>,
        val systemInfo: String,
    ) : NativeStatus

    data class Unavailable(
        val error: AppError.Engine.NotAvailable,
    ) : NativeStatus
}

/**
 * Loads the native library, picks a ggml backend, and owns the crash sentinel.
 *
 * ## Load order, and why it is this order
 *
 * 1. `System.loadLibrary("ollamamobile_llm")`. This resolves `libllama.so`,
 *    `libggml.so` and `libggml-base.so` through `DT_NEEDED` and runs
 *    `JNI_OnLoad`, which is where `RegisterNatives` binds every method on
 *    [LlamaBridge]. Any mismatch between the C++ class-name string and this
 *    package fails here, at load, rather than at first inference.
 * 2. `llama_backend_init()`.
 * 3. Backend discovery, from [directoryProvider] — `applicationInfo.nativeLibraryDir`.
 *
 * Step 3 is a *directory scan*: `GGML_BACKEND_DL=ON` builds each ggml CPU
 * feature variant as its own `.so` that ggml dlopens, scores against the
 * running CPU and keeps the best of. That only works if the files exist as
 * files, which is why packaging sets `jniLibs.useLegacyPackaging = true` so
 * they are extracted at install time instead of being mapped out of the APK.
 *
 * That packaging choice does **not** weaken 16 KB page-size compliance.
 * Compliance is a property of ELF `LOAD` segment alignment inside each `.so`
 * (`-Wl,-z,max-page-size=16384`, applied at directory scope in our CMakeLists
 * so llama.cpp's own targets inherit it), not of how the file is stored in the
 * APK zip. Zip page alignment matters only for loading uncompressed straight
 * out of the APK, which is exactly the mode legacy packaging opts out of.
 *
 * ## Safe mode
 *
 * See [CrashSentinel]. If the previous run died between arming and the first
 * token, this run skips the scan and loads the baseline CPU variant by name.
 */
internal class NativeLibraryLoader(
    private val backendApi: NativeBackendApi,
    private val sentinel: CrashSentinel,
    private val directoryProvider: () -> String,
    private val abiProvider: () -> String,
    private val nativeEnabled: Boolean,
) {
    /**
     * Resolved once, on first access.
     *
     * `lazy` and not an `init` block: constructing this class must not load a
     * multi-megabyte native library, because Hilt constructs singletons eagerly
     * enough that it would land on whatever thread first touched the graph.
     */
    val status: NativeStatus by lazy { resolve() }

    private var armed: SentinelRecord? = null

    /**
     * Writes the sentinel. Called immediately before the first `llama_decode`
     * of a session — the earliest point at which a SIGILL becomes possible.
     */
    fun armSentinel() {
        val ready = status as? NativeStatus.Ready ?: return
        val backend = ready.backends.firstOrNull() ?: "unknown"
        val record = SentinelRecord(attempt = ready.attempt, mode = ready.mode, backend = backend)
        armed = record
        sentinel.arm(record)
    }

    /**
     * Clears the sentinel. Called after the first token, which is the first
     * proof that the chosen kernels actually execute on this CPU.
     */
    fun disarmSentinel() {
        if (armed != null) {
            armed = null
            sentinel.disarm()
        }
    }

    private fun resolve(): NativeStatus {
        if (!nativeEnabled) {
            return NativeStatus.Unavailable(EngineErrors.notAvailable())
        }
        return when (val plan = BackendSelection.plan(sentinel.read())) {
            is BackendPlan.Disabled -> {
                // Leave the sentinel in place. Clearing it here would re-enable
                // native code on the next launch and resume the crash loop; the
                // user clearing app data is a deliberate act and the right way
                // out.
                NativeStatus.Unavailable(EngineErrors.notAvailable(plan.reason))
            }

            is BackendPlan.Load -> {
                load(plan)
            }
        }
    }

    private fun load(plan: BackendPlan.Load): NativeStatus = try {
        backendApi.loadLibrary(LlamaBridge.LIBRARY_NAME)
        backendApi.nativeBackendInit()
        val directory = directoryProvider()
        val mode = loadBackends(plan.mode, directory)
        val backends = backendApi.nativeBackendNames().toList()
        if (backends.isEmpty()) {
            NativeStatus.Unavailable(
                EngineErrors.notAvailable(
                    "No ggml backend could be loaded from $directory.",
                ),
            )
        } else {
            NativeStatus.Ready(
                mode = mode,
                attempt = plan.attempt,
                backends = backends,
                systemInfo = backendApi.nativeSystemInfo(),
            )
        }
    } catch (error: UnsatisfiedLinkError) {
        // The honest outcome of a missing or mismatched ABI. Not a crash: the
        // app is a working remote client without it.
        NativeStatus.Unavailable(
            EngineErrors.notAvailable(
                "The on-device engine could not be loaded: ${error.message}",
            ),
        )
    }

    /** Returns the mode actually used, which may differ from the one asked for. */
    private fun loadBackends(requested: BackendMode, directory: String): BackendMode {
        if (requested == BackendMode.SAFE_BASELINE) {
            val baseline = BackendSelection.baselineLibraryFor(abiProvider())
            if (baseline != null && backendApi.nativeLoadBackend("$directory/$baseline")) {
                return BackendMode.SAFE_BASELINE
            }
            // The baseline variant is missing or would not load — an unknown
            // ABI, or an upstream rename of the variant tags. Falling back to
            // the scan is strictly better than having no backend at all, and
            // the caller learns which mode it really got.
        }
        backendApi.nativeLoadBackendsFromPath(directory)
        return BackendMode.FULL_SCAN
    }
}
