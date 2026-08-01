package io.github.jaypetez.ollamamobile.ml

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `/proc/cpuinfo` fallback, against fixtures for several SoC families.
 *
 * ## What the fixtures are, precisely
 *
 * The files under `src/test/resources/cpuinfo` are **representative**, written from
 * publicly documented `Features:` lines for each SoC family. They were **not**
 * captured from devices this project owns, because this project owns no arm64
 * device at all. They are correct as a test of the parser — which is a string
 * problem, not a hardware problem — and they are not evidence about what any
 * particular phone reports.
 *
 * That distinction matters for exactly one reason: if a real device ever
 * disagrees with a fixture, the fixture is wrong, not the device.
 */
class CpuInfoParserTest {
    @Test
    fun `armv9 flagship reports the full feature set`() {
        val features = CpuInfoParser.parseFeatures(fixture("snapdragon_8_gen_3"))

        assertThat(features).containsExactly(
            CpuFeature.DOTPROD,
            CpuFeature.FP16,
            CpuFeature.I8MM,
            CpuFeature.SVE,
        )
    }

    @Test
    fun `sve is detected without sme being inferred from sve2`() {
        val features = CpuInfoParser.parseFeatures(fixture("exynos_2200"))

        assertThat(features).contains(CpuFeature.SVE)
        // The fixture carries sve2, sveaes, svei8mm and svebf16. None of those
        // is `sme`, and a substring match would not have noticed.
        assertThat(features).doesNotContain(CpuFeature.SME)
    }

    @Test
    fun `2019 flagship has dotprod and fp16 but no i8mm`() {
        val features = CpuInfoParser.parseFeatures(fixture("snapdragon_855"))

        assertThat(features).containsExactly(CpuFeature.DOTPROD, CpuFeature.FP16)
    }

    @Test
    fun `armv8 baseline reports nothing optional`() {
        val features = CpuInfoParser.parseFeatures(fixture("snapdragon_660"))

        assertThat(features).isEmpty()
    }

    @Test
    fun `tensor g3 matches the same tier as other armv9 parts`() {
        val features = CpuInfoParser.parseFeatures(fixture("tensor_g3"))

        assertThat(features).containsAtLeast(CpuFeature.DOTPROD, CpuFeature.I8MM, CpuFeature.SVE)
    }

    @Test
    fun `x86 emulator reports no arm features from its flags line`() {
        // The emulator's cpuinfo uses `flags:` rather than `Features:` and
        // contains `sse4_1`, `f16c` and `avx2`. None of them may be mistaken for
        // an ARM extension; `f16c` in particular is a near-miss for fp16.
        val features = CpuInfoParser.parseFeatures(fixture("emulator_x86_64"))

        assertThat(features).isEmpty()
    }

    @Test
    fun `core count comes from processor lines`() {
        assertThat(CpuInfoParser.parseCoreCount(fixture("snapdragon_8_gen_3"))).isEqualTo(3)
        assertThat(CpuInfoParser.parseCoreCount(fixture("emulator_x86_64"))).isEqualTo(2)
    }

    @Test
    fun `core count is null rather than zero for unparseable input`() {
        assertThat(CpuInfoParser.parseCoreCount("")).isNull()
        assertThat(CpuInfoParser.parseCoreCount("nothing useful here")).isNull()
    }

    @Test
    fun `empty input yields no features rather than throwing`() {
        assertThat(CpuInfoParser.parseFeatures("")).isEmpty()
    }

    @Test
    fun `topology splits cores at the highest max frequency`() {
        // 1 prime + 3 big + 4 little, as an 8-core big.LITTLE part reports.
        val frequencies = listOf(
            1_800_000L,
            1_800_000L,
            1_800_000L,
            1_800_000L,
            2_800_000L,
            2_800_000L,
            2_800_000L,
            3_300_000L,
        )

        val topology = CpuInfoParser.topologyOf(frequencies, fallbackCoreCount = 8)

        assertThat(topology.totalCores).isEqualTo(8)
        // Only the prime tier counts. Conservative on purpose: over-threading
        // costs more than under-threading at a ggml barrier.
        assertThat(topology.performanceCores).isEqualTo(1)
        assertThat(topology.efficiencyCores).isEqualTo(7)
        assertThat(topology.isHeterogeneous).isTrue()
    }

    @Test
    fun `a homogeneous device has every core as a performance core`() {
        val topology = CpuInfoParser.topologyOf(List(4) { 2_000_000L }, fallbackCoreCount = 4)

        assertThat(topology.performanceCores).isEqualTo(4)
        assertThat(topology.efficiencyCores).isEqualTo(0)
        assertThat(topology.isHeterogeneous).isFalse()
    }

    @Test
    fun `missing frequency data falls back to the runtime core count`() {
        val topology = CpuInfoParser.topologyOf(emptyList(), fallbackCoreCount = 6)

        assertThat(topology.totalCores).isEqualTo(6)
        assertThat(topology.performanceCores).isEqualTo(6)
    }

    @Test
    fun `zero frequencies are dropped rather than counted as efficiency cores`() {
        val topology = CpuInfoParser.topologyOf(listOf(0L, 2_000_000L, 2_000_000L), fallbackCoreCount = 3)

        assertThat(topology.totalCores).isEqualTo(2)
        assertThat(topology.performanceCores).isEqualTo(2)
    }

    @Test
    fun `a fallback of zero still yields at least one core`() {
        val topology = CpuInfoParser.topologyOf(emptyList(), fallbackCoreCount = 0)

        assertThat(topology.totalCores).isEqualTo(1)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/cpuinfo/$name.txt")) {
            "Missing fixture cpuinfo/$name.txt"
        }.bufferedReader().use { it.readText() }
}
