package io.github.jaypetez.ollamamobile.remote.health

import io.github.jaypetez.ollamamobile.common.dispatcher.ApplicationScope
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.OllamaClient
import io.github.jaypetez.ollamamobile.remote.RunningModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a server's circuit breaker is. */
enum class BreakerState {
    /** Normal. Requests go through and failures are counted. */
    CLOSED,

    /** Too many consecutive failures: nothing is sent until the cooldown expires. */
    OPEN,

    /** The cooldown expired. Exactly one probe is allowed through to see if the server came back. */
    HALF_OPEN,
}

/** What the monitor currently believes about one server. */
data class ServerHealth(
    val serverId: ServerId,
    val reachable: Boolean = false,
    val version: String? = null,
    val loadedModels: List<String> = emptyList(),
    /** Round-trip time of the last successful probe. */
    val latencyMillis: Long? = null,
    val lastCheckedAtMillis: Long? = null,
    val lastError: AppError? = null,
    val breaker: BreakerState = BreakerState.CLOSED,
    val consecutiveFailures: Int = 0,
) {
    /** True when the breaker is refusing traffic, so the router should look elsewhere immediately. */
    val isCircuitOpen: Boolean get() = breaker == BreakerState.OPEN
}

/**
 * A per-server circuit breaker.
 *
 * The point is not to protect the server — it is to stop a dead server from
 * adding a full connect timeout to every request the app makes, which is the
 * difference between "this server is unavailable" and "the app is broken".
 *
 * Only transport-level failures trip it. A 404 for a model that does not exist
 * and a 400 for a malformed request are problems with the *request*; counting
 * those would let one bad prompt disable a perfectly healthy server.
 */
