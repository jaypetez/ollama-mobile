package io.github.jaypetez.ollamamobile.common.crash

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.common.log.LogRing
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes a JSON crash record to `filesDir/crashes` and hands the throwable back
 * to whatever handler was installed before.
 *
 * ## This is local-only, and that is a decision, not an omission
 *
 * Nothing here touches the network. There is no endpoint, no API key, no
 * upload queue and no batching. The record stays in app-private storage until
 * the user chooses to open it or share it from the developer-tools screen.
 *
 * **Adding a crash-reporting SaaS — Crashlytics, Sentry, Bugsnag, any of them —
 * is a rejected change.** The reasons are specific:
 *
 *  * a crash record from this app contains the log ring, and the log ring
 *    contains prompt text. Prompts are the most sensitive thing the app holds.
 *    "We only upload stack traces" stops being true the first time someone logs
 *    a request body.
 *  * the app's privacy claim is that it makes no network call the user did not
 *    ask for. A background uploader breaks that claim outright, and a claim
 *    with an exception is not a claim.
 *  * every one of those SDKs ships its own network stack, which would be a
 *    second `OkHttpClient` that `LanOnlyGuard` does not police — the exact hole
 *    the Konsist architecture test exists to prevent.
 *
 * If crash volume ever needs to be understood in aggregate, the answer is a
 * user-initiated "share this report" action, not telemetry.
 */
