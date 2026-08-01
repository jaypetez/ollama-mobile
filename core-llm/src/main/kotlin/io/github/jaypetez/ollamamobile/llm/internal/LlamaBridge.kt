package io.github.jaypetez.ollamamobile.llm.internal

/**
 * Every native entry point in the app, in one object.
 *
 * ## The class name is a contract
 *
 * `core-llm/src/main/cpp/jni/llama_jni.cpp` binds these with `RegisterNatives`
 * from `JNI_OnLoad`, using the literal string
 * `io/github/jaypetez/ollamamobile/llm/internal/LlamaBridge`. Renaming or
 * moving this class without editing `kBridgeClass` there makes
 * `System.loadLibrary` throw at load time — which is the good failure: loud,
 * immediate, and nowhere near a user's first inference.
 *
 * Explicit registration rather than name mangling because R8 full mode is on
 * for release builds. A mangled `Java_io_github_..._nativeFoo` symbol embeds
 * the package and class name R8 rewrites, so implicit binding breaks in release
 * only, after shrinking, and is typically found by a user.
 *
 * ## Nothing here is safe to call on its own
 *
 * These are raw calls into C++ with no argument validation beyond what the
 * native side does. Handles are opaque; a zero handle is the null handle; a
 * stale handle is a registry miss rather than a crash, but relying on that is
 * not a design. Go through [NativeLlamaEngine].
 */
internal object LlamaBridge : NativeBackendApi, NativeSessionApi {
    /** Name passed to `System.loadLibrary`. Matches the CMake target. */
    const val LIBRARY_NAME: String = "ollamamobile_llm"

    override fun loadLibrary(name: String) {
        System.loadLibrary(name)
    }

    external override fun nativeBackendInit()

    external override fun nativeLoadBackendsFromPath(directory: String): Int

    external override fun nativeLoadBackend(path: String): Boolean

    external override fun nativeBackendNames(): Array<String>

    external override fun nativeSystemInfo(): String

    external override fun nativeCreateSession(
        modelPath: String,
        contextTokens: Int,
        threads: Int,
        batchTokens: Int,
        embeddingMode: Boolean,
        useMmap: Boolean,
    ): Long

    external override fun nativeDestroySession(handle: Long)

    external override fun nativeContextSize(handle: Long): Int

    external override fun nativeLastError(handle: Long): String?

    external override fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
        enableThinking: Boolean,
    ): String?

    external override fun nativeTokenCount(handle: Long, text: String): Int

    external override fun nativeConfigureSampler(
        handle: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
    )

    external override fun nativeStartGeneration(handle: Long, prompt: String, maxTokens: Int): Boolean

    external override fun nativeGenerateNextToken(handle: Long): ByteArray?

    external override fun nativeRequestAbort(handle: Long)

    external override fun nativeFinishReason(handle: Long): Int

    external override fun nativeStats(handle: Long): LongArray?

    external override fun nativeEmbed(handle: Long, text: String): FloatArray?

    external override fun nativeSetLoraAdapters(
        handle: Long,
        paths: Array<String>,
        scales: FloatArray,
    ): Boolean
}

/**
 * The backend-loading half of the bridge, as an interface.
 *
 * Split out purely so the load-order and safe-mode state machine in
 * [NativeLibraryLoader] can be unit-tested on a JVM with no `.so` anywhere near
 * it. That machine is the one piece of this module whose bugs surface as a
 * crash loop on a stranger's phone, so it is the piece that most needs a test.
 */
internal interface NativeBackendApi {
    fun loadLibrary(name: String)

    fun nativeBackendInit()

    /** Scans [directory] for `libggml-*.so`, scores them, keeps the best. Returns the registry size. */
    fun nativeLoadBackendsFromPath(directory: String): Int

    /** Loads exactly one backend by absolute path, skipping the scan. Safe mode uses this. */
    fun nativeLoadBackend(path: String): Boolean

    fun nativeBackendNames(): Array<String>

    fun nativeSystemInfo(): String
}

/**
 * The per-session half of the bridge.
 *
 * An interface for the same reason as [NativeBackendApi]: the engine's
 * bookkeeping — prompt rendering, sampler configuration, the pull loop, error
 * mapping, stats — is ordinary Kotlin that a test can exercise against a
 * scripted fake, and only the arithmetic inside llama.cpp genuinely needs a
 * device.
 */
@Suppress("TooManyFunctions")
internal interface NativeSessionApi {
    fun nativeCreateSession(
        modelPath: String,
        contextTokens: Int,
        threads: Int,
        batchTokens: Int,
        embeddingMode: Boolean,
        useMmap: Boolean,
    ): Long

    fun nativeDestroySession(handle: Long)

    fun nativeContextSize(handle: Long): Int

    fun nativeLastError(handle: Long): String?

    fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
        enableThinking: Boolean,
    ): String?

    fun nativeTokenCount(handle: Long, text: String): Int

    fun nativeConfigureSampler(
        handle: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
    )

    fun nativeStartGeneration(handle: Long, prompt: String, maxTokens: Int): Boolean

    /**
     * The next token's bytes, or null when generation is over.
     *
     * **Bytes, not a String.** JNI's `NewStringUTF` consumes *modified* UTF-8,
     * where a character outside the BMP is a six-byte surrogate pair rather
     * than the four-byte sequence real UTF-8 uses. Model output is full of
     * emoji. Passing four-byte sequences to `NewStringUTF` is undefined
     * behaviour that ART sometimes turns into an abort, so the bytes come
     * across raw and Kotlin decodes them.
     *
     * An **empty** array is a valid, non-terminal result: that token completed
     * no code point and its bytes are being carried into the next one.
     */
    fun nativeGenerateNextToken(handle: Long): ByteArray?

    /** Sets the abort flag. Callable from any thread, including while a decode is in flight. */
    fun nativeRequestAbort(handle: Long)

    fun nativeFinishReason(handle: Long): Int

    /** `[promptTokens, completionTokens, promptEvalNanos, evalNanos, loadNanos]`. */
    fun nativeStats(handle: Long): LongArray?

    fun nativeEmbed(handle: Long, text: String): FloatArray?

    fun nativeSetLoraAdapters(handle: Long, paths: Array<String>, scales: FloatArray): Boolean
}
