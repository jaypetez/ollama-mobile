package io.github.jaypetez.ollamamobile.llm.internal

import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.model.AppError

/**
 * The integer codes `llama_jni.cpp` returns from `nativeFinishReason`.
 *
 * Two halves of one enum that no compiler checks: the constants here must match
 * `kFinish*` in `core-llm/src/main/cpp/jni/llama_jni.cpp`. They are named and
 * mapped in one place so a mismatch is a one-line fix rather than a hunt, and
 * so the mapping itself is unit-testable without a device.
 */
internal object NativeFinishReason {
    const val RUNNING: Int = 0
    const val STOP: Int = 1
    const val LENGTH: Int = 2
    const val CANCELLED: Int = 3
    const val ERROR: Int = 4

    /**
     * Maps a native code to the public vocabulary.
     *
     * [RUNNING] maps to [FinishReason.UNKNOWN] rather than throwing: seeing it
     * means the pull loop ended without the native side deciding why, which is
     * a bug on our side, and turning a bug into a crash in the user's chat
     * window is not an improvement over an honest "we do not know".
     */
    fun toFinishReason(code: Int): FinishReason = when (code) {
        STOP -> FinishReason.STOP
        LENGTH -> FinishReason.LENGTH
        CANCELLED -> FinishReason.CANCELLED
        else -> FinishReason.UNKNOWN
    }

    /** True for the one code that has to become an [AppError] instead of a clean finish. */
    fun isFailure(code: Int): Boolean = code == ERROR
}

/**
 * Turns native failure strings into the typed vocabulary the UI can act on.
 *
 * The native side reports a message, not a code, because most of what can go
 * wrong inside llama.cpp is only distinguishable by what it says. Classifying
 * here rather than at the call site means one place to add a case when a new
 * message shows up, and one place a test can pin.
 */
internal object EngineErrors {
    private const val GENERIC_LOAD = "The model could not be loaded."
    private const val GENERIC_GENERATION = "Generation failed."

    /** The failure of `nativeCreateSession` returning 0. */
    fun loadFailure(nativeMessage: String?): AppError {
        val message = nativeMessage?.takeIf { it.isNotBlank() }
        return when {
            message == null -> {
                AppError.Engine.LoadFailed(GENERIC_LOAD)
            }

            // llama.cpp reports an oversized context as a failed context init
            // rather than as an allocation error, and the recovery is entirely
            // different from "the file is broken": shrink the context and try
            // again, which is advice worth giving.
            message.contains("context", ignoreCase = true) &&
                message.contains("fit", ignoreCase = true) -> {
                AppError.Engine.LoadFailed(
                    "The requested context window did not fit in memory. Try a smaller one.",
                )
            }

            message.contains("unsupported", ignoreCase = true) ||
                message.contains("unknown model architecture", ignoreCase = true) -> {
                AppError.Model.Unsupported(reason = message)
            }

            message.contains("magic", ignoreCase = true) ||
                message.contains("corrupt", ignoreCase = true) -> {
                AppError.Model.Corrupt(message = message)
            }

            else -> {
                AppError.Engine.LoadFailed(message)
            }
        }
    }

    /** The failure of a generation that had already started. */
    fun generationFailure(nativeMessage: String?): AppError =
        AppError.Engine.GenerationFailed(
            nativeMessage?.takeIf { it.isNotBlank() } ?: GENERIC_GENERATION,
        )

    /** No engine in this build, or the native library refused to load. */
    fun notAvailable(reason: String? = null): AppError.Engine.NotAvailable =
        if (reason == null) {
            AppError.Engine.NotAvailable()
        } else {
            AppError.Engine.NotAvailable(message = reason)
        }
}
