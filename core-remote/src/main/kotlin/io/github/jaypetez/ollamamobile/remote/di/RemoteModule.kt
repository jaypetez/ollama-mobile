package io.github.jaypetez.ollamamobile.remote.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.remote.NoOpSecretResolver
import io.github.jaypetez.ollamamobile.remote.OllamaClient
import io.github.jaypetez.ollamamobile.remote.OllamaClientImpl
import io.github.jaypetez.ollamamobile.remote.OpenAiCompatClient
import io.github.jaypetez.ollamamobile.remote.OpenAiCompatClientImpl
import io.github.jaypetez.ollamamobile.remote.SecretResolver
import io.github.jaypetez.ollamamobile.remote.discovery.ConnectivityLinkInfoSource
import io.github.jaypetez.ollamamobile.remote.discovery.LinkInfoSource
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import io.github.jaypetez.ollamamobile.remote.imagegen.ImageGenBackend
import io.github.jaypetez.ollamamobile.remote.imagegen.NoOpImageGenBackend
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The qualifier `:core-storage` will bind its Keystore-backed resolver with.
 *
 * It exists so that module can supply an implementation without either module
 * depending on the other and without the two bindings colliding — see
 * [SecretResolverModule].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KeystoreSecrets

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteModule {
    @Binds
    @Singleton
    abstract fun bindOllamaClient(impl: OllamaClientImpl): OllamaClient

    @Binds
    @Singleton
    abstract fun bindOpenAiCompatClient(impl: OpenAiCompatClientImpl): OpenAiCompatClient

    @Binds
    @Singleton
    abstract fun bindLinkInfoSource(impl: ConnectivityLinkInfoSource): LinkInfoSource

    companion object {
        /**
         * Wall time as its own binding rather than `System.currentTimeMillis()`
         * at each call site: the health monitor's circuit breaker and the
         * request history are both time-driven, and a test that cannot move the
         * clock has to sleep.
         */
        @Provides
        @Singleton
        fun provideWallClock(): WallClock = WallClock { System.currentTimeMillis() }

        /**
         * Image generation is not a thing Ollama does. The binding exists so the
         * seam is wired and a future ComfyUI or stable-diffusion.cpp backend can
         * replace it without touching any caller. See [ImageGenBackend].
         */
        @Provides
        @Singleton
        fun provideImageGenBackend(): ImageGenBackend = NoOpImageGenBackend
    }
}

/**
 * The guarded [SecretResolver] binding.
 *
 * `:core-storage` owns the Keystore and will bind the real resolver with
 * [KeystoreSecrets]. It cannot simply `@Binds` [SecretResolver] itself, because
 * this module must provide one *today* — otherwise `:core-remote` does not
 * assemble on its own and cannot be exercised by an app that has no storage
 * layer wired yet — and two unqualified bindings of the same type is a
 * duplicate-binding error, not an override.
 *
 * `@BindsOptionalOf` is the mechanism Dagger provides for exactly this: the
 * qualified binding is *optional*, so the graph builds whether or not
 * `:core-storage` is on it, and [provideSecretResolver] prefers the real one
 * whenever it is present. No `@TestInstallIn`, no module-replacement dance, and
 * nothing to remember to delete when storage lands.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecretResolverModule {
    @BindsOptionalOf
    @KeystoreSecrets
    abstract fun optionalKeystoreResolver(): SecretResolver

    companion object {
        @Provides
        @Singleton
        fun provideSecretResolver(
            @KeystoreSecrets keystore: Optional<SecretResolver>,
        ): SecretResolver =
            keystore.orElse(NoOpSecretResolver)
    }
}
