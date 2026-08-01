package io.github.jaypetez.ollamamobile.service

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.data.engine.InferenceActivityTracker
import io.github.jaypetez.ollamamobile.data.engine.ModelLifecycleManager

/**
 * Reaches the engine's lifecycle from the [android.app.Application] and from
 * [InferenceForegroundService].
 *
 * An entry point rather than field injection, for two reasons that both bite:
 * `onTrimMemory` can arrive before member injection has run, and `lateinit` is
 * a detekt error in this project. `EntryPointAccessors` resolves from the
 * singleton component on demand, which is exactly the guarantee an arbitrary
 * platform callback needs.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EngineLifecycleEntryPoint {
    fun modelLifecycleManager(): ModelLifecycleManager

    fun inferenceServiceController(): InferenceServiceController

    fun inferenceActivityTracker(): InferenceActivityTracker

    fun inferenceNotifications(): InferenceNotifications
}
