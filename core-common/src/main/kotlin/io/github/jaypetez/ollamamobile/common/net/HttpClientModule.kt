package io.github.jaypetez.ollamamobile.common.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.common.inspector.ApiInspectorInterceptor
import java.time.Duration
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * The one app-wide [OkHttpClient].
 *
 * "One" is a security property, not a performance one. A second client is a
 * second connection pool, a second interceptor chain, and — the part that
 * matters — a path to the network that [LanOnlyGuard] does not police. The
 * Konsist architecture test in this module asserts that no other production
 * class constructs an `OkHttpClient`, and that assertion is what makes the
 * offline and LAN-only settings meaningful rather than advisory.
 *
 * Call sites that need different settings must derive from this instance with
 * `client.newBuilder()`, which shares the connection pool, the dispatcher and
 * every interceptor rather than starting a new stack.
 */
@Module
@InstallIn(SingletonComponent::class)
object HttpClientModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(
        guard: LanOnlyGuard,
        inspector: ApiInspectorInterceptor,
    ): OkHttpClient = OkHttpClient
        .Builder()
        // Layer 2 of the guard: every hostname is classified after resolution.
        .dns(guard.dns())
        // Layer 1: application interceptor, so it runs before the cache, before
        // retries and before a redirect is followed. Added first so a blocked
        // request is never even recorded as an attempt by the inspector.
        .addInterceptor(guard.interceptor())
        .addInterceptor(inspector)
        // Layer 3: the only hook that sees the real socket address, and so the
        // only one that catches DNS rebinding.
        .eventListenerFactory(guard.eventListenerFactory())
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .writeTimeout(WRITE_TIMEOUT)
        // No call timeout. An overall deadline is the right default for a REST
        // API and the wrong one for token generation, which legitimately runs
        // for minutes on a slow local model.
        .callTimeout(Duration.ZERO)
        .retryOnConnectionFailure(true)
        // Explicitly left at OkHttp's defaults: the platform trust manager and
        // the platform hostname verifier. Supplying our own of either is banned
        // — see the Konsist test — because a custom TrustManager is one careless
        // refactor away from being an accept-all.
        .build()

    /**
     * Connect only. A LAN server either answers the SYN quickly or is not
     * there; waiting 30 seconds to discover a typo'd IP is bad UX.
     */
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

    /**
     * Gap between bytes, not total duration.
     *
     * **Streaming call sites must override this to zero.** An SSE or NDJSON
     * response from `/api/chat` never completes — the socket stays open and
     * emits a token at a time — so any non-zero read timeout eventually fires
     * mid-generation and looks to the user like the model stopped. Derive a
     * streaming client with:
     *
     * ```
     * client.newBuilder().readTimeout(Duration.ZERO).build()
     * ```
     *
     * which keeps the shared pool and the guard while removing the deadline.
     * Cancellation of a stream is the coroutine's job, not the timeout's.
     */
    private val READ_TIMEOUT: Duration = Duration.ofSeconds(60)

    /** Uploads here are prompts and small multipart images, never a model file. */
    private val WRITE_TIMEOUT: Duration = Duration.ofSeconds(30)
}
