package io.github.jaypetez.ollamamobile.server

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore

/**
 * A bounded admission gate in front of inference.
 *
 * ## Why bounded, and why the bound is on *waiters*
 *
 * A `Semaphore` alone limits concurrency but not the queue: every request over
 * the limit suspends, and each suspended request holds its parsed body, its
 * coroutine frame and its socket. A client that opens two hundred streams and
 * walks away therefore costs two hundred held request bodies, indefinitely.
 * That is the failure this class exists to prevent, so the counter below counts
 * *pending* requests and refuses beyond [ServerConfig.maxQueuedRequests] before
 * anything suspends.
 *
 * The refusal is Ollama's own 503 body ([ServerErrors.MAX_QUEUE]) because
 * clients match on it.
 */
class RequestAdmission(
    private val maxConcurrent: Int,
    private val maxQueued: Int,
) {
    constructor(config: ServerConfig) : this(config.maxConcurrentRequests, config.maxQueuedRequests)

    private val permits = Semaphore(maxConcurrent)

    /**
     * In-flight plus waiting. Incremented *before* the check so two racing
     * arrivals cannot both see room for the last slot.
     */
    private val outstanding = AtomicInteger(0)

    private val peakOutstanding = AtomicInteger(0)

    /** How many requests are currently running or waiting. */
    val inFlight: Int
        get() = outstanding.get()

    /** The high-water mark, for the diagnostics screen. */
    val peak: Int
        get() = peakOutstanding.get()

    /**
     * Runs [block] with a permit held, or returns null when the queue is full.
     *
     * Null rather than an exception: "the server is busy" is an ordinary
     * outcome with a specific HTTP shape, and making the caller catch would put
     * the 503 body in a `catch` block where a later refactor loses it.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T? {
        val depth = outstanding.incrementAndGet()
        if (depth > maxConcurrent + maxQueued) {
            outstanding.decrementAndGet()
            return null
        }
        peakOutstanding.updateAndGet { previous -> maxOf(previous, depth) }
        try {
            permits.acquire()
            try {
                return block()
            } finally {
                permits.release()
            }
        } finally {
            outstanding.decrementAndGet()
        }
    }
}
