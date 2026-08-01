package io.github.jaypetez.ollamamobile.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.data.InferenceGatewayImpl
import io.github.jaypetez.ollamamobile.data.SecretResolverImpl
import io.github.jaypetez.ollamamobile.data.routing.CircuitBreaker
import io.github.jaypetez.ollamamobile.llm.InferenceGateway
import io.github.jaypetez.ollamamobile.remote.SecretResolver
import io.github.jaypetez.ollamamobile.remote.di.KeystoreSecrets
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import javax.inject.Singleton

/**
 * The bindings that make the aggregation layer usable.
 *
 * Two of them close seams that other modules deliberately left open:
 *
 *  * [InferenceGateway] is declared in `:core-llm-api` so `:server` can depend
 *    on the contract without reaching `:core-data`. This is where the real
 *    implementation is attached — and because the binding lives here rather
 *    than in the contract module, a host that wants a different gateway swaps
 *    this module and nothing else.
 *  * [SecretResolver] is declared in `:core-remote` and implemented against
 *    `:core-storage`'s Keystore. Neither of those modules may depend on the
 *    other; `RemoteModule` publishes the slot with `@BindsOptionalOf` and the
 *    binding below fills it. `:core-remote` therefore still assembles on its
 *    own with `NoOpSecretResolver` when this module is absent.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    abstract fun bindInferenceGateway(impl: InferenceGatewayImpl): InferenceGateway

    /**
     * Qualified with [KeystoreSecrets], which is what `RemoteModule` declared
     * optional. An unqualified `@Binds` here would be a *duplicate* binding
     * against the one `:core-remote` already provides — a build error, not an
     * override.
     */
    @Binds
    @Singleton
    @KeystoreSecrets
    abstract fun bindSecretResolver(impl: SecretResolverImpl): SecretResolver

    companion object {
        /**
         * Provided rather than `@Inject`ed: [CircuitBreaker.Config] has a
         * default value, and Dagger cannot satisfy a defaulted constructor
         * parameter — it sees the two-argument JVM constructor and looks for a
         * binding for `Config` that nothing declares.
         */
        @Provides
        @Singleton
        fun provideCircuitBreaker(clock: WallClock): CircuitBreaker = CircuitBreaker(clock)
    }
}
