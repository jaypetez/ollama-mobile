package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.storage.dao.ModelDao
import io.github.jaypetez.ollamamobile.storage.entity.ModelEntity
import io.github.jaypetez.ollamamobile.storage.entity.ModelOriginColumn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What one sweep removed. Returned rather than logged so the UI can report it. */
public data class SweepReport(
    public val removedPaths: List<String> = emptyList(),
    public val bytesReclaimed: Long = 0,
) {
    public val isEmpty: Boolean get() = removedPaths.isEmpty()

    public operator fun plus(other: SweepReport): SweepReport = SweepReport(
        removedPaths = removedPaths + other.removedPaths,
        bytesReclaimed = bytesReclaimed + other.bytesReclaimed,
    )
}

/**
 * Reclaims space that nothing owns any more.
 *
 * Three jobs, and the distinction between the first two and the third is a
 * product decision, not a technical one:
 *
 *  * [sweepOrphanedDownloads] and [sweepUnknownModels] delete things that are
 *    *already* nobody's: a `.part` file whose work request no longer exists, a
 *    model directory with no row in the database. Both run automatically at
 *    startup. Both are safe — the worst case is a download that starts again
 *    from a valid resume point.
 *  * [evictLeastRecentlyUsed] deletes things the user still owns, and therefore
 *    **only ever runs when the user asks for it**. A model is a deliberate
 *    multi-gigabyte download, often over a metered or slow connection.
 *    Reclaiming one unasked is hostile, and "we freed up space for you" is not a
 *    sentence this app says. The right behaviour when storage is low is to show
 *    what is using it, with per-model sizes, and let the user choose.
 */
