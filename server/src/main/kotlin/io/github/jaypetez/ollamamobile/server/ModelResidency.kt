package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.remote.dto.RunningModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Wall clock, injectable so the expiry tests do not sleep. */
fun interface ServerClock {
    fun nowMillis(): Long

    companion object {
        val System: ServerClock = ServerClock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Which models this server currently claims to have loaded, and until when.
 *
 * ## Why this exists rather than a boolean
 *
 * `/api/ps` reports `expires_at`, and clients use it: a scheduler that wants a
 * warm model polls `/api/ps` and re-issues a tiny request before the deadline.
 * Reporting a fabricated timestamp makes that scheduler keep a model warm that
 * was evicted ten minutes ago, so the timestamp has to come from the same state
 * the eviction uses. That state is here, and [sweep] is what makes it true.
 *
 * A `keep_alive` of 0 means the entry is gone the moment the request ends;
 * a negative one means no [expiresAt] at all, which is how Ollama spells
 * "resident until the process exits".
 */
class ModelResidency(
    private val clock: ServerClock = ServerClock.System,
) {
    private val entries = ConcurrentHashMap<String, Entry>()

    private data class Entry(
        val model: ModelRef,
        /** Null means "never expires". */
        val expiresAtMillis: Long?,
    )

    /**
     * Records that [model] was just used and how long it should stay.
     *
     * Returns false when [keepAlive] is [KeepAlive.Immediate], i.e. when the
     * caller should treat the model as already unloaded — which is what makes
     * `keep_alive: 0` observable through `/api/ps` on the very next call.
     */
    fun touch(model: ModelRef, keepAlive: KeepAlive): Boolean {
        val key = model.name
        return when (keepAlive) {
            KeepAlive.Immediate -> {
                entries.remove(key)
                false
            }

            KeepAlive.Forever -> {
                entries[key] = Entry(model, expiresAtMillis = null)
                true
            }

            is KeepAlive.For -> {
                entries[key] = Entry(model, clock.nowMillis() + keepAlive.duration.inWholeMilliseconds)
                true
            }
        }
    }

    /** Drops every entry whose deadline has passed. Returns the names evicted. */
    fun sweep(): List<String> {
        val now = clock.nowMillis()
        val expired = entries.entries
            .filter { (_, entry) -> entry.expiresAtMillis != null && entry.expiresAtMillis <= now }
            .map { it.key }
        expired.forEach(entries::remove)
        return expired
    }

    /** Forgets everything, e.g. when the service stops. */
    fun clear() {
        entries.clear()
    }

    /** The `/api/ps` payload, after a [sweep] so nothing stale is reported. */
    fun running(): List<RunningModel> {
        sweep()
        return entries.values
            .sortedBy { it.model.name }
            .map { entry ->
                RunningModel(
                    name = entry.model.name,
                    model = entry.model.name,
                    size = entry.model.sizeBytes,
                    digest = entry.model.syntheticDigest(),
                    details = entry.model.toModelDetails(),
                    expiresAt = entry.expiresAtMillis?.let(::formatRfc3339),
                    // Zero, not null: this server has no GPU allocator, and
                    // Ollama reports 0 on a CPU-only host rather than omitting.
                    sizeVram = 0L,
                )
            }
    }

    private companion object {
        /**
         * Ollama emits RFC 3339 with an offset, e.g. `2026-01-01T10:00:00Z`.
         * Clients parse it with a strict parser, so UTC with the `Z` suffix is
         * the safest spelling to produce.
         */
        private val RFC_3339: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        fun formatRfc3339(epochMillis: Long): String =
            RFC_3339.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))
    }
}
