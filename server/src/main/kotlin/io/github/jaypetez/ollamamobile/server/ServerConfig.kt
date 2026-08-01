package io.github.jaypetez.ollamamobile.server

import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Which interface the embedded server binds to. */
enum class BindPolicy {
    /**
     * `127.0.0.1`. The default, and the only mode that needs no token: nothing
     * off-device can open the socket in the first place.
     */
    LOOPBACK,

    /**
     * `0.0.0.0`. An explicit, per-session opt-in — never persisted as "on" and
     * never restored on boot. Requires [ServerConfig.bearerToken].
     */
    LAN,
    ;

    /** The address passed to the engine. */
    val bindAddress: String
        get() = if (this == LOOPBACK) LOOPBACK_ADDRESS else ANY_ADDRESS

    companion object {
        const val LOOPBACK_ADDRESS: String = "127.0.0.1"
        const val ANY_ADDRESS: String = "0.0.0.0"
    }
}

/**
 * Everything the HTTP surface needs to know about how it is exposed.
 *
 * Held as one immutable value so that a running server can never observe half
 * of a settings change — a restart swaps the whole thing.
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val bindPolicy: BindPolicy = BindPolicy.LOOPBACK,
    /**
     * Bearer token required on everything except the discovery probes.
     *
     * Null is only legal for [BindPolicy.LOOPBACK]; [requireValid] enforces
     * that rather than leaving it to the caller, because "LAN on, token
     * forgotten" is an open inference endpoint on a coffee-shop Wi-Fi.
     */
    val bearerToken: String? = null,
    /**
     * The `OLLAMA_ORIGINS` equivalent. `"*"` means any origin, which is what
     * makes Open WebUI and other browser clients work at all.
     */
    val allowedOrigins: Set<String> = setOf(WILDCARD_ORIGIN),
    /** Requests admitted to the model concurrently. */
    val maxConcurrentRequests: Int = DEFAULT_CONCURRENCY,
    /**
     * Requests allowed to *wait*. Beyond this the server answers 503 with
     * Ollama's own message instead of parking another coroutine — an unbounded
     * waiter list under a misbehaving client is a memory leak with extra steps.
     */
    val maxQueuedRequests: Int = DEFAULT_QUEUE_DEPTH,
    /** What `keep_alive` means when a request does not say. */
    val defaultKeepAlive: Duration = DEFAULT_KEEP_ALIVE,
) {
    val bindAddress: String
        get() = bindPolicy.bindAddress

    /** What the notification and the settings screen display. */
    val displayAddress: String
        get() = "$bindAddress:$port"

    val requiresAuth: Boolean
        get() = bearerToken != null

    /**
     * @throws IllegalArgumentException when the configuration would expose
     *   inference to the LAN without a token, or when the port is not usable.
     */
    fun requireValid(): ServerConfig {
        require(port in MIN_PORT..MAX_PORT) { "Port $port is outside $MIN_PORT..$MAX_PORT." }
        require(maxConcurrentRequests >= 1) { "maxConcurrentRequests must be at least 1." }
        require(maxQueuedRequests >= 0) { "maxQueuedRequests cannot be negative." }
        require(bindPolicy != BindPolicy.LAN || !bearerToken.isNullOrEmpty()) {
            "LAN exposure requires a bearer token."
        }
        return this
    }

    companion object {
        /** The port every Ollama client assumes. Changing it breaks discovery. */
        const val DEFAULT_PORT: Int = 11434

        const val WILDCARD_ORIGIN: String = "*"

        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val DEFAULT_CONCURRENCY = 1
        private const val DEFAULT_QUEUE_DEPTH = 8
        private val DEFAULT_KEEP_ALIVE: Duration = 5.minutes

        /** 32 bytes of `SecureRandom`, base64url without padding. */
        private const val TOKEN_BYTES = 32

        /**
         * Mints a session token.
         *
         * Base64url so it survives being typed into a shell as
         * `Authorization: Bearer …` and pasted into a URL bar without
         * percent-encoding, which is exactly how people move it to a laptop.
         */
        fun generateToken(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
