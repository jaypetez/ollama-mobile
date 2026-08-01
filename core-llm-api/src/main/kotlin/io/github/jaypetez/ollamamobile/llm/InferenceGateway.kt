package io.github.jaypetez.ollamamobile.llm

import io.github.jaypetez.ollamamobile.model.ModelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one way anything in this app runs inference.
 *
 * ## Why this interface is declared here and not in :core-data
 *
 * `:server` — the embedded Ollama-compatible HTTP server — has to call
 * inference, and it must not be able to reach `:core-data`, `:core-storage`,
 * `:core-download` or `:core-llm`. `checkModuleGraph` fails the build on any
 * of those four edges, so a gateway declared next to its implementation would
 * make the server unbuildable. Declaring the contract in this pure-JVM module
 * is what lets `:server` depend on the *shape* of inference while `:app`
 * decides at assembly time which implementation is behind it.
 *
 * The same property is why this module has no Android types, no Room, no
 * OkHttp and no serialization runtime: every dependency added here is one that
 * a host of the server inherits.
 *
 * ## Contract
 *
 * [chat] returns a **cold** flow. Nothing is sent until collection starts, and
 * cancelling the collector cancels the underlying work. It does not throw:
 * every failure arrives as [InferenceEvent.Failed] and the stream then ends.
 * The only exception that ever crosses this boundary is
 * `CancellationException`, which must propagate for structured concurrency to
 * work.
 *
 * Every stream begins with exactly one [InferenceEvent.Started] and ends with
 * exactly one [InferenceEvent.Completed] or [InferenceEvent.Failed].
 */
public interface InferenceGateway {
    /**
     * Runs one turn.
     *
     * Routing happens on subscription, so a request made while every server is
     * down fails on collection rather than at construction — the caller has one
     * place to handle failure, not two.
     */
    public fun chat(request: InferenceRequest): Flow<InferenceEvent>

    /**
     * Every model the app can currently run, across every enabled backend.
     *
     * Does not throw and does not report per-backend failure: a server that is
     * down contributes nothing rather than failing the whole list, because a
     * model picker that shows nothing when one of three Pis is asleep is worse
     * than one that shows the other two. Reachability is [reachableTargets].
     */
    public suspend fun listAvailableModels(): List<ModelRef>

    /**
     * The targets a request could be routed to *right now*.
     *
     * A [StateFlow] rather than a suspend function because it is read to
     * decide whether the send button is enabled, which happens on every
     * recomposition and cannot await I/O. Empty means every backend is
     * unreachable or none is configured — the UI has to distinguish those two
     * by asking the server repository, since this flow cannot.
     */
    public val reachableTargets: StateFlow<List<InferenceTarget>>
}
