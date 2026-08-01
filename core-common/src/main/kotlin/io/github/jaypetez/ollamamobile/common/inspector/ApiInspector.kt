package io.github.jaypetez.ollamamobile.common.inspector

import io.github.jaypetez.ollamamobile.common.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A header as it will be shown. [value] is already redacted where required. */
data class InspectedHeader(
    val name: String,
    val value: String,
)

/**
 * One captured request/response pair.
 *
 * Bodies are stored as a bounded prefix, never in full: a model download is
 * gigabytes and a chat stream never ends, so "capture the body" has to mean
 * "capture enough of the body to see what the shape was".
 */
data class ApiExchange(
    val id: Long,
    val startedAtMillis: Long,
    val method: String,
    val url: String,
    val requestHeaders: List<InspectedHeader>,
    val requestBodyPreview: String? = null,
    val requestBodyBytes: Long? = null,
    val requestBodyTruncated: Boolean = false,
    val protocol: String? = null,
    val statusCode: Int? = null,
    val statusMessage: String? = null,
    val responseHeaders: List<InspectedHeader> = emptyList(),
    val responseBodyPreview: String? = null,
    val responseBodyTruncated: Boolean = false,
    val responseBodyOmittedReason: String? = null,
    val durationMillis: Long? = null,
    val failure: String? = null,
) {
    val isComplete: Boolean get() = statusCode != null || failure != null

    /**
     * A `curl` command reproducing the request.
     *
     * Headers come from [requestHeaders], which is already redacted, so the
     * export cannot leak a token even though a working `curl` would need one.
     * That is the correct trade: this string ends up pasted into bug reports.
     */
    fun toCurl(): String = buildString {
        append("curl -X ").append(method)
        requestHeaders.forEach { header ->
            append(" \\\n  -H ").append(shellQuote("${header.name}: ${header.value}"))
        }
        requestBodyPreview?.let { body ->
            append(" \\\n  --data-raw ").append(shellQuote(body))
            if (requestBodyTruncated) append(" # body truncated for display")
        }
        append(" \\\n  ").append(shellQuote(url))
    }

    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}

/**
 * A bounded store of recent [ApiExchange]s for the developer-tools screen.
 *
 * Bounded twice over — a capped number of exchanges, each holding only a
 * capped prefix of its bodies — because the alternative is a debugging aid that
 * causes the memory problem it is being used to investigate.
 */
@Singleton
class ApiInspector
    @Inject
    constructor() {
        private val lock = Any()
        private val exchanges = ArrayDeque<ApiExchange>()
        private val nextId = AtomicLong(1L)

        private val mutableSnapshot = MutableStateFlow<List<ApiExchange>>(emptyList())

        /** Newest first, so the UI does not have to reverse it on every emission. */
        val recorded: StateFlow<List<ApiExchange>> = mutableSnapshot.asStateFlow()

        /**
         * On in debug builds, off in release — and off in release is the part
         * that matters. Captured bodies contain prompt text, so this is a
         * privacy surface; a release build must not accumulate one because a
         * developer-tools screen was left reachable.
         */
        @Volatile
        var enabled: Boolean = BuildConfig.DEBUG

        var capacity: Int = DEFAULT_CAPACITY
            set(value) {
                require(value > 0) { "capacity must be positive, was $value" }
                field = value
                synchronized(lock) {
                    trimLocked()
                    publishLocked()
                }
            }

        fun nextExchangeId(): Long = nextId.getAndIncrement()

        /** Inserts or replaces by [ApiExchange.id]. */
        fun record(exchange: ApiExchange) {
            if (!enabled) return
            synchronized(lock) {
                val existing = exchanges.indexOfFirst { it.id == exchange.id }
                if (existing >= 0) exchanges[existing] = exchange else exchanges.addFirst(exchange)
                trimLocked()
                publishLocked()
            }
        }

        fun find(id: Long): ApiExchange? = synchronized(lock) { exchanges.firstOrNull { it.id == id } }

        fun clear() {
            synchronized(lock) {
                exchanges.clear()
                publishLocked()
            }
        }

        private fun trimLocked() {
            while (exchanges.size > capacity) exchanges.removeLast()
        }

        private fun publishLocked() {
            mutableSnapshot.value = exchanges.toList()
        }

        companion object {
            const val DEFAULT_CAPACITY: Int = 100

            /**
             * Header names whose values never leave this class intact.
             *
             * Matched case-insensitively because HTTP header names are
             * case-insensitive and `authorization` is exactly as sensitive as
             * `Authorization`. Compared against a set rather than a substring
             * so a new header cannot slip through on a partial match.
             */
            val REDACTED_HEADERS: Set<String> = setOf(
                "authorization",
                "proxy-authorization",
                "cookie",
                "set-cookie",
                "x-api-key",
                "api-key",
                "x-auth-token",
            )

            const val REDACTION: String = "REDACTED"

            /** Redacts by name, returning a header safe to store and to export. */
            fun redact(name: String, value: String): InspectedHeader = InspectedHeader(
                name = name,
                value = if (name.lowercase() in REDACTED_HEADERS) REDACTION else value,
            )
        }
    }