internal class CircuitBreaker(
    private val clock: WallClock,
    private val failureThreshold: Int,
    private val baseCooldownMillis: Long,
    private val maxCooldownMillis: Long,
) {
    private var consecutiveFailures = 0
    private var openedAtMillis: Long? = null
    private var cooldownMillis = baseCooldownMillis

    val failures: Int get() = consecutiveFailures

    fun state(): BreakerState {
        val openedAt = openedAtMillis ?: return BreakerState.CLOSED
        return if (clock.nowMillis() - openedAt >= cooldownMillis) BreakerState.HALF_OPEN else BreakerState.OPEN
    }

    /** True when a probe may be sent now. */
    fun allowsRequest(): Boolean = state() != BreakerState.OPEN

    fun recordSuccess() {
        consecutiveFailures = 0
        openedAtMillis = null
        cooldownMillis = baseCooldownMillis
    }

    /** @return true when this failure counted towards the breaker. */
    fun recordFailure(error: AppError): Boolean {
        if (!trips(error)) return false
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            // Reopening from half-open doubles the wait, capped: a server that
            // is down for an hour should not be probed every fifteen seconds
            // for an hour.
            cooldownMillis = if (openedAtMillis == null) {
                baseCooldownMillis
            } else {
                (cooldownMillis * 2).coerceAtMost(maxCooldownMillis)
            }
            openedAtMillis = clock.nowMillis()
        }
        return true
    }

    private fun trips(error: AppError): Boolean = when (error) {
        is AppError.Network.Timeout, is AppError.Network.Unreachable, is AppError.Network.Tls -> true

        // The server is up and telling us it is overloaded. That is a reason to
        // stop hammering it, so it counts.
        is AppError.Network.QueueFull -> true

        is AppError.Network.Http -> error.code >= HTTP_SERVER_ERROR

        else -> false
    }

    private companion object {
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * Polls the enabled servers for reachability, version and loaded models.
 *
 * Two things keep this from being a battery problem. The circuit breaker above
 * stops a dead server from being probed at the live rate, and [setForegrounded]
 * stretches the interval when the app is in the background — where nobody is
 * looking at the health indicator, and the only thing a probe achieves is
 * waking the radio.
 */
@Singleton
class ServerHealthMonitor
    @Inject
    constructor(
        private val client: OllamaClient,
        @param:ApplicationScope private val scope: CoroutineScope,
        private val clock: WallClock,
    ) {
        /** Tuning, in one place, so a test can run the whole thing on virtual time. */
        data class Config(
            val foregroundIntervalMillis: Long = 15_000L,
            /** Long enough that a backgrounded app is not a background network user. */
            val backgroundIntervalMillis: Long = 300_000L,
            val failureThreshold: Int = 3,
            val cooldownMillis: Long = 60_000L,
            val maxCooldownMillis: Long = 600_000L,
        )

        private var config = Config()

        private val mutableHealth = MutableStateFlow<Map<ServerId, ServerHealth>>(emptyMap())
        val health: StateFlow<Map<ServerId, ServerHealth>> = mutableHealth.asStateFlow()

        private val jobs = LinkedHashMap<ServerId, Job>()
        private val breakers = LinkedHashMap<ServerId, CircuitBreaker>()
        private val foregrounded = MutableStateFlow(true)

        /** Replaces the polled set. Disabled servers are dropped, not merely skipped. */
        fun setServers(servers: List<ServerRef>, config: Config = this.config) {
            this.config = config
            val enabled = servers.filter { it.enabled }.associateBy { it.id }

            jobs.keys.toList().filterNot { it in enabled }.forEach { id ->
                jobs.remove(id)?.cancel()
                breakers.remove(id)
                mutableHealth.value = mutableHealth.value - id
            }

            enabled.forEach { (id, server) ->
                if (jobs[id]?.isActive == true) return@forEach
                breakers.getOrPut(id) {
                    CircuitBreaker(
                        clock = clock,
                        failureThreshold = config.failureThreshold,
                        baseCooldownMillis = config.cooldownMillis,
                        maxCooldownMillis = config.maxCooldownMillis,
                    )
                }
                mutableHealth.value = mutableHealth.value + (id to (mutableHealth.value[id] ?: ServerHealth(id)))
                jobs[id] = scope.launch { pollLoop(server) }
            }
        }

        /** Call from a process-lifecycle observer. Backgrounding stretches the interval. */
        fun setForegrounded(value: Boolean) {
            foregrounded.value = value
        }

        /** Stops all polling. The monitor can be restarted with [setServers]. */
        fun stop() {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
        }

        /** One probe, outside the loop, for a "check now" button. */
        suspend fun probeNow(server: ServerRef) {
            probe(server, breakerFor(server.id))
        }

        private suspend fun pollLoop(server: ServerRef) {
            val breaker = breakerFor(server.id)
            while (currentCoroutineContext().isActive) {
                if (breaker.allowsRequest()) {
                    probe(server, breaker)
                } else {
                    // Still publish, so the UI can say "not retrying until…"
                    // rather than showing a stale "checking" spinner forever.
                    publish(server.id) { it.copy(breaker = breaker.state()) }
                }
                awaitNextTick()
            }
        }

        /**
         * Waits out one polling interval, re-reading the interval whenever the
         * app moves between foreground and background.
         *
         * A plain `delay(interval())` would sample the interval once and then
         * sleep through the change, so backgrounding the app would still cost
         * one more foreground-rate probe — and, worse, coming back to the
         * foreground would leave the health indicator stale for up to five
         * minutes. Waiting on the foreground flag with a timeout costs nothing
         * while nothing changes and reacts immediately when it does.
         */
        private suspend fun awaitNextTick() {
            val startedAt = clock.nowMillis()
            while (currentCoroutineContext().isActive) {
                val remaining = interval() - (clock.nowMillis() - startedAt)
                if (remaining <= 0L) return
                // drop(1): a StateFlow replays its current value, and only a
                // *change* is a reason to recompute.
                withTimeoutOrNull(remaining) { foregrounded.drop(1).first() } ?: return
            }
        }

        private suspend fun probe(server: ServerRef, breaker: CircuitBreaker) {
            val startedAt = clock.nowMillis()
            when (val result = client.version(server)) {
                is AppResult.Success -> {
                    breaker.recordSuccess()
                    val loaded = client.runningModels(server)
                    publish(server.id) {
                        it.copy(
                            reachable = true,
                            version = result.value.version,
                            loadedModels = (loaded as? AppResult.Success)
                                ?.value
                                ?.map(RunningModel::name)
                                .orEmpty(),
                            latencyMillis = clock.nowMillis() - startedAt,
                            lastCheckedAtMillis = clock.nowMillis(),
                            lastError = null,
                            breaker = breaker.state(),
                            consecutiveFailures = 0,
                        )
                    }
                }

                is AppResult.Failure -> {
                    breaker.recordFailure(result.error)
                    publish(server.id) {
                        it.copy(
                            reachable = false,
                            loadedModels = emptyList(),
                            latencyMillis = null,
                            lastCheckedAtMillis = clock.nowMillis(),
                            lastError = result.error,
                            breaker = breaker.state(),
                            consecutiveFailures = breaker.failures,
                        )
                    }
                }
            }
        }

        private fun breakerFor(id: ServerId): CircuitBreaker = breakers.getOrPut(id) {
            CircuitBreaker(
                clock = clock,
                failureThreshold = config.failureThreshold,
                baseCooldownMillis = config.cooldownMillis,
                maxCooldownMillis = config.maxCooldownMillis,
            )
        }

        private fun interval(): Long =
            if (foregrounded.value) config.foregroundIntervalMillis else config.backgroundIntervalMillis

        private fun publish(id: ServerId, update: (ServerHealth) -> ServerHealth) {
            val current = mutableHealth.value
            mutableHealth.value = current + (id to update(current[id] ?: ServerHealth(id)))
        }
    }
