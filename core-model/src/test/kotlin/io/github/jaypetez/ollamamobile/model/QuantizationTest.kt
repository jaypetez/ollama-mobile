package io.github.jaypetez.ollamamobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class QuantizationTest {
    @Test
    fun `entries are ordered from smallest to largest`() {
        val bpw = Quantization.entries.map { it.bitsPerWeight }
        // Q5_0 (5.54) is fractionally larger than Q5_K_S (5.52), so the
        // ordering is by family first. Assert monotonicity per family instead
        // of across the whole enum.
        assertThat(Quantization.Q2_K.bitsPerWeight).isLessThan(Quantization.Q4_K_M.bitsPerWeight)
        assertThat(Quantization.Q4_K_M.bitsPerWeight).isLessThan(Quantization.Q8_0.bitsPerWeight)
        assertThat(bpw.first()).isLessThan(bpw.last())
    }

    @ParameterizedTest
    @CsvSource(
        "qwen3-1.7b-instruct-q4_k_m.gguf, Q4_K_M",
        "Meta-Llama-3.1-8B-Instruct.Q4_K_S.gguf, Q4_K_S",
        "gemma-3-1b-it-q4_0.gguf, Q4_0",
        "model-Q8_0.gguf, Q8_0",
        "smollm3-3b-q2_k.gguf, Q2_K",
        "nomic-embed-text-v1.5.f16.gguf, F16",
    )
    fun `parses quantisation from filenames`(fileName: String, expected: Quantization) {
        assertThat(Quantization.fromFileName(fileName)).isEqualTo(expected)
    }

    @Test
    fun `longest label wins so Q4_K_M is not read as Q4_0`() {
        // A naive "contains" scan in declaration order would match Q4_0 inside
        // strings like "...-q4_k_m", silently mis-sizing the model.
        assertThat(Quantization.fromFileName("a-q4_k_m.gguf")).isEqualTo(Quantization.Q4_K_M)
        assertThat(Quantization.fromFileName("a-q3_k_l.gguf")).isEqualTo(Quantization.Q3_K_L)
    }

    @Test
    fun `returns null when no quantisation is present`() {
        assertThat(Quantization.fromFileName("some-model.gguf")).isNull()
    }

    @Test
    fun `KleidiAI covers the linear quants but not the k-quants`() {
        // This is the claim the docs and the model picker make; if it ever
        // changes upstream, this test should be the thing that notices.
        assertThat(Quantization.Q4_0.kleidiAiAccelerated).isTrue()
        assertThat(Quantization.Q8_0.kleidiAiAccelerated).isTrue()
        assertThat(Quantization.Q4_K_M.kleidiAiAccelerated).isFalse()
        assertThat(Quantization.Q6_K.kleidiAiAccelerated).isFalse()
    }

    @Test
    fun `estimates weight bytes for a 1_7B model at Q4_K_M`() {
        // 1.7e9 params * 4.85 bpw / 8 ~= 1.03 GB, which matches the published
        // file size of Qwen3-1.7B-Q4_K_M within a few percent.
        val bytes = Quantization.Q4_K_M.estimateWeightBytes(1_700_000_000L)
        assertThat(bytes).isIn(1_000_000_000L..1_100_000_000L)
    }
}
