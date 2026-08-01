package io.github.jaypetez.ollamamobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.MainActivity
import io.github.jaypetez.ollamamobile.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The channel and the notification the inference service runs under.
 *
 * A foreground service must show a notification, so this is not decoration: it
 * is the only place a user can see that their phone is decoding tokens with the
 * screen off, and the only place they can stop it. The Stop action is therefore
 * mandatory rather than a nicety — a notification that says "generating" with no
 * way out is worse than no notification, because it tells the user about a
 * battery drain they cannot end.
 */
@Singleton
class InferenceNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val manager: NotificationManager?
            get() = context.getSystemService(NotificationManager::class.java)

        /**
         * Idempotent, and called before every `startForeground`.
         *
         * `IMPORTANCE_LOW`: this notification appears because the user asked for
         * an answer, not because anything went wrong, and a sound every time a
         * generation moves to the background is how an app gets muted.
         */
        fun ensureChannel() {
            if (manager?.getNotificationChannel(CHANNEL_ID) != null) return
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.inference_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.inference_channel_description)
                    setShowBadge(false)
                },
            )
        }

        /**
         * @param modelLabel the model that is answering. Named because "running
         *   a model" tells the user nothing about which of theirs it is, and on
         *   a device with a 4 GB model resident that is the fact that matters.
         */
        fun generating(modelLabel: String, onDevice: Boolean): Notification {
            ensureChannel()
            val text = if (onDevice) {
                context.getString(R.string.inference_notification_local, modelLabel)
            } else {
                context.getString(R.string.inference_notification_remote, modelLabel)
            }
            return NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.inference_notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openAppIntent())
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.inference_notification_stop),
                    stopIntent(),
                ).build()
        }

        /**
         * Reopens the app rather than a deep link into the thread.
         *
         * The conversation id is not carried on the notification on purpose: the
         * service exists for the seconds after the app is backgrounded, the
         * activity is almost always still in the task, and `singleTask` brings
         * back exactly the screen the user left.
         */
        private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Explicit, and `FLAG_IMMUTABLE`.
         *
         * An implicit or mutable pending intent here would let another app fill
         * in the target and have this app deliver it — which is what
         * `UnsafeImplicitIntentLaunch` is an error for in this project.
         */
        private fun stopIntent(): PendingIntent = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, InferenceForegroundService::class.java).setAction(
                InferenceForegroundService.ACTION_STOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        companion object {
            const val CHANNEL_ID: String = "inference"

            /** Fixed, because there is at most one of these notifications at a time. */
            const val NOTIFICATION_ID: Int = 4_300_001

            private const val REQUEST_OPEN = 1
            private const val REQUEST_STOP = 2
        }
    }