@Singleton
class CrashReporter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val logRing: LogRing,
    ) {
        private val directory: File get() = File(context.filesDir, CRASH_DIRECTORY)

        @Volatile
        private var previousHandler: Thread.UncaughtExceptionHandler? = null

        @Volatile
        private var installed = false

        /**
         * Installs the handler. Idempotent — installing twice would chain this
         * reporter to itself and write the record twice.
         */
        @Synchronized
        fun install() {
            if (installed) return
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // Best-effort: a failure while recording must not stop the
                // process from dying the way the platform expects it to.
                runCatching { record(thread, throwable) }
                // Delegating rather than calling exitProcess: the platform's own
                // handler is what shows the "app has stopped" dialog and reports
                // the ANR-adjacent bookkeeping. Swallowing the crash here would
                // leave a zombie process with a dead main thread.
                previousHandler?.uncaughtException(thread, throwable)
            }
            installed = true
        }

        /** Writes one crash record and returns the file, or null if it could not be written. */
        fun record(thread: Thread, throwable: Throwable): File? = runCatching {
            directory.mkdirs()
            val timestamp = System.currentTimeMillis()
            val file = File(directory, "crash-${FILE_TIMESTAMP.format(Instant.ofEpochMilli(timestamp))}.json")
            file.writeText(JSON.encodeToString(JsonObject.serializer(), buildRecord(thread, throwable, timestamp)))
            prune()
            file
        }.getOrNull()

        /**
         * Folds a record left by the native signal handler into the same crash
         * log the Kotlin handler writes.
         *
         * Called once on the launch path. The two producers are kept in one
         * directory and one schema deliberately: a user reporting a bug should
         * not have to know that "the app crashed" has two completely different
         * capture mechanisms behind it, and the developer-tools crash list
         * would otherwise need two of everything.
         *
         * The `log` array is filled from the *current* ring, which is empty
         * this early — that is correct and better than the alternative. The ring
         * at the moment of a native crash died with the process; anything else
         * in there would be from the wrong run entirely.
         *
         * @return the file written, or null if there was no native crash.
         */
        fun mergeNativeCrash(recordFile: File): File? {
            val record = NativeCrashRecord.consume(recordFile) ?: return null
            return runCatching {
                directory.mkdirs()
                val timestamp = if (record.epochSeconds > 0) {
                    record.epochSeconds * MILLIS_PER_SECOND
                } else {
                    System.currentTimeMillis()
                }
                val file =
                    File(directory, "crash-${FILE_TIMESTAMP.format(Instant.ofEpochMilli(timestamp))}-native.json")
                file.writeText(
                    JSON.encodeToString(JsonObject.serializer(), buildNativeRecord(record, timestamp)),
                )
                prune()
                file
            }.getOrNull()
        }

        private fun buildNativeRecord(record: NativeCrashRecord, timestamp: Long): JsonObject = buildJsonObject {
            put("schema", SCHEMA_VERSION)
            put("kind", "native-signal")
            put("timestamp", ISO_TIMESTAMP.format(Instant.ofEpochMilli(timestamp)))
            put("thread", "native tid ${record.tid}")
            put("device", deviceInfo())
            put("app", appInfo())
            put(
                "throwable",
                buildJsonObject {
                    put("type", record.signalName)
                    put("message", record.summary)
                    // No stack trace: unwinding allocates, and a signal handler
                    // may not. See native_crash_handler.cpp.
                    put("stackTrace", "")
                },
            )
            put(
                "native",
                buildJsonObject {
                    put("signal", record.signalName)
                    put("signo", record.signalNumber)
                    put("code", record.code)
                    put("faultAddress", record.faultAddress)
                    put("pid", record.pid)
                    put("tid", record.tid)
                    put("phase", record.phase)
                },
            )
            put("log", logInfo())
        }

        /** Newest first. */
        fun reports(): List<File> = directory
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        fun clear() {
            reports().forEach { it.delete() }
        }

        private fun buildRecord(thread: Thread, throwable: Throwable, timestamp: Long): JsonObject = buildJsonObject {
            put("schema", SCHEMA_VERSION)
            put("timestamp", ISO_TIMESTAMP.format(Instant.ofEpochMilli(timestamp)))
            put("thread", thread.name)
            put("device", deviceInfo())
            put("app", appInfo())
            put("throwable", throwableInfo(throwable))
            put("log", logInfo())
        }

        private fun deviceInfo(): JsonObject = buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("abis", buildJsonArray { Build.SUPPORTED_ABIS.orEmpty().forEach { add(it) } })
        }

        private fun appInfo(): JsonObject = buildJsonObject {
            put("packageName", context.packageName)
            val info = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            put("versionName", info?.versionName ?: UNKNOWN)
            put("versionCode", info?.longVersionCode ?: -1L)
        }

        private fun throwableInfo(throwable: Throwable): JsonObject = buildJsonObject {
            put("type", throwable::class.java.name)
            put("message", throwable.message ?: "")
            put("stackTrace", stackTraceOf(throwable))
        }

        private fun logInfo(): JsonArray = buildJsonArray {
            logRing.recent(MAX_LOG_RECORDS).forEach { record ->
                add(
                    buildJsonObject {
                        put("at", ISO_TIMESTAMP.format(Instant.ofEpochMilli(record.timestampMillis)))
                        put("level", record.level.name)
                        put("tag", record.tag ?: "")
                        put("message", record.message)
                        record.throwable?.let { put("throwable", stackTraceOf(it)) }
                    },
                )
            }
        }

        /** Keeps the newest [MAX_REPORTS]; a crash loop must not fill the user's storage. */
        private fun prune() {
            reports().drop(MAX_REPORTS).forEach { it.delete() }
        }

        private fun stackTraceOf(throwable: Throwable): String {
            val writer = StringWriter()
            PrintWriter(writer).use(throwable::printStackTrace)
            return writer.toString()
        }

        companion object {
            const val CRASH_DIRECTORY: String = "crashes"
            const val SCHEMA_VERSION: Int = 1
            const val MAX_REPORTS: Int = 10
            const val MAX_LOG_RECORDS: Int = 256

            private const val UNKNOWN = "unknown"
            private const val MILLIS_PER_SECOND = 1_000L

            private val JSON = Json { prettyPrint = true }

            private val ISO_TIMESTAMP: DateTimeFormatter = DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)

            /** Colons are legal in a filename on ext4 but not on every export target. */
            private val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC)
        }
    }
