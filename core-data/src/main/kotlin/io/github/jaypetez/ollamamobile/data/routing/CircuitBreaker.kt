package io.github.jaypetez.ollamamobile.data.routing

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.remote.health.WallClock

/** Where one server's breaker is. */
enum class BreakerState {
    /** Normal. Requests go through and failures are counted. */
    CLOSED,

    /** Too many failures in a row: nothing is sent until the cooldown expires. */
    OPEN,

    /** The cooldown expired. Traffic is allowed again and the next failure re-opens immediately. */
    HALF_OPEN,
}

/** A read-only view of one server's breaker, for a diagnostics screen. */
data class BreakerStatus(
    val serverId: ServerId,
    val state: BreakerState,
    val consecutiveFailures: Int,
    /** Epoch millis at which [BreakerState.OPEN] becomes [BreakerState.HALF_OPEN]. Null when closed. */
    val retryAtMillis: Long? = null,
)

/**
 * Per-server circuit breaking for the router.
 *
 * ## What this is for
 *
 * Not protecting the server — a Raspberry Pi is not going to be overwhelmed by
 * one phone. It protects *every other request* from a dead one. Without it, a
 * server that has been unplugged adds a full connect timeout to the front of
 * every chat turn while the router keeps optimistically trying it, which the
 * user experiences as the whole app being slow rather than as one server being
 * down.
 *
 * ## The decay
 *
 * Two things decay, and they answer different questions.
 *
 * **The failure count** resets when [Config.failureDecayMillis] passes with no
 * new failure. Otherwise a server that fails once a day trips after a
 * fortnight — the failures were never *consecutive* in any sense the user
 * would recognise, and the breaker would open on a machine that is basically
 * fine.
 *
 * **The cooldown** doubles each time the breaker re-opens from half-open, and
 * drops back to [Config.baseCooldownMillis] after a success. A server that has
 * been down for an hour should not be probed every thirty seconds for an hour;
 * a server that blipped once should not be sidelined for ten minutes.
 *
 * ## Why half-open lets everything through
 *
 * A half-open window admitting exactly one probe needs a release on every exit
 * path — success, failure, cancellation, an exception from a layer below. Miss
 * one and the breaker is wedged half-open forever, which is a far worse
 * failure than the cost of what this does instead: allow requests and re-open
 * on the first failure, bypassing the threshold. The worst case is a few
 * wasted round trips per cooldown.
 *
 * ## Why this is not `:core-remote`'s breaker
 *
 * That one paces *health probes* on a timer this class knows nothing about;
 * this one gates *user requests*. Sharing one instance would mean a failed
 * chat turn silenced the health poll that is supposed to notice the server
 * coming back.
 *
 * Constructed by `DataModule` rather than with `@Inject`: [Config] has a
 * default value, and Dagger cannot satisfy a defaulted constructor parameter —
 * it sees the two-argument JVM constructor and looks for a binding that does
 * not exist.
 */
class CircuitBreaker(
    private val clock: WallClock,
    private val config: Config = Config(),
) {
    data class Config(
        /** Consecutive failures before the breaker opens. */
        val failureThreshold: Int = 3,
        /** First cooldown. Doubles on each re-open, capped by [maxCooldownMillis]. */
        val baseCooldownMillis: Long = 30_000L,
        val maxCooldownMillis: Long = 600_000L,
        /** Quiet period after which the failure count is forgotten. */
        val failureDecayMillis: Long = 120_000L,
    )

    private data class Entry(
        val consecutiveFailures: Int = 0,
        val lastFailureAtMillis: Long = 0L,
        val openedAtMillis: Long? = null,
        val cooldownMillis: Long = 0L,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<ServerId, Entry>()

    /** True when a request to [serverId] may be attempted now. */
    fun allows(serverId: ServerId): Boolean = state(serverId) != BreakerState.OPEN

    fun state(serverId: ServerId): BreakerState = synchronized(lock) { stateOf(entries[serverId]) }

    fun status(serverId: ServerId): BreakerStatus = synchronized(lock) {
        val entry = entries[serverId]
        BreakerStatus(
            serverId = serverId,
            state = stateOf(entry),
            consecutiveFailures = decayed(entry)?.consecutiveFailures ?: 0,
            retryAtMillis = entry?.openedAtMillis?.plus(entry.cooldownMillis),
        )
    }

    fun snapshot(): Map<ServerId, BreakerState> = synchronized(lock) {
        entries.keys.associateWith { stateOf(entries[it]) }
    }

    /** Closes the breaker and resets the cooldown escalation. */
    fun onSuccess(serverId: ServerId) {
        synchronized(lock) { entries.remove(serverId) }
    }

    /**
     * Counts a failure.
     *
     * @return true when it counted — see [trips]. A request-level failure
     *   returns false and leaves the breaker exactly as it was.
     */
    fun onFailure(serverId: ServerId, error: AppError): Boolean = synchronized(lock) {
        if (!trips(error)) return false
        val now = clock.nowMillis()
        val previous = entries[serverId]
        val wasHalfOpen = stateOf(previous) == BreakerState.HALF_OPEN
        val current = decayed(previous) ?: Entry()
        val failures = current.consecutiveFailures + 1

        entries[serverId] = if (wasHalfOpen || failures >= config.failureThreshold) {
            val cooldown = if (current.openedAtMillis == null) {
                config.baseCooldownMillis
            } else {
                // Re-opening after a half-open probe failed: the server had its
                // chance, so wait twice as long before offering another.
                (current.cooldownMillis * 2).coerceAtMost(config.maxCooldownMillis)
            }
            Entry(failures, now, openedAtMillis = now, cooldownMillis = cooldown)
        } else {
            current.copy(consecutiveFailures = failures, lastFailureAtMillis = now)
        }
        true
    }

    /** Forgets a server entirely. Call when the user deletes or edits one. */
    fun forget(serverId: ServerId) {
        synchronized(lock) { entries.remove(serverId) }
    }

    fun reset() {
        synchronized(lock) { entries.clear() }
    }

    private fun stateOf(entry: Entry?): BreakerState {
        val openedAt = entry?.openedAtMillis ?: return BreakerState.CLOSED
        return if (clock.nowMillis() - openedAt >= entry.cooldownMillis) {
            BreakerState.HALF_OPEN
        } else {
            BreakerState.OPEN
        }
    }

    /**
     * Drops a stale failure count.
     *
     * Only while the breaker is closed: once it is open the count is what
     * decides how far the cooldown has escalated, and forgetting it mid-outage
     * would restart the backoff from the beginning.
     */
    private fun decayed(entry: Entry?): Entry? = when {
        entry == null -> null
        entry.openedAtMillis != null -> entry
        clock.nowMillis() - entry.lastFailureAtMillis >= config.failureDecayMillis -> null
        else -> entry
    }

    /**
     * Whether a failure says anything about the *server*.
     *
     * A 404 for a model that does not exist and a 400 for a malformed request
     * are problems with the request. Counting those would let one bad prompt
     * disable a perfectly healthy machine for ten minutes.
     */
    private fun trips(error: AppError): Boolean = when (error) {
        is AppError.Network.Timeout,
        is AppError.Network.Unreachable,
        is AppError.Network.Tls,
        -> true

        // The server is up and saying it is saturated. Continuing to send
        // deepens the queue, so it counts.
        is AppError.Network.QueueFull -> true

        is AppError.Network.Http -> error.code >= HTTP_SERVER_ERROR

        else -> false
    }

    private companion object {
        const val HTTP_SERVER_ERROR = 500
    }
}
