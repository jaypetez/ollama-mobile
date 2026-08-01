package io.github.jaypetez.ollamamobile.llm.internal.di

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.llm.BuildConfig
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.internal.CrashSentinel
import io.github.jaypetez.ollamamobile.llm.internal.InferenceArbiter
import io.github.jaypetez.ollamamobile.llm.internal.LlamaBridge
import io.github.jaypetez.ollamamobile.llm.internal.NativeLibraryLoader
import io.github.jaypetez.ollamamobile.llm.internal.NativeLlamaEngine
import io.github.jaypetez.ollamamobile.llm.internal.StubLlamaEngine
import io.github.jaypetez.ollamamobile.llm.internal.lora.LoraAdapterManager
import io.github.jaypetez.ollamamobile.llm.internal.lora.NoOpLoraAdapterManager
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The single OS thread every native call runs on.
 *
 * See the threading note on `NativeLlamaEngine`: a `llama_context` is not
 * thread-safe, and `llama_decode` blocks for seconds, which is not something to
 * do on a shared pool.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EngineDispatcher

/**
 * Chooses which [LlamaEngine] the app runs with.
 *
 * The choice is `BuildConfig.NATIVE_ENABLED`, which the native convention
 * plugin sets from `-Pollama.nativeSource`. This is the one place the two build
 * shapes diverge: everything above [LlamaEngine] is identical, which is what
 * lets the default (`none`) build produce a working remote-only app with no NDK
 * installed.
 *
 * The native dependencies are injected as [Provider]s so a stub build never
 * constructs [NativeLibraryLoader] — constructing it is harmless, but it owns
 * `System.loadLibrary`, and "harmless because of a `lazy`" is a weaker
 * guarantee than "never built".
 *
 * `internal`, unlike the other DI modules in this project: everything it binds
 * except [LlamaEngine] itself is an implementation detail of `:core-llm`, and a
 * public module would have to expose [NativeLibraryLoader] and friends to say
 * so. Hilt is fine with it — the generated factories land in this package, in
 * this compilation, and members of an internal *class* are not name-mangled the
 * way an explicitly `internal fun` would be.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class LlmModule {
    @Binds
    @Singleton
    abstract fun bindLoraAdapterManager(impl: NoOpLoraAdapterManager): LoraAdapterManager

    companion object {
        @Provides
        @Singleton
        @EngineDispatcher
        fun provideEngineDispatcher(): CoroutineDispatcher =
            Executors
                .newSingleThreadExecutor { runnable ->
                    // Named so a tombstone or a thread dump says which thread was
                    // inside ggml, and daemon so it can never hold the process open.
                    Thread(runnable, "llama-engine").apply { isDaemon = true }
                }.asCoroutineDispatcher()

        @Provides
        @Singleton
        fun provideCrashSentinel(
            @ApplicationContext context: Context,
        ): CrashSentinel =
            CrashSentinel(File(context.filesDir, CrashSentinel.FILE_NAME))

        @Provides
        @Singleton
        fun provideNativeLibraryLoader(
            @ApplicationContext context: Context,
            sentinel: CrashSentinel,
        ): NativeLibraryLoader = NativeLibraryLoader(
            backendApi = LlamaBridge,
            sentinel = sentinel,
            // The directory ggml scans for libggml-*.so. Real files, because
            // packaging sets jniLibs.useLegacyPackaging = true; see the note in
            // NativeLibraryLoader for why that does not affect 16 KB alignment.
            directoryProvider = { context.applicationInfo.nativeLibraryDir },
            // Build.SUPPORTED_ABIS[0] is the ABI this process actually runs as,
            // which on a 64-bit device with a 32-bit-only APK is not the one the
            // device prefers. It picks which baseline CPU variant safe mode
            // loads by name.
            abiProvider = { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() },
            nativeEnabled = BuildConfig.NATIVE_ENABLED,
        )

        @Provides
        @Singleton
        @Suppress("LongParameterList")
        fun provideLlamaEngine(
            arbiter: InferenceArbiter,
            loraAdapters: LoraAdapterManager,
            loader: Provider<NativeLibraryLoader>,
            @EngineDispatcher engineDispatcher: Provider<CoroutineDispatcher>,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
            stub: Provider<StubLlamaEngine>,
        ): LlamaEngine = if (BuildConfig.NATIVE_ENABLED) {
            NativeLlamaEngine(
                loader = loader.get(),
                session = LlamaBridge,
                arbiter = arbiter,
                loraAdapters = loraAdapters,
                engineDispatcher = engineDispatcher.get(),
                ioDispatcher = ioDispatcher,
            )
        } else {
            stub.get()
        }
    }
}
