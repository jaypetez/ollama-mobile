package io.github.jaypetez.ollamamobile.common.dispatcher

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Dispatchers are injected rather than referenced as `Dispatchers.IO` at the
 * call site so a test can replace them with a `TestDispatcher` and control
 * virtual time. Code that hard-codes `Dispatchers.IO` is code whose timing a
 * test cannot observe, which is how flaky tests are born.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * A [CoroutineScope] that lives as long as the process.
 *
 * Distinct from a `viewModelScope`: work launched here survives navigation and
 * is deliberately never cancelled, so only genuinely process-lifetime work
 * belongs in it (restoring the network policy from disk, draining a log sink).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Bulk injection point for the three dispatchers.
 *
 * The individual qualifiers above are the right choice for a class that needs
 * exactly one dispatcher; this interface exists for the classes that need two
 * or three, where three constructor parameters would be noise.
 */
interface AppDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

class DefaultAppDispatchers
    @Inject
    constructor(
        @param:IoDispatcher override val io: CoroutineDispatcher,
        @param:DefaultDispatcher override val default: CoroutineDispatcher,
        @param:MainDispatcher override val main: CoroutineDispatcher,
    ) : AppDispatchers

@Module
@InstallIn(SingletonComponent::class)
abstract class DispatchersModule {
    @Binds
    abstract fun bindAppDispatchers(impl: DefaultAppDispatchers): AppDispatchers

    companion object {
        @Provides
        @IoDispatcher
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @DefaultDispatcher
        fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

        @Provides
        @MainDispatcher
        fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

        /**
         * `SupervisorJob` and not `Job`: one failed child must not tear down
         * every other piece of process-lifetime work.
         */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(
            @DefaultDispatcher dispatcher: CoroutineDispatcher,
        ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    }
}
