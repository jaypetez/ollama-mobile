package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Which protocol a server was found to speak. */
enum class ServerProtocol {
    /** Ollama's own `/api` endpoints. Preferred: it is the only one with timing statistics and `keep_alive`. */
    NATIVE,

    /** OpenAI-shaped `/v1` endpoints. Used when `/api/version` does not answer. */
    OPENAI_COMPATIBLE,
}

/** A chosen client and the reason it was chosen. */
data class SelectedClient(
    val protocol: ServerProtocol,
    val client: RemoteChatClient,
    /** The version string, when the native probe answered with one. */
    val version: String? = null,
)

/**
 * Decides which client to use for a server, and remembers the answer.
 *
 * ## How the choice is made
 *
 * `GET /api/version` is the probe, because it is the cheapest request either
 * protocol has and because answering it *is* the definition of "this is
 * Ollama". If it answers, the native client wins — it carries the timing
 * statistics, `keep_alive`, the full options block and object-shaped tool
 * arguments, all of which `/v1` throws away. If it does not, the endpoint is
 * assumed to be something OpenAI-compatible; that assumption costs one failed
 * request to discover and is right for llama.cpp's server, vLLM, LM Studio and
 * every gateway.
 *
 * ## Why the answer is cached, and why not for long
 *
 * Without a cache, every chat turn pays a probe. With a permanent cache, a
 * server that was swapped from llama.cpp to Ollama keeps being addressed the
 * old way until the app restarts. A short TTL is the compromise; the probe is a
 * few bytes on a LAN, so the TTL is about avoiding a per-request round trip,
 * not about avoiding load.
 *
 * The cache is keyed by [ServerId] *and* the base URL, so editing a server's
 * address invalidates it — the common way this would otherwise go wrong is a
 * user fixing a typo in a URL and the client still talking to the old shape.
 */
@Singleton
class ServerClientFactory
    @Inject
    constructor(
        private val native: OllamaClient,
        private val compat: OpenAiCompatClient,
        private val clock: WallClock,
    ) {
        private data class CacheKey(
            val serverId: ServerId,
            val baseUrl: String,
        )

        private data class CacheEntry(
            val protocol: ServerProtocol,
            val version: String?,
            val decidedAtMillis: Long,
        )

        private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

        /**
         * The client for [server], probing when the cached answer has expired.
         *
         * Never fails: an unreachable server still gets a client, because the
         * failure the user needs to see is the one from the request they
         * actually made, with its own message, not one from a probe they never
         * asked for.
         */
        suspend fun clientFor(server: ServerRef): SelectedClient {
            val key = CacheKey(server.id, server.baseUrl)
            cache[key]?.takeIf { clock.nowMillis() - it.decidedAtMillis < CACHE_TTL_MILLIS }?.let { cached ->
                return cached.toSelection()
            }

            val entry = when (val probe = native.version(server)) {
                is AppResult.Success -> CacheEntry(ServerProtocol.NATIVE, probe.value.version, clock.nowMillis())
                is AppResult.Failure -> CacheEntry(ServerProtocol.OPENAI_COMPATIBLE, null, clock.nowMillis())
            }
            cache[key] = entry
            return entry.toSelection()
        }

        /** Forgets what was decided for [server]. Call after the user edits its configuration. */
        fun invalidate(server: ServerRef) {
            cache.remove(CacheKey(server.id, server.baseUrl))
        }

        fun invalidateAll() {
            cache.clear()
        }

        private fun CacheEntry.toSelection(): SelectedClient = SelectedClient(
            protocol = protocol,
            client = if (protocol == ServerProtocol.NATIVE) native else compat,
            version = version,
        )

        private companion object {
            /** Long enough to cover a burst of turns, short enough that swapping the server is noticed. */
            const val CACHE_TTL_MILLIS = 60_000L
        }
    }
