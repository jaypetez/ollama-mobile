package io.github.jaypetez.ollamamobile.llm.internal

import java.io.File
import java.io.IOException

/**
 * Which ggml backend the previous run was using when it died.
 *
 * [attempt] counts how many times this process family has tried to start
 * inference without getting a token out. It is the escalation counter: 1 is a
 * normal first try, 2 means the first try died and we are on the baseline CPU
 * variant, 3 means even that died.
 */
internal data class SentinelRecord(
    val attempt: Int,
    val mode: BackendMode,
    val backend: String,
) {
    fun serialize(): String = "$attempt|${mode.name}|$backend"

    companion object {
        private const val FIELDS = 3

        /**
         * Parses a record, or returns a conservative stand-in for garbage.
         *
         * A file that exists but does not parse still proves the thing the
         * sentinel exists to prove — the previous run wrote it and never came
         * back to clear it. Returning null for it would treat a crash as a
         * clean start, so unreadable content is reported as attempt 1 in
         * [BackendMode.FULL_SCAN], which escalates exactly like a real crash.
         */
        fun parse(raw: String): SentinelRecord {
            val parts = raw.trim().split('|')
            if (parts.size != FIELDS) return unknown()
            val attempt = parts[0].toIntOrNull() ?: return unknown()
            val mode = BackendMode.entries.firstOrNull { it.name == parts[1] } ?: return unknown()
            return SentinelRecord(attempt = attempt, mode = mode, backend = parts[2])
        }

        private fun unknown(): SentinelRecord =
            SentinelRecord(attempt = 1, mode = BackendMode.FULL_SCAN, backend = "unknown")
    }
}

/**
 * A file that exists only while native code is executing for the first time in
 * a session.
 *
 * ## Why this exists at all
 *
 * A SIGILL inside a ggml kernel is not catchable. The process dies; no Kotlin
 * `finally` runs, no crash handler with a usable stack fires, and the next
 * launch has no idea anything happened. On a device whose CPU reports a feature
 * bit that its kernels then trap on — which is the failure `GGML_CPU_ALL_VARIANTS`
 * exists to make *less* likely, not impossible — that is an unbreakable crash
 * loop on the user's phone.
 *
 * The file is armed before the first `llama_decode` and cleared after the first
 * token. Finding it at startup therefore means exactly one thing: the previous
 * run entered native code and never came out. That is enough to fall back.
 *
 * This matters more here than it normally would. This project has no arm64 test
 * device, so the first real hardware to run these kernels belongs to a user.
 *
 * ## Why a file and not SharedPreferences
 *
 * `SharedPreferences.apply()` writes asynchronously and `commit()` does an fsync
 * on the main thread. A file write with an explicit flush is smaller, has no
 * background writer that a SIGILL can outrun, and is trivially readable from a
 * unit test with a temporary directory and no Robolectric.
 */
internal class CrashSentinel(
    private val file: File,
) {
    /** The record left by a previous run, or null if the last run exited cleanly. */
    fun read(): SentinelRecord? = try {
        if (file.exists()) SentinelRecord.parse(file.readText()) else null
    } catch (_: IOException) {
        // Unreadable but present is still evidence of a crash.
        SentinelRecord(attempt = 1, mode = BackendMode.FULL_SCAN, backend = "unreadable")
    }

    /**
     * Writes [record] and flushes it.
     *
     * Failure is swallowed on purpose: losing the safety net is much better
     * than refusing to run inference because a diagnostic file could not be
     * written. The consequence of a missed write is one un-escalated crash.
     */
    fun arm(record: SentinelRecord) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(record.serialize())
        } catch (_: IOException) {
            // See above.
        }
    }

    /** Clears the sentinel. Called after the first token proves the backend runs. */
    fun disarm() {
        try {
            if (file.exists()) file.delete()
        } catch (_: SecurityException) {
            // See [arm].
        }
    }

    companion object {
        /** Lives in `filesDir`, not the cache: the OS may clear the cache between launches. */
        const val FILE_NAME: String = "llm-backend-sentinel"
    }
}
