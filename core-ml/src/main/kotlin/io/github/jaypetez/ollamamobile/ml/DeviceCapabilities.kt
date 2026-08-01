package io.github.jaypetez.ollamamobile.ml

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An optional ARM CPU extension that changes which ggml CPU kernels can run.
 *
 * Only the extensions ggml actually branches on are listed. Adding one that
 * ggml does not build a variant for would produce a label with no consequence,
 * which is worse than not detecting it: it reads as a capability claim.
 *
 * [hwcapBit] is the bit in `AT_HWCAP` or `AT_HWCAP2` (see [inHwcap2]) as defined
 * by the Linux kernel's `arch/arm64/include/uapi/asm/hwcap.h`. [cpuinfoFlag] is
 * the token the same feature appears as in the `Features:` line of
 * `/proc/cpuinfo`, which is the fallback path.
 */
public enum class CpuFeature(
    public val hwcapBit: Long,
    public val inHwcap2: Boolean,
    public val cpuinfoFlag: String,
) {
    /** `SDOT`/`UDOT`. The single biggest win for int8 quantised matmul. */
    DOTPROD(hwcapBit = 1L shl 20, inHwcap2 = false, cpuinfoFlag = "asimddp"),

    /** `SMMLA` int8 matrix multiply. Drives prompt-processing throughput. */
    I8MM(hwcapBit = 1L shl 13, inHwcap2 = true, cpuinfoFlag = "i8mm"),

    /** Half-precision *arithmetic*, not merely fp16 storage conversion. */
    FP16(hwcapBit = 1L shl 10, inHwcap2 = false, cpuinfoFlag = "asimdhp"),

    /** Scalable Vector Extension. */
    SVE(hwcapBit = 1L shl 22, inHwcap2 = false, cpuinfoFlag = "sve"),

    /** Scalable Matrix Extension. */
    SME(hwcapBit = 1L shl 23, inHwcap2 = true, cpuinfoFlag = "sme"),
}

/** Where a [DeviceCapabilities] came from. Recorded so a bug report can say. */
public enum class CapabilitySource {
    /** `getauxval(AT_HWCAP/AT_HWCAP2)` through this module's own JNI library. */
    HWCAP,

    /** Parsed from `/proc/cpuinfo` because the JNI library was not present. */
    PROC_CPUINFO,

    /** Neither worked. The feature set is empty and must not be read as "absent". */
    UNKNOWN,
}

/**
 * Core count split by maximum clock.
 *
 * The split matters because thread count is the one inference knob that is
 * routinely set wrong: `availableProcessors()` on a big.LITTLE phone schedules
 * work onto efficiency cores that finish late and stall every other thread at
 * the next ggml barrier. [performanceCores] is the number worth defaulting to.
 *
 * "Performance" here means "shares the highest `cpuinfo_max_freq` bucket on this
 * device". That is a heuristic, not a reading of the DynamIQ cluster topology,
 * and on a three-tier SoC (prime + big + little) it counts only the prime tier.
 * It is deliberately conservative: under-threading loses throughput, over-
 * threading loses more.
 */
public data class CpuTopology(
    public val totalCores: Int,
    public val performanceCores: Int,
) {
    public val efficiencyCores: Int get() = (totalCores - performanceCores).coerceAtLeast(0)

    /** True when the device reported more than one distinct max-frequency bucket. */
    public val isHeterogeneous: Boolean get() = performanceCores in 1 until totalCores
}

/**
 * What this device can do, as far as anything measurable can tell.
 *
 * This is a *description*, not an accelerator configuration. Nothing in this
 * module makes inference faster; it makes the choice ggml already makes
 * visible, and it gives the thread and thermal policies something factual to
 * decide from.
 */
public data class DeviceCapabilities(
    /** The ABI this process is actually running as, e.g. `arm64-v8a`. */
    public val abi: String,
    public val features: Set<CpuFeature>,
    public val topology: CpuTopology,
    /** Total physical RAM in bytes, or 0 when [ActivityManager] was unavailable. */
    public val totalRamBytes: Long,
    /** `ActivityManager.isLowRamDevice`. Governs whether large models are offered. */
    public val isLowRamDevice: Boolean,
    public val source: CapabilitySource,
) {
    public fun has(feature: CpuFeature): Boolean = feature in features

    /** A one-line summary for a bug report. Contains nothing user-identifying. */
    public fun describe(): String = buildString {
        append(abi)
        append(", ")
        append(topology.totalCores)
        append(" cores (")
        append(topology.performanceCores)
        append("P/")
        append(topology.efficiencyCores)
        append("E), features=")
        append(if (features.isEmpty()) "none" else features.sorted().joinToString("+") { it.name })
        append(", source=")
        append(source.name)
    }

    public companion object {
        /** Used when probing failed outright, so callers never see a null. */
        public val Unknown: DeviceCapabilities = DeviceCapabilities(
            abi = "",
            features = emptySet(),
            topology = CpuTopology(totalCores = 1, performanceCores = 1),
            totalRamBytes = 0L,
            isLowRamDevice = false,
            source = CapabilitySource.UNKNOWN,
        )
    }
}

/**
 * Pure parsing of the two `/proc` and `/sys` shapes the fallback path reads.
 *
 * Separated from [DeviceCapabilitiesProbe] on purpose: this is the part with the
 * bugs, and it is the part that can be tested against captured fixtures from
 * SoC families nobody here owns. The probe around it is plumbing.
 */
