package io.github.jaypetez.ollamamobile.common.log

/** Severity, mirroring `android.util.Log` priorities so a Timber tree maps 1:1. */
enum class LogLevel(
    val priority: Int,
    val initial: Char,
) {
    VERBOSE(2, 'V'),
    DEBUG(3, 'D'),
    INFO(4, 'I'),
    WARN(5, 'W'),
    ERROR(6, 'E'),
    ASSERT(7, 'A'),
    ;

    companion object {
        private val BY_PRIORITY = entries.associateBy { it.priority }

        /** Unknown priorities are treated as [DEBUG] rather than dropped. */
        fun fromPriority(priority: Int): LogLevel = BY_PRIORITY[priority] ?: DEBUG
    }
}

/**
 * One structured log line.
 *
 * The [throwable] is kept by reference rather than pre-formatted: the ring sits
 * on the hot path of every log call, and rendering a stack trace to a string
 * costs far more than storing a pointer. Only the log viewer and the crash
 * reporter pay that cost, and only for the records they actually show.
 */
data class LogRecord(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String?,
    val message: String,
    val throwable: Throwable? = null,
)

/**
 * A bounded in-memory ring of [LogRecord]s, read by the in-app log viewer and
 * by the crash reporter.
 *
 * Fixed capacity and a plain array: the buffer is allocated once at
 * construction and [add] allocates nothing at all, so logging cannot itself
 * cause the GC pressure someone is trying to diagnose. Overwriting the oldest
 * entry is the point — an unbounded log is a memory leak with a friendly name.
 */
class LogRing(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val buffer = arrayOfNulls<LogRecord>(capacity)

    /** Index of the next slot to write. */
    private var writeIndex = 0
    private var count = 0
    private var evicted = 0L

    /** Records currently held, never more than [capacity]. */
    val size: Int get() = synchronized(lock) { count }

    /** How many records have been overwritten since construction. */
    val evictedCount: Long get() = synchronized(lock) { evicted }

    fun add(record: LogRecord) {
        synchronized(lock) {
            if (count == capacity) evicted++
            buffer[writeIndex] = record
            writeIndex = (writeIndex + 1) % capacity
            if (count < capacity) count++
        }
    }

    /**
     * A stable oldest-first copy.
     *
     * Copies under the lock so a reader never observes a half-overwritten ring,
     * and so the caller can iterate without holding it. This is the one
     * allocating operation, which is why it is not called per log line.
     */
    fun snapshot(): List<LogRecord> = synchronized(lock) {
        val out = ArrayList<LogRecord>(count)
        val start = (writeIndex - count + capacity) % capacity
        for (offset in 0 until count) {
            buffer[(start + offset) % capacity]?.let(out::add)
        }
        out
    }

    /** The [limit] most recent records, oldest-first. */
    fun recent(limit: Int): List<LogRecord> {
        val all = snapshot()
        return if (all.size <= limit) all else all.subList(all.size - limit, all.size)
    }

    fun clear() {
        synchronized(lock) {
            buffer.fill(null)
            writeIndex = 0
            count = 0
            evicted = 0L
        }
    }

    companion object {
        /**
         * Roughly the last few minutes of a chatty session: large enough for a
         * crash report to be useful, small enough that the retained
         * `Throwable`s cannot dominate the heap.
         */
        const val DEFAULT_CAPACITY: Int = 512
    }
}
