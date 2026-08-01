package io.github.jaypetez.ollamamobile.common.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import io.github.jaypetez.ollamamobile.model.asException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor

/**
 * Where an address sits relative to the device's own networks.
 *
 * Kept as a separate vocabulary from "allowed / denied" because the two are not
 * the same question: [SHARED_CGNAT] is LAN-ish or public depending on the
 * transport, and only the policy layer knows which.
 */
enum class AddressScope {
    /** `127/8`, `::1`. Includes the app's own embedded server. */
    LOOPBACK,

    /** RFC 1918: `10/8`, `172.16/12`, `192.168/16`. */
    PRIVATE,

    /** `169.254/16`, `fe80::/10`. */
    LINK_LOCAL,

    /** IPv6 unique-local, `fc00::/7`. */
    UNIQUE_LOCAL,

    /** RFC 6598 shared address space, `100.64/10`. Tailscale lives here. */
    SHARED_CGNAT,

    /** Anything else, including multicast and the unspecified address. */
    PUBLIC,
    ;

    /** True for the ranges that are private regardless of transport. */
    val isUnconditionallyLocal: Boolean
        get() = this == LOOPBACK || this == PRIVATE || this == LINK_LOCAL || this == UNIQUE_LOCAL
}

/**
 * Whether the active network is a VPN transport.
 *
 * An interface rather than a direct `ConnectivityManager` call so the
 * `100.64/10` decision can be exercised in both states by a unit test. That
 * branch is the one most likely to be got wrong, so it must be cheap to test.
 */
fun interface VpnPresence {
    fun isVpnActive(): Boolean
}

/**
 * Enforces [NetworkPolicy.OFFLINE] and [NetworkPolicy.LAN_ONLY].
 *
 * ## Why this is in code and not in `network_security_config.xml`
 *
 * The requirement is "permit plain HTTP to private-range addresses, refuse it to
 * the public internet". A network security config cannot express that. Its
 * `<domain>` element takes a hostname or a single IP literal — there is no
 * `<cidr>`, no wildcard meaning "the 192.168.0.0/16 block", no predicate for
 * "on the local subnet". And the config is a *static resource compiled into the
 * APK*, whereas the hosts here are typed in by the user at runtime: today
 * `192.168.1.50`, tomorrow `pi.local`, next week a Tailscale name. The only
 * static allowlists available are "empty" (the app does not work) and "*"
 * (which is what `cleartextTrafficPermitted=true` already is).
 *
 * ## Why three layers
 *
 * Each layer catches something the others structurally cannot:
 *
 *  1. **[interceptor]** runs before anything happens. It is the only layer that
 *     can reject with a message the UI can attribute to a specific request, and
 *     it catches IP literals without paying for a DNS lookup. It cannot judge a
 *     hostname, because a hostname is just a string until it is resolved.
 *  2. **[dns]** classifies what a name actually resolved to. `evil.example` may
 *     resolve into `192.168.0.0/16` and `nas.local` may resolve out of it, so
 *     the check has to run on addresses. It cannot see what OkHttp finally
 *     dials, because it hands back a list and loses control of it.
 *  3. **[eventListenerFactory]** overrides `connectStart`, which receives the
 *     concrete [InetSocketAddress] the socket is about to connect to. This is
 *     the only layer that sees the *actual* peer, and therefore the only one
 *     that defeats **DNS rebinding** — a resolver that answers with a private
 *     address while layer 2 is looking and a public one a moment later. It also
 *     covers routes we did not resolve ourselves, such as a connection through
 *     a proxy, because the proxy's own address is what gets classified.
 *
 * Any one of them alone leaves a hole. Layer 3 alone would be sound but useless
 * for diagnostics — by then there is no request context to explain to the user
 * and no cheap early exit. Layers 1 and 2 alone are bypassable.
 *
 * Violations are thrown as [AppErrorException] wrapping an [AppError.Policy]
 * variant, never as a bare `IOException`: a generic connection failure would be
 * indistinguishable from the server being off, and the UI needs to say
 * "LAN-only mode blocked this" and offer the setting.
 */