public object CpuInfoParser {
    /**
     * Extracts the ARM feature set from the text of `/proc/cpuinfo`.
     *
     * The `Features:` line is per-core and every core on an Android arm64 device
     * reports the same set, but a heterogeneous SoC can in principle differ, so
     * the union across all lines is taken — a feature present on any core is
     * one ggml may execute on that core.
     *
     * Tokens are matched whole. A substring match would see `sve` inside
     * `sve2`, `svebf16` and `svei8mm`, all of which imply `sve` anyway, but it
     * would also see `sme` inside `smei16i32`, and matching by accident is not
     * the same as matching correctly.
     */
    public fun parseFeatures(cpuinfo: String): Set<CpuFeature> {
        val tokens = cpuinfo
            .lineSequence()
            .filter { it.startsWith(FEATURES_KEY) || it.startsWith(FLAGS_KEY) }
            .flatMap { line -> line.substringAfter(':', "").trim().splitToSequence(' ') }
            .filter { it.isNotEmpty() }
            .toSet()
        return CpuFeature.entries.filterTo(mutableSetOf()) { it.cpuinfoFlag in tokens }
    }

    /**
     * Counts `processor :` lines.
     *
     * Returns null rather than 0 when there are none, so the caller can fall
     * back to `availableProcessors()` instead of believing a zero-core device.
     * `/proc/cpuinfo` on some kernels lists only *online* cores, which is why
     * this is a fallback for the runtime value rather than a replacement.
     */
    public fun parseCoreCount(cpuinfo: String): Int? = cpuinfo
        .lineSequence()
        .count { it.startsWith(PROCESSOR_KEY) }
        .takeIf { it > 0 }

    /**
     * Splits cores into performance and efficiency by max clock.
     *
     * Cores sharing the highest frequency are the performance tier. A device
     * that reports one frequency, or reports none at all, is treated as
     * homogeneous — every core is a performance core — because that is the
     * assumption that does the least damage when the data is missing.
     */
    public fun topologyOf(maxFrequenciesKHz: List<Long>, fallbackCoreCount: Int): CpuTopology {
        val usable = maxFrequenciesKHz.filter { it > 0 }
        if (usable.isEmpty()) {
            val cores = fallbackCoreCount.coerceAtLeast(1)
            return CpuTopology(totalCores = cores, performanceCores = cores)
        }
        val peak = usable.max()
        return CpuTopology(
            totalCores = usable.size,
            performanceCores = usable.count { it == peak },
        )
    }

    private const val FEATURES_KEY = "Features"
    private const val FLAGS_KEY = "flags"
    private const val PROCESSOR_KEY = "processor"
}

/**
 * Reads the device's CPU capabilities once and caches the answer.
 *
 * ## Two paths, and why the fallback is the important one
 *
 * `getauxval(AT_HWCAP)` is the authoritative answer — it is what the kernel
 * told the dynamic loader, and it is what ggml's own dispatch reads. It needs
 * JNI, and JNI needs the NDK.
 *
 * This module must not be a hard NDK dependency: the project's default build is
 * `-Pollama.nativeSource=none`, which produces an app with no native code at
 * all, and capability display has to keep working there. So the JNI library is
 * optional and `/proc/cpuinfo` is parsed when it is absent. The fallback is
 * less precise — `/proc/cpuinfo` on some vendor kernels omits features the
 * hardware has — and [DeviceCapabilities.source] records which one answered so
 * a report can be read correctly.
 *
 * Reads happen on the caller's thread. Each is a handful of small `/proc` and
 * `/sys` files, done once per process, so a dispatcher hop would cost more than
 * it saves.
 */
@Singleton
public class DeviceCapabilitiesProbe
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val cached: DeviceCapabilities by lazy { probe() }

        public fun capabilities(): DeviceCapabilities = cached

        private fun probe(): DeviceCapabilities {
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val runtimeCores = Runtime.getRuntime().availableProcessors()
            val topology = CpuInfoParser.topologyOf(
                maxFrequenciesKHz = readMaxFrequencies(runtimeCores),
                fallbackCoreCount = runtimeCores,
            )
            val activityManager = context.getSystemService<ActivityManager>()
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)

            val native = NativeCpuFeatures.detect()
            val features = native ?: CpuInfoParser.parseFeatures(readTextOrEmpty(PROC_CPUINFO))
            val source = when {
                native != null -> CapabilitySource.HWCAP
                else -> CapabilitySource.PROC_CPUINFO
            }

            return DeviceCapabilities(
                abi = abi,
                features = features,
                topology = topology,
                totalRamBytes = memoryInfo.totalMem,
                isLowRamDevice = activityManager?.isLowRamDevice == true,
                source = source,
            )
        }

        /**
         * Reads `cpuinfo_max_freq` for each core.
         *
         * Missing or unreadable entries are dropped rather than zero-filled: a
         * zero would land in the "not the peak" bucket and be counted as an
         * efficiency core, inventing a topology the device does not have.
         */
        private fun readMaxFrequencies(coreCount: Int): List<Long> =
            (0 until coreCount).mapNotNull { core ->
                readTextOrEmpty(File(CPUFREQ_ROOT, "cpu$core/cpufreq/cpuinfo_max_freq"))
                    .trim()
                    .toLongOrNull()
            }

        private fun readTextOrEmpty(file: File): String = try {
            if (file.canRead()) file.readText() else ""
        } catch (_: IOException) {
            ""
        } catch (_: SecurityException) {
            // SELinux denies some /sys reads on some vendor images. Not fatal.
            ""
        }

        private companion object {
            val PROC_CPUINFO = File("/proc/cpuinfo")
            const val CPUFREQ_ROOT = "/sys/devices/system/cpu"
        }
    }
