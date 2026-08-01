package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.common.net.AddressScope
import io.github.jaypetez.ollamamobile.common.net.LanOnlyGuard

/**
 * Rejects a request whose `Host` header is not a private address.
 *
 * ## Why a header check stops a real attack
 *
 * The phone's server has no way to know which *network* a browser is on; it
 * only sees a TCP connection. A page on `https://evil.example` can make a
 * browser issue requests to any address the browser can reach, and if the
 * victim's laptop is on the same Wi-Fi as the phone, that includes the phone.
 * The browser will faithfully send `Host: <whatever hostname the attacker
 * used>` — and an attacker who owns a domain can point it at the phone's LAN
 * IP (DNS rebinding).
 *
 * Requiring the `Host` to be a **private IP literal** breaks that: the attacker
 * cannot make the browser send a Host header they did not choose, and a Host
 * that is a literal RFC 1918 address is one the user typed. It is not a
 * substitute for the bearer token — it is the thing that stops the phone being
 * used as a confused deputy before the token is even consulted.
 *
 * Loopback binding does not need this (nothing off-device can connect), but the
 * guard is applied there too so the same code path is the one under test.
 */
object HostGuard {
    /**
     * Hostnames accepted without being IP literals.
     *
     * `localhost` only. It cannot be rebound to a LAN address by an attacker's
     * DNS because browsers resolve it locally, and it is what a client on the
     * phone itself uses.
     */
    private val ALLOWED_NAMES = setOf("localhost", "localhost.localdomain")

    /**
     * @param hostHeader the raw `Host` header, port included or not.
     * @return true when the request may proceed.
     */
    fun isAllowed(hostHeader: String?): Boolean {
        val host = hostHeader?.trim().orEmpty()
        // An absent Host is allowed, and that does not weaken the guard. The
        // attack this defends against is a *browser* being pointed at the
        // phone, and a browser always sends Host — it is mandatory in HTTP/1.1
        // and synthesised from `:authority` in HTTP/2. A raw client that omits
        // it is not a confused deputy; it is a direct caller, and the bearer
        // token is what stands in its way. Rejecting here would instead break
        // HTTP/1.0 probes and in-process test transports for no security gain.
        if (host.isEmpty()) return true

        val name = stripPort(host)
        if (name.isEmpty()) return false
        if (name.lowercase() in ALLOWED_NAMES) return true

        val address = LanOnlyGuard.parseIpLiteralOrNull(name) ?: return false
        // SHARED_CGNAT and PUBLIC are excluded: 100.64/10 is LAN-ish only over a
        // VPN transport, and this guard has no way to know the transport.
        return LanOnlyGuard.classify(address).isUnconditionallyLocal
    }

    /**
     * Splits `host[:port]`, tolerating a bracketed IPv6 literal.
     *
     * A bare IPv6 literal with no brackets is illegal in a Host header, so a
     * colon outside brackets is unambiguously a port separator — except when
     * there are several, which means somebody sent an unbracketed v6 address
     * and we hand the whole thing to the parser to reject.
     */
    private fun stripPort(host: String): String {
        if (host.startsWith("[")) return host.substringBefore(']').removePrefix("[")
        val colons = host.count { it == ':' }
        return if (colons == 1) host.substringBefore(':') else host
    }
}

/**
 * Rejects a request that already passed through an OllamaMobile server.
 *
 * Two phones can each be configured with the other as a remote server. Without
 * this, one inbound `/api/chat` becomes an infinite ping-pong that ends when a
 * device runs out of file descriptors — and it looks like a hang, not a loop.
 */
object LoopGuard {
    /** True when [viaHeaders] shows this request has already been here. */
    fun isLoop(viaHeaders: List<String>): Boolean =
        viaHeaders.any { it.contains(VIA_TOKEN, ignoreCase = true) }

    /**
     * True when [upstreamUrl] points back at this server.
     *
     * Used before a route delegates to a configured remote: a server whose
     * upstream is itself is the same loop reached from the other direction.
     */
    fun isSelfReferential(upstreamUrl: String?, config: ServerConfig): Boolean {
        val url = upstreamUrl?.trim().orEmpty()
        if (url.isEmpty()) return false
        val authority = url.substringAfter("://", url).substringBefore('/')
        val host = authority.substringBeforeLast(':', authority)
        val port = authority.substringAfterLast(':', "").toIntOrNull()
        if (port != null && port != config.port) return false
        val bare = host.removeSurrounding("[", "]")
        if (bare.lowercase() in setOf("localhost", BindPolicy.LOOPBACK_ADDRESS, "::1")) return true
        val address = LanOnlyGuard.parseIpLiteralOrNull(bare) ?: return false
        return LanOnlyGuard.classify(address) == AddressScope.LOOPBACK
    }
}
