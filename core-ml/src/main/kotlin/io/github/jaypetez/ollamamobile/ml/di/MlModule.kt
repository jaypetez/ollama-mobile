package io.github.jaypetez.ollamamobile.ml.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.ml.BackendQuarantine
import io.github.jaypetez.ollamamobile.ml.VectorKernels
import io.github.jaypetez.ollamamobile.ml.VectorKernelsProvider
import java.io.File
import javax.inject.Singleton

/**
 * Bindings for `:core-ml`.
 *
 * `DeviceCapabilitiesProbe`, `ThermalMonitor` and `PerfHints` are
 * `@Singleton @Inject`-constructed and need no entry here. Only the two things
 * with a construction argument that Hilt cannot infer do.
 */
@Module
@InstallIn(SingletonComponent::class)
public object MlModule {
    /**
     * The quarantine ledger lives in `filesDir`, alongside `:core-llm`'s crash
     * sentinel and for the same reason: the OS may clear the cache directory
     * between launches, and a safety record that evaporates is not one.
     */
    @Provides
    @Singleton
    public fun provideBackendQuarantine(
        @ApplicationContext context: Context,
    ): BackendQuarantine = BackendQuarantine(File(context.filesDir, BackendQuarantine.FILE_NAME))

    /**
     * The kernel set is chosen once, at graph construction, rather than per
     * call, so a log line can state which one the process is using.
     */
    @Provides
    @Singleton
    public fun provideVectorKernels(): VectorKernels = VectorKernelsProvider.best()
}
