package io.github.jaypetez.ollamamobile.download

import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** The outcome of one whole model download, shards included. */
public sealed interface DownloadResult {
    /** The model was already on disk and verified. Nothing was transferred. */
    public data class AlreadyInstalled(
        public val metadata: InstalledModelMetadata,
    ) : DownloadResult

    public data class Completed(
        public val metadata: InstalledModelMetadata,
        public val bytesTransferred: Long,
        /** True when a server answered 200 to a ranged request and a file began again from zero. */
        public val restartedFromZero: Boolean,
    ) : DownloadResult

    public data class Failed(
        public val error: DownloadError,
    ) : DownloadResult
}

/**
 * The download sequence, with no WorkManager in it.
 *
 * Pre-flight, transfer every file, publish the set. Separated from
 * [ModelDownloadWorker] because the worker's other job — foreground service
 * types, notification throttling, the Android 15 timeout — is platform
 * plumbing that a unit test cannot exercise, while the sequence itself is
 * exactly what has to be tested against a real socket.
 *
 * The order is the product decision. Quota is checked **first**, so a model that
 * cannot possibly fit fails in a second rather than at 94%; and publication
 * happens **last and as a set**, so a half-downloaded sharded model never
 * appears under `models/`.
 */
@Singleton
public class ModelDownloader
    @Inject
    constructor(
        private val transfer: ModelTransfer,
        private val storage: ModelStorage,
        private val quota: StorageQuotaManager,
        private val resolvers: DownloadResolvers,
    ) {
        public suspend fun download(
            request: DownloadRequest,
            resolver: DownloadUrlResolver = resolvers.forSource(request.source),
            onProgress: suspend (DownloadProgress) -> Unit = {},
        ): DownloadResult = try {
            run(request, resolver, onProgress)
        } catch (e: DownloadException) {
            DownloadResult.Failed(e.error)
        }

        private suspend fun run(
            request: DownloadRequest,
            resolver: DownloadUrlResolver,
            onProgress: suspend (DownloadProgress) -> Unit,
        ): DownloadResult {
            storage.installedMetadata(request.source.storageDir)?.let { installed ->
                Timber.i("%s is already installed; nothing to download.", request.displayName)
                return DownloadResult.AlreadyInstalled(installed)
            }

            refuseIfNoRoom(request)?.let { return it }

            val digests = mutableMapOf<String, String>()
            var completed = 0L
            var restarted = false

            request.files.forEachIndexed { index, file ->
                onProgress(
                    progress(request, DownloadStatus.RUNNING, completed, index + 1, file.fileName, restarted),
                )
                val outcome = transfer.download(
                    spec = TransferSpec(
                        fileName = file.fileName,
                        partFile = storage.partFile(request.source.storageDir, file.fileName),
                        expectedSizeBytes = file.sizeBytes,
                        expectedSha256 = file.sha256,
                        originLabel = request.source.originLabel,
                    ),
                    resolver = resolver,
                ) { onDisk, _ ->
                    onProgress(
                        progress(
                            request,
                            DownloadStatus.RUNNING,
                            completed + onDisk,
                            index + 1,
                            file.fileName,
                            restarted,
                        ),
                    )
                }
                restarted = restarted || outcome.restartedFromZero
                completed += outcome.bytesWritten
                digests[file.fileName] = outcome.sha256
            }

            onProgress(progress(request, DownloadStatus.VERIFYING, completed, request.files.size, null, restarted))
            val metadata = storage.publish(request, digests)
            onProgress(progress(request, DownloadStatus.COMPLETED, completed, request.files.size, null, restarted))
            return DownloadResult.Completed(
                metadata = metadata,
                bytesTransferred = completed,
                restartedFromZero = restarted,
            )
        }

        /**
         * Only the bytes still missing are budgeted for.
         *
         * A resumed download of a 5 GB model that already has 4.5 GB on disk
         * needs half a gigabyte, and refusing it for want of five would be
         * wrong — and would strand the bytes it already has.
         */
        private suspend fun refuseIfNoRoom(request: DownloadRequest): DownloadResult.Failed? {
            val total = request.totalBytes ?: return null
            val remaining = (total - bytesOnDisk(request)).coerceAtLeast(0L)
            return when (val decision = quota.preflight(remaining)) {
                is QuotaDecision.Granted -> null
                is QuotaDecision.Refused -> DownloadResult.Failed(decision.toError())
            }
        }

        public fun bytesOnDisk(request: DownloadRequest): Long = request.files.sumOf { file ->
            storage.partFile(request.source.storageDir, file.fileName).takeIf { it.isFile }?.length() ?: 0L
        }

        private fun progress(
            request: DownloadRequest,
            status: DownloadStatus,
            bytes: Long,
            fileIndex: Int,
            fileName: String?,
            restarted: Boolean,
        ): DownloadProgress = DownloadProgress(
            modelId = request.modelId,
            status = status,
            bytesDownloaded = bytes,
            totalBytes = request.totalBytes,
            fileIndex = fileIndex,
            fileCount = request.files.size,
            currentFileName = fileName ?: request.files.lastOrNull()?.fileName,
            restartedFromZero = restarted,
        )
    }
