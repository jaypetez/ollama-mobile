package io.github.jaypetez.ollamamobile.llm

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.SamplingParams

/**
 * The streaming protocol every backend emits, local or remote.
 *
 * ## Failure is an event, not an exception
 *
 * A remote generation can fail *after* HTTP 200: the server sends one more
 * NDJSON line carrying `{"error": ...}` and closes. At the transport layer
 * "the stream ended" and "the generation failed" are therefore the same
 * observation, and they must not be the same observation here. A collector
 * that has to `when` over the event type cannot accidentally present a failed
 * generation as a finished answer — which is exactly what happens when the
 * error is a `catch` somebody forgot to write.
 *
 * A well-behaved [InferenceGateway] therefore ends every stream with exactly
 * one of [Completed] or [Failed], including when the underlying transport
 * simply stopped talking.
 *
 * ## Token deltas are raw
 *
 * [Token.text] is exactly what the backend produced, with nothing removed.
 * **The consumer must apply the stop-sequence hold-back** described by
 * [SamplingParams.stopHoldBackChars] before painting anything: a stop sequence
 * is matched against decoded text, not tokens, so `<|im_end|>` legitimately
 * arrives as `"<|im_"` then `"end|>"`. A consumer that renders each delta on
 * arrival has already painted `<|im_` by the time it recognises the sequence,
 * and erasing it again is a visible flash on screen.
 *
 * [StopSequenceFilter] implements the hold-back; use it rather than
 * re-deriving the buffering rule, which is easy to get subtly wrong in the
 * direction of leaking one character.
 */
public sealed interface InferenceEvent {
    /**
     * Routing is done and the request is on its way.
     *
     * Always the first event. Emitted before any network I/O completes, so the
     * UI can name the machine while the model is still loading.
     */
    public data class Started(
        public val target: InferenceTarget,
    ) : InferenceEvent

    /** Answer text. A delta, never cumulative, never pre-filtered. See the class doc. */
    public data class Token(
        public val text: String,
    ) : InferenceEvent

    /**
     * Chain-of-thought from a reasoning model, already separated from [Token].
     *
     * Emitted only when [InferenceRequest.wantReasoning] was set and the
     * backend produced any. Never interleaved into [Token]: the UI collapses
     * this by default and excludes it when replaying the thread as context.
     */
    public data class Reasoning(
        public val text: String,
    ) : InferenceEvent

    /**
     * A complete tool call. Backends that fragment these across chunks
     * reassemble them first, so a consumer never sees a partial one.
     */
    public data class ToolCall(
        public val call: ToolInvocation,
    ) : InferenceEvent

    /**
     * Counters for this generation.
     *
     * Emitted **only when the backend actually reported something**. There is
     * no "stats event with zeroes": every field of [GenerationStats] is absent
     * on the wire when the server did not measure it, and a zero would render
     * as "0 tok/s" for data nobody ever collected. A consumer that receives no
     * [Stats] shows no throughput, which is the honest outcome.
     */
    public data class Stats(
        public val stats: GenerationStats,
    ) : InferenceEvent

    /** The generation finished normally. Terminal. */
    public data class Completed(
        public val reason: FinishReason,
    ) : InferenceEvent

    /**
     * The generation failed. Terminal, and nothing is emitted after it.
     *
     * Any [Token]s already emitted stand: a partial answer plus an explanation
     * is more useful than a blank bubble, and the persisted turn keeps them.
     */
    public data class Failed(
        public val error: AppError,
    ) : InferenceEvent
}

/**
 * Why generation stopped.
 *
 * [LENGTH] is the one that has to reach the user: a truncated answer looks
 * exactly like a finished one, and the difference is whether "continue" is the
 * right next action.
 */
public enum class FinishReason {
    /** The model emitted an end-of-turn token or hit a stop sequence. */
    STOP,

    /** The token budget or the context window ran out. The answer is truncated. */
    LENGTH,

    /** The model asked to call tools and is waiting for the results. */
    TOOL_CALLS,

    /** The backend's own safety filter cut the generation short. */
    CONTENT_FILTER,

    /** The caller or its scope cancelled. Not a fault; never shown as an error. */
    CANCELLED,

    /** The backend ended the stream without saying why. */
    UNKNOWN,
}
