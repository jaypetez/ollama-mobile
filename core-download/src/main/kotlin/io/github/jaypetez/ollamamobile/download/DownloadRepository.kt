package io.github.jaypetez.ollamamobile.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ModelId
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import timber.log.Timber

/**
 * The download API the rest of the app uses.
 *
 * WorkManager is the scheduler; this type owns the *meaning*. Three things it
 * does that WorkManager does not:
 *
 *  * **Unique work per model.** Two enqueues of the same model are one download,
 *    not two workers writing to the same `.part` file — which is data corruption,
 *    not a duplicated effort.
 *  * **A durable copy of the request.** WorkManager keeps input data for a live
 *    work request, but a paused download has no live request, and resuming it has
 *    to reproduce the file list and hashes exactly. The request is therefore
 *    written to disk next to the partial bytes.
 *  * **Pause, which WorkManager has no concept of.** A pause is a cancel that
 *    keeps the `.part` file and leaves a marker, so the UI can distinguish "the
 *    user stopped this" from "this failed" — two very different sentences.
 */
@Singleton
public class DownloadRepository
    @Inject
    constructor(
        private val workManager: WorkManager,
        private val storage: ModelStorage,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Starts, or joins, the download of [request].
         *
         * [ExistingWorkPolicy.KEEP] and not `REPLACE`: enqueuing the same model
         * twice is what happens when a user taps a download button twice, and the
         * right answer is to keep watching the transfer that is already running.
         */
        public suspend fun enqueue(request: DownloadRequest): ModelId = withContext(io) {
            saveRequest(request)
            clearPausedMarker(request.modelId.value)
            workManager.enqueueUniqueWork(
                uniqueWorkName(request.modelId.value),
                ExistingWorkPolicy.KEEP,
                workRequest(request).build(),
            )
            request.modelId
        }

        /**
         * Stops the transfer and keeps the bytes.
         *
         * The `.part` file and its validator sidecar survive, so [resume] costs a
         * range request rather than a re-download.
         */
        public suspend fun pause(modelId: ModelId): Unit = withContext(io) {
            markPaused(modelId.value)
            workManager.cancelUniqueWork(uniqueWorkName(modelId.value))
        }

        /** Re-enqueues a paused download from the request stored on disk. */
        public suspend fun resume(modelId: ModelId): Boolean = withContext(io) {
            val request = loadRequest(modelId.value) ?: return@withContext false
            clearPausedMarker(modelId.value)
            workManager.enqueueUniqueWork(
                uniqueWorkName(modelId.value),
                ExistingWorkPolicy.REPLACE,
                workRequest(request).build(),
            )
            true
        }

        /**
         * Abandons the download.
         *
         * @param keepPartialBytes true keeps the `.part` file so a later enqueue
         *   resumes. The default discards it, because "cancel" means the user has
         *   changed their mind and leaving four gigabytes on a phone after that is
         *   not a favour.
         */
        public suspend fun cancel(modelId: ModelId, keepPartialBytes: Boolean = false): Unit = withContext(io) {
            workManager.cancelUniqueWork(uniqueWorkName(modelId.value))
            clearPausedMarker(modelId.value)
            val request = loadRequest(modelId.value)
            if (!keepPartialBytes) {
                request?.let { storage.clearDownloadDir(it.source.storageDir) }
                requestFile(modelId.value).delete()
            }
        }

        /**
         * Progress for one model.
         *
         * Sourced from `WorkInfo` rather than from an in-memory bus so that it
         * survives process death: the worker writes its progress into WorkManager's
         * database, and a UI that comes back after the app was killed sees the real
         * state instead of an empty one.
         */
        public fun progress(modelId: ModelId): Flow<DownloadProgress> = combine(
            workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(modelId.value)),
            pausedMarkerFlow(modelId),
        ) { infos, paused ->
            toProgress(modelId, infos.firstOrNull(), paused)
        }

        /** True while [modelId] has a live or paused download. */
        public suspend fun isTracked(modelId: ModelId): Boolean = withContext(io) {
            requestFile(modelId.value).isFile
        }

        /** Storage directories that a live work request accounts for; the GC's input. */
        public suspend fun activeStorageDirs(): Set<String> = withContext(io) {
            requestsDir
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && it.name.endsWith(REQUEST_SUFFIX) }
                .mapNotNull { readRequest(it) }
                .map { it.source.storageDir }
                .toSet()
        }

        // -------------------------------------------------------------- mapping

        private fun toProgress(modelId: ModelId, info: WorkInfo?, paused: Boolean): DownloadProgress {
            if (info == null) {
                return DownloadProgress(
                    modelId = modelId,
                    status = if (paused) DownloadStatus.PAUSED else DownloadStatus.QUEUED,
                )
            }
            val reported = info.progress.toProgress(modelId)
            val status = when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadStatus.QUEUED
                WorkInfo.State.RUNNING -> reported?.status ?: DownloadStatus.RUNNING
                WorkInfo.State.SUCCEEDED -> DownloadStatus.COMPLETED
                WorkInfo.State.FAILED -> DownloadStatus.FAILED
                WorkInfo.State.CANCELLED -> if (paused) DownloadStatus.PAUSED else DownloadStatus.CANCELLED
            }
            return (reported ?: DownloadProgress(modelId = modelId, status = status)).copy(
                status = status,
                error = failureOf(info),
            )
        }

        /**
         * The worker's typed error does not survive `Data`, which only carries
         * primitives, so a failed download is reported as a transport failure
         * carrying the message the worker wrote. Callers that need the exact case
         * — the licence link for a gated repo — read
         * [ModelDownloadWorker.KEY_ERROR_TYPE] from the output data.
         */
        private fun failureOf(info: WorkInfo): DownloadError? {
            if (info.state != WorkInfo.State.FAILED) return null
            val message = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                ?: return DownloadError.Transport(AppError.Unexpected(message = "The download failed."))
            return DownloadError.Transport(AppError.Unexpected(message = message))
        }

        // -------------------------------------------------------------- storage

        private val requestsDir: File get() = File(storage.downloadsRoot, REQUESTS_DIR)

        private fun requestFile(modelId: String): File = File(requestsDir, "${key(modelId)}$REQUEST_SUFFIX")

        private fun pausedFile(modelId: String): File = File(requestsDir, "${key(modelId)}$PAUSED_SUFFIX")

        private fun saveRequest(request: DownloadRequest) {
            requestsDir.mkdirs()
            try {
                requestFile(request.modelId.value)
                    .writeText(DownloadJson.encodeToString(DownloadRequest.serializer(), request))
            } catch (e: IOException) {
                // Not fatal for this run — WorkManager already has the input data —
                // but pause and resume will not work until the next enqueue.
                Timber.w(e, "Could not persist the download request for %s.", request.modelId)
            }
        }

        private fun loadRequest(modelId: String): DownloadRequest? = readRequest(requestFile(modelId))

        private fun readRequest(file: File): DownloadRequest? {
            if (!file.isFile) return null
            return try {
                DownloadJson.decodeFromString(DownloadRequest.serializer(), file.readText())
            } catch (e: SerializationException) {
                Timber.w(e, "Unreadable stored download request %s.", file.name)
                null
            } catch (e: IOException) {
                Timber.w(e, "Could not read stored download request %s.", file.name)
                null
            }
        }

        private fun markPaused(modelId: String) {
            requestsDir.mkdirs()
            runCatching { pausedFile(modelId).writeText(System.currentTimeMillis().toString()) }
                .onFailure { Timber.w(it, "Could not record the paused state for %s.", modelId) }
        }

        private fun clearPausedMarker(modelId: String) {
            pausedFile(modelId).delete()
        }

        /**
         * The paused marker as a flow.
         *
         * It changes only when this repository writes it, so the marker is read
         * whenever the work info changes rather than watched with a FileObserver —
         * which would be a thread and an inotify watch for a boolean that only this
         * class can flip.
         */
        private fun pausedMarkerFlow(modelId: ModelId): Flow<Boolean> =
            workManager
                .getWorkInfosForUniqueWorkFlow(uniqueWorkName(modelId.value))
                .map { pausedFile(modelId.value).isFile }

        public companion object {
            private const val REQUESTS_DIR = ".requests"
            private const val REQUEST_SUFFIX = ".request.json"
            private const val PAUSED_SUFFIX = ".paused"
            private const val KEY_CHARS = 24

            /** Also the WorkManager tag, so every download can be queried at once. */
            public const val WORK_TAG: String = "model-download"

            /** One live transfer per model. See the class KDoc. */
            public fun uniqueWorkName(modelId: String): String = "$WORK_TAG:$modelId"

            /**
             * The work request, shared with [ModelDownloadWorker] so that the
             * worker's own re-enqueue after a foreground-service timeout produces
             * an identical request rather than a subtly different one.
             *
             * Returned as a builder, not a request, because the timeout path adds
             * an initial delay to it.
             */
            public fun workRequest(request: DownloadRequest): OneTimeWorkRequest.Builder = OneTimeWorkRequest
                .Builder(ModelDownloadWorker::class.java)
                .addTag(WORK_TAG)
                .addTag(uniqueWorkName(request.modelId.value))
                .setInputData(
                    workDataOf(
                        ModelDownloadWorker.KEY_REQUEST to
                            DownloadJson.encodeToString(DownloadRequest.serializer(), request),
                    ),
                ).setConstraints(constraintsFor(request))
                // Exponential from 30 seconds. A linear policy would hammer a
                // server that is down, and WorkManager's 10-second floor is far
                // too eager for something that moves gigabytes.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                // Never expedited. An expedited job runs in a quota-limited
                // foreground slot measured in minutes, which is the wrong shape
                // entirely for a transfer measured in hours.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)

            /**
             * Unmetered and storage-not-low by default, both overridable per
             * download.
             *
             * `NetworkType.UNMETERED` rather than `CONNECTED` because four
             * gigabytes over cellular is a real bill, and the default has to be the
             * one nobody regrets. A user who wants it anyway says so on the
             * download sheet and gets [NetworkType.CONNECTED].
             */
            public fun constraintsFor(request: DownloadRequest): Constraints = Constraints
                .Builder()
                .setRequiredNetworkType(
                    if (request.requireUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED,
                ).setRequiresStorageNotLow(request.requireStorageNotLow)
                .build()

            private const val BACKOFF_SECONDS = 30L

            /**
             * A filesystem-safe key for a model id.
             *
             * Model ids contain slashes and colons — `hf:bartowski/…` — so they
             * cannot be filenames. A hash also bounds the length, which matters on
             * filesystems with a 255-byte limit and model ids that include a
             * filename.
             */
            private fun key(modelId: String): String = MessageDigest
                .getInstance("SHA-256")
                .digest(modelId.toByteArray(Charsets.UTF_8))
                .toHexString()
                .take(KEY_CHARS)
        }
    }

/** Reads back what [toData] wrote. Null when the worker has not reported yet. */
internal fun Data.toProgress(modelId: ModelId): DownloadProgress? {
    val status = getString(ModelDownloadWorker.KEY_STATUS) ?: return null
    val total = getLong(ModelDownloadWorker.KEY_TOTAL, -1L)
    return DownloadProgress(
        modelId = modelId,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.RUNNING),
        bytesDownloaded = getLong(ModelDownloadWorker.KEY_BYTES, 0L),
        totalBytes = total.takeIf { it >= 0 },
        fileIndex = getInt(ModelDownloadWorker.KEY_FILE_INDEX, 0),
        fileCount = getInt(ModelDownloadWorker.KEY_FILE_COUNT, 1),
        currentFileName = getString(ModelDownloadWorker.KEY_FILE_NAME),
        restartedFromZero = getBoolean(ModelDownloadWorker.KEY_RESTARTED, false),
    )
}
