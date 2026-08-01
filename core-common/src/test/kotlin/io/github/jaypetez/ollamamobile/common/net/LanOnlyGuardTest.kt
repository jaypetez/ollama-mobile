package io.github.jaypetez.ollamamobile.common.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import io.mockk.every
import io.mockk.mockk
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The most important test in the module.
 *
 * `LanOnlyGuard` is the entire justification for
 * `cleartextTrafficPermitted="true"` in the network security config, so the
 * classification table below is effectively the security policy in executable
 * form. A row removed from it is a control removed from the app.
 */
@RunWith(JUnit4::class)
class LanOnlyGuardTest {
    private val policyController = mockk<NetworkPolicyController>()
    private var vpnActive = false
    private val guard = LanOnlyGuard(policyController, VpnPresence { vpnActive })

    private fun policy(policy: NetworkPolicy) {
        every { policyController.current } returns policy
    }

    // ---------------------------------------------------------------- table

    private data class ScopeCase(
        val literal: String,
        val expected: AddressScope,
    )

    private val classificationTable = listOf(
        // Loopback.
        ScopeCase("127.0.0.1", AddressScope.LOOPBACK),
        ScopeCase("127.255.255.254", AddressScope.LOOPBACK),
        ScopeCase("::1", AddressScope.LOOPBACK),
        // RFC 1918, including the addresses just outside each block.
        ScopeCase("10.0.0.1", AddressScope.PRIVATE),
        ScopeCase("10.255.255.255", AddressScope.PRIVATE),
        ScopeCase("9.255.255.255", AddressScope.PUBLIC),
        ScopeCase("11.0.0.1", AddressScope.PUBLIC),
        ScopeCase("172.16.0.1", AddressScope.PRIVATE),
        ScopeCase("172.31.255.255", AddressScope.PRIVATE),
        ScopeCase("172.15.255.255", AddressScope.PUBLIC),
        ScopeCase("172.32.0.1", AddressScope.PUBLIC),
        ScopeCase("192.168.0.1", AddressScope.PRIVATE),
        ScopeCase("192.168.255.255", AddressScope.PRIVATE),
        ScopeCase("192.167.255.255", AddressScope.PUBLIC),
        ScopeCase("192.169.0.1", AddressScope.PUBLIC),
        // Link-local.
        ScopeCase("169.254.0.1", AddressScope.LINK_LOCAL),
        ScopeCase("169.253.255.255", AddressScope.PUBLIC),
        ScopeCase("fe80::1", AddressScope.LINK_LOCAL),
        ScopeCase("febf:ffff::1", AddressScope.LINK_LOCAL),
        // fec0::/10 is the *deprecated* site-local prefix, not link-local.
        ScopeCase("fec0::1", AddressScope.PUBLIC),
        // IPv6 unique-local. fd00::/8 is what tools generate; fc00::/8 is the
        // other half of the /7 and is equally unroutable.
        ScopeCase("fc00::1", AddressScope.UNIQUE_LOCAL),
        ScopeCase("fd12:3456:789a::1", AddressScope.UNIQUE_LOCAL),
        ScopeCase("fbff:ffff::1", AddressScope.PUBLIC),
        // RFC 6598 shared address space, both edges of the /10.
        ScopeCase("100.64.0.0", AddressScope.SHARED_CGNAT),
        ScopeCase("100.100.100.100", AddressScope.SHARED_CGNAT),
        ScopeCase("100.127.255.255", AddressScope.SHARED_CGNAT),
        ScopeCase("100.63.255.255", AddressScope.PUBLIC),
        ScopeCase("100.128.0.0", AddressScope.PUBLIC),
        // Public.
        ScopeCase("8.8.8.8", AddressScope.PUBLIC),
        ScopeCase("1.1.1.1", AddressScope.PUBLIC),
        ScopeCase("2001:4860:4860::8888", AddressScope.PUBLIC),
        // The unspecified address and multicast are not LAN. Nothing in this
        // app dials them over HTTP, and "unclassified" must fail closed.
        ScopeCase("0.0.0.0", AddressScope.PUBLIC),
        ScopeCase("::", AddressScope.PUBLIC),
        ScopeCase("224.0.0.251", AddressScope.PUBLIC),
        ScopeCase("ff02::1", AddressScope.PUBLIC),
        // IPv4-mapped IPv6. Java normally folds these into an Inet4Address, but
        // the mapped form must classify by the embedded v4 either way.
        ScopeCase("::ffff:192.168.1.5", AddressScope.PRIVATE),
        ScopeCase("::ffff:8.8.8.8", AddressScope.PUBLIC),
    )

    @Test
    fun `classifies every address class`() {
        classificationTable.forEach { case ->
            val address = InetAddress.getByName(case.literal)
            assertWithMessage("classify(%s)", case.literal)
                .that(LanOnlyGuard.classify(address))
                .isEqualTo(case.expected)
        }
    }

