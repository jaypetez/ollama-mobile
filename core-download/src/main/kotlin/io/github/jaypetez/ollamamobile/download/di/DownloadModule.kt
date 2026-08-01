package io.github.jaypetez.ollamamobile.download.di

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.download.AndroidStorageQuotaManager
import io.github.jaypetez.ollamamobile.download.StorageQuotaManager
import io.github.jaypetez.ollamamobile.download.hf.HuggingFaceBaseUrl
import io.github.jaypetez.ollamamobile.download.hf.HuggingFaceTokenProvider
import io.github.jaypetez.ollamamobile.storage.gguf.GgufHeaderParser
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The qualifier `:app` (or `:core-data`) binds the user's stored token with.
 *
 * `:core-download` must not read the Keystore itself — the token is entered in
 * Settings and stored by `:core-storage` — and neither module may depend on the
 * other. See [HuggingFaceTokenModule].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class UserHuggingFaceToken

@Module
@InstallIn(SingletonComponent::class)
public abstract class DownloadModule {
    @Binds
    @Singleton
    internal abstract fun bindStorageQuotaManager(impl: AndroidStorageQuotaManager): StorageQuotaManager

    public companion object {
        /**
         * The Hub's origin.
         *
         * A binding rather than a constant read at the call site, and the
         * indirection is the entire point: `HuggingFaceApi` takes the base URL as
         * a constructor parameter so a test can aim it at a local server, and
         * something has to supply the production value. Hence the suppression —
         * "this function only returns a constant" is true and is the design.
         */
        @Provides
        @Singleton
        @HuggingFaceBaseUrl
        @Suppress("FunctionOnlyReturningConstant")
        public fun provideHuggingFaceBaseUrl(): String = HUGGING_FACE_BASE_URL

        /**
         * One parser instance, because its only state is the window-growth
         * schedule and that is configuration, not per-call state.
         */
        @Provides
        @Singleton
        public fun provideGgufHeaderParser(): GgufHeaderParser = GgufHeaderParser()

        @Provides
        @Singleton
        public fun provideWorkManager(
            @ApplicationContext context: Context,
        ): WorkManager = WorkManager.getInstance(context)

        /**
         * WorkManager's on-demand configuration, wired to Hilt's worker factory.
         *
         * `@HiltWorker` workers take injected constructor parameters, which the
         * default `WorkerFactory` cannot supply — it can only call a
         * `(Context, WorkerParameters)` constructor, so without this binding
         * [io.github.jaypetez.ollamamobile.download.ModelDownloadWorker] fails to
         * instantiate at runtime with a message that does not mention Hilt.
         *
         * Providing this from here rather than from `:app` keeps `:app` free of a
         * `hilt-work` dependency: it only has to implement
         * `Configuration.Provider` and return the injected instance, and it must
         * also remove `androidx.work.WorkManagerInitializer` from the merged
         * manifest — on-demand initialisation and the default startup initialiser
         * are mutually exclusive.
         */
        @Provides
        @Singleton
        public fun provideWorkManagerConfiguration(workerFactory: HiltWorkerFactory): Configuration = Configuration
            .Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

        private const val HUGGING_FACE_BASE_URL = "https://huggingface.co"
    }
}

/**
 * How the application reaches the WorkManager configuration.
 *
 * `Configuration.Provider` is read by WorkManager during `Application.onCreate`,
 * before member injection has run, so a `@Inject lateinit var` in the
 * application class is a race as well as a lint finding. An entry point resolves
 * the binding on demand, at the moment the getter is called.
 */
@dagger.hilt.EntryPoint
@InstallIn(SingletonComponent::class)
public interface WorkManagerConfigurationEntryPoint {
    public fun workManagerConfiguration(): Configuration
}

/**
 * The optional Hugging Face token binding.
 *
 * `@BindsOptionalOf` and not a plain `@Binds`, for the same reason
 * `:core-remote` uses it for `SecretResolver`: this module must supply a working
 * default *today*, so that `:core-download` assembles and can be exercised on
 * its own, while still letting the app override it with the Keystore-backed one.
 * Two unqualified bindings of the same type would be a duplicate-binding error,
 * not an override.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class HuggingFaceTokenModule {
    @BindsOptionalOf
    @UserHuggingFaceToken
    internal abstract fun optionalUserToken(): HuggingFaceTokenProvider

    public companion object {
        /** Anonymous access. Public repositories work; gated and private ones do not. */
        @Provides
        @Singleton
        public fun provideTokenProvider(
            @UserHuggingFaceToken stored: Optional<HuggingFaceTokenProvider>,
        ): HuggingFaceTokenProvider = stored.orElse(HuggingFaceTokenProvider { null })
    }
}
