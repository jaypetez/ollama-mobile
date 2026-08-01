package io.github.jaypetez.ollamamobile.model

/**
 * Sampling settings for one request.
 *
 * Every field is nullable and every null means "whatever the server or engine
 * defaults to" — not "zero". Sending an explicit `temperature = 0.0` because a
 * non-null default happened to be zero is a different request from sending no
 * temperature at all, and the difference is a model that stops being creative
 * for reasons nobody configured. Omit what the user has not set.
 */
public data class SamplingParams(
    public val temperature: Double? = null,
    public val topP: Double? = null,
    public val topK: Int? = null,
    public val minP: Double? = null,
    public val repeatPenalty: Double? = null,
    public val repeatLastN: Int? = null,
    public val seed: Long? = null,
    public val numPredict: Int? = null,
    public val numCtx: Int? = null,
    public val stop: List<String> = emptyList(),
) {
    /**
     * How many trailing characters a streaming consumer must withhold before
     * emitting text to the UI.
     *
     * A stop sequence is matched against the decoded text, not against tokens,
     * so it can straddle a token boundary: with `stop = ["<|im_end|>"]` the
     * model may emit `"<|im_"` then `"end|>"`. A consumer that renders each
     * delta on arrival has already painted `<|im_` on screen by the time it
     * recognises the sequence, and deleting it again produces a visible flash.
     *
     * Holding back `max(stop.length) - 1` characters is exactly enough: any
     * prefix of a stop sequence that could still complete is shorter than the
     * sequence itself. Flush the withheld tail when the stream ends normally.
     */
    public val stopHoldBackChars: Int
        get() = ((stop.maxOfOrNull { it.length } ?: 0) - 1).coerceAtLeast(0)

    public companion object {
        /** Everything unset: the engine's own defaults apply. */
        public val Default: SamplingParams = SamplingParams()
    }
}
