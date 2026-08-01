package io.github.jaypetez.ollamamobile.common.net

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The privacy claims in `docs/privacy-policy.md`, as executable assertions.
 *
 * A claim nobody tests is a wish. Each test here corresponds to one sentence
 * the app shows the user, and is written so that deleting the enforcement makes
 * it fail — the offline test in particular counts *packets*, not error types,
 * because "throws the right exception after connecting" is exactly the bug that
 * a type-only assertion cannot see.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkPolicyEnforcementTest {
    private val server = MockWebServer()

    private var policy = NetworkPolicy.LAN_ONLY
    private var vpnActive = false

    private val policyController = mockk<NetworkPolicyController>()

    /**
     * A [Dns] that records every lookup and answers with a fixed address, so a
     * hostname can be pointed at 8.8.8.8 or 192.168.1.50 without touching a
     * real resolver.
     */
    private class ScriptedDns(
        private val answer: String,
    ) : Dns {
        val lookups = AtomicInteger(0)

        override fun lookup(hostname: String): List<InetAddress> {
            lookups.incrementAndGet()
            return listOf(InetAddress.getByName(answer))
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun guard(): LanOnlyGuard {
        every { policyController.current } returns policy
        return LanOnlyGuard(policyController, VpnPresence { vpnActive })
    }

    private fun client(dns: Dns = Dns.SYSTEM): OkHttpClient {
        val guard = guard()
        return OkHttpClient
            .Builder()
            .addInterceptor(guard.interceptor())
            .dns(guard.dns(dns))
            .eventListenerFactory(guard.eventListenerFactory())
            .build()
    }

    // -- OFFLINE ------------------------------------------------------------

    @Test
    fun `OFFLINE refuses a remote chat with a typed error and sends zero packets`() {
        server.start()
        val url = server.url("/api/chat")
        policy = NetworkPolicy.OFFLINE

        val thrown = runCatching {
            client().newCall(Request.Builder().url(url).build()).execute()
        }.exceptionOrNull()

        // Typed, not a bare IOException: the UI has to be able to say "offline
        // mode blocked this" and offer the setting, which it cannot do if the
        // failure is indistinguishable from the server being down.
        assertThat(thrown).isInstanceOf(AppErrorException::class.java)
        assertThat((thrown as AppErrorException).error).isInstanceOf(AppError.Policy.OfflineMode::class.java)

        // The actual claim. MockWebServer counts connections it accepted, so a
        // non-zero value here means bytes left the process.
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `OFFLINE refuses before the resolver is even consulted`() {
        policy = NetworkPolicy.OFFLINE
        val dns = ScriptedDns("192.168.1.50")

        runCatching {
            client(dns).newCall(Request.Builder().url("http://nas.local/api/tags").build()).execute()
        }

        // A DNS query is a packet, and a DNS query leaks the hostname the user
        // typed to whoever runs the resolver. Offline has to mean offline.
        assertThat(dns.lookups.get()).isEqualTo(0)
    }

    @Test
    fun `OFFLINE blocks loopback too, because the claim is about packets and not about trust`() {
        policy = NetworkPolicy.OFFLINE

        val error = guard().evaluate("127.0.0.1", InetAddress.getByName("127.0.0.1"))

        assertThat(error).isInstanceOf(AppError.Policy.OfflineMode::class.java)
    }

    // -- LAN_ONLY -----------------------------------------------------------

    @Test
    fun `LAN_ONLY refuses a public address`() {
        policy = NetworkPolicy.LAN_ONLY

        val error = guard().evaluate("dns.google", InetAddress.getByName("8.8.8.8"))

        assertThat(error).isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
    }

    @Test
    fun `LAN_ONLY allows an RFC 1918 address`() {
        policy = NetworkPolicy.LAN_ONLY

        assertThat(guard().evaluate("nas", InetAddress.getByName("192.168.1.50"))).isNull()
        assertThat(guard().evaluate("nas", InetAddress.getByName("10.0.0.5"))).isNull()
        assertThat(guard().evaluate("nas", InetAddress.getByName("172.16.0.1"))).isNull()
    }

    @Test
    fun `LAN_ONLY refuses 100_64 without a VPN and allows it with one`() {
        policy = NetworkPolicy.LAN_ONLY
        val tailscalePeer = InetAddress.getByName("100.101.102.103")

        vpnActive = false
        // On a carrier network a 100.64/10 address is another customer's device.
        assertThat(guard().evaluate("desktop.tailnet", tailscalePeer))
            .isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)

        vpnActive = true
        // Over a VPN transport it is a Tailscale peer, reached through a
        // WireGuard tunnel. See the rationale in LanOnlyGuard.evaluate.
        assertThat(guard().evaluate("desktop.tailnet", tailscalePeer)).isNull()
    }

    @Test
    fun `LAN_ONLY refuses a hostname that resolves publicly, at the DNS layer`() {
        policy = NetworkPolicy.LAN_ONLY
        val dns = ScriptedDns("8.8.8.8")

        val thrown = runCatching {
            client(dns).newCall(Request.Builder().url("http://ollama.local/api/tags").build()).execute()
        }.exceptionOrNull()

        assertThat(dns.lookups.get()).isEqualTo(1)
        assertThat((thrown as AppErrorException).error)
            .isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
    }

    @Test
    fun `LAN_ONLY allows a real request to a loopback server`() {
        server.start()
        policy = NetworkPolicy.LAN_ONLY
        server.enqueue(mockwebserver3.MockResponse(code = 200, body = "{}"))

        val response = client().newCall(Request.Builder().url(server.url("/api/tags")).build()).execute()

        response.use { assertThat(it.code).isEqualTo(200) }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an IP literal is judged by the interceptor without any DNS lookup`() {
        policy = NetworkPolicy.LAN_ONLY
        val dns = ScriptedDns("192.168.1.50")

        runCatching {
            client(dns).newCall(Request.Builder().url("http://8.8.8.8/api/tags").build()).execute()
        }

        // Layer 1 exists so a literal costs nothing to refuse.
        assertThat(dns.lookups.get()).isEqualTo(0)
    }

    @Test
    fun `OPEN allows a public address`() {
        policy = NetworkPolicy.OPEN

        assertThat(guard().evaluate("dns.google", InetAddress.getByName("8.8.8.8"))).isNull()
    }
}
