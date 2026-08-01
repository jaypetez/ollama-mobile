package io.github.jaypetez.ollamamobile.llm

import io.github.jaypetez.ollamamobile.model.SamplingParams

/**
 * The stop-sequence hold-back every consumer of [InferenceEvent.Token] owes.
 *
 * Implemented once, here, because the rule is short and the failure mode of
 * getting it wrong is invisible in review: hold back one character too few and
 * the last character of a stop sequence flashes on screen, which nobody
 * reproduces on demand and nobody writes a test for after the fact.
 *
 * ## The rule
 *
 * Withhold [SamplingParams.stopHoldBackChars] — `max(stop.length) - 1` —
 * trailing characters from every delta. Any prefix of a stop sequence that
 * could still complete is strictly shorter than the sequence itself, so that
 * is exactly enough and no more. When the stream ends normally, [flush] the
 * withheld tail: a partial match that never completed is real answer text.
 *
 * Not thread-safe, and does not need to be: one filter belongs to one stream,
 * which is collected by one coroutine.
 */
public class StopSequenceFilter(
    private val stopSequences: List<String>,
    private val holdBackChars: Int,
) {
    public constructor(sampling: SamplingParams) : this(
        stopSequences = sampling.stop.filter { it.isNotEmpty() },
        holdBackChars = sampling.stopHoldBackChars,
    )

    private val pending = StringBuilder()

    private var stopHit = false

    /**
     * True once a stop sequence has been seen.
     *
     * Everything after it — including the sequence itself — is suppressed.
     * A caller may also use this to cancel the upstream early, which is worth
     * doing when a server ignored the stop list it was given.
     */
    public val isStopped: Boolean
        get() = stopHit

    /** Text withheld so far, for a caller that wants to size a buffer. */
    public val heldBackLength: Int
        get() = pending.length

    /**
     * Feeds one raw delta in and returns the text that is safe to display now,
     * which is frequently `""`.
     */
    public fun push(delta: String): String {
        if (stopHit) return ""
        pending.append(delta)
        val text = pending.toString()

        val hit = earliestStopIndex(text)
        if (hit >= 0) {
            stopHit = true
            pending.setLength(0)
            return text.take(hit)
        }

        // Retaining the tail is what makes the next call's scan complete: a
        // stop sequence that begins before the retained region would have to be
        // longer than the longest one there is, so it cannot exist.
        val keep = holdBackChars.coerceAtMost(text.length)
        pending.setLength(0)
        pending.append(text, text.length - keep, text.length)
        return text.substring(0, text.length - keep)
    }

    /**
     * Releases the withheld tail at the end of a stream and resets the buffer.
     *
     * Returns `""` when a stop sequence was hit, because everything still held
     * at that point was part of it or came after it.
     */
    public fun flush(): String {
        if (stopHit) {
            pending.setLength(0)
            return ""
        }
        val remainder = pending.toString()
        pending.setLength(0)
        return remainder
    }

    /**
     * The index of the *earliest* stop sequence in [text], or -1.
     *
     * Earliest and not first-configured: with `stop = ["\n\n", "END"]` against
     * `"a END b\n\n"`, taking the first configured match would keep `"a END b"`
     * and emit text the user asked to have cut.
     */
    private fun earliestStopIndex(text: String): Int {
        var earliest = -1
        for (sequence in stopSequences) {
            val index = text.indexOf(sequence)
            if (index >= 0 && (earliest < 0 || index < earliest)) earliest = index
        }
        return earliest
    }
}
