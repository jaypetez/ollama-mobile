package io.github.jaypetez.ollamamobile.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dagger.hilt.android.EntryPointAccessors
import io.github.jaypetez.ollamamobile.data.engine.InferenceActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps a generation alive while the UI is not on screen.
 *
 * ## Why this exists at all, and why it is so narrow
 *
 * Without it, backgrounding the app during a long answer means the process
 * becomes a cached process: the CPU is throttled, the network read stalls, and
 * on a doze-eligible device the whole thing stops. The answer the user asked for
 * does not arrive, and there is nothing on screen to explain why.
 *
 * That is the *only* problem this service solves, so its life is exactly the
 * generation window:
 *
 *  * it is started by [InferenceServiceController] only when a generation is in
 *    flight **and** the app has gone to the background,
 *  * it stops itself the moment [InferenceActivityTracker] reports nothing
 *    running, without waiting to be told,
 *  * the wake lock is taken when the service starts and released in
 *    [onDestroy], so it cannot outlive the notification the user can see.
 *
 * A service that lingered would be a battery bug the user has no way to
 * diagnose, and `specialUse` is not a licence to stay resident.
 *
 * ## `specialUse`, and why not `dataSync`
 *
 * None of the defined foreground service types describes "run a language model
 * the user asked for". `dataSync` is the nearest and is wrong — nothing is being
 * synchronised, and Android 15 puts a 6-hour daily cap on it — so the type is
 * `specialUse` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in the manifest saying
 * in plain words what it is for. That string is what a Play reviewer reads.
 */
class InferenceForegroundService : Service() {
    /**
     * Resolved on demand rather than field-injected.
     *
     * `@Inject lateinit var` is the usual Hilt shape for a service and is a
     * detekt error in this project, which is a rule worth keeping: an
     * uninitialised-property crash inside a platform callback is reported as a
     * service that "sometimes fails to start". A lazy entry point has the same
     * cost and cannot be observed half-built.
     */
    private val entryPoint: EngineLifecycleEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, EngineLifecycleEntryPoint::class.java)
    }

    private val tracker: InferenceActivityTracker
        get() = entryPoint.inferenceActivityTracker()

    private val notifications: InferenceNotifications
        get() = entryPoint.inferenceNotifications()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Job? = null

    /** Not a bound service: there is nothing to call on it. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Cancels the coroutine the stream is being produced in, which is
            // the same path the Stop button in the UI takes. Stopping the
            // service without this would hide the notification and leave the
            // model decoding.
            tracker.cancelAll()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val running = tracker.current
        if (running == null) {
            // The generation finished between the decision to start and this
            // callback. Promoting to the foreground anyway would show a
            // notification for work that is over.
            stopSelfSafely()
            return START_NOT_STICKY
        }

        startForegroundWithType(running.modelLabel, running.isLocal)
        acquireWakeLock()
        observeUntilIdle()

        // START_NOT_STICKY: a process killed mid-generation has lost the
        // generation. Restarting the service would put an ongoing notification
        // on screen for a stream that no longer exists.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Stops itself as soon as the tracker is empty.
     *
     * Self-stopping rather than waiting for the controller: the generation
     * finishing is the fact that matters, and routing it through another object
     * adds a window in which the notification is still up and nothing is
     * running.
     */
    private fun observeUntilIdle() {
        watcher?.cancel()
        watcher = scope.launch {
            tracker.active
                .map { it.isEmpty() }
                .distinctUntilChanged()
                .collect { idle -> if (idle) stopSelfSafely() }
        }
    }

    private fun startForegroundWithType(modelLabel: String, onDevice: Boolean) {
        val notification = notifications.generating(modelLabel, onDevice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                InferenceNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            // The type constant does not exist before API 34 and the manifest
            // attribute is ignored there, so the two-argument call is the
            // correct one rather than a fallback.
            startForeground(InferenceNotifications.NOTIFICATION_ID, notification)
        }
    }

    /**
     * A partial wake lock for the generation window only.
     *
     * The timeout is a backstop, not the policy: [onDestroy] releases it, and
     * the service destroys itself the moment the tracker empties. The timeout
     * exists because a wake lock leaked by a bug is invisible until the user's
     * battery is flat, and an upper bound turns that into a slow answer instead.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MILLIS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /**
     * Leaves the foreground and removes the notification before stopping.
     *
     * `stopSelf` alone leaves the notification on screen until the process is
     * reaped, which reads as a generation that never ended.
     */
    private fun stopSelfSafely() {
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Timber.w(it, "Could not leave the foreground.") }
        stopSelf()
    }

    companion object {
        /** The notification action that actually cancels the generation. */
        const val ACTION_STOP: String = "io.github.jaypetez.ollamamobile.action.STOP_INFERENCE"

        private const val WAKE_LOCK_TAG = "OllamaMobile:inference"

        /** Ten minutes. Longer than any answer worth waiting for on a phone. */
        private const val MAX_WAKE_LOCK_MILLIS = 10L * 60L * 1_000L
    }
}
