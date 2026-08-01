package io.github.jaypetez.ollamamobile.llm

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.SamplingParams
import org.junit.jupiter.api.Test

/**
 * The hold-back, at the level it actually goes wrong: a stop sequence split
 * across token boundaries.
 */
class StopSequenceFilterTest {
    @Test
    fun `passes text straight through when no stop sequences are configured`() {
        val filter = StopSequenceFilter(SamplingParams.Default)

        assertThat(filter.push("Hello ")).isEqualTo("Hello ")
        assertThat(filter.push("world")).isEqualTo("world")
        assertThat(filter.flush()).isEmpty()
        assertThat(filter.isStopped).isFalse()
    }

    @Test
    fun `never emits a stop sequence that spans two deltas`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("<|im_end|>")))

        val first = filter.push("Answer.<|im_")
        val second = filter.push("end|>")

        // "<|im_" must not have reached the screen at any point.
        assertThat(first + second).isEqualTo("Answer.")
        assertThat(first).doesNotContain("<")
        assertThat(filter.isStopped).isTrue()
        assertThat(filter.flush()).isEmpty()
    }

    @Test
    fun `suppresses a stop sequence split across three deltas`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("<|im_end|>")))

        val emitted = buildString {
            append(filter.push("Done"))
            append(filter.push("<|"))
            append(filter.push("im_"))
            append(filter.push("end|>"))
        }

        assertThat(emitted).isEqualTo("Done")
        assertThat(filter.isStopped).isTrue()
    }

    @Test
    fun `releases a withheld tail that turned out not to be a stop sequence`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("<|im_end|>")))

        val streamed = filter.push("value is <|x")
        val flushed = filter.flush()

        // Held back mid-stream because "<|" could still have completed...
        assertThat(streamed).isEqualTo("val")
        // ...and released once the stream ended, because it did not.
        assertThat(streamed + flushed).isEqualTo("value is <|x")
        assertThat(filter.isStopped).isFalse()
    }

    @Test
    fun `cuts at the earliest stop sequence, not the first one configured`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("END", "\n\n")))

        val emitted = filter.push("first\n\nsecond END third")

        assertThat(emitted).isEqualTo("first")
        assertThat(filter.isStopped).isTrue()
    }

    @Test
    fun `emits nothing at all once a stop sequence has been seen`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("STOP")))

        filter.push("keep STOP")

        assertThat(filter.push(" and more")).isEmpty()
        assertThat(filter.push("and more still")).isEmpty()
        assertThat(filter.flush()).isEmpty()
    }

    @Test
    fun `holds back nothing beyond what the longest sequence requires`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("ab")))

        // holdBackChars is 1, so all but the final character is displayable at once.
        assertThat(filter.push("hello")).isEqualTo("hell")
        assertThat(filter.heldBackLength).isEqualTo(1)
    }

    @Test
    fun `a single-character stop sequence needs no hold-back and still cuts`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("#")))

        assertThat(filter.heldBackLength).isEqualTo(0)
        assertThat(filter.push("done#tail")).isEqualTo("done")
        assertThat(filter.isStopped).isTrue()
    }

    @Test
    fun `an empty configured stop sequence is ignored rather than cutting everything`() {
        // An empty string matches at index 0 of any text, which would truncate
        // every response to nothing. Servers and imported model files do emit
        // stray empty stop entries.
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("", "END")))

        assertThat(filter.push("hello ") + filter.flush()).isEqualTo("hello ")
        assertThat(filter.isStopped).isFalse()
    }

    @Test
    fun `flush is idempotent`() {
        val filter = StopSequenceFilter(SamplingParams(stop = listOf("END")))
        filter.push("ab")

        assertThat(filter.flush()).isEqualTo("ab")
        assertThat(filter.flush()).isEmpty()
    }
}
