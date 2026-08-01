package io.github.jaypetez.ollamamobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SamplingParamsTest {
    @Test
    fun `Default leaves every knob unset so the engine decides`() {
        val params = SamplingParams.Default

        assertThat(params.temperature).isNull()
        assertThat(params.topP).isNull()
        assertThat(params.topK).isNull()
        assertThat(params.minP).isNull()
        assertThat(params.repeatPenalty).isNull()
        assertThat(params.repeatLastN).isNull()
        assertThat(params.seed).isNull()
        assertThat(params.numPredict).isNull()
        assertThat(params.numCtx).isNull()
        assertThat(params.stop).isEmpty()
    }

    @Test
    fun `holds back nothing when there are no stop sequences`() {
        assertThat(SamplingParams.Default.stopHoldBackChars).isEqualTo(0)
    }

    @Test
    fun `holds back one less than the longest stop sequence`() {
        // "<|im_end|>" is 10 chars, so 9 must be withheld: the model can emit
        // "<|im_end|" across one token boundary and ">" across the next, and a
        // consumer that painted the first part has already leaked it.
        assertThat(SamplingParams(stop = listOf("<|im_end|>")).stopHoldBackChars).isEqualTo(9)
    }

    @Test
    fun `uses the longest sequence, not the first or the last`() {
        val params = SamplingParams(stop = listOf("\n\n", "<|im_end|>", "###"))

        assertThat(params.stopHoldBackChars).isEqualTo(9)
    }

    @Test
    fun `a single-character stop needs no hold-back`() {
        // The whole sequence arrives inside one token, so there is no partial
        // prefix that could still complete.
        assertThat(SamplingParams(stop = listOf("\n")).stopHoldBackChars).isEqualTo(0)
    }

    @Test
    fun `never returns a negative hold-back for degenerate input`() {
        assertThat(SamplingParams(stop = listOf("")).stopHoldBackChars).isEqualTo(0)
        assertThat(SamplingParams(stop = listOf("", "ab")).stopHoldBackChars).isEqualTo(1)
    }

    @Test
    fun `copy keeps the computed hold-back in step with the stop list`() {
        val params = SamplingParams.Default.copy(temperature = 0.7, stop = listOf("</s>"))

        assertThat(params.stopHoldBackChars).isEqualTo(3)
        assertThat(params.copy(stop = emptyList()).stopHoldBackChars).isEqualTo(0)
    }
}
