package io.github.jaypetez.ollamamobile.llm.internal

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The mutex that keeps chat, embedding and background indexing off each other's
 * toes.
 *
 * Every test below runs on `runTest`'s virtual clock, so "a two-minute indexing
 * job" costs nothing and the interleavings are deterministic rather than
 * dependent on how loaded the machine is.
 */
class InferenceArbiterTest {
    @Test
    fun `two callers do not overlap`() = runTest {
        val arbiter = InferenceArbiter()
        val order = mutableListOf<String>()

        val first = launch {
            arbiter.withAccess(InferenceArbiter.Priority.EMBEDDING) {
                order += "first-in"
                delay(1_000)
                order += "first-out"
            }
        }
        val second = launch {
            arbiter.withAccess(InferenceArbiter.Priority.EMBEDDING) {
                order += "second-in"
                order += "second-out"
            }
        }
        first.join()
        second.join()

        assertThat(order)
            .containsExactly("first-in", "first-out", "second-in", "second-out")
            .inOrder()
    }

    @Test
    fun `an interactive request preempts background indexing`() = runTest {
        val arbiter = InferenceArbiter()
        val indexingStarted = CompletableDeferred<Unit>()
        var indexingFinished = false

        val indexing = launch {
            runCatching {
                arbiter.withAccess(InferenceArbiter.Priority.BACKGROUND) {
                    indexingStarted.complete(Unit)
                    delay(10 * 60 * 1_000)
                    indexingFinished = true
                }
            }
        }
        indexingStarted.await()

        val answered = async {
            arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) { "answer" }
        }

        assertThat(answered.await()).isEqualTo("answer")
        assertThat(indexingFinished).isFalse()
        indexing.join()
    }

    @Test
    fun `preempted background work is told why`() = runTest {
        val arbiter = InferenceArbiter()
        val started = CompletableDeferred<Unit>()
        var caught: Throwable? = null

        val indexing = launch {
            try {
                arbiter.withAccess(InferenceArbiter.Priority.BACKGROUND) {
                    started.complete(Unit)
                    delay(Long.MAX_VALUE / 2)
                }
            } catch (error: InferenceArbiter.Preempted) {
                caught = error
            }
        }
        started.await()

        arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) { }
        indexing.join()

        assertThat(caught).isInstanceOf(InferenceArbiter.Preempted::class.java)
    }

    @Test
    fun `an embedding request waits for background work instead of killing it`() = runTest {
        // A query embedding is short and usually on the critical path of a chat
        // turn, but it is not a reason to throw away minutes of indexing.
        val arbiter = InferenceArbiter()
        val started = CompletableDeferred<Unit>()
        var indexingFinished = false

        val indexing = launch {
            arbiter.withAccess(InferenceArbiter.Priority.BACKGROUND) {
                started.complete(Unit)
                delay(1_000)
                indexingFinished = true
            }
        }
        started.await()

        val embedding = launch {
            arbiter.withAccess(InferenceArbiter.Priority.EMBEDDING) {
                assertThat(indexingFinished).isTrue()
            }
        }

        indexing.join()
        embedding.join()
        assertThat(indexingFinished).isTrue()
    }

    @Test
    fun `the holder is reported while work runs and cleared afterwards`() = runTest {
        val arbiter = InferenceArbiter()
        assertThat(arbiter.holder.value).isNull()

        arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) {
            assertThat(arbiter.holder.value).isEqualTo(InferenceArbiter.Priority.INTERACTIVE)
        }

        assertThat(arbiter.holder.value).isNull()
    }

    @Test
    fun `a caller that throws still releases the lock`() = runTest {
        val arbiter = InferenceArbiter()

        runCatching {
            arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) {
                error("boom")
            }
        }

        // Would deadlock, not fail, if the mutex leaked.
        val result = arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) { "ok" }
        assertThat(result).isEqualTo("ok")
        assertThat(arbiter.holder.value).isNull()
    }

    @Test
    fun `background work that finishes normally is not cancelled by a later request`() = runTest {
        val arbiter = InferenceArbiter()
        var completed = false

        arbiter.withAccess(InferenceArbiter.Priority.BACKGROUND) { completed = true }
        advanceUntilIdle()
        arbiter.withAccess(InferenceArbiter.Priority.INTERACTIVE) { }

        assertThat(completed).isTrue()
    }
}
