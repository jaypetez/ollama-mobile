package io.github.jaypetez.ollamamobile.llm.internal

import java.io.File

/**
 * Kotlin side of the `sigaction` handler in
 * `core-llm/src/main/cpp/jni/native_crash_handler.cpp`.
 *
 * ## How this differs from [CrashSentinel], which it does not replace
 *
 * The sentinel is a file that exists while native code is running. It answers
 * one question — "did the previous run enter native code and never come out?" —
 * and it answers it even when the process is killed in a way no handler can
 * observe, such as the low-memory killer or a kernel oops. That is why it stays.
 *
 * This handler answers a different question: *what* killed it. It cannot run at
 * all for an SIGKILL, so it is strictly the less reliable of the two, and
 * strictly the more informative when it does fire. Together: the sentinel
 * decides whether to escalate the backend fallback, the record explains the
 * crash in the bug report.
 *
 * ## Why the externals live in an object with no state
 *
 * Every byte the handler needs is copied into C++ static storage by
 * [install] and [setPhase], on a healthy thread. The handler never calls back
 * into the JVM, so there is nothing here for it to hold a reference to.
 */
internal object NativeCrashHandler {
    /** The record path is fixed at install time so the handler never builds a string. */
    @Volatile
    private var installed = false

    /**
     * Installs the handler. Must be called *after* `System.loadLibrary`, and
     * is a no-op if the library is not present — a missing handler degrades
     * diagnostics and must never be the reason the app will not start.
     *
     * @return true when the handler is armed.
     */
    @Synchronized
    fun install(recordFile: File): Boolean {
        if (installed) return true
        installed = try {
            recordFile.parentFile?.mkdirs()
            nativeInstall(recordFile.absolutePath)
        } catch (_: UnsatisfiedLinkError) {
            // StubLlamaEngine builds, and any host-JVM test, have no .so.
            false
        }
        return installed
    }

    /**
     * Labels what native code is about to do, so the record says which stage
     * died. Cheap: one `memcpy` into a static buffer.
     */
    fun setPhase(phase: String) {
        if (!installed) return
        runCatching { nativeSetPhase(phase) }
    }

    private external fun nativeInstall(recordPath: String): Boolean

    private external fun nativeSetPhase(phase: String)
}
