package io.github.jaypetez.ollamamobile.common.log

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LogRingTest {
    private fun record(index: Int) = LogRecord(
        timestampMillis = index.toLong(),
        level = LogLevel.INFO,
        tag = "T",
        message = "message-$index",
    )

    @Test
    fun `holds records oldest-first until it is full`() {
        val ring = LogRing(capacity = 4)

        repeat(3) { ring.add(record(it)) }

        assertThat(ring.size).isEqualTo(3)
        assertThat(ring.snapshot().map { it.message })
            .containsExactly("message-0", "message-1", "message-2")
            .inOrder()
        assertThat(ring.evictedCount).isEqualTo(0)
    }

    @Test
    fun `evicts the oldest record once full`() {
        val ring = LogRing(capacity = 3)

        repeat(5) { ring.add(record(it)) }

        assertThat(ring.size).isEqualTo(3)
        assertThat(ring.snapshot().map { it.message })
            .containsExactly("message-2", "message-3", "message-4")
            .inOrder()
        assertThat(ring.evictedCount).isEqualTo(2)
    }

    @Test
    fun `keeps wrapping correctly across many laps`() {
        val ring = LogRing(capacity = 8)

        repeat(8 * 5 + 3) { ring.add(record(it)) }

        val messages = ring.snapshot().map { it.message }
        assertThat(messages).hasSize(8)
        assertThat(messages.first()).isEqualTo("message-35")
        assertThat(messages.last()).isEqualTo("message-42")
    }

    @Test
    fun `recent returns the tail`() {
        val ring = LogRing(capacity = 10)
        repeat(10) { ring.add(record(it)) }

        assertThat(ring.recent(3).map { it.message })
            .containsExactly("message-7", "message-8", "message-9")
            .inOrder()
        assertThat(ring.recent(50)).hasSize(10)
    }

    @Test
    fun `clear resets size and eviction count`() {
        val ring = LogRing(capacity = 2)
        repeat(5) { ring.add(record(it)) }

        ring.clear()

        assertThat(ring.size).isEqualTo(0)
        assertThat(ring.evictedCount).isEqualTo(0)
        assertThat(ring.snapshot()).isEmpty()
    }

    @Test
    fun `rejects a non-positive capacity`() {
        val thrown = runCatching { LogRing(capacity = 0) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * Concurrent writers plus a concurrent reader.
     *
     * The assertion that matters is not the count — it is that `snapshot()`
     * never sees a null slot or a torn ring while writers are wrapping. An
     * unsynchronised implementation of this class fails here rather than in
     * production at 3am.
     */
    @Test
    fun `stays consistent under concurrent writers and readers`() {
        val capacity = 64
        val ring = LogRing(capacity = capacity)
        val writers = 8
        val perWriter = 2_000
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val failures = mutableListOf<Throwable>()

        repeat(writers) { writer ->
            pool.execute {
                start.await()
                try {
                    repeat(perWriter) { index -> ring.add(record(writer * perWriter + index)) }
                } catch (throwable: Throwable) {
                    synchronized(failures) { failures += throwable }
                } finally {
                    done.countDown()
                }
            }
        }
        pool.execute {
            start.await()
            while (done.count > 0) {
                try {
                    val snapshot = ring.snapshot()
                    check(snapshot.size <= capacity) { "snapshot larger than capacity: ${snapshot.size}" }
                    snapshot.forEach { check(it.message.startsWith("message-")) }
                } catch (throwable: Throwable) {
                    synchronized(failures) { failures += throwable }
                }
            }
        }

        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()

        assertThat(failures).isEmpty()
        assertThat(ring.size).isEqualTo(capacity)
        assertThat(ring.evictedCount).isEqualTo((writers.toLong() * perWriter) - capacity)
        assertThat(ring.snapshot()).hasSize(capacity)
    }
}
