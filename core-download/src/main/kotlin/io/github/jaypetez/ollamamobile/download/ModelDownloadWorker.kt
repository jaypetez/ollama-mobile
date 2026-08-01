package io.github.jaypetez.ollamamobile.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import timber.log.Timber

/**
 * Performs one model download, shards included, as a single unit of work.
 *
 * The transfer itself is [ModelDownloader]'s. What lives here is everything that
 * only makes sense inside WorkManager: the foreground service, the progress
 * notification, and the platform's six-hour wall.
 *
 * ## Foreground, and the six-hour wall
 *
 * The transfer runs in a `dataSync` foreground service, because a background job
 * moving four gigabytes will not survive the app leaving the screen. On
 * **Android 15 (API 35) and above the platform caps `dataSync` at six hours in
 * any rolling 24-hour window**, across the whole app. When the cap is reached the
 * system calls `Service.onTimeout`, WorkManager stops this worker with
 * [WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT] — and an app that does not
 * handle that is killed.
 *
 * Six hours is not a theoretical limit here. A 4 GB model over a 2 Mbit link is
 * about four and a half hours on its own, the budget is shared with every other
 * `dataSync` use in the app, and it does not reset when the download does.
 *
 * `CoroutineWorker.onStopped()` is `final`, so the stop arrives as a
 * `CancellationException` inside [doWork]. The recovery therefore runs inside
 * [NonCancellable], which is the only way to enqueue anything from a coroutine
 * that is already being torn down, and it enqueues the follow-up with a **delay**
 * — re-entering a foreground service on an exhausted budget just gets stopped
 * again, having woken the device to do it.
 *
 * Nothing is lost by stopping: progress lives in the `.part` file's length and
 * the validator in its sidecar, so the next attempt resumes. See [ModelTransfer].
 */
