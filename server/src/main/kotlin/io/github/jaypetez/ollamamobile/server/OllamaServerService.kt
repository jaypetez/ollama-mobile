package io.github.jaypetez.ollamamobile.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the embedded server alive while it is bound to a socket.
 *
 * ## Why a foreground service, and why `specialUse`
 *
 * A bound listening socket that dies when the app is backgrounded is worse than
 * no server at all: the laptop's `curl` succeeds, then fails, then succeeds, and
 * the user concludes the network is broken. So the process has to be
 * foreground-visible while the socket is open — and the visible notification is
 * also the honest disclosure that the phone is accepting inbound connections.
 *
 * `specialUse` is the correct type. `dataSync` describes a bounded transfer that
 * completes; this is an open listener with no completion, and mislabelling it
 * invites the platform to kill it mid-request.
 *
 * ## It never starts itself
 *
 * There is no `BOOT_COMPLETED` receiver, and there must not be one. Starting a
 * server on boot means exposing inference on every network the phone later
 * joins, long after the user has forgotten they enabled it.
 */
class OllamaServerService : Service() {
    /**
     * Resolved through an entry point rather than `@Inject lateinit`.
     *
     * A service cannot take constructor arguments, so Hilt's usual answer is a
     * field-injected `lateinit var` — a publicly mutable, publicly readable
     * property that throws if anything touches it before `onCreate`. Reading it
     * lazily from the singleton component gives the same wiring with an
     * immutable, private, always-valid reference.
     */
    private val controller: OllamaServerController by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, ServerServiceEntryPoint::class.java)
            .controller()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            combine(controller.state, controller.requestCount) { state, count -> state to count }
                .collect { (state, count) ->
                    when (state) {
                        // The socket is gone, so the disclosure would be a lie.
                        is ServerState.Stopped, is ServerState.Failed -> stopSelf()

                        else -> notify(state, count)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                controller.stop()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(controller.state.value, controller.requestCount.value))

        val port = intent?.getIntExtra(EXTRA_PORT, ServerConfig.DEFAULT_PORT) ?: ServerConfig.DEFAULT_PORT
        val lan = intent?.getBooleanExtra(EXTRA_LAN, false) == true
        scope.launch {
            controller.start(port, if (lan) BindPolicy.LAN else BindPolicy.LOOPBACK)
        }

        // NOT_STICKY: a restart by the system would re-open a listening socket
        // with no user present to have consented to it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(state: ServerState, count: Long) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(state, count))
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun buildNotification(state: ServerState, count: Long): Notification {
        val address = when (state) {
            is ServerState.Running -> state.config.displayAddress
            else -> getString(R.string.server_notification_starting)
        }
        // Explicit by two independent means: the component is named in the
        // constructor and the package is pinned. Either alone is enough for the
        // platform, but a PendingIntent wrapping an implicit Intent is
        // deliverable to any app that declares a matching filter, and this one
        // stops a listening socket — so it is worth being unambiguous. Written
        // as statements rather than a chained `.setAction()` because the chain
        // loses the component in CodeQL's dataflow and trips
        // java/android/implicit-pendingintents.
        val stop = Intent(this, OllamaServerService::class.java)
        stop.action = ACTION_STOP
        stop.setPackage(packageName)
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stop,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(getString(R.string.server_notification_text, address, count))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, getString(R.string.server_notification_stop), stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.server_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.server_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP: String = "io.github.jaypetez.ollamamobile.server.action.STOP"
        const val EXTRA_PORT: String = "port"
        const val EXTRA_LAN: String = "lan"

        private const val CHANNEL_ID = "ollama_server"
        private const val NOTIFICATION_ID = 4711
        private const val REQUEST_STOP = 1

        /** Starts the server. Only ever called from an explicit user action. */
        fun start(context: Context, port: Int = ServerConfig.DEFAULT_PORT, lanExposure: Boolean = false) {
            val intent = Intent(context, OllamaServerService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_LAN, lanExposure)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OllamaServerService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** How [OllamaServerService] reaches the singleton graph without field injection. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ServerServiceEntryPoint {
    fun controller(): OllamaServerController
}