@Singleton
class LanOnlyGuard
    @Inject
    constructor(
        private val policyController: NetworkPolicyController,
        private val vpnPresence: VpnPresence,
    ) {
        /**
         * The single decision function. All three layers call this and nothing
         * else, so there is exactly one place where the rules live.
         *
         * @param host the host as the user or the request wrote it, used only
         *   for the error message — never for the decision itself.
         * @return the violation, or null when the connection is permitted.
         */
        fun evaluate(host: String, address: InetAddress): AppError.Policy? {
            val policy = policyController.current
            if (!policy.allowsNetwork) return AppError.Policy.OfflineMode()
            if (policy.allowsPublicInternet) return null

            val scope = classify(address)
            val permitted = when {
                scope.isUnconditionallyLocal -> true

                // 100.64/10 is RFC 6598 carrier-grade NAT space, not RFC 1918.
                // On a mobile carrier's network a 100.64 address is another
                // customer, so treating it as "local" unconditionally would be
                // wrong. It is allowed *only* over a VPN transport because that
                // is Tailscale, and reaching a home Ollama server over Tailscale
                // is the single most common way people use this app away from
                // home. Blocking it would break the headline use case for a
                // path that is end-to-end encrypted by WireGuard and therefore
                // better protected than the RFC 1918 cleartext we permit without
                // argument. This is a deliberate decision, not an oversight.
                scope == AddressScope.SHARED_CGNAT -> vpnPresence.isVpnActive()

                else -> false
            }
            return if (permitted) null else AppError.Policy.LanOnlyViolation(host = host)
        }

        /** Convenience for the layers: throw the violation, or return. */
        private fun enforce(host: String, address: InetAddress) {
            evaluate(host, address)?.let { throw it.asException() }
        }

        /**
         * Layer 1. Registered as an *application* interceptor so it runs before
         * the cache, before retries and before any redirect is followed.
         */
        fun interceptor(): Interceptor = Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val policy = policyController.current

            if (!policy.allowsNetwork) throw AppError.Policy.OfflineMode().asException()

            if (!policy.allowsPublicInternet) {
                // Only an IP literal can be judged here. A hostname is deferred
                // to layers 2 and 3, which see what it resolves to.
                parseIpLiteralOrNull(host)?.let { literal -> enforce(host, literal) }
            }
            chain.proceed(request)
        }

        /** Layer 2. Wraps [delegate], filtering every address it returns. */
        fun dns(delegate: Dns = Dns.SYSTEM): Dns = GuardedDns(delegate)

        /** Layer 3. The only layer that sees the real socket address. */
        fun eventListenerFactory(): EventListener.Factory = EventListener.Factory { ConnectGuardListener() }

        private inner class GuardedDns(
            private val delegate: Dns,
        ) : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val policy = policyController.current
                if (!policy.allowsNetwork) throw AppError.Policy.OfflineMode().asException()

                val resolved = delegate.lookup(hostname)
                if (policy.allowsPublicInternet) return resolved

                val permitted = resolved.filter { evaluate(hostname, it) == null }
                if (permitted.isEmpty()) {
                    // Deliberately not UnknownHostException: the name resolved
                    // perfectly well, we refused the answer.
                    throw AppError.Policy.LanOnlyViolation(host = hostname).asException()
                }
                // Filtering rather than all-or-nothing: a dual-stack host with a
                // public AAAA and a private A is still reachable privately, and
                // OkHttp will try only what we hand back.
                return permitted
            }
        }

        private inner class ConnectGuardListener : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                val address = inetSocketAddress.address
                    ?: throw AppError.Policy
                        .LanOnlyViolation(host = inetSocketAddress.hostString.orEmpty())
                        .asException()
                // `proxy` is not inspected separately on purpose: when a proxy is
                // in play, `inetSocketAddress` IS the proxy, so classifying the
                // socket address already answers "where is this byte stream
                // actually going first".
                enforce(call.request().url.host, address)
            }
        }

        companion object {
            /**
             * Classifies an address by its bytes. Pure, no I/O, no reverse DNS —
             * `InetAddress.isSiteLocalAddress()` and friends are deliberately not
             * used: they do not know about `100.64/10`, they disagree between
             * JVM versions on `fc00::/7`, and `isReachable`-style helpers do I/O.
             */
            fun classify(address: InetAddress): AddressScope = when (address) {
                is Inet4Address -> classifyV4(address.address)

                is Inet6Address -> classifyV6(address.address)

                // Some other InetAddress subclass should not exist, but "unknown
                // shape" must fail closed rather than fall through to allowed.
                else -> AddressScope.PUBLIC
            }

            // The literals below are IP octets and prefix lengths straight out
            // of the RFCs — `first == 192 && second == 168` is the clearest
            // possible spelling of 192.168/16, and hoisting each octet to a
            // named constant would obscure the one thing a reviewer needs to
            // check: that the ranges match RFC 1918, 3927, 4193, 4291 and 6598.
            @Suppress("MagicNumber")
            private fun classifyV4(bytes: ByteArray): AddressScope {
                if (bytes.size != IPV4_BYTES) return AddressScope.PUBLIC
                val first = bytes[0].toInt() and BYTE_MASK
                val second = bytes[1].toInt() and BYTE_MASK
                return when {
                    first == 127 -> AddressScope.LOOPBACK
                    first == 169 && second == 254 -> AddressScope.LINK_LOCAL
                    first == 10 -> AddressScope.PRIVATE
                    first == 172 && second in 16..31 -> AddressScope.PRIVATE
                    first == 192 && second == 168 -> AddressScope.PRIVATE
                    first == 100 && second in 64..127 -> AddressScope.SHARED_CGNAT
                    else -> AddressScope.PUBLIC
                }
            }

            @Suppress("MagicNumber") // RFC octets; see classifyV4
            private fun classifyV6(bytes: ByteArray): AddressScope {
                if (bytes.size != IPV6_BYTES) return AddressScope.PUBLIC

                // ::ffff:a.b.c.d — an IPv4 address wearing an IPv6 hat. Java
                // usually normalises these to Inet4Address, but not always
                // (a raw 16-byte address from a socket does not get normalised),
                // and a mapped public address must not sneak through as "not
                // matching any IPv6 private prefix".
                if (isV4Mapped(bytes)) return classifyV4(bytes.copyOfRange(12, IPV6_BYTES))

                val first = bytes[0].toInt() and BYTE_MASK
                val second = bytes[1].toInt() and BYTE_MASK
                return when {
                    isLoopbackV6(bytes) -> AddressScope.LOOPBACK

                    // fe80::/10
                    first == 0xFE && (second and 0xC0) == 0x80 -> AddressScope.LINK_LOCAL

                    // fc00::/7 — fc00::/8 is unassigned in practice and fd00::/8
                    // is what everything actually uses, but the RFC 4193 prefix
                    // is /7 and both halves are unroutable on the public net.
                    (first and 0xFE) == 0xFC -> AddressScope.UNIQUE_LOCAL

                    else -> AddressScope.PUBLIC
                }
            }

            @Suppress("MagicNumber") // RFC octets; see classifyV4
            private fun isV4Mapped(bytes: ByteArray): Boolean {
                for (index in 0 until 10) {
                    if (bytes[index].toInt() != 0) return false
                }
                return bytes[10].toInt() and BYTE_MASK == BYTE_MASK &&
                    bytes[11].toInt() and BYTE_MASK == BYTE_MASK
            }

            @Suppress("MagicNumber") // RFC octets; see classifyV4
            private fun isLoopbackV6(bytes: ByteArray): Boolean {
                for (index in 0 until IPV6_BYTES - 1) {
                    if (bytes[index].toInt() != 0) return false
                }
                return bytes[IPV6_BYTES - 1].toInt() == 1
            }

            /**
             * Parses [host] as an IP literal, or returns null when it is a name.
             *
             * `InetAddress.getByName` is only reached for strings that cannot be
             * hostnames, so this never performs a DNS lookup — which matters
             * because layer 1 runs on the caller's thread before the request is
             * dispatched.
             */
            fun parseIpLiteralOrNull(host: String): InetAddress? {
                val bare = host.removeSurrounding("[", "]")
                val looksNumeric = bare.contains(':') || IPV4_LITERAL.matches(bare)
                if (!looksNumeric) return null
                return try {
                    InetAddress.getByName(bare)
                } catch (_: UnknownHostException) {
                    null
                }
            }

            private const val BYTE_MASK = 0xFF
            private const val IPV4_BYTES = 4
            private const val IPV6_BYTES = 16
            private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkGuardModule {
    @Binds
    abstract fun bindVpnPresence(impl: ConnectivityVpnPresence): VpnPresence
}

/**
 * Reads the VPN transport off the active network.
 *
 * Queried per connection attempt rather than cached behind a `NetworkCallback`:
 * connections are pooled so this runs rarely, and a cached answer that is stale
 * by one transport change is exactly the wrong thing for a security decision.
 */
@Singleton
class ConnectivityVpnPresence
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : VpnPresence {
        override fun isVpnActive(): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
