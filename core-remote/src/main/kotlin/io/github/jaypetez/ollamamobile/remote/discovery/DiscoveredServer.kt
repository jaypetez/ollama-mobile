package io.github.jaypetez.ollamamobile.remote.discovery

/**
 * A host that answered `GET /api/version` with something that was actually
 * Ollama.
 *
 * There is no `ServerId` here and no [ServerRef][
 * io.github.jaypetez.ollamamobile.model.ServerRef]: a discovered host is a
 * candidate the user has not adopted yet. Minting an id at discovery time would
 * either create a duplicate row for a server they already have, or require this
 * module to know how servers are stored, and it knows neither.
 *
 * [modelCount] is best-effort. It comes from a second `/api/tags` call made
 * only after confirmation, because "192.168.1.40 — Ollama 0.12.3, 6 models" is
 * a useful list entry and "192.168.1.40" is not. A server that refuses that
 * call — auth in front of it, a slow disk — is still reported, with the count
 * absent rather than zero.
 */
data class DiscoveredServer(
    /** The IP literal that answered. Never a hostname: the sweep enumerates addresses. */
    val address: String,
    val port: Int,
    val version: String,
    /** Round-trip time of the confirming `/api/version` call, in milliseconds. */
    val roundTripMillis: Long,
    val modelCount: Int? = null,
) {
    /**
     * The base URL to store if the user adopts this server.
     *
     * Always `http`: the sweep only speaks cleartext, because a TLS handshake
     * against 254 addresses is not a discovery sweep, and a server behind TLS
     * is behind a proxy with a hostname the sweep could not have found anyway.
     */
    val baseUrl: String
        get() = if (address.contains(':')) "http://[$address]:$port" else "http://$address:$port"
}
