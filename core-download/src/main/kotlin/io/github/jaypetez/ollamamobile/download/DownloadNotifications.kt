package io.github.jaypetez.ollamamobile.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The download channel and its progress notification.
 *
 * A foreground service needs a notification, so this is not decoration: without
 * it the transfer is an ordinary background job and the platform will stop it
 * the moment the app leaves the screen. It is also the only place a user can
 * cancel a download from, which is why the cancel action is not optional.
 *
 * The small icon is a platform drawable on purpose. `:core-download` shipping
 * its own resource would put a second download glyph in the APK that has to be
 * kept in step with the app's icon set for no benefit.
 */
@Singleton
public class DownloadNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val manager: NotificationManager?
            get() = context.getSystemService(NotificationManager::class.java)

        /**
         * Idempotent, and called before every `setForeground`.
         *
         * The channel is `IMPORTANCE_LOW`: a progress bar that pings and vibrates
         * every time it updates is the fastest way to get every notification from
         * this app muted.
         */
        public fun ensureChannel() {
            val existing = manager?.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = CHANNEL_DESCRIPTION
                    setShowBadge(false)
                },
            )
        }

        /**
         * @param progress null for an indeterminate bar — which is the honest
         *   rendering before the total size is known, rather than a bar sitting
         *   at zero that looks stuck.
         */
        public fun progress(
            title: String,
            text: String,
            progress: Float?,
            cancelIntent: PendingIntent?,
        ): Notification {
            ensureChannel()
            val builder = NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (progress == null) {
                builder.setProgress(PROGRESS_MAX, 0, true)
            } else {
                builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX), false)
            }
            cancelIntent?.let { builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, CANCEL_LABEL, it) }
            return builder.build()
        }

        /**
         * A stable per-model notification id.
         *
         * Derived from the id's hash rather than from a counter so that a worker
         * restarted after process death replaces its own notification instead of
         * stacking a second one next to it.
         */
        public fun notificationIdFor(modelId: String): Int = NOTIFICATION_ID_BASE + abs(modelId.hashCode() % ID_SPREAD)

        public companion object {
            public const val CHANNEL_ID: String = "model_downloads"
            private const val CHANNEL_NAME = "Model downloads"
            private const val CHANNEL_DESCRIPTION = "Progress while a model is being downloaded."
            private const val CANCEL_LABEL = "Cancel"
            private const val PROGRESS_MAX = 1000
            private const val NOTIFICATION_ID_BASE = 4_200_000
            private const val ID_SPREAD = 100_000
        }
    }
