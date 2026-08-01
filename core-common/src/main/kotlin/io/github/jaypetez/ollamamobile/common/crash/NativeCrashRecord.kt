package io.github.jaypetez.ollamamobile.common.crash

import java.io.File
import java.io.IOException

/**
 * One record written by the native signal handler in
 * `core-llm/src/main/cpp/jni/native_crash_handler.cpp`.
 *
 * The wire format is a single line of `key=value` pairs, because the writer is
 * a signal handler: it may not allocate, so it cannot build JSON, and it may
 * not call `strftime`, so it cannot format a date. Everything expensive —
 * parsing, naming, timestamp formatting, merging into the JSON crash log — is
 * deferred to here, on the next launch, in a healthy process.
 *
 * @param faultAddress raw from `siginfo_t.si_addr`. `0x0` for a null
 *   dereference, which is the single most informative value it takes.
 * @param phase the label staged before the native call — "model-load",
 *   "decode" — which is what turns "it crashed" into "it crashed loading".
 */
data class NativeCrashRecord(
    val signalName: String,
    val signalNumber: Int,
    val code: Int,
    val faultAddress: String,
    val pid: Int,
    val tid: Int,
    val epochSeconds: Long,
    val phase: String,
) {
    /**
     * A one-line explanation for the crash list.
     *
     * There is no stack trace and there deliberately never will be — unwinding
     * allocates — so the signal, the phase and the fault address have to carry
     * the whole diagnosis between them.
     */
    val summary: String
        get() = buildString {
            append(signalName)
            append(" during ")
            append(phase)
            if (faultAddress == NULL_FAULT) append(" (null dereference)")
        }

    companion object {
        const val FILE_NAME: String = "native-crash-record"

        private const val NULL_FAULT = "0x0"
        private const val EXPECTED_VERSION = "v1"

        /**
         * Parses one record, or returns null if the file is absent or is not a
         * record this version understands.
         *
         * Tolerant by design: a half-written file is entirely possible, because
         * the process was being killed while it was written. A truncated record
         * is worth nothing but must not throw on the launch path.
         */
        fun parse(line: String): NativeCrashRecord? {
            val trimmed = line.trim()
            if (!trimmed.startsWith(EXPECTED_VERSION)) return null
            val fields = trimmed
                .split(' ')
                .mapNotNull { token ->
                    val index = token.indexOf('=')
                    if (index <= 0) null else token.substring(0, index) to token.substring(index + 1)
                }.toMap()

            return NativeCrashRecord(
                signalName = fields["signal"] ?: return null,
                signalNumber = fields["signo"]?.toIntOrNull() ?: return null,
                code = fields["code"]?.toIntOrNull() ?: 0,
                faultAddress = fields["fault"] ?: "0x0",
                pid = fields["pid"]?.toIntOrNull() ?: 0,
                tid = fields["tid"]?.toIntOrNull() ?: 0,
                epochSeconds = fields["epoch"]?.toLongOrNull() ?: 0L,
                phase = fields["phase"].orEmpty().ifEmpty { "unknown" },
            )
        }

        /**
         * Reads and **deletes** the record.
         *
         * Deleting is what makes the file mean "a native crash happened since
         * the last launch". Leaving it behind would re-report the same crash on
         * every subsequent start, which reads to a user as a crash loop that is
         * not happening.
         */
        fun consume(file: File): NativeCrashRecord? {
            val record = try {
                if (file.exists()) parse(file.readText()) else null
            } catch (_: IOException) {
                null
            }
            runCatching { file.delete() }
            return record
        }
    }
}
