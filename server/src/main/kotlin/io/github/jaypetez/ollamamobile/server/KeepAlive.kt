package io.github.jaypetez.ollamamobile.server

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * How long a model stays resident after a request, as Ollama spells it.
 *
 * `keep_alive` arrives as either a Go duration string (`"5m"`, `"1h30m"`), a
 * number of seconds (`300`), `0` (unload immediately) or a negative value
 * (never unload). All four appear in the wild — the Python client passes
 * whatever the caller gave it — so all four are parsed here rather than at four
 * call sites.
 */
sealed interface KeepAlive {
    /** Unload as soon as the request finishes. `keep_alive: 0`. */
    data object Immediate : KeepAlive

    /** Stay loaded until the process dies. Any negative duration. */
    data object Forever : KeepAlive

    data class For(
        val duration: Duration,
    ) : KeepAlive

    companion object {
        /**
         * Parses the wire value, falling back to [default] for null or garbage.
         *
         * Garbage falls back rather than failing the request: a client that
         * sends `keep_alive: "soon"` still wants an answer, and refusing the
         * whole generation over an unparsable hint would be the wrong trade.
         */
        fun parse(element: JsonElement?, default: Duration): KeepAlive {
            val primitive = element as? JsonPrimitive ?: return For(default)
            val raw = primitive.content.trim()
            if (raw.isEmpty()) return For(default)

            raw.toLongOrNull()?.let { return ofSeconds(it) }
            raw.toDoubleOrNull()?.let { return ofSeconds(it.toLong()) }
            return parseGoDuration(raw) ?: For(default)
        }

        private fun ofSeconds(seconds: Long): KeepAlive = when {
            seconds < 0L -> Forever
            seconds == 0L -> Immediate
            else -> For(seconds.seconds)
        }

        /**
         * Go's `time.ParseDuration` subset that matters: an optional sign then
         * one or more `<number><unit>` pairs.
         *
         * Only the units Ollama documents are accepted. `ms`, `us` and `ns` are
         * included because a client computing a duration programmatically emits
         * them, and rounding one down to zero would silently mean "unload now".
         */
        @Suppress("ReturnCount")
        private fun parseGoDuration(text: String): KeepAlive? {
            var index = 0
            var negative = false
            if (text.startsWith('-')) {
                negative = true
                index = 1
            } else if (text.startsWith('+')) {
                index = 1
            }
            if (index >= text.length) return null

            var totalNanos = 0.0
            var matchedAny = false
            while (index < text.length) {
                val numberStart = index
                while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
                if (index == numberStart) return null
                val value = text.substring(numberStart, index).toDoubleOrNull() ?: return null

                val unitStart = index
                while (index < text.length && !text[index].isDigit() && text[index] != '.') index++
                val unit = text.substring(unitStart, index)
                val nanosPerUnit = UNIT_NANOS[unit] ?: return null

                totalNanos += value * nanosPerUnit
                matchedAny = true
            }
            if (!matchedAny) return null

            val nanos = totalNanos.toLong()
            return when {
                negative -> Forever
                nanos == 0L -> Immediate
                else -> For(nanos.nanoseconds)
            }
        }

        private val UNIT_NANOS: Map<String, Double> = mapOf(
            "ns" to 1.0,
            "us" to 1_000.0,
            "µs" to 1_000.0,
            "ms" to 1_000_000.0,
            "s" to 1_000_000_000.0,
            "m" to 60_000_000_000.0,
            "h" to 3_600_000_000_000.0,
        )
    }
}