@HiltWorker
public class ModelDownloadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted private val params: WorkerParameters,
        private val downloader: ModelDownloader,
        private val notifications: DownloadNotifications,
    ) : CoroutineWorker(appContext, params) {
        private var lastNotifiedAt = 0L
        private var latest: DownloadProgress? = null

        override suspend fun doWork(): Result {
            val request = parseRequest()
                ?: return Result.failure(errorData("The download request could not be read."))
            return try {
                perform(request)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { onStop(request) }
                throw e
            }
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
            title = latest?.currentFileName ?: PREPARING,
            text = PREPARING,
            fraction = latest?.fraction,
        )

        private suspend fun perform(request: DownloadRequest): Result {
            setForeground(foregroundInfo(request.displayName, PREPARING, null))
            val result = downloader.download(request) { progress ->
                latest = progress
                setProgress(progress.toData())
                updateNotification(request, progress)
            }
            return when (result) {
                is DownloadResult.AlreadyInstalled, is DownloadResult.Completed -> {
                    Result.success(successData(request))
                }

                is DownloadResult.Failed -> {
                    failureFor(request, result.error)
                }
            }
        }

        /**
         * Retry only what a retry can fix.
         *
         * A gated repository, a rejected token, a model that does not fit or a
         * device with no room will fail identically on every attempt, and
         * WorkManager's backoff would keep waking the device to prove it. A
         * transport failure or a damaged transfer is genuinely worth another go —
         * up to a bound, because a repository serving corrupt bytes would
         * otherwise retry for ever.
         */
        private suspend fun failureFor(request: DownloadRequest, error: DownloadError): Result {
            val retryable = error is DownloadError.Transport || error is DownloadError.IntegrityMismatch
            if (retryable && runAttemptCount < MAX_RUN_ATTEMPTS) {
                Timber.w("Download of %s failed (%s); retrying.", request.displayName, error.message)
                return Result.retry()
            }
            Timber.w("Download of %s failed: %s", request.displayName, error.message)
            setProgress(
                DownloadProgress(
                    modelId = request.modelId,
                    status = DownloadStatus.FAILED,
                    bytesDownloaded = downloader.bytesOnDisk(request),
                    totalBytes = request.totalBytes,
                    fileCount = request.files.size,
                    error = error,
                ).toData(),
            )
            return Result.failure(errorData(error.message, error::class.java.simpleName))
        }

        /**
         * Called when WorkManager stops this worker, whatever the reason.
         *
         * The only case needing more than WorkManager's own rescheduling is the
         * Android 15 `dataSync` timeout: the budget is spent for the rest of the
         * window, so a delayed unique re-enqueue is the recovery.
         */
        private suspend fun onStop(request: DownloadRequest) {
            if (!timedOutInForeground()) {
                Timber.i("Download of %s stopped; WorkManager will reschedule it.", request.displayName)
                return
            }
            Timber.w(
                "The dataSync foreground budget is exhausted; %s continues in %d minutes.",
                request.displayName,
                TIMEOUT_BACKOFF_MINUTES,
            )
            WorkManager
                .getInstance(applicationContext)
                .enqueueUniqueWork(
                    DownloadRepository.uniqueWorkName(request.modelId.value),
                    ExistingWorkPolicy.REPLACE,
                    DownloadRepository
                        .workRequest(request)
                        .setInitialDelay(TIMEOUT_BACKOFF_MINUTES, TimeUnit.MINUTES)
                        .build(),
                )
        }

        /**
         * Whether the platform revoked the foreground-service budget.
         *
         * `getStopReason()` is backed by JobScheduler's stop reasons, which only
         * exist from API 31; below that WorkManager has nothing to report. The
         * version guard is therefore not defensive padding — the API is genuinely
         * absent, and the condition it detects (the Android 15 `dataSync` cap)
         * cannot arise there either.
         */
        private fun timedOutInForeground(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT

        // -------------------------------------------------------------- plumbing

        private fun parseRequest(): DownloadRequest? {
            val payload = inputData.getString(KEY_REQUEST) ?: return null
            return try {
                DownloadJson.decodeFromString(DownloadRequest.serializer(), payload)
            } catch (e: SerializationException) {
                Timber.e(e, "The worker's input data is not a DownloadRequest.")
                null
            }
        }

        /**
         * Notification updates are rate-limited independently of progress
         * updates. The transfer reports every megabyte, which on a fast link is
         * many times a second, and re-posting a notification at that rate shows
         * up as jank in the shade.
         */
        private suspend fun updateNotification(request: DownloadRequest, progress: DownloadProgress) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotifiedAt < NOTIFICATION_INTERVAL_MILLIS && !progress.isTerminal) return
            lastNotifiedAt = now
            val text = when {
                progress.status == DownloadStatus.VERIFYING -> VERIFYING
                progress.fileCount > 1 -> "Part ${progress.fileIndex} of ${progress.fileCount}"
                else -> PREPARING
            }
            try {
                setForeground(foregroundInfo(request.displayName, text, progress.fraction))
            } catch (e: IllegalStateException) {
                // The process is no longer allowed to start a foreground service —
                // it has been backgrounded, or the budget is gone. The transfer
                // itself is unaffected; only the notification is.
                Timber.d(e, "Could not update the download notification.")
            }
        }

        private fun foregroundInfo(title: String, text: String, fraction: Float?): ForegroundInfo {
            val notification = notifications.progress(
                title = title,
                text = text,
                progress = fraction,
                cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            // dataSync, explicitly. From Android 14 a foreground service started
            // with no type, or with a type the manifest does not declare, is a
            // hard crash — and WorkManager cannot guess which type a worker needs.
            return ForegroundInfo(
                notifications.notificationIdFor(params.id.toString()),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }

        private fun successData(request: DownloadRequest): Data = workDataOf(
            KEY_MODEL_ID to request.modelId.value,
        )

        private fun errorData(message: String, type: String = "Unknown"): Data = workDataOf(
            KEY_ERROR_MESSAGE to message,
            KEY_ERROR_TYPE to type,
        )

        public companion object {
            public const val KEY_REQUEST: String = "request"
            public const val KEY_MODEL_ID: String = "modelId"
            public const val KEY_ERROR_MESSAGE: String = "errorMessage"
            public const val KEY_ERROR_TYPE: String = "errorType"

            public const val KEY_STATUS: String = "status"
            public const val KEY_BYTES: String = "bytes"
            public const val KEY_TOTAL: String = "total"
            public const val KEY_FILE_INDEX: String = "fileIndex"
            public const val KEY_FILE_COUNT: String = "fileCount"
            public const val KEY_FILE_NAME: String = "fileName"
            public const val KEY_RESTARTED: String = "restarted"

            /**
             * How long to wait after the platform revokes the foreground budget.
             *
             * The `dataSync` allowance is six hours per rolling 24, so there is no
             * value in trying again soon. An hour is long enough for the window to
             * have moved and short enough that a user who plugs the phone in
             * overnight wakes up to a finished download.
             */
            private const val TIMEOUT_BACKOFF_MINUTES = 60L

            private const val MAX_RUN_ATTEMPTS = 5
            private const val NOTIFICATION_INTERVAL_MILLIS = 1_000L
            private const val PREPARING = "Downloading…"
            private const val VERIFYING = "Verifying…"
        }
    }

/** The progress snapshot as WorkManager `Data`, which is how it survives process death. */
internal fun DownloadProgress.toData(): Data = workDataOf(
    ModelDownloadWorker.KEY_MODEL_ID to modelId.value,
    ModelDownloadWorker.KEY_STATUS to status.name,
    ModelDownloadWorker.KEY_BYTES to bytesDownloaded,
    ModelDownloadWorker.KEY_TOTAL to (totalBytes ?: -1L),
    ModelDownloadWorker.KEY_FILE_INDEX to fileIndex,
    ModelDownloadWorker.KEY_FILE_COUNT to fileCount,
    ModelDownloadWorker.KEY_FILE_NAME to currentFileName,
    ModelDownloadWorker.KEY_RESTARTED to restartedFromZero,
)
