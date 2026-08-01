package io.github.jaypetez.ollamamobile.ml

import java.io.File
import java.io.IOException

/** One backend variant's crash history on this install. */
public data class QuarantineEntry(
    public val libraryName: String,
    public val crashCount: Int,
    public val lastCrashAtMillis: Long,
    /** The variant a further crash should fall back to, or null at the baseline. */
    public val demoteTo: String?,
) {
    internal fun serialize(): String =
        "$libraryName$FIELD_SEPARATOR$crashCount$FIELD_SEPARATOR$lastCrashAtMillis" +
            "$FIELD_SEPARATOR${demoteTo.orEmpty()}"

    internal companion object {
        const val FIELD_SEPARATOR: Char = '|'
        private const val FIELD_COUNT = 4

        /** Returns null for a line that does not parse; a corrupt line is dropped. */
        fun parse(line: String): QuarantineEntry? {
            val parts = line.trim().split(FIELD_SEPARATOR)
            if (parts.size != FIELD_COUNT) return null
            val name = parts[0].takeIf { it.isNotEmpty() } ?: return null
            val count = parts[1].toIntOrNull() ?: return null
            val at = parts[2].toLongOrNull() ?: return null
            return QuarantineEntry(
                libraryName = name,
                crashCount = count,
                lastCrashAtMillis = at,
                demoteTo = parts[3].takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * The ledger behind `:core-llm`'s crash sentinel.
 *
 * ## Division of labour
 *
 * `CrashSentinel` answers one question — "did the previous run enter native code
 * and never come out?" — and it has to answer it from a file written before the
 * crash, because a SIGILL inside a ggml kernel runs no `finally` block. It is
 * intentionally tiny and single-valued.
 *
 * This is the memory that outlives that: **which** variants have died, **how
 * often**, and **what to try instead**. The sentinel escalates within one
 * session; the quarantine stops the app from walking back up to a variant that
 * has already killed it twice on this device, on every launch, forever.
 *
 * That distinction is worth the second file. Without it, a device whose i8mm
 * kernels trap would crash once per launch indefinitely: the sentinel would
 * demote, the run would succeed, the sentinel would clear, and the next launch
 * would pick the bad variant again.
 *
 * ## Storage
 *
 * A newline-delimited text file, one [QuarantineEntry] per line, for the same
 * reasons `CrashSentinel` is a file: no async writer that a fatal signal can
 * outrun, no Room migration, and trivially readable in a unit test with a
 * temporary directory. Corrupt lines are dropped rather than failing the read —
 * a partially written ledger still carries the entries that were flushed.
 *
 * Not thread-safe by design; it is touched at startup and after a crash, both
 * on the engine's own thread.
 */
public class BackendQuarantine(
    private val file: File,
) {
    /**
     * Crashes on one variant before it stops being offered.
     *
     * Two, not one. A single crash can be a genuinely unrelated OOM kill or a
     * corrupted model file, and permanently giving up throughput on the evidence
     * of one event is an overreaction. Two crashes on the same variant is a
     * pattern.
     */
    public val threshold: Int = DEFAULT_THRESHOLD

    /** Every recorded entry, most-recently-crashed first. */
    public fun entries(): List<QuarantineEntry> = read().sortedByDescending { it.lastCrashAtMillis }

    /** True when [libraryName] has crashed at least [threshold] times. */
    public fun isQuarantined(libraryName: String): Boolean =
        read().firstOrNull { it.libraryName == libraryName }?.crashCount?.let { it >= threshold } == true

    /** The set of variants [BackendPolicy.select] should skip. */
    public fun quarantinedVariants(): Set<GgmlCpuVariant> = read()
        .asSequence()
        .filter { it.crashCount >= threshold }
        .mapNotNull { BackendPolicy.byLibraryName(it.libraryName) }
        .toSet()

    /**
     * Records a crash against [libraryName] and returns the updated entry.
     *
     * [nowMillis] is passed in rather than read from the clock so the ledger is
     * testable and so the caller can use the same timestamp it puts in a log.
     */
    public fun recordCrash(libraryName: String, nowMillis: Long): QuarantineEntry {
        val existing = read()
        val previous = existing.firstOrNull { it.libraryName == libraryName }
        val updated = QuarantineEntry(
            libraryName = libraryName,
            crashCount = (previous?.crashCount ?: 0) + 1,
            lastCrashAtMillis = nowMillis,
            demoteTo = BackendPolicy
                .byLibraryName(libraryName)
                ?.let(BackendPolicy::demotionFor)
                ?.libraryName,
        )
        write(existing.filterNot { it.libraryName == libraryName } + updated)
        return updated
    }

    /**
     * Clears the whole ledger.
     *
     * Offered to the user in developer tools, because the alternative for
     * someone whose device was quarantined by a since-fixed llama.cpp bug is to
     * clear the app's data and lose their conversations.
     */
    public fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (_: SecurityException) {
            // See [write]: losing the ledger costs safety, not correctness.
        }
    }

    private fun read(): List<QuarantineEntry> = try {
        if (file.exists()) {
            file.readLines().mapNotNull(QuarantineEntry::parse)
        } else {
            emptyList()
        }
    } catch (_: IOException) {
        emptyList()
    }

    /**
     * Failure is swallowed deliberately.
     *
     * Refusing to run inference because a diagnostic file could not be written
     * would trade a working app for a bookkeeping guarantee. The cost of a lost
     * write is one un-quarantined crash.
     */
    private fun write(entries: List<QuarantineEntry>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(entries.joinToString(separator = "\n") { it.serialize() })
        } catch (_: IOException) {
            // See above.
        }
    }

    public companion object {
        private const val DEFAULT_THRESHOLD = 2

        /** Lives in `filesDir`; the OS may clear the cache directory between launches. */
        public const val FILE_NAME: String = "ml-backend-quarantine"
    }
}
