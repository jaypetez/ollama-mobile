package io.github.jaypetez.ollamamobile.remote.discovery

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.remote.RemoteError
import io.github.jaypetez.ollamamobile.remote.ServerUrls
import io.github.jaypetez.ollamamobile.remote.awaitResponse
import java.io.IOException
import java.net.Inet4Address
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Why a sweep did not happen, in a form the UI can turn into a sentence.
 *
 * A typed reason rather than a boolean or a log line: every one of these cases
 * has a different next step for the user, and "scan found nothing" is the wrong
 * thing to show for all of them.
 */
sealed interface SweepRefusal {
    /**
     * The link's subnet is bigger than [minimumPrefixLength] allows.
     *
     * The UI should say how many addresses that would be and offer manual
     * entry, because on a university or corporate network this is the *normal*
     * outcome and the user is not doing anything wrong.
     */
    data class SubnetTooWide(
        val prefixLength: Int,
        val addressCount: Long,
        val minimumPrefixLength: Int,
    ) : SweepRefusal

    /** No active network at all — aeroplane mode, or Wi-Fi off. */
    data object NoActiveNetwork : SweepRefusal

    /**
     * There is a network but no IPv4 link address to derive a subnet from.
     *
     * The usual cause is a VPN: on Android the tunnel becomes the default
     * network, and its `LinkProperties` describe the tunnel rather than the
     * Wi-Fi link.
     */
    data object NoIpv4Link : SweepRefusal

    /** The app's own network policy refused the probes. Offline mode, usually. */
    data class Blocked(
        val error: AppError,
    ) : SweepRefusal
}

/** What a sweep reports as it runs. */
sealed interface DiscoveryEvent {
    /** Emitted once, before any probe, so the UI can show a determinate progress bar. */
    data class Started(
        val candidateCount: Int,
    ) : DiscoveryEvent

    data class Found(
        val server: DiscoveredServer,
    ) : DiscoveryEvent

    /** Terminal. No [Found] will follow. */
    data class Refused(
        val reason: SweepRefusal,
    ) : DiscoveryEvent

    /** Terminal, after every candidate has been probed or the flow was cancelled. */
    data class Finished(
        val probed: Int,
        val found: Int,
    ) : DiscoveryEvent
}

/** The IPv4 address of the device on the current link, and the link's real prefix length. */
data class LinkInfo(
    val address: Inet4Address,
    val prefixLength: Int,
)

/** What the platform could tell us about the current link. */
sealed interface LinkLookup {
    data class Found(
        val link: LinkInfo,
    ) : LinkLookup

    data object NoActiveNetwork : LinkLookup

    /** A network, but no IPv4 link address — a VPN tunnel, usually. */
    data object NoIpv4 : LinkLookup
}

/**
 * Where [SubnetScanner] gets the link's address and prefix.
 *
 * An interface so the sweep can be tested against a fake /24 without a
 * Robolectric shadow for every `ConnectivityManager` call, and so a future
 * "scan this subnet instead" setting has somewhere to plug in.
 */
fun interface LinkInfoSource {
    fun current(): LinkLookup
}

/** One address/port pair to probe. */
data class Candidate(
    val host: String,
    val port: Int,
)

/** Tuning for one sweep. */
data class SweepConfig(
    /**
     * 11434 and only 11434 by default. Sweeping a second port doubles the cost
     * of the sweep to cover a case manual entry already handles.
     */
    val port: Int = ServerUrls.DEFAULT_PORT,
    /**
     * A few dozen at a time. Unbounded parallelism hits the per-process file
     * descriptor limit, and even under it a burst of hundreds of simultaneous
     * SYNs is dropped by the Wi-Fi stack or treated as hostile by the AP.
     */
    val concurrency: Int = 32,
    /**
     * On a local subnet a host that exists answers in single-digit
     * milliseconds. This timeout is what determines how long the sweep takes,
     * because it is the only thing that ends a probe against an address where
     * nothing is listening at all.
     */
    val connectTimeoutMillis: Long = 400L,
    val readTimeoutMillis: Long = 1_000L,
    /** Refuse anything wider. See [SweepRefusal.SubnetTooWide]. */
    val minimumPrefixLength: Int = 22,
)

