package io.github.jaypetez.ollamamobile.service

import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.common.dispatcher.ApplicationScope
import io.github.jaypetez.ollamamobile.data.engine.InferenceActivityTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Decides when [InferenceForegroundService] should exist.
 *
 * The rule is deliberately narrow, because a foreground service is a promise to
 * the user about their battery:
 *
 * > start it when a generation is in flight **and** the app is not on screen;
 * > stop it as soon as either of those stops being true.
 *
 * Nothing is started while the UI is visible. A visible activity already keeps
 * the process alive, so a service then would add a notification and a wake lock
 * for no benefit at all — and the notification would be sitting next to the
 * screen that is already showing the same answer arriving.
 *
 * ## The one platform risk in here
 *
 * From Android 12 an app may not start a foreground service from the
 * background, and the moment this controller acts on is by definition the
 * moment the app stopped being in the foreground. `ON_STOP` from
 * [androidx.lifecycle.ProcessLifecycleOwner] is dispatched as the last activity
 * stops, while the process state has not yet dropped — which is why the start
 * is placed there rather than on a later signal. The call is nonetheless
 * wrapped: if the platform refuses, the generation continues in the background
 * unaided and may be throttled, which is a worse answer but not a crash. **This
 * has not been verified on a device; there is no arm64 device on this project.**
 */
@Singleton
class InferenceServiceController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val activity: InferenceActivityTracker,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private val appVisible = MutableStateFlow(true)
        private var serviceRunning = false

        /**
         * Observes the process lifecycle and the tracker together.
         *
         * Called once, from `Application.onCreate`. Split from the constructor
         * so that construction stays free of side effects and a test can decide
         * when the observation starts.
         */
        fun start(owner: LifecycleOwner) {
            owner.lifecycle.addObserver(this)
            scope.launch {
                combine(activity.active, appVisible) { running, visible ->
                    running.isNotEmpty() && !visible
                }.distinctUntilChanged()
                    .collect { wanted -> if (wanted) startService() else stopService() }
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            appVisible.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            appVisible.value = false
        }

        private fun startService() {
            if (serviceRunning) return
            val intent = Intent(context, InferenceForegroundService::class.java)
            val started = runCatching { context.startForegroundService(intent) }
                .onFailure { Timber.w(it, "The platform refused to start the inference service.") }
                .isSuccess
            serviceRunning = started
        }

        private fun stopService() {
            if (!serviceRunning) return
            serviceRunning = false
            // The service also stops itself the moment the tracker empties;
            // this is the other half — the app coming back to the foreground,
            // where the activity keeps the process alive on its own.
            runCatching { context.stopService(Intent(context, InferenceForegroundService::class.java)) }
                .onFailure { Timber.w(it, "Could not stop the inference service.") }
        }
    }
