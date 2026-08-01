package io.github.jaypetez.ollamamobile.model

/**
 * Timing and token counters for one generation.
 *
 * TWO TRAPS, AND THEY ARE THE USUAL WAY THIS INTEGRATION GOES SILENTLY WRONG:
 *
 * 1. **Every field here is `omitempty` on the Ollama wire.** A response that
 *    hit the prompt cache carries no `prompt_eval_duration`; a response that
 *    only loaded a model carries no `eval_*` at all. So every field is nullable
 *    with a null default, and absent must stay distinguishable from zero — a
 *    non-null `0` default would render as "0 tok/s" for data that was never
 *    reported.
 * 2. **The durations are int64 NANOSECONDS, not millis.** Reading them as
 *    milliseconds is off by a factor of a million and still looks plausible in
 *    a log. The rates below therefore multiply by 1e9, and they return `null`
 *    rather than dividing by zero: Kotlin's Double division yields `Infinity`
 *    instead of throwing, which reaches the UI as "Infinity tok/s" rather than
 *    as a crash anyone would notice.
 *
 * The durations are also not disjoint — [totalNanos] includes [loadNanos],
 * [promptEvalNanos] and [evalNanos] plus queueing — so do not present them as a
 * breakdown that sums to the total.
 */
public data class GenerationStats(
    public val promptTokens: Int? = null,
    public val completionTokens: Int? = null,
    public val promptEvalNanos: Long? = null,
    public val evalNanos: Long? = null,
    public val loadNanos: Long? = null,
    public val totalNanos: Long? = null,
) {
    /** Token generation rate, or `null` when it cannot be computed honestly. */
    public val tokensPerSecond: Double?
        get() = rate(completionTokens, evalNanos)

    /** Prompt processing rate, or `null` when it cannot be computed honestly. */
    public val promptTokensPerSecond: Double?
        get() = rate(promptTokens, promptEvalNanos)

    /** True when the server reported nothing at all. */
    public val isEmpty: Boolean
        get() = this == Empty

    public companion object {
        /** All fields absent — the correct value for a response with no stats. */
        public val Empty: GenerationStats = GenerationStats()

        private const val NANOS_PER_SECOND: Double = 1_000_000_000.0

        private fun rate(tokens: Int?, nanos: Long?): Double? {
            if (tokens == null || tokens < 0) return null
            // `> 0` and not `!= 0`: a zero duration is division by zero, and a
            // negative one is a server bug we must not turn into a number.
            if (nanos == null || nanos <= 0L) return null
            return tokens.toDouble() / nanos.toDouble() * NANOS_PER_SECOND
        }
    }
}
