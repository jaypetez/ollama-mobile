package io.github.jaypetez.ollamamobile.ml

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackendPolicyTest {
    @Test
    fun `an armv9 part with sme selects the richest tier`() {
        val choice = BackendPolicy.select(
            capabilities(
                CpuFeature.DOTPROD,
                CpuFeature.FP16,
                CpuFeature.I8MM,
                CpuFeature.SVE,
                CpuFeature.SME,
            ),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV9_2_SME)
        assertThat(choice.rejected).isEmpty()
    }

    @Test
    fun `i8mm without sve stops at the armv8_2 i8mm tier`() {
        val choice = BackendPolicy.select(
            capabilities(CpuFeature.DOTPROD, CpuFeature.FP16, CpuFeature.I8MM),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_2_I8MM)
        assertThat(choice.rejected)
            .containsExactly(
                GgmlCpuVariant.ANDROID_ARMV9_2_SME,
                GgmlCpuVariant.ANDROID_ARMV9_2_SVE,
            ).inOrder()
    }

    @Test
    fun `sve without i8mm cannot reach the armv9 tier`() {
        // The tiers are cumulative, so a part advertising SVE but not i8mm —
        // which the fixture set does not contain but the enum must handle —
        // falls back to the highest tier it fully satisfies.
        val choice = BackendPolicy.select(
            capabilities(CpuFeature.DOTPROD, CpuFeature.FP16, CpuFeature.SVE),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_2_FP16)
    }

    @Test
    fun `a featureless arm64 part selects the baseline`() {
        val choice = BackendPolicy.select(capabilities())

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_0)
        assertThat(choice.variant.libraryName).isEqualTo("libggml-cpu-android_armv8.0_1.so")
    }

    @Test
    fun `x86_64 selects the only variant ggml builds for it`() {
        val choice = BackendPolicy.select(
            DeviceCapabilities.Unknown.copy(abi = "x86_64", features = emptySet()),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.X64)
    }

    @Test
    fun `quarantine moves the choice one tier down and records the skip`() {
        val choice = BackendPolicy.select(
            capabilities(CpuFeature.DOTPROD, CpuFeature.FP16, CpuFeature.I8MM),
            quarantined = setOf(GgmlCpuVariant.ANDROID_ARMV8_2_I8MM),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_2_FP16)
        assertThat(choice.quarantined).containsExactly(GgmlCpuVariant.ANDROID_ARMV8_2_I8MM)
        assertThat(choice.describe()).contains("libggml-cpu-android_armv8.2_3.so")
    }

    @Test
    fun `the baseline is never skipped even when quarantined`() {
        // If the baseline itself keeps crashing, :core-llm's sentinel disables
        // native inference outright. This function must not be what decides
        // that, because returning "nothing" here would be a null every caller
        // would have to handle for a case that is already handled elsewhere.
        val choice = BackendPolicy.select(
            capabilities(),
            quarantined = setOf(GgmlCpuVariant.ANDROID_ARMV8_0),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_0)
    }

    @Test
    fun `demotion walks one tier at a time and stops at the baseline`() {
        assertThat(BackendPolicy.demotionFor(GgmlCpuVariant.ANDROID_ARMV9_2_SME))
            .isEqualTo(GgmlCpuVariant.ANDROID_ARMV9_2_SVE)
        assertThat(BackendPolicy.demotionFor(GgmlCpuVariant.ANDROID_ARMV8_2_DOTPROD))
            .isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_0)
        assertThat(BackendPolicy.demotionFor(GgmlCpuVariant.ANDROID_ARMV8_0)).isNull()
        assertThat(BackendPolicy.demotionFor(GgmlCpuVariant.X64)).isNull()
    }

    @Test
    fun `library names round-trip through the lookup`() {
        GgmlCpuVariant.entries.forEach { variant ->
            assertThat(BackendPolicy.byLibraryName(variant.libraryName)).isEqualTo(variant)
        }
        assertThat(BackendPolicy.byLibraryName("libggml-cpu-nonsense.so")).isNull()
    }

    @Test
    fun `the arm tier list is ordered richest first and cumulative`() {
        val tiers = BackendPolicy.armTiers()

        tiers.zipWithNext().forEach { (richer, poorer) ->
            assertThat(richer.requires).containsAtLeastElementsIn(poorer.requires)
        }
        assertThat(tiers.last()).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_0)
    }

    @Test
    fun `the selection matches what the parser reads from a real feature line`() {
        // End to end over the fallback path: cpuinfo text in, variant out.
        val cpuinfo = checkNotNull(javaClass.getResourceAsStream("/cpuinfo/snapdragon_855.txt"))
            .bufferedReader()
            .use { it.readText() }

        val choice = BackendPolicy.select(
            DeviceCapabilities.Unknown.copy(
                abi = "arm64-v8a",
                features = CpuInfoParser.parseFeatures(cpuinfo),
                source = CapabilitySource.PROC_CPUINFO,
            ),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_2_FP16)
    }

    private fun capabilities(vararg features: CpuFeature): DeviceCapabilities =
        DeviceCapabilities.Unknown.copy(
            abi = "arm64-v8a",
            features = features.toSet(),
            topology = CpuTopology(totalCores = 8, performanceCores = 4),
            source = CapabilitySource.HWCAP,
        )
}