/**
 * Finds Ollama servers on the local link by probing addresses.
 *
 * ## There is no mDNS path here, and adding one would be wasted work
 *
 * **Ollama publishes no DNS-SD/mDNS service record.** There is no
 * `_ollama._tcp.local`, nothing registers with Bonjour, and
 * `NsdManager.discoverServices()` will run perfectly and find zero servers,
 * forever. Anything on the internet that appears to show otherwise is
 * describing a reverse proxy, a Home Assistant add-on or a wrapper that
 * somebody added an advertisement to — not `ollama serve`. So discovery has to
 * be active, which means a port scan of the user's own network, which is a
 * thing to do narrowly and on an explicit tap, never on a timer.
 *
 * ## VPN peers are invisible to this and always will be
 *
 * A Tailscale peer lives on `100.64.0.0/10` behind the tunnel interface, not on
 * the Wi-Fi link's subnet. This sweep enumerates addresses derived from the
 * link's own [LinkInfo], so `100.x.y.z` is never in the candidate set and is
 * never probed. The same goes for a WireGuard peer on `10.x` reached through a
 * tunnel, and for anything behind a router the phone is not attached to. Worse,
 * on Android a VPN usually *becomes* the default network, so the active
 * network's `LinkProperties` may describe the tunnel — a `/32`, or a `/10` —
 * and the sweep is then either empty or refused for being too wide. Both
 * outcomes are correct and both mean the same thing: discovery cannot find your
 * server.
 *
 * That is precisely why manual entry is a first-class path in the UI and not a
 * fallback hidden behind a disclosure triangle.
 *
 * ## Why the probe is an HTTP call and not a raw socket
 *
 * A bare TCP connect would be marginally cheaper, but a raw `Socket` bypasses
 * the `Dns`, `Interceptor` and `EventListener` layers of `LanOnlyGuard`
 * entirely — the app's offline and LAN-only settings would not apply to the one
 * feature that touches the most addresses. A Konsist test enforces that. Going
 * through the shared client costs an HTTP request line per candidate and buys
 * the guard, the connection pool and cancellation that actually works.
 */
