package io.github.jaypetez.ollamamobile.llm.internal

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single lane that all on-device inference has to drive down.
 *
 * ## Why serialise at all
 *
 * A `llama_context` is not thread-safe, and even with two contexts — a chat
 * model and an embedding model, both resident for RAG — running them at once is
 * worse than running them in sequence. They contend for the same cores and the
 * same memory bandwidth, so two concurrent generations do not each run at half
 * speed; they run at rather less than half, and the phone gets hot enough to
 * throttle both. Sequential access is the fast answer as well as the safe one.
 *
 * ## Why background work is preempted rather than queued
 *
 * Indexing a document set for RAG is minutes of work. If the user sends a
 * message while it runs, a fair queue makes them wait for those minutes. So
 * [Priority.BACKGROUND] holders are *cancelled* when an [Priority.INTERACTIVE]
 * caller arrives, and background work is expected to be resumable — an indexer
 * checkpoints per document and picks up where it stopped.
 *
 * [Priority.EMBEDDING] is not preempted: it is a single short query embedding
 * that a chat turn is usually itself waiting on.
 */
@Singleton
internal class InferenceArbiter
    @Inject
    constructor() {
        enum class Priority {
            /** A user is watching. Preempts [BACKGROUND]. */
            INTERACTIVE,

            /** A query embedding, on the critical path of an interactive request. */
            EMBEDDING,

            /** Bulk indexing. Cancellable, and expected to be resumable. */
            BACKGROUND,
        }

        /** Thrown into background work that an interactive request displaced. */
        class Preempted : CancellationException("Preempted by an interactive request")

        private val mutex = Mutex()
        private val backgroundJob = AtomicReference<Job?>(null)
        private val _holder = MutableStateFlow<Priority?>(null)

        /** Who holds the engine right now. Exposed for the developer tools screen. */
        val holder: StateFlow<Priority?> = _holder.asStateFlow()

        /**
         * Runs [block] with exclusive access.
         *
         * Throws [Preempted] out of a [Priority.BACKGROUND] block that an
         * interactive caller displaced. That is a [CancellationException], so a
         * caller that does not catch it simply stops, which is the right default.
         */
        suspend fun <T> withAccess(priority: Priority, block: suspend () -> T): T {
            if (priority == Priority.INTERACTIVE) {
                // Before taking the lock, not after: the point is to make the
                // current holder let go, and we cannot acquire until it has.
                backgroundJob.getAndSet(null)?.cancel(Preempted())
            }
            return mutex.withLock {
                _holder.value = priority
                try {
                    if (priority == Priority.BACKGROUND) runPreemptible(block) else block()
                } finally {
                    _holder.value = null
                }
            }
        }

        /**
         * Runs [block] in a child scope whose [Job] is published, so
         * [withAccess] can cancel exactly this block without touching the caller's
         * own job — cancelling that would take down whatever else the indexer is
         * doing alongside the engine work.
         */
        private suspend fun <T> runPreemptible(block: suspend () -> T): T = coroutineScope {
            val job = currentCoroutineContext()[Job]
            backgroundJob.set(job)
            try {
                block()
            } finally {
                backgroundJob.compareAndSet(job, null)
            }
        }
    }
