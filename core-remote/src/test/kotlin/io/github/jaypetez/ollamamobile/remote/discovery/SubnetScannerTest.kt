package io.github.jaypetez.ollamamobile.remote.discovery

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.remote.testOkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** How long the concurrency probe holds a request open, so overlap is observable. */
private const val PROBE_HOLD_MILLIS = 60L

/**
 * The sweep, against real sockets.
 *
 * `runBlocking`, not `runTest`: the whole point of three of these tests is what
 * happens in real time — a probe that never answers, a sweep that is cancelled,
 * a confirmation round trip. Virtual time would skip exactly the part under
 * test.
 */
@RunWith(JUnit4::class)
class SubnetScannerTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    /** A server that behaves like Ollama: a root that answers, and a real `/api/version`. */
    private fun ollama(version: String, models: Int = 2): MockWebServer = mockServer { request ->
        when (request.target) {
            "/api/version" -> MockResponse.Builder().body("""{"version":"$version"}""").build()
            "/api/tags" -> MockResponse.Builder().body(tagsBody(models)).build()
            else -> MockResponse.Builder().body("Ollama is running").build()
        }
    }

    /** A server that has 11434 open and is emphatically not Ollama. */
    private fun impostor(): MockWebServer = mockServer {
        MockResponse.Builder().body("<html><body>Printer status: ready</body></html>").build()
    }

    private fun mockServer(handler: (RecordedRequest) -> MockResponse): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
        server.start()
        servers += server
        return server
    }

    private fun tagsBody(models: Int): String =
        (0 until models).joinToString(prefix = """{"models":[""", postfix = "]}") { """{"name":"m$it"}""" }

    private fun scanner(linkInfoSource: LinkInfoSource = LinkInfoSource { LinkLookup.NoActiveNetwork }) =
        SubnetScanner(testOkHttpClient(readTimeoutMillis = 1_000), linkInfoSource, Dispatchers.IO)

    private fun candidatesOf(vararg servers: MockWebServer) =
        servers.map { Candidate(host = it.hostName, port = it.port) }

    private val fastSweep = SweepConfig(concurrency = 8, connectTimeoutMillis = 500, readTimeoutMillis = 1_000)

    /**
     * How many addresses a link of this shape enumerates.
     *
     * Read off [DiscoveryEvent.Started], so no probe has to complete; the
     * one-millisecond timeouts are there only so the handful of probes that do
     * start before the collector walks away die immediately.
     */
    private fun candidateCountFor(address: String, prefixLength: Int): Int = runBlocking {
        val link = LinkInfo(InetAddress.getByName(address) as Inet4Address, prefixLength)
        val started = scanner(LinkInfoSource { LinkLookup.Found(link) })
            .scan(fastSweep.copy(connectTimeoutMillis = 1, readTimeoutMillis = 1))
            .first() as DiscoveryEvent.Started
        started.candidateCount
    }

    @Test
    fun `three Ollama servers produce exactly three results`() = runBlocking {
        val found = scanner()
            .sweep(candidatesOf(ollama("0.12.1"), ollama("0.12.2"), ollama("0.12.3")), fastSweep)
            .toList()

        val discovered = found.filterIsInstance<DiscoveryEvent.Found>().map { it.server }
        assertThat(discovered).hasSize(3)
        assertThat(discovered.map { it.version }).containsExactly("0.12.1", "0.12.2", "0.12.3")
        assertThat(found.first()).isInstanceOf(DiscoveryEvent.Started::class.java)
        assertThat(found.last()).isEqualTo(DiscoveryEvent.Finished(probed = 3, found = 3))
    }

    @Test
    fun `the model count is reported when it is cheap to get`() = runBlocking {
        val found = scanner().sweep(candidatesOf(ollama("0.12.3", models = 6)), fastSweep).toList()

        assertThat(
            found
                .filterIsInstance<DiscoveryEvent.Found>()
                .single()
                .server.modelCount,
        ).isEqualTo(6)
    }

    @Test
    fun `something else listening on the port is rejected by the version confirmation`() = runBlocking {
        val found = scanner()
            .sweep(candidatesOf(ollama("0.12.3"), impostor()), fastSweep)
            .toList()

        val discovered = found.filterIsInstance<DiscoveryEvent.Found>().map { it.server }
        // The impostor answered the TCP connect and the HTTP request. Only the
        // shape of /api/version tells the two apart, and presenting the wrong
        // one is how a conversation ends up somewhere unexpected.
        assertThat(discovered).hasSize(1)
        assertThat(discovered.single().version).isEqualTo("0.12.3")
    }

    @Test
    fun `a subnet wider than 22 bits is refused with a reason the UI can explain`() = runBlocking {
        val link = LinkInfo(InetAddress.getByName("10.1.2.3") as Inet4Address, prefixLength = 16)

        val events = scanner(LinkInfoSource { LinkLookup.Found(link) }).scan(fastSweep).toList()

        val refusal = (events.single() as DiscoveryEvent.Refused).reason
        assertThat(refusal).isInstanceOf(SweepRefusal.SubnetTooWide::class.java)
        val tooWide = refusal as SweepRefusal.SubnetTooWide
        assertThat(tooWide.prefixLength).isEqualTo(16)
        assertThat(tooWide.addressCount).isEqualTo(65_536L)
        assertThat(tooWide.minimumPrefixLength).isEqualTo(22)
    }

    @Test
    fun `a 24 is enumerated without the network, broadcast or the device itself`() = runBlocking {
        val link = LinkInfo(InetAddress.getByName("192.168.1.40") as Inet4Address, prefixLength = 24)
        // No probes are needed to check the enumeration: Started carries the count.
        val started = scanner(LinkInfoSource { LinkLookup.Found(link) })
            .scan(fastSweep.copy(connectTimeoutMillis = 1, readTimeoutMillis = 1))
            .first() as DiscoveryEvent.Started

        // 256 addresses, minus network, minus broadcast, minus us.
        assertThat(started.candidateCount).isEqualTo(253)
    }

    @Test
    fun `the enumeration follows the link's real prefix length rather than an assumed 24`() {
        // The /24 case above passes just as well against a hard-coded 24, which
        // is exactly the bug: home routers hand out /24 often enough for the
        // assumption to survive testing and then find nothing on a /16 and
        // waste 240 probes on a /28.
        assertThat(candidateCountFor("192.168.1.37", prefixLength = 28)).isEqualTo(13)
        assertThat(candidateCountFor("192.168.4.9", prefixLength = 23)).isEqualTo(509)
    }

    @Test
    fun `no more than the configured number of probes are ever in flight`() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val busy = mockServer {
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(PROBE_HOLD_MILLIS)
            inFlight.decrementAndGet()
            MockResponse.Builder().body("not ollama").build()
        }

        scanner()
            .sweep(
                List(24) { Candidate(busy.hostName, busy.port) },
                SweepConfig(concurrency = 4, connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000),
            ).toList()

        // The probes genuinely overlapped, so the ceiling below is a ceiling
        // and not an artefact of everything running one at a time.
        assertThat(peak.get()).isGreaterThan(1)
        // And they never exceeded the permit count. The calls are made with
        // execute() rather than enqueue(), so OkHttp's own per-host dispatcher
        // limit does not apply: without the semaphore all 24 would be open at
        // once, which is how a sweep hits the file-descriptor limit.
        assertThat(peak.get()).isAtMost(4)
    }

    @Test
    fun `no active network is its own reason, not an empty result`() = runBlocking {
        val events = scanner(LinkInfoSource { LinkLookup.NoActiveNetwork }).scan(fastSweep).toList()

        assertThat((events.single() as DiscoveryEvent.Refused).reason).isEqualTo(SweepRefusal.NoActiveNetwork)
    }

    @Test
    fun `a tunnel with no IPv4 link address is reported as such`() = runBlocking {
        val events = scanner(LinkInfoSource { LinkLookup.NoIpv4 }).scan(fastSweep).toList()

        assertThat((events.single() as DiscoveryEvent.Refused).reason).isEqualTo(SweepRefusal.NoIpv4Link)
    }

    @Test
    fun `cancelling the collector stops the sweep promptly`() = runBlocking {
        // A server that never answers within the test's patience. If
        // cancellation did not reach the in-flight call, the sweep would sit
        // here for the full delay.
        val slow = mockServer {
            MockResponse
                .Builder()
                .headersDelay(30, TimeUnit.SECONDS)
                .body("{}")
                .build()
        }
        val scanner = scanner()
        val candidates = List(20) { Candidate(slow.hostName, slow.port) }

        val elapsed = measureTimeMillis {
            val job = launch {
                scanner
                    .sweep(candidates, SweepConfig(connectTimeoutMillis = 30_000, readTimeoutMillis = 30_000))
                    .collect { }
            }
            // Let the probes get as far as being in flight, then cancel.
            delay(200)
            job.cancelAndJoin()
        }

        assertThat(elapsed).isLessThan(5_000L)
    }
}
