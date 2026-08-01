package io.github.jaypetez.ollamamobile

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import io.github.jaypetez.ollamamobile.download.di.WorkManagerConfigurationEntryPoint
import io.github.jaypetez.ollamamobile.service.EngineLifecycleEntryPoint
import timber.log.Timber

/**
 * Also WorkManager's [Configuration.Provider], and the only object the platform
 * will deliver a memory-pressure callback to.
 *
 * ## WorkManager
 *
 * The model download worker is a `@HiltWorker` with injected constructor
 * parameters, which the default `WorkerFactory` cannot build — it only knows the
 * `(Context, WorkerParameters)` constructor, and the failure at runtime does not
 * mention Hilt. On-demand initialisation is therefore mandatory, and it is
 * mutually exclusive with the default `androidx.work.WorkManagerInitializer`,
 * which `AndroidManifest.xml` removes from the merged manifest.
 *
 * ## Memory pressure
 *
 * [onTrimMemory] and [onLowMemory] are overridden here because there is nowhere
 * else to override them: `ComponentCallbacks2` is delivered to the application,
 * to activities and to registered callbacks, and a model loaded by a repository
 * has no seat at that table. Forwarding them is not an optimisation. The memory
 * estimate that allowed the load is a snapshot of `availMem` at one instant, and
 * the low-memory killer is entitled to disagree a minute later; dropping the
 * weights costs the answer in flight, whereas being killed costs the whole
 * conversation and the process with it.
 *
 * Both callbacks are resolved through an entry point rather than a `lateinit`
 * field: they can arrive before member injection has run.
 */
@HiltAndroidApp
class OllamaMobileApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = EntryPointAccessors
            .fromApplication(this, WorkManagerConfigurationEntryPoint::class.java)
            .workManagerConfiguration()

    private val engineLifecycle: EngineLifecycleEntryPoint
        get() = EntryPointAccessors.fromApplication(this, EngineLifecycleEntryPoint::class.java)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Watches for "a generation is running and the app just went away",
        // which is the only condition under which a foreground service is
        // started. See InferenceServiceController.
        engineLifecycle
            .inferenceServiceController()
            .start(ProcessLifecycleOwner.get())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engineLifecycle.modelLifecycleManager().onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        engineLifecycle.modelLifecycleManager().onLowMemory()
    }
}