    @Test
    fun `classifies a genuine IPv4-mapped Inet6Address by its embedded v4`() {
        // Inet6Address.getByAddress does not fold the mapped form down to an
        // Inet4Address the way InetAddress.getByName does, so this is the only
        // way to reach the mapped branch of the IPv6 classifier.
        val privateMapped = Inet6Address.getByAddress(
            null,
            mappedBytes(byteArrayOf(10, 0, 0, 7)),
            0,
        )
        val publicMapped = Inet6Address.getByAddress(
            null,
            mappedBytes(byteArrayOf(8, 8, 8, 8)),
            0,
        )

        assertThat(LanOnlyGuard.classify(privateMapped)).isEqualTo(AddressScope.PRIVATE)
        assertThat(LanOnlyGuard.classify(publicMapped)).isEqualTo(AddressScope.PUBLIC)
    }

    private fun mappedBytes(v4: ByteArray) = ByteArray(16).also { bytes ->
        bytes[10] = 0xFF.toByte()
        bytes[11] = 0xFF.toByte()
        v4.copyInto(bytes, 12)
    }

    // -------------------------------------------------------------- LAN_ONLY

    @Test
    fun `LAN_ONLY permits every unconditionally local range and refuses public`() {
        policy(NetworkPolicy.LAN_ONLY)
        classificationTable
            .filter { it.expected != AddressScope.SHARED_CGNAT }
            .forEach { case ->
                val violation = guard.evaluate(case.literal, InetAddress.getByName(case.literal))
                val allowed = violation == null
                assertWithMessage("LAN_ONLY allows %s (%s)", case.literal, case.expected)
                    .that(allowed)
                    .isEqualTo(case.expected.isUnconditionallyLocal)
            }
    }

    @Test
    fun `LAN_ONLY names the host in the violation`() {
        policy(NetworkPolicy.LAN_ONLY)

        val violation = guard.evaluate("huggingface.co", InetAddress.getByName("8.8.8.8"))

        assertThat(violation).isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
        assertThat((violation as AppError.Policy.LanOnlyViolation).host).isEqualTo("huggingface.co")
        assertThat(violation.message).contains("huggingface.co")
    }

    // ------------------------------------------------------------ 100.64/10

    @Test
    fun `CGNAT is allowed over a VPN transport and refused without one`() {
        policy(NetworkPolicy.LAN_ONLY)
        val tailscale = InetAddress.getByName("100.101.102.103")

        vpnActive = true
        assertWithMessage("100.64/10 over a VPN is Tailscale and must work")
            .that(guard.evaluate("nas.tail1234.ts.net", tailscale))
            .isNull()

        vpnActive = false
        assertWithMessage("100.64/10 with no VPN is carrier NAT, i.e. other customers")
            .that(guard.evaluate("nas.tail1234.ts.net", tailscale))
            .isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
    }

    @Test
    fun `a VPN transport does not widen anything other than CGNAT`() {
        policy(NetworkPolicy.LAN_ONLY)
        vpnActive = true

        assertThat(guard.evaluate("example.com", InetAddress.getByName("93.184.216.34")))
            .isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
    }

    // --------------------------------------------------------------- OFFLINE

    @Test
    fun `OFFLINE refuses everything, including LAN and loopback`() {
        policy(NetworkPolicy.OFFLINE)

        listOf("127.0.0.1", "192.168.1.10", "100.64.0.1", "8.8.8.8", "fd00::1").forEach { literal ->
            vpnActive = true
            assertWithMessage("OFFLINE blocks %s", literal)
                .that(guard.evaluate(literal, InetAddress.getByName(literal)))
                .isInstanceOf(AppError.Policy.OfflineMode::class.java)
        }
    }

    // ------------------------------------------------------------------ OPEN

    @Test
    fun `OPEN permits everything`() {
        policy(NetworkPolicy.OPEN)

        classificationTable.forEach { case ->
            assertWithMessage("OPEN allows %s", case.literal)
                .that(guard.evaluate(case.literal, InetAddress.getByName(case.literal)))
                .isNull()
        }
    }

    // ------------------------------------------------------- literal parsing

    @Test
    fun `parses IP literals without resolving hostnames`() {
        assertThat(LanOnlyGuard.parseIpLiteralOrNull("192.168.1.1")).isNotNull()
        assertThat(LanOnlyGuard.parseIpLiteralOrNull("[fd00::1]")).isNotNull()
        assertThat(LanOnlyGuard.parseIpLiteralOrNull("::1")).isNotNull()
        assertThat(LanOnlyGuard.parseIpLiteralOrNull("ollama.local")).isNull()
        assertThat(LanOnlyGuard.parseIpLiteralOrNull("huggingface.co")).isNull()
        // Not four octets, so not a literal — and specifically not something we
        // hand to the resolver.
        assertThat(LanOnlyGuard.parseIpLiteralOrNull("192.168.1")).isNull()
    }

    // ------------------------------------------------------- layer 1: Interceptor

    @Test
    fun `interceptor refuses an IP literal outside the LAN before the request is sent`() {
        policy(NetworkPolicy.LAN_ONLY)
        val chain = chainFor("http://8.8.8.8:11434/api/tags")

        val thrown = assertThrows(AppErrorException::class.java) { guard.interceptor().intercept(chain) }

        assertThat(thrown.error).isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
    }

