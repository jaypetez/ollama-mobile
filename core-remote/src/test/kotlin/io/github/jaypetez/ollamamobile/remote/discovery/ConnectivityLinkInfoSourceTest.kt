package io.github.jaypetez.ollamamobile.remote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The half of trap 10 that cannot be reached through [SubnetScanner]: where the
 * prefix length actually comes from.
 *
 * [SubnetScannerTest] proves the sweep *uses* whatever prefix it is handed. This
 * proves the production [LinkInfoSource] reads the platform's real
 * `LinkAddress.getPrefixLength()` rather than substituting the 24 that happens
 * to be right on a home router and wrong on every managed network.
 *
 * The platform types are mocked rather than shadowed because there is no public
 * constructor for [LinkAddress] or [LinkProperties] — they are `@SystemApi` —
 * and the two getters this class depends on are public.
 */
@RunWith(JUnit4::class)
class ConnectivityLinkInfoSourceTest {
    private val manager = mockk<ConnectivityManager>()
    private val context = mockk<Context> {
        every { getSystemService(ConnectivityManager::class.java) } returns manager
    }

    private fun linkAddress(address: String, prefixLength: Int): LinkAddress = mockk {
        every { this@mockk.address } returns InetAddress.getByName(address)
        every { this@mockk.prefixLength } returns prefixLength
    }

    private fun withLinkAddresses(vararg addresses: LinkAddress) {
        val network = mockk<Network>()
        val properties = mockk<LinkProperties> {
            every { linkAddresses } returns addresses.toList()
        }
        every { manager.activeNetwork } returns network
        every { manager.getLinkProperties(network) } returns properties
    }

    @Test
    fun `the prefix length is the platform's, not an assumed 24`() {
        withLinkAddresses(linkAddress("10.24.3.9", prefixLength = 20))

        val lookup = ConnectivityLinkInfoSource(context).current()

        val link = (lookup as LinkLookup.Found).link
        assertThat(link.prefixLength).isEqualTo(20)
        assertThat(link.address.hostAddress).isEqualTo("10.24.3.9")
    }

    @Test
    fun `a slash 28 guest network is reported as a slash 28`() {
        withLinkAddresses(linkAddress("192.168.1.37", prefixLength = 28))

        assertThat((ConnectivityLinkInfoSource(context).current() as LinkLookup.Found).link.prefixLength)
            .isEqualTo(28)
    }

    @Test
    fun `loopback and IPv6 addresses are not mistaken for the link`() {
        // A tunnel interface typically offers an IPv6 address and nothing else,
        // which is the case SweepRefusal.NoIpv4Link exists for.
        withLinkAddresses(
            linkAddress("127.0.0.1", prefixLength = 8),
            linkAddress("fd00::1", prefixLength = 64),
        )

        assertThat(ConnectivityLinkInfoSource(context).current()).isEqualTo(LinkLookup.NoIpv4)
    }

    @Test
    fun `the first usable IPv4 address wins over a preceding loopback`() {
        withLinkAddresses(
            linkAddress("127.0.0.1", prefixLength = 8),
            linkAddress("192.168.8.5", prefixLength = 22),
        )

        assertThat((ConnectivityLinkInfoSource(context).current() as LinkLookup.Found).link.prefixLength)
            .isEqualTo(22)
    }

    @Test
    fun `no active network and no link properties are both NoActiveNetwork`() {
        every { manager.activeNetwork } returns null
        assertThat(ConnectivityLinkInfoSource(context).current()).isEqualTo(LinkLookup.NoActiveNetwork)

        val network = mockk<Network>()
        every { manager.activeNetwork } returns network
        every { manager.getLinkProperties(network) } returns null
        assertThat(ConnectivityLinkInfoSource(context).current()).isEqualTo(LinkLookup.NoActiveNetwork)
    }

    @Test
    fun `a device with no ConnectivityManager at all does not crash`() {
        val bare = mockk<Context> {
            every { getSystemService(ConnectivityManager::class.java) } returns null
        }

        assertThat(ConnectivityLinkInfoSource(bare).current()).isEqualTo(LinkLookup.NoActiveNetwork)
    }
}
