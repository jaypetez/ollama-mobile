package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerAuth
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import java.time.Duration
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/**
 * Shared fixtures for the client tests.
 *
 * Note that these build an `OkHttpClient` directly, which production code in
 * this module is forbidden to do. That is the point of the rule: the Konsist
 * architecture test scopes itself to `src/main`, because a test needs a client
 * with a 250 ms read timeout to prove that the streaming path overrides it.
 */
internal class MutableClock(
    var now: Long = 0L,
) : WallClock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** A resolver backed by a map, standing in for `:core-storage`'s Keystore-backed one. */
internal class FakeSecretResolver(
    private val values: Map<String, String> = emptyMap(),
) : SecretResolver {
    override suspend fun resolve(ref: SecretRef): String? = values[ref.alias]
}

internal fun testServer(baseUrl: String, auth: ServerAuth = ServerAuth.None, id: String = "server-1"): ServerRef =
    ServerRef(id = ServerId(id), label = "Test server", baseUrl = baseUrl, auth = auth)

internal fun MockWebServer.serverRef(auth: ServerAuth = ServerAuth.None, id: String = "server-1"): ServerRef =
    testServer(url("/").toString(), auth, id)

/**
 * A client with a deliberately short read timeout.
 *
 * The short value is load-bearing in [OllamaClientImplTest]: a streaming call
 * has to survive a pause longer than this one, which it can only do if the
 * streaming path really does set `readTimeout = 0`.
 */
internal fun testOkHttpClient(readTimeoutMillis: Long = 250L): OkHttpClient = OkHttpClient
    .Builder()
    .readTimeout(Duration.ofMillis(readTimeoutMillis))
    .connectTimeout(Duration.ofSeconds(2))
    .build()

internal fun testHttp(
    client: OkHttpClient = testOkHttpClient(),
    history: RequestHistory = RequestHistory(MutableClock()),
    clock: WallClock = MutableClock(),
    resolver: SecretResolver = NoOpSecretResolver,
    // A fixed seed keeps the jitter deterministic without making the policy
    // pretend it has none.
    retryPolicy: RetryPolicy = RetryPolicy(Random(seed = 1)),
): RemoteHttp = RemoteHttp(
    sharedClient = client,
    secretResolver = resolver,
    retryPolicy = retryPolicy,
    history = history,
    clock = clock,
    io = Dispatchers.IO,
)