    @Test
    fun `interceptor lets a private IP literal through`() {
        policy(NetworkPolicy.LAN_ONLY)
        val chain = chainFor("http://192.168.1.40:11434/api/tags")

        assertThat(guard.interceptor().intercept(chain).code).isEqualTo(200)
    }

    @Test
    fun `interceptor cannot judge a hostname and defers to the resolver`() {
        policy(NetworkPolicy.LAN_ONLY)
        val chain = chainFor("http://huggingface.co/api/models")

        // Deliberately allowed here: the string tells us nothing, and guessing
        // from it is exactly the hostname-matching mistake this design avoids.
        assertThat(guard.interceptor().intercept(chain).code).isEqualTo(200)
    }

    @Test
    fun `interceptor refuses everything under OFFLINE`() {
        policy(NetworkPolicy.OFFLINE)
        val chain = chainFor("http://127.0.0.1:11434/api/tags")

        val thrown = assertThrows(AppErrorException::class.java) { guard.interceptor().intercept(chain) }

        assertThat(thrown.error).isInstanceOf(AppError.Policy.OfflineMode::class.java)
    }

    // -------------------------------------------------------------- layer 2: Dns

    @Test
    fun `dns refuses a name that resolves outside the LAN`() {
        policy(NetworkPolicy.LAN_ONLY)
        val dns = guard.dns(fixedDns("93.184.216.34"))

        val thrown = assertThrows(AppErrorException::class.java) { dns.lookup("evil.example") }

        assertThat(thrown.error).isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
        // Not an UnknownHostException: the name resolved fine, we refused it.
        assertThat(thrown.error.message).contains("evil.example")
    }

    @Test
    fun `dns keeps only the permitted answers for a dual-stack host`() {
        policy(NetworkPolicy.LAN_ONLY)
        val dns = guard.dns(fixedDns("93.184.216.34", "192.168.1.40"))

        val resolved = dns.lookup("nas.example")

        assertThat(resolved).containsExactly(InetAddress.getByName("192.168.1.40"))
    }

    @Test
    fun `dns does not resolve at all under OFFLINE`() {
        policy(NetworkPolicy.OFFLINE)
        var resolverCalled = false
        val dns = guard.dns(
            Dns { _ ->
                resolverCalled = true
                listOf(InetAddress.getByName("127.0.0.1"))
            },
        )

        val thrown = assertThrows(AppErrorException::class.java) { dns.lookup("localhost") }

        assertThat(thrown.error).isInstanceOf(AppError.Policy.OfflineMode::class.java)
        assertThat(resolverCalled).isFalse()
    }

    @Test
    fun `dns passes everything through under OPEN`() {
        policy(NetworkPolicy.OPEN)
        val dns = guard.dns(fixedDns("93.184.216.34"))

        assertThat(dns.lookup("example.com")).hasSize(1)
    }

    // ----------------------------------------------------- layer 3: EventListener

    @Test
    fun `connectStart defeats DNS rebinding`() {
        policy(NetworkPolicy.LAN_ONLY)
        // The resolver answered with a private address a moment ago and layer 2
        // let it through. By connect time the answer has changed to a public
        // one. Only this layer sees the address actually being dialled.
        val listener = guard.eventListenerFactory().create(callFor("http://rebind.example/"))

        val thrown = assertThrows(AppErrorException::class.java) {
            listener.connectStart(
                callFor("http://rebind.example/"),
                InetSocketAddress(InetAddress.getByName("93.184.216.34"), 80),
                Proxy.NO_PROXY,
            )
        }

        assertThat(thrown.error).isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
    }

    @Test
    fun `connectStart allows a private socket address`() {
        policy(NetworkPolicy.LAN_ONLY)
        val call = callFor("http://nas.example/")
        val listener = guard.eventListenerFactory().create(call)

        listener.connectStart(
            call,
            InetSocketAddress(InetAddress.getByName("192.168.1.40"), 11434),
            Proxy.NO_PROXY,
        )
    }

    @Test
    fun `connectStart refuses an unresolved socket address`() {
        policy(NetworkPolicy.LAN_ONLY)
        val call = callFor("http://nas.example/")
        val listener = guard.eventListenerFactory().create(call)

        val thrown = assertThrows(AppErrorException::class.java) {
            listener.connectStart(
                call,
                InetSocketAddress.createUnresolved("nas.example", 11434),
                Proxy.NO_PROXY,
            )
        }

        assertThat(thrown.error).isInstanceOf(AppError.Policy.LanOnlyViolation::class.java)
    }

    // ------------------------------------------------------------------ helpers

    private fun fixedDns(vararg literals: String) = Dns { _ -> literals.map(InetAddress::getByName) }

    private fun requestFor(url: String) = Request.Builder().url(url).build()

    private fun callFor(url: String): Call = mockk<Call>().also {
        every { it.request() } returns requestFor(url)
    }

    private fun chainFor(url: String): Interceptor.Chain {
        val request = requestFor(url)
        val response = Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()
        return mockk<Interceptor.Chain>().also {
            every { it.request() } returns request
            every { it.proceed(any()) } returns response
        }
    }
}
