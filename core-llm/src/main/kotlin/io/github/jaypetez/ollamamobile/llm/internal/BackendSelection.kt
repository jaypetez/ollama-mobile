package io.github.jaypetez.ollamamobile.llm.internal

/** How the ggml backend for this run is chosen. */
internal enum class BackendMode {
    /**
     * Let ggml scan `nativeLibraryDir`, score every `libggml-*.so` against the
     * running CPU and keep the best. The normal path, and the one that makes a
     * single APK fast on a current flagship and functional on a 2019 phone.
     */
    FULL_SCAN,

    /**
     * Load one named baseline CPU variant and nothing else.
     *
     * The recovery path. The baseline variant is built with no optional ARM
     * extensions at all, so it cannot execute the i8mm/SVE/SME instructions a
     * SIGILL comes from — at the cost of throughput, which is the correct
     * trade against not starting.
     */
    SAFE_BASELINE,
}

/** What [NativeLibraryLoader] should do this run. */
internal sealed interface BackendPlan {
    /** The attempt number to record in the sentinel if we get as far as decoding. */
    val attempt: Int

    data class Load(
        override val attempt: Int,
        val mode: BackendMode,
    ) : BackendPlan

    /**
     * Do not load native code at all this run.
     *
     * Reached when the baseline variant *also* died. At that point the device
     * cannot run these kernels and retrying is just another crash: the app
     * degrades to remote-only, which still works, instead of looping.
     */
    data class Disabled(
        override val attempt: Int,
        val reason: String,
    ) : BackendPlan
}

/**
 * The escalation policy, as a pure function of the sentinel.
 *
 * Deliberately has no dependencies — no Context, no files, no JNI — because it
 * is the piece whose bugs are only reproducible by crashing a phone twice.
 * Everything it needs to decide is in [SentinelRecord].
 */
internal object BackendSelection {
    /**
     * Baseline CPU variant per ABI.
     *
     * These names come from `ggml_add_cpu_backend_variant` calls in
     * `third_party/llama.cpp/ggml/src/CMakeLists.txt` at the pinned tag: the
     * Android ARM tier list starts at `android_armv8.0_1` (no dotprod, no fp16
     * arithmetic, no i8mm, no SVE, no SME) and the x86 list at `x64`. Getting a
     * name wrong here is not silent — [NativeLibraryLoader] reports the
     * `ggml_backend_load` failure and falls back to a scan.
     */
    private val baselineVariants = mapOf(
        "arm64-v8a" to "libggml-cpu-android_armv8.0_1.so",
        "x86_64" to "libggml-cpu-x64.so",
    )

    /** Highest attempt that is still allowed to touch native code. */
    private const val MAX_ATTEMPTS = 2

    fun baselineLibraryFor(abi: String): String? = baselineVariants[abi]

    /**
     * Decides what to do given whatever the previous run left behind.
     *
     * * no sentinel — a clean previous exit, full scan, attempt 1;
     * * a sentinel from a full scan — that scan's pick killed us, drop to the
     *   baseline variant, attempt 2;
     * * a sentinel from a baseline run — the baseline killed us too, so there
     *   is nothing safer left to try and native inference is off.
     */
    fun plan(previous: SentinelRecord?): BackendPlan {
        if (previous == null) {
            return BackendPlan.Load(attempt = 1, mode = BackendMode.FULL_SCAN)
        }
        val nextAttempt = previous.attempt + 1
        if (nextAttempt > MAX_ATTEMPTS) {
            return BackendPlan.Disabled(
                attempt = nextAttempt,
                reason = "On-device inference crashed twice, including on the baseline CPU " +
                    "backend (last: ${previous.backend}). It is disabled until you reinstall " +
                    "or clear the app's data.",
            )
        }
        return BackendPlan.Load(attempt = nextAttempt, mode = BackendMode.SAFE_BASELINE)
    }
}