@Singleton
public class ModelGarbageCollector
    @Inject
    constructor(
        private val storage: ModelStorage,
        private val modelDao: ModelDao,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Removes in-flight files that no live work request accounts for.
         *
         * [activeStorageDirs] is what [DownloadRepository] believes is running or
         * enqueued. Anything under `downloads/` outside that set is dead weight,
         * potentially gigabytes of it, and anything older than [maxAgeMillis] goes
         * regardless — a resume point that is a fortnight old has usually outlived
         * the signed URL and often the revision it was fetched from.
         */
        public suspend fun sweepOrphanedDownloads(
            activeStorageDirs: Set<String>,
            maxAgeMillis: Long = DEFAULT_MAX_PART_AGE_MILLIS,
            nowMillis: Long = System.currentTimeMillis(),
        ): SweepReport = withContext(io) {
            val root = storage.downloadsRoot
            if (!root.isDirectory) return@withContext SweepReport()
            val active = activeStorageDirs.map { ModelStorage.safeRelativePath(it) }.toSet()

            var report = SweepReport()
            root
                .walkTopDown()
                .filter { it.isFile && (it.name.endsWith(ModelStorage.PART_SUFFIX) || it.isResumeSidecar()) }
                .forEach { file ->
                    val owner = relativeDirOf(root, file)
                    val stale = nowMillis - file.lastModified() > maxAgeMillis
                    if (owner !in active || stale) report += delete(file)
                }
            pruneEmptyDirectories(root)
            report
        }

        /**
         * Removes model directories the database has never heard of.
         *
         * A directory with no row cannot be selected, cannot be deleted from the
         * UI and will never be loaded, so it is pure loss. The grace period
         * matters: a download that has just published its files has not
         * necessarily had its row written yet, and deleting it in that window
         * would make a successful download vanish.
         */
        public suspend fun sweepUnknownModels(
            gracePeriodMillis: Long = DEFAULT_REGISTRATION_GRACE_MILLIS,
            nowMillis: Long = System.currentTimeMillis(),
        ): SweepReport = withContext(io) {
            val root = storage.modelsRoot
            if (!root.isDirectory) return@withContext SweepReport()
            val known = modelDao
                .observeAll()
                .first()
                .map { it.id }
                .toSet()

            var report = SweepReport()
            root
                .walkTopDown()
                .filter { it.isDirectory && File(it, ModelStorage.METADATA_FILE).isFile }
                .toList()
                .forEach { directory ->
                    val storageDir = relativeDirOf(root, File(directory, ModelStorage.METADATA_FILE))
                    val metadata = storage.installedMetadata(storageDir)
                    val recent = metadata != null &&
                        nowMillis - metadata.downloadedAtEpochMillis < gracePeriodMillis
                    if (metadata?.modelId !in known && !recent) {
                        Timber.i("Removing %s: no database row refers to it.", storageDir)
                        report += deleteDirectory(directory)
                    }
                }
            pruneEmptyDirectories(root)
            report
        }

        /**
         * Deletes least-recently-used local models until [bytesToFree] have been
         * reclaimed.
         *
         * **Only call this from an explicit user action.** See the class KDoc.
         * Favourites are never candidates, and neither is [keepModelId] — the
         * model the caller is trying to make room *for*, or the one currently
         * loaded.
         */
        public suspend fun evictLeastRecentlyUsed(
            bytesToFree: Long,
            keepModelId: String? = null,
        ): SweepReport = withContext(io) {
            if (bytesToFree <= 0) return@withContext SweepReport()
            val candidates = modelDao
                .observeAll()
                .first()
                .filter { it.originType == ModelOriginColumn.LOCAL && !it.favourite && it.id != keepModelId }
                // Never used sorts oldest: it was downloaded and then ignored.
                .sortedBy { it.lastUsedAt ?: 0L }

            var report = SweepReport()
            for (model in candidates) {
                if (report.bytesReclaimed >= bytesToFree) break
                report += evict(model)
            }
            pruneEmptyDirectories(storage.modelsRoot)
            report
        }

        private suspend fun evict(model: ModelEntity): SweepReport {
            val path = model.localPath?.let(::File)
            val directory = path?.parentFile
            val report = when {
                directory != null && directory.isDirectory -> deleteDirectory(directory)
                path != null && path.isFile -> delete(path)
                else -> SweepReport()
            }
            modelDao.deleteById(model.id)
            Timber.i("Evicted %s, reclaiming %d bytes.", model.displayName, report.bytesReclaimed)
            return report
        }

        private fun delete(file: File): SweepReport {
            val size = file.length()
            return if (file.delete()) {
                SweepReport(removedPaths = listOf(file.path), bytesReclaimed = size)
            } else {
                Timber.w("Could not delete %s.", file.path)
                SweepReport()
            }
        }

        private fun deleteDirectory(directory: File): SweepReport {
            val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            return if (directory.deleteRecursively()) {
                SweepReport(removedPaths = listOf(directory.path), bytesReclaimed = size)
            } else {
                Timber.w("Could not delete %s.", directory.path)
                SweepReport()
            }
        }

        /**
         * Directory names come from repository ids, so an emptied `bartowski/`
         * would otherwise accumulate forever. The root itself is kept.
         */
        private fun pruneEmptyDirectories(root: File) {
            root
                .walkBottomUp()
                .filter { it.isDirectory && it != root }
                .forEach { directory -> if (directory.listFiles()?.isEmpty() == true) directory.delete() }
        }

        private fun relativeDirOf(root: File, file: File): String {
            val relative = file.parentFile
                ?.relativeToOrNull(root)
                ?.path
                .orEmpty()
            return relative.replace('\\', '/')
        }

        private fun File.isResumeSidecar(): Boolean = name.endsWith(ModelTransfer.RESUME_SUFFIX)

        public companion object {
            /** A fortnight. Older than that and the signed URL, and often the revision, are gone. */
            public const val DEFAULT_MAX_PART_AGE_MILLIS: Long = 14L * 24 * 60 * 60 * 1000

            /** Long enough for the repository to write the row after publishing the files. */
            public const val DEFAULT_REGISTRATION_GRACE_MILLIS: Long = 10L * 60 * 1000
        }
    }
