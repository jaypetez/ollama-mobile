package io.github.jaypetez.ollamamobile.ml

/**
 * One entry of ggml's Android CPU variant tier list.
 *
 * ## What this is and, more importantly, what it is not
 *
 * ggml already picks a CPU variant at runtime. `GGML_CPU_ALL_VARIANTS=ON` builds
 * one `libggml-cpu-<tier>.so` per tier, and `ggml_backend_load_all` scores each
 * against the running CPU and keeps the best. Nothing in this file changes that
 * choice, and nothing here is consulted by ggml.
 *
 * What it buys is *visibility*. Without it, "which kernels am I running?" has no
 * answer that a user or a bug report can produce, and the difference between a
 * device that got `android_armv8.2_3` and one that fell back to
 * `android_armv8.0_1` is a factor in throughput that would otherwise be
 * invisible. Mirroring the rule and displaying the result is the whole value.
 *
 * [libraryName] is the file ggml would produce for this tier. The names come
 * from `ggml_add_cpu_backend_variant` in `third_party/llama.cpp`'s
 * `ggml/src/CMakeLists.txt` at the pinned tag. **They are not verified against a
 * built artefact in this repository** — the submodule is not vendored in every
 * checkout and no arm64 build has been produced here. A drifted name degrades a
 * label; it cannot affect which backend runs, because ggml never reads it.
 */
public enum class GgmlCpuVariant(
    public val libraryName: String,
    public val requires: Set<CpuFeature>,
) {
    /** Nothing optional at all. The recovery target for the crash sentinel. */
    ANDROID_ARMV8_0(
        libraryName = "libggml-cpu-android_armv8.0_1.so",
        requires = emptySet(),
    ),
    ANDROID_ARMV8_2_DOTPROD(
        libraryName = "libggml-cpu-android_armv8.2_1.so",
        requires = setOf(CpuFeature.DOTPROD),
    ),
    ANDROID_ARMV8_2_FP16(
        libraryName = "libggml-cpu-android_armv8.2_2.so",
        requires = setOf(CpuFeature.DOTPROD, CpuFeature.FP16),
    ),
    ANDROID_ARMV8_2_I8MM(
        libraryName = "libggml-cpu-android_armv8.2_3.so",
        requires = setOf(CpuFeature.DOTPROD, CpuFeature.FP16, CpuFeature.I8MM),
    ),
    ANDROID_ARMV9_2_SVE(
        libraryName = "libggml-cpu-android_armv9.2_1.so",
        requires = setOf(CpuFeature.DOTPROD, CpuFeature.FP16, CpuFeature.I8MM, CpuFeature.SVE),
    ),
    ANDROID_ARMV9_2_SME(
        libraryName = "libggml-cpu-android_armv9.2_2.so",
        requires = setOf(
            CpuFeature.DOTPROD,
            CpuFeature.FP16,
            CpuFeature.I8MM,
            CpuFeature.SVE,
            CpuFeature.SME,
        ),
    ),

    /**
     * The only x86_64 variant ggml builds. Emulator territory.
     *
     * Present because the debug ABI set includes `x86_64` so instrumentation
     * tests can run, not because anyone should read a number from it.
     */
    X64(libraryName = "libggml-cpu-x64.so", requires = emptySet()),
    ;

    /** True when every extension this tier needs is present in [features]. */
    public fun isSupportedBy(features: Set<CpuFeature>): Boolean = features.containsAll(requires)
}

/** The outcome of [BackendPolicy.select], including the tiers that were ruled out. */
public data class BackendChoice(
    public val variant: GgmlCpuVariant,
    /** Tiers above [variant] that the CPU cannot run, best-first. Display only. */
    public val rejected: List<GgmlCpuVariant>,
    /** Tiers skipped because [BackendQuarantine] has them in quarantine. */
    public val quarantined: List<GgmlCpuVariant>,
) {
    public fun describe(): String = buildString {
        append(variant.libraryName)
        if (quarantined.isNotEmpty()) {
            append(" (quarantined: ")
            append(quarantined.joinToString(", ") { it.libraryName })
            append(')')
        }
    }
}

/**
 * Maps a detected feature set onto the ggml CPU variant that should be selected.
 *
 * A pure function of its inputs, so the mapping can be tested for every
 * plausible SoC without a device — which is the only way it can be tested at
 * all here.
 */
public object BackendPolicy {
    /** Android arm64 tiers, richest first. Order is the selection order. */
    private val armTiers: List<GgmlCpuVariant> = listOf(
        GgmlCpuVariant.ANDROID_ARMV9_2_SME,
        GgmlCpuVariant.ANDROID_ARMV9_2_SVE,
        GgmlCpuVariant.ANDROID_ARMV8_2_I8MM,
        GgmlCpuVariant.ANDROID_ARMV8_2_FP16,
        GgmlCpuVariant.ANDROID_ARMV8_2_DOTPROD,
        GgmlCpuVariant.ANDROID_ARMV8_0,
    )

    /**
     * The tier ggml is expected to choose on this device.
     *
     * [quarantined] removes variants that have already crashed this install; see
     * [BackendQuarantine]. Removing a tier only ever moves the answer *down* the
     * list, and the baseline is never removed — if the baseline itself is in
     * quarantine, `:core-llm`'s sentinel has already disabled native inference
     * and this function is not what stops it.
     */
    public fun select(
        capabilities: DeviceCapabilities,
        quarantined: Set<GgmlCpuVariant> = emptySet(),
    ): BackendChoice {
        if (!capabilities.abi.startsWith(ARM64_ABI_PREFIX)) {
            return BackendChoice(
                variant = GgmlCpuVariant.X64,
                rejected = emptyList(),
                quarantined = emptyList(),
            )
        }

        val rejected = mutableListOf<GgmlCpuVariant>()
        val skipped = mutableListOf<GgmlCpuVariant>()
        for (tier in armTiers) {
            when {
                !tier.isSupportedBy(capabilities.features) -> rejected += tier
                tier != GgmlCpuVariant.ANDROID_ARMV8_0 && tier in quarantined -> skipped += tier
                else -> return BackendChoice(tier, rejected, skipped)
            }
        }
        // Unreachable: the baseline requires nothing and is never skipped.
        return BackendChoice(GgmlCpuVariant.ANDROID_ARMV8_0, rejected, skipped)
    }

    /**
     * The next tier down from [variant], or null at the baseline.
     *
     * This is what a quarantine demotion targets: one step, not straight to the
     * baseline. A crash in an SME kernel says nothing about the i8mm kernels,
     * and dropping five tiers on one crash gives up throughput the device has.
     */
    public fun demotionFor(variant: GgmlCpuVariant): GgmlCpuVariant? {
        val index = armTiers.indexOf(variant)
        return when {
            index < 0 -> null
            index == armTiers.lastIndex -> null
            else -> armTiers[index + 1]
        }
    }

    /** Tiers richest-first. Exposed for display and for the quarantine ledger. */
    public fun armTiers(): List<GgmlCpuVariant> = armTiers

    /** Looks a tier up by the `.so` name recorded in a sentinel or a ledger. */
    public fun byLibraryName(name: String): GgmlCpuVariant? =
        GgmlCpuVariant.entries.firstOrNull { it.libraryName == name }

    private const val ARM64_ABI_PREFIX = "arm64"
}
