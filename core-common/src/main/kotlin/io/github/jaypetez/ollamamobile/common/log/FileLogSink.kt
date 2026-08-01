package io.github.jaypetez.ollamamobile.common.log

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Appends [LogRecord]s to a rotating file under `filesDir/logs`.
 *
 * The point of writing to disk at all is the class of bug that kills the
 * process before anyone can read the in-memory ring — an ANR, a native abort in
 * ggml, a low-memory kill. None of those run an uncaught-exception handler, so
 * `CrashReporter` never sees them and the file is the only record.
 *
 * Consequences of that goal:
 *  * every record is flushed immediately. Buffering would lose exactly the
 *    lines that explain the crash.
 *  * the total on disk is capped by [maxFileBytes] × ([maxFiles] + 1). A log
 *    that grows without bound on a user's phone is a bug, not a feature.
 *  * a write failure is swallowed. A logger that throws turns a diagnosable
 *    problem into an undiagnosable one.
 */
class FileLogSink(
    private val directory: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    private val lock = Any()

    /** The file currently being appended to. */
    val currentFile: File get() = File(directory, "$BASE_NAME.$EXTENSION")

    fun write(record: LogRecord) {
        synchronized(lock) {
            try {
                directory.mkdirs()
                val target = currentFile
                if (target.length() >= maxFileBytes) rotate()
                target.appendText(format(record))
            } catch (_: IOException) {
                // See the class KDoc: logging must never be the thing that fails.
            } catch (_: SecurityException) {
                // Same, for the case where the directory is not writable at all.
            }
        }
    }

    /** Newest first: the live file, then the numbered archives. */
    fun files(): List<File> = synchronized(lock) {
        buildList {
            if (currentFile.isFile) add(currentFile)
            for (index in 1..maxFiles) {
                val archive = archive(index)
                if (archive.isFile) add(archive)
            }
        }
    }

    /** Everything on disk, oldest archive first, for the log-export action. */
    fun readAll(): String = synchronized(lock) {
        files().reversed().joinToString(separator = "") { file ->
            runCatching { file.readText() }.getOrDefault("")
        }
    }

    fun clear() {
        synchronized(lock) {
            files().forEach { it.delete() }
        }
    }

    /** Shifts `log.txt` → `log.1.txt` → … and drops whatever falls off the end. */
    private fun rotate() {
        archive(maxFiles).delete()
        for (index in maxFiles - 1 downTo 1) {
            val source = archive(index)
            if (source.isFile) source.renameTo(archive(index + 1))
        }
        if (maxFiles >= 1) currentFile.renameTo(archive(1)) else currentFile.delete()
    }

    private fun archive(index: Int) = File(directory, "$BASE_NAME.$index.$EXTENSION")

    private fun format(record: LogRecord): String = buildString {
        append(TIMESTAMP.format(Instant.ofEpochMilli(record.timestampMillis)))
        append(' ')
        append(record.level.initial)
        append('/')
        append(record.tag ?: "-")
        append(": ")
        append(record.message)
        append('\n')
        record.throwable?.let { throwable ->
            append(stackTraceOf(throwable))
            if (!endsWith('\n')) append('\n')
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use(throwable::printStackTrace)
        return writer.toString()
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 256L * 1024L
        const val DEFAULT_MAX_FILES: Int = 3

        private const val BASE_NAME = "ollamamobile"
        private const val EXTENSION = "log"

        /** UTC and ISO-8601: a log timestamp is for correlation, not for reading aloud. */
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC)
    }
}
