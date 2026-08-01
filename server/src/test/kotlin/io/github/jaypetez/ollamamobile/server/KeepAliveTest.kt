package io.github.jaypetez.ollamamobile.server

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * `keep_alive` arrives in four shapes, and the difference between them is
 * whether a model stays in memory.
 */
class KeepAliveTest {
    private val default = 5.minutes

    @Test
    fun `a Go duration string parses`() {
        assertThat(KeepAlive.parse(JsonPrimitive("10m"), default)).isEqualTo(KeepAlive.For(10.minutes))
        assertThat(KeepAlive.parse(JsonPrimitive("1h30m"), default)).isEqualTo(KeepAlive.For(90.minutes))
        assertThat(KeepAlive.parse(JsonPrimitive("30s"), default)).isEqualTo(KeepAlive.For(30.seconds))
        assertThat(KeepAlive.parse(JsonPrimitive("2h"), default)).isEqualTo(KeepAlive.For(2.hours))
    }

    @Test
    fun `a bare number is seconds`() {
        assertThat(KeepAlive.parse(JsonPrimitive(300), default)).isEqualTo(KeepAlive.For(300.seconds))
    }

    @Test
    fun `zero means unload now and a negative value means never`() {
        assertThat(KeepAlive.parse(JsonPrimitive(0), default)).isEqualTo(KeepAlive.Immediate)
        assertThat(KeepAlive.parse(JsonPrimitive("0"), default)).isEqualTo(KeepAlive.Immediate)
        assertThat(KeepAlive.parse(JsonPrimitive(-1), default)).isEqualTo(KeepAlive.Forever)
        assertThat(KeepAlive.parse(JsonPrimitive("-1m"), default)).isEqualTo(KeepAlive.Forever)
    }

    @Test
    fun `sub-second units are not rounded down to zero`() {
        // Rounding `500ms` to 0 would silently mean "unload immediately",
        // which is the opposite of what the caller asked for.
        assertThat(KeepAlive.parse(JsonPrimitive("500ms"), default)).isEqualTo(KeepAlive.For(500.milliseconds))
    }

    @Test
    fun `absent or unparsable falls back rather than failing the request`() {
        assertThat(KeepAlive.parse(null, default)).isEqualTo(KeepAlive.For(default))
        assertThat(KeepAlive.parse(JsonPrimitive("soon"), default)).isEqualTo(KeepAlive.For(default))
        assertThat(KeepAlive.parse(JsonPrimitive(""), default)).isEqualTo(KeepAlive.For(default))
    }
}

/** The state behind `/api/ps`, which is only useful if it is true. */
class ModelResidencyTest {
    private val clock = FakeClock()
    private val residency = ModelResidency(clock)

    @Test
    fun `expires_at is reported in RFC 3339 UTC`() {
        residency.touch(LOCAL_MODEL, KeepAlive.For(10.minutes))

        assertThat(residency.running().single().expiresAt).isEqualTo("2023-11-14T22:23:20Z")
    }

    @Test
    fun `a resident-forever model reports no expires_at at all`() {
        residency.touch(LOCAL_MODEL, KeepAlive.Forever)

        val running = residency.running().single()
        // Ollama omits the field rather than inventing a far-future date.
        assertThat(running.expiresAt).isNull()
        assertThat(running.sizeVram).isEqualTo(0L)
    }

    @Test
    fun `the deadline is enforced, so the reported timestamp is not a lie`() {
        residency.touch(LOCAL_MODEL, KeepAlive.For(1.minutes))
        assertThat(residency.running()).hasSize(1)

        clock.millis += 61_000L

        assertThat(residency.running()).isEmpty()
    }

    @Test
    fun `keep_alive zero evicts immediately and touch reports it`() {
        residency.touch(LOCAL_MODEL, KeepAlive.For(1.minutes))

        val stillResident = residency.touch(LOCAL_MODEL, KeepAlive.Immediate)

        assertThat(stillResident).isFalse()
        assertThat(residency.running()).isEmpty()
    }

    @Test
    fun `two models are reported in a stable order`() {
        residency.touch(REMOTE_MODEL, KeepAlive.For(1.minutes))
        residency.touch(LOCAL_MODEL, KeepAlive.For(1.minutes))

        assertThat(residency.running().map { it.name }).containsExactly("llama3.2:3b", "qwen3:1.7b").inOrder()
    }
}
