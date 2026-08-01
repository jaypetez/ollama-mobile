package io.github.jaypetez.ollamamobile.designsystem.component

/** What a [StatusDot] is saying. */
enum class StatusKind {
    /** Answered the last probe. */
    Online,

    /** Did not answer, or the circuit breaker is holding traffic back. */
    Offline,

    /** Reachable but degraded — slow, or failing intermittently. */
    Warning,

    /** Never probed, or the answer is stale. Not the same as "down". */
    Unknown,
}