@Singleton
class SubnetScanner
    @Inject
    constructor(
        private val sharedClient: OkHttpClient,
        private val linkInfoSource: LinkInfoSource,
        @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    ) {
        /**
         * Sweeps the current link.
         *
         * Results arrive as they are confirmed, so the UI can list the first
         * server while the rest of the subnet is still being probed.
         * Cancelling the collector cancels every in-flight call.
         *
         * Cold, including the link lookup: reading `LinkProperties` is a binder
         * call, and doing it when the flow is *built* would both run it on
         * whatever thread called this — the main one, from a ViewModel — and
         * freeze the answer, so re-collecting after the user turned Wi-Fi on
         * would replay the stale refusal.
         */
        fun scan(config: SweepConfig = SweepConfig()): Flow<DiscoveryEvent> = flow {
            val link = when (val lookup = withContext(dispatcher) { linkInfoSource.current() }) {
                is LinkLookup.Found -> {
                    lookup.link
                }

                LinkLookup.NoActiveNetwork -> {
                    emit(DiscoveryEvent.Refused(SweepRefusal.NoActiveNetwork))
                    return@flow
                }

                LinkLookup.NoIpv4 -> {
                    emit(DiscoveryEvent.Refused(SweepRefusal.NoIpv4Link))
                    return@flow
                }
            }

            if (link.prefixLength < config.minimumPrefixLength) {
                // A /22 is 1022 usable hosts, which is already a lot of
                // connection attempts. A /16 is 65534: that is not a discovery
                // sweep, it is a network scan. It takes minutes, it exhausts
                // the socket table, it flattens the battery, and on a managed
                // network it gets the device flagged by whatever is watching.
                emit(
                    DiscoveryEvent.Refused(
                        SweepRefusal.SubnetTooWide(
                            prefixLength = link.prefixLength,
                            addressCount = addressCount(link.prefixLength),
                            minimumPrefixLength = config.minimumPrefixLength,
                        ),
                    ),
                )
                return@flow
            }

            emitAll(sweep(candidatesFor(link, config.port), config))
        }

        /**
         * Sweeps an explicit candidate list.
         *
         * Public because "re-probe the servers I already know about" is the
         * same operation minus the enumeration, and because a test can point it
         * at three loopback ports.
         */
        fun sweep(candidates: List<Candidate>, config: SweepConfig = SweepConfig()): Flow<DiscoveryEvent> =
            channelFlow {
                send(DiscoveryEvent.Started(candidates.size))

                val client = probeClient(config)
                val semaphore = Semaphore(config.concurrency)
                val found = AtomicInteger()
                val blocked = AtomicReference<AppError?>(null)

                coroutineScope {
                    candidates.forEach { candidate ->
                        launch {
                            // The policy that refused one candidate will refuse
                            // every remaining one for the same reason; probing
                            // 253 more times to be told the same thing is pure
                            // battery.
                            if (blocked.get() != null) return@launch
                            semaphore.withPermit {
                                when (val result = probe(client, candidate)) {
                                    is ProbeResult.Confirmed -> {
                                        found.incrementAndGet()
                                        send(DiscoveryEvent.Found(result.server))
                                    }

                                    is ProbeResult.Blocked -> {
                                        blocked.compareAndSet(null, result.error)
                                    }

                                    ProbeResult.NotOllama, ProbeResult.Silent -> {
                                        Unit
                                    }
                                }
                            }
                        }
                    }
                }

                val refusal = blocked.get()
                if (refusal == null) {
                    send(DiscoveryEvent.Finished(probed = candidates.size, found = found.get()))
                } else {
                    send(DiscoveryEvent.Refused(SweepRefusal.Blocked(refusal)))
                }
            }.flowOn(dispatcher)

        /**
         * A client derived from the shared one with LAN-sized timeouts.
         *
         * `newBuilder()` keeps the connection pool, the dispatcher and every
         * interceptor — including the guard. Building a second `OkHttpClient`
         * here would be a second path to the network that nothing polices, and
         * the architecture test would fail the build.
         */
        private fun probeClient(config: SweepConfig): OkHttpClient = sharedClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis))
            .readTimeout(Duration.ofMillis(config.readTimeoutMillis))
            .callTimeout(Duration.ofMillis(config.connectTimeoutMillis + config.readTimeoutMillis))
            // A redirect from a probe is not something to follow: it is some
            // other service on 11434 sending us somewhere else entirely.
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private suspend fun probe(client: OkHttpClient, candidate: Candidate): ProbeResult {
            val base = ServerUrls.parseOrNull("http://${bracket(candidate.host)}:${candidate.port}")
                ?: return ProbeResult.Silent

            // Step 1: is anything listening? Any HTTP answer counts, including
            // a 404 or a 401 — this only establishes that the port is open.
            when (val liveness = request(client, base)) {
                null -> return ProbeResult.Silent
                is ProbeOutcome.Blocked -> return ProbeResult.Blocked(liveness.error)
                is ProbeOutcome.Answered -> Unit
            }

            // Step 2: is it Ollama? A successful TCP connect on 11434 means
            // *something* is listening, not that it speaks this protocol.
            // Reporting a random device that happens to have the port open is
            // how a user ends up sending a conversation somewhere unexpected.
            val startedAt = System.nanoTime()
            val confirmation = request(client, ServerUrls.resolveOrNull(base, "api/version"))
            val roundTripMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

            val version = when (confirmation) {
                is ProbeOutcome.Blocked -> {
                    return ProbeResult.Blocked(confirmation.error)
                }

                null -> {
                    null
                }

                is ProbeOutcome.Answered -> {
                    confirmation.body.takeIf { confirmation.code == HTTP_OK }?.let(::versionOrNull)
                }
            }

            return version?.let {
                ProbeResult.Confirmed(
                    DiscoveredServer(
                        address = candidate.host,
                        port = candidate.port,
                        version = it,
                        roundTripMillis = roundTripMillis,
                        modelCount = modelCountOrNull(client, base),
                    ),
                )
            } ?: ProbeResult.NotOllama
        }

        /** `/api/tags` is cheap and makes the list entry readable. Its failure is not the sweep's problem. */
        private suspend fun modelCountOrNull(client: OkHttpClient, base: HttpUrl): Int? {
            val outcome = request(client, ServerUrls.resolveOrNull(base, "api/tags"))
            val body = (outcome as? ProbeOutcome.Answered)?.takeIf { it.code == HTTP_OK }?.body ?: return null
            return runCatching { (Json.parseToJsonElement(body).jsonObject["models"] as? JsonArray)?.size }
                .getOrNull()
        }

        private suspend fun request(client: OkHttpClient, url: HttpUrl): ProbeOutcome? = try {
            client
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .build(),
                ).awaitResponse()
                .use { response ->
                    // peekBody, not body.string(): an unrelated service on 11434 is
                    // free to answer with a megabyte of HTML, and the probe only
                    // needs enough bytes to see whether this is Ollama.
                    ProbeOutcome.Answered(response.code, response.peekBody(PROBE_BODY_LIMIT).string())
                }
        } catch (failure: IOException) {
            classify(failure, url)
        } catch (failure: AppErrorException) {
            // LanOnlyGuard throws this from the interceptor chain; it is not an
            // IOException and must not be mistaken for "nothing answered".
            classify(failure, url)
        }

        private fun classify(failure: Throwable, url: HttpUrl): ProbeOutcome? =
            when (val error = RemoteError.fromThrowable(failure)) {
                is AppError.Policy -> {
                    ProbeOutcome.Blocked(error)
                }

                else -> {
                    Timber.v("Discovery probe of %s: %s", url.host, error.message)
                    null
                }
            }

        private fun versionOrNull(body: String): String? = runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["version"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        private sealed interface ProbeOutcome {
            data class Answered(
                val code: Int,
                val body: String,
            ) : ProbeOutcome

            data class Blocked(
                val error: AppError,
            ) : ProbeOutcome
        }

        private sealed interface ProbeResult {
            data class Confirmed(
                val server: DiscoveredServer,
            ) : ProbeResult

            /** Something answered, but it was not Ollama. */
            data object NotOllama : ProbeResult

            /** Nothing answered before the timeout. The common case, by a wide margin. */
            data object Silent : ProbeResult

            data class Blocked(
                val error: AppError,
            ) : ProbeResult
        }

        private companion object {
            const val HTTP_OK = 200
            const val NANOS_PER_MILLI = 1_000_000L
            const val IPV4_BITS = 32
            const val PROBE_BODY_LIMIT = 8_192L
            const val BYTE_MASK = 0xFFL
        }

        /** Enumerates the usable host addresses of [link]'s subnet, minus the device's own. */
        private fun candidatesFor(link: LinkInfo, port: Int): List<Candidate> {
            val own = link.address.address.fold(0L) { acc, byte -> (acc shl BITS_PER_BYTE) or (byte.toLong() and 0xFF) }
            val hostBits = IPV4_BITS - link.prefixLength
            val mask = if (hostBits >= IPV4_BITS) 0L else (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
            val network = own and mask
            val broadcast = network or (mask.inv() and 0xFFFFFFFFL)

            // Skip the network and broadcast addresses, and skip ourselves:
            // probing the device's own address finds the app's embedded server,
            // which is correct and confusing to present as a discovered remote.
            return ((network + 1) until broadcast)
                .filter { it != own }
                .map { Candidate(host = toDottedQuad(it), port = port) }
        }

        private fun toDottedQuad(value: Long): String = (IPV4_BYTES - 1 downTo 0).joinToString(".") { index ->
            ((value shr (index * BITS_PER_BYTE)) and BYTE_MASK).toString()
        }

        private fun addressCount(prefixLength: Int): Long = 1L shl (IPV4_BITS - prefixLength)

        private fun bracket(host: String): String =
            if (host.count { it == ':' } >= 2 && !host.startsWith("[")) "[$host]" else host
    }

private const val BITS_PER_BYTE = 8
private const val IPV4_BYTES = 4

/**
 * Reads the link's real prefix length from the platform.
 *
 * `LinkAddress.getPrefixLength()` and never an assumed /24. Home routers hand
 * out /24 often enough to make the assumption look right in testing; corporate
 * and university networks routinely use /16, guest networks /20, and some
 * ISP-supplied routers /22. Assuming /24 on a /16 quietly finds nothing outside
 * the device's own third octet, and assuming /24 on a /28 wastes 240 probes on
 * addresses that cannot exist.
 */
@Singleton
class ConnectivityLinkInfoSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : LinkInfoSource {
        override fun current(): LinkLookup {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return LinkLookup.NoActiveNetwork
            val network = manager.activeNetwork ?: return LinkLookup.NoActiveNetwork
            val properties = manager.getLinkProperties(network) ?: return LinkLookup.NoActiveNetwork
            val link = properties.linkAddresses
                .asSequence()
                .mapNotNull { linkAddress ->
                    val address = linkAddress.address as? Inet4Address ?: return@mapNotNull null
                    if (address.isLoopbackAddress) null else LinkInfo(address, linkAddress.prefixLength)
                }.firstOrNull()
            return link?.let(LinkLookup::Found) ?: LinkLookup.NoIpv4
        }
    }
