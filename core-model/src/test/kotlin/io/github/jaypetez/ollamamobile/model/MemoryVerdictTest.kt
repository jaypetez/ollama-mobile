package io.github.jaypetez.ollamamobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MemoryVerdictTest {
    @Test
    fun `Fits explains the headroom and permits the load`() {
        val verdict = MemoryVerdict.Fits(headroomBytes = 1_610_612_736L)

        assertThat(verdict.explain()).isEqualTo("Fits with 1.5 GiB to spare.")
        assertThat(verdict.allowsLoad).isTrue()
    }

    @Test
    fun `Tight repeats the reason so the warning is actionable`() {
        val verdict = MemoryVerdict.Tight(
            headroomBytes = 268_435_456L,
            reason = "Reduce the context length to 4K or quantise the KV cache.",
        )

        assertThat(verdict.explain()).isEqualTo(
            "Tight: only 256.0 MiB of headroom. Reduce the context length to 4K or quantise the KV cache.",
        )
        // Tight still loads — it is a warning, not a refusal.
        assertThat(verdict.allowsLoad).isTrue()
    }

    @Test
    fun `Refuse states the shortfall in the same terms as the estimate`() {
        val verdict = MemoryVerdict.Refuse(
            requiredBytes = 5_368_709_120L,
            availableBytes = 3_221_225_472L,
            reason = "Try a Q4_K_M build of the same model.",
        )

        assertThat(verdict.explain()).isEqualTo(
            "Needs 5.0 GiB but only 3.0 GiB is available (2.0 GiB short). " +
                "Try a Q4_K_M build of the same model.",
        )
        assertThat(verdict.shortfallBytes).isEqualTo(2_147_483_648L)
        assertThat(verdict.allowsLoad).isFalse()
    }

    @Test
    fun `Refuse never reports a negative shortfall`() {
        // A refusal on grounds other than raw size (a low-RAM device policy)
        // can carry a required figure below what is available.
        val verdict = MemoryVerdict.Refuse(
            requiredBytes = 1_000L,
            availableBytes = 2_000L,
            reason = "This device is flagged low-RAM.",
        )

        assertThat(verdict.shortfallBytes).isEqualTo(0L)
    }

    @Test
    fun `small values are reported in bytes without a misleading decimal`() {
        assertThat(MemoryVerdict.Fits(headroomBytes = 512L).explain()).isEqualTo("Fits with 512 B to spare.")
        assertThat(MemoryVerdict.Fits(headroomBytes = 0L).explain()).isEqualTo("Fits with 0 B to spare.")
    }

    @Test
    fun `every verdict explains itself`() {
        val verdicts: List<MemoryVerdict> = listOf(
            MemoryVerdict.Fits(1L),
            MemoryVerdict.Tight(1L, "reason"),
            MemoryVerdict.Refuse(2L, 1L, "reason"),
        )

        verdicts.forEach { verdict ->
            assertThat(verdict.explain()).isNotEmpty()
        }
    }
}
