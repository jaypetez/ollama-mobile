package io.github.jaypetez.ollamamobile.llm

/**
 * Which targets a router is allowed to pick, and in what order of preference.
 *
 * Each value below is defined by what an observer can *see happen* — which
 * target a request lands on, and what error comes back when none is usable.
 * None of them is defined by an intention, because a setting the user cannot
 * verify from the app's behaviour is a setting they cannot trust.
 *
 * A note that applies to every value: there is no on-device engine in this
 * build. Nothing constructs an [InferenceTarget.Local], so the behaviours
 * below that mention a local target describe what happens once one exists, and
 * are reachable today only in a test that supplies one.
 */
public enum class RoutingPolicy {
    /**
     * Only [InferenceTarget.Local] is ever chosen.
     *
     * Remote servers are not contacted even when one is reachable and has the
     * model. With no local engine present every request fails immediately with
     * [AppError.Engine.NotAvailable][io.github.jaypetez.ollamamobile.model.AppError.Engine.NotAvailable]
     * and nothing leaves the device.
     */
    LOCAL_ONLY,

    /**
     * Only [InferenceTarget.Remote] is ever chosen.
     *
     * The best-scoring reachable server that serves the requested model wins.
     * When no configured server has it the request fails with
     * [AppError.Model.NotFound][io.github.jaypetez.ollamamobile.model.AppError.Model.NotFound];
     * when one has it but none is reachable, with
     * [AppError.Network.Unreachable][io.github.jaypetez.ollamamobile.model.AppError.Network.Unreachable].
     */
    REMOTE_ONLY,

    /**
     * A local target if there is one; otherwise a remote one.
     *
     * The remote fallback is silent — the request succeeds and
     * [InferenceEvent.Started] names the server it went to, which is how the
     * user can tell it did not run on the device.
     */
    PREFER_LOCAL,

    /**
     * A remote target if any reachable server serves the model; otherwise a
     * local one.
     *
     * With every server unreachable and no local engine, the failure is the
     * remote one — [AppError.Network.Unreachable][io.github.jaypetez.ollamamobile.model.AppError.Network.Unreachable]
     * — and not an engine error, because the remote path is the one that was
     * actually attempted.
     */
    PREFER_REMOTE,

    /**
     * Whichever candidate scores highest, across both kinds.
     *
     * Scoring prefers a reachable server over an unreachable one, a closed
     * circuit breaker over a half-open one, and lower measured latency over
     * higher. **In this build AUTO and [PREFER_REMOTE] select the same target
     * for every request**, because the candidate set never contains a local
     * entry; the two stop being interchangeable the day an engine ships.
     */
    AUTO,
    ;

    /** False only for [REMOTE_ONLY]. */
    public val allowsLocal: Boolean
        get() = this != REMOTE_ONLY

    /** False only for [LOCAL_ONLY]. */
    public val allowsRemote: Boolean
        get() = this != LOCAL_ONLY

    /**
     * Whether a usable local target beats a usable remote one.
     *
     * False for [AUTO]: AUTO ranks by score rather than by kind, so it has no
     * standing preference to express here.
     */
    public val prefersLocal: Boolean
        get() = this == LOCAL_ONLY || this == PREFER_LOCAL

    public companion object {
        /**
         * What a fresh install routes with.
         *
         * [AUTO] rather than [PREFER_REMOTE] even though they behave
         * identically today: persisting the weaker of two equivalent settings
         * would silently pin every existing user to remote-only behaviour on
         * the day a local engine lands.
         */
        public val Default: RoutingPolicy = AUTO

        /** Parses a persisted name, returning null for anything unrecognised. */
        public fun fromNameOrNull(name: String?): RoutingPolicy? =
            entries.firstOrNull { it.name == name?.trim()?.uppercase() }
    }
}
