package io.github.jaypetez.ollamamobile

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import io.github.jaypetez.ollamamobile.download.di.WorkManagerConfigurationEntryPoint
import timber.log.Timber

/**
 * Also WorkManager's [Configuration.Provider].
 *
 * The model download worker is a `@HiltWorker` with injected constructor
 * parameters, which the default `WorkerFactory` cannot build — it only knows the
 * `(Context, WorkerParameters)` constructor, and the failure at runtime does not
 * mention Hilt. On-demand initialisation is therefore mandatory, and it is
 * mutually exclusive with the default `androidx.work.WorkManagerInitializer`,
 * which `AndroidManifest.xml` removes from the merged manifest.
 *
 * The configuration is built in `:core-download` and reached through an entry
 * point rather than field injection: WorkManager reads this property during
 * `Application.onCreate`, before member injection has run.
 */
@HiltAndroidApp
class OllamaMobileApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = EntryPointAccessors
            .fromApplication(this, WorkManagerConfigurationEntryPoint::class.java)
            .workManagerConfiguration()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
