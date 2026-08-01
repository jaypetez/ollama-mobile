package io.github.jaypetez.ollamamobile.download

import android.content.Context
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The answer to "is there room for this?". */
public sealed interface QuotaDecision {
    /**
     * There is room, and [reservedBytes] have been asked of the platform so the
     * space is not handed to something else — or reclaimed from caches — while
     * the download runs.
     */
    public data class Granted(
        public val reservedBytes: Long,
    ) : QuotaDecision

    public data class Refused(
        public val requiredBytes: Long,
        public val allocatableBytes: Long,
    ) : QuotaDecision {
        public fun toError(): DownloadError.InsufficientStorage = DownloadError.InsufficientStorage(
            requiredBytes = requiredBytes,
            allocatableBytes = allocatableBytes,
        )
    }
}

/**
 * Decides, before a byte moves, whether a download can finish.
 *
 * An interface with one production implementation, because a fake is the only
 * way a test can exercise "refuse before transferring" — the platform's own
 * allocation APIs have no seam and Robolectric does not model them.
 */
public interface StorageQuotaManager {
    /** Checks and, where the platform allows it, reserves space for [requiredBytes]. */
    public suspend fun preflight(requiredBytes: Long): QuotaDecision

    /** What could be made available right now, for the UI's storage screen. */
    public suspend fun allocatableBytes(): Long
}

/**
 * Pre-flight against `StorageManager`, not `StatFs`.
 *
 * `getAllocatableBytes()` answers a different and much more useful question than
 * "how much is free": it includes the space the system is *willing to reclaim*
 * from other apps' caches. On a phone that has been full for months, that
 * difference is frequently gigabytes, and refusing a download the device could
 * actually accommodate is as bad an answer as accepting one it cannot.
 *
 * `allocateBytes()` then makes the reclaim happen up front. Without it the
 * download competes for the space it was just told it could have, and loses at
 * 94% — which is the worst possible moment, because the bytes are already spent.
 *
 * `StatFs` remains as the fallback for the case where the platform refuses to
 * answer at all. It is strictly worse — it cannot see reclaimable cache — but a
 * conservative number beats no pre-flight.
 */
@Singleton
public class AndroidStorageQuotaManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val storage: ModelStorage,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : StorageQuotaManager {
        override suspend fun preflight(requiredBytes: Long): QuotaDecision = withContext(io) {
            val needed = requiredBytes + margin(requiredBytes)
            val target = storage.downloadsRoot.also { it.mkdirs() }
            val manager = context.getSystemService(StorageManager::class.java)
                ?: return@withContext fromFreeSpace(target, requiredBytes, needed)

            val uuid = try {
                manager.getUuidForPath(target)
            } catch (e: IOException) {
                Timber.w(e, "StorageManager would not identify the volume for %s.", target.path)
                return@withContext fromFreeSpace(target, requiredBytes, needed)
            }

            val allocatable = try {
                manager.getAllocatableBytes(uuid)
            } catch (e: IOException) {
                Timber.w(e, "getAllocatableBytes failed; falling back to raw free space.")
                return@withContext fromFreeSpace(target, requiredBytes, needed)
            }

            if (allocatable < needed) {
                return@withContext QuotaDecision.Refused(requiredBytes = needed, allocatableBytes = allocatable)
            }

            try {
                manager.allocateBytes(uuid, needed)
            } catch (e: IOException) {
                // The reservation is an optimisation, not the decision. The check
                // above already said the space exists; failing to pin it means the
                // download may still lose a race, which the transfer surfaces as
                // an ordinary I/O failure.
                Timber.w(e, "Could not reserve %d bytes up front.", needed)
            }
            QuotaDecision.Granted(reservedBytes = needed)
        }

        override suspend fun allocatableBytes(): Long = withContext(io) {
            val target = storage.downloadsRoot.also { it.mkdirs() }
            val manager = context.getSystemService(StorageManager::class.java)
                ?: return@withContext target.usableSpace
            try {
                manager.getAllocatableBytes(manager.getUuidForPath(target))
            } catch (e: IOException) {
                Timber.w(e, "getAllocatableBytes failed; reporting raw free space instead.")
                target.usableSpace
            }
        }

        private fun fromFreeSpace(target: File, requiredBytes: Long, needed: Long): QuotaDecision {
            val usable = target.usableSpace
            return if (usable >= needed) {
                QuotaDecision.Granted(reservedBytes = 0)
            } else {
                QuotaDecision.Refused(requiredBytes = requiredBytes, allocatableBytes = usable)
            }
        }

        private companion object {
            /**
             * Headroom on top of the file itself.
             *
             * Filesystems degrade badly when nearly full, and Android's storage
             * manager starts deleting things on the user's behalf — so finishing a
             * download with 20 MB left is not a success. The floor matters more
             * than the percentage on small models, hence both.
             */
            const val MINIMUM_MARGIN_BYTES = 256L * 1024 * 1024
            const val MARGIN_FRACTION = 0.05

            fun margin(requiredBytes: Long): Long =
                maxOf(MINIMUM_MARGIN_BYTES, (requiredBytes * MARGIN_FRACTION).toLong())
        }
    }
