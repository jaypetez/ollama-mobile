package io.github.jaypetez.ollamamobile.ml

/**
 * The optional JNI side of this module.
 *
 * ## What it is
 *
 * `libollama-ml.so` (built from `core-ml/src/main/cpp`) is a few hundred bytes
 * of C: one call to `getauxval(AT_HWCAP)`, one to `getauxval(AT_HWCAP2)`, and a
 * NEON int8 dot product for [VectorKernels]. It links against nothing but libc.
 *
 * It is deliberately **not** `:core-llm`'s library. Capability display and the
 * RAG vector kernel must keep working in a build with no llama.cpp, and folding
 * them into that library would make them disappear exactly when the default
 * `-Pollama.nativeSource=none` build is used — which is most of the time.
 *
 * ## Why every call site treats it as absent
 *
 * `:core-ml` does not apply the native convention plugin, so in the current
 * build **the library is not compiled and this object always reports
 * unavailable**. The CMake sources exist so that wiring it up is a build-file
 * change rather than a design change. Nothing here may become load-bearing:
 * [DeviceCapabilitiesProbe] has the `/proc/cpuinfo` fallback and
 * [VectorKernels] has the Kotlin reference, and those are the paths that
 * actually run today.
 */
internal object NativeCpuFeatures {
    /** Set once, on first touch. `null` means "we tried and it is not there". */
    private val handle: NativeHandle? by lazy { load() }

    val isAvailable: Boolean get() = handle != null

    /**
     * Returns the feature set from `getauxval`, or null when the library is
     * absent.
     *
     * Null and empty are different answers and the distinction matters: empty
     * means "the kernel says this CPU has none of these extensions", null means
     * "nobody asked the kernel". Collapsing them would make a missing library
     * look like an ancient CPU.
     */
    fun detect(): Set<CpuFeature>? {
        val loaded = handle ?: return null
        val hwcap = loaded.hwcap
        val hwcap2 = loaded.hwcap2
        return CpuFeature.entries.filterTo(mutableSetOf()) { feature ->
            val word = if (feature.inHwcap2) hwcap2 else hwcap
            word and feature.hwcapBit != 0L
        }
    }

    private fun load(): NativeHandle? = try {
        System.loadLibrary(LIBRARY_NAME)
        NativeHandle(hwcap = nativeHwcap(), hwcap2 = nativeHwcap2())
    } catch (_: UnsatisfiedLinkError) {
        // The expected outcome in every build that does not compile the module's
        // CMake target, which today is all of them. Not an error condition.
        null
    } catch (_: SecurityException) {
        null
    }

    private data class NativeHandle(
        val hwcap: Long,
        val hwcap2: Long,
    )

    @JvmStatic
    private external fun nativeHwcap(): Long

    @JvmStatic
    private external fun nativeHwcap2(): Long

    /**
     * `dst = sum(a[i] * b[i])` over signed bytes, widened to 32 bits.
     *
     * Declared here rather than in [VectorKernels] so that `System.loadLibrary`
     * has exactly one owner in this module.
     */
    @JvmStatic
    external fun nativeDotInt8(a: ByteArray, b: ByteArray, length: Int): Int

    const val LIBRARY_NAME: String = "ollama-ml"
}
