package io.github.jaypetez.ollamamobile.feature.benchmark

import java.io.File
import java.io.IOException

/**
 * Memory figures read from `/proc`, because the SDK's are wrong for this
 * workload.
 *
 * ## Why not `Debug.getNativeHeapAllocatedSize()`
 *
 * llama.cpp `mmap`s the model file. Mapped pages never pass through the
 * allocator, so the native heap counter does not count them: a 4 GB model can be
 * fully resident while that API reports a few tens of megabytes. The number is
 * not slightly low, it is unrelated to the quantity anyone cares about, and
 * because it *looks* like a memory figure it is the single most likely way for
 * this harness to publish a confident lie.
 *
 * `VmHWM` from `/proc/self/status` is the kernel's own high-water mark for
 * resident set size, covering anonymous memory, file-backed pages and mapped
 * regions alike. It is exactly "how much physical memory did this process ever
 * hold", which is what decides whether the low-memory killer takes an interest.
 *
 * Two properties to hold in mind. `VmHWM` never decreases within a process, so
 * it is a per-process-launch figure and not a per-iteration one — comparing two
 * configurations in one process compares the larger against itself. And RSS
 * counts shared pages in full, which is irrelevant for a single process and
 * would double-count if summed across several.
 */
public object ProcStatus {
    /**
     * Extracts a `VmHWM:` value from the text of `/proc/<pid>/status`, in kB.
     *
     * The format is `Key:` then whitespace then a decimal then ` kB`. The unit
     * is asserted rather than assumed: every kernel in existence writes kB here,
     * and a kernel that wrote bytes would silently make the harness report a
     * peak 1024× too small, which is the kind of wrongness that survives review.
     */
    public fun parseVmHwmKilobytes(status: String): Long? = parseKilobytes(status, VM_HWM_KEY)

    /** Current resident set size in kB, for sampling the shape over a run. */
    public fun parseVmRssKilobytes(status: String): Long? = parseKilobytes(status, VM_RSS_KEY)

    /**
     * Extracts `MemAvailable:` from the text of `/proc/meminfo`, in kB.
     *
     * `MemAvailable`, not `MemFree`: free memory on a healthy Android device is
     * near zero because the page cache uses the rest, and budgeting against it
     * would refuse every model.
     */
    public fun parseMemAvailableKilobytes(meminfo: String): Long? =
        parseKilobytes(meminfo, MEM_AVAILABLE_KEY)

    /** Peak RSS of this process in bytes, or null if `/proc` was unreadable. */
    public fun peakRssBytes(statusFile: File = File(SELF_STATUS)): Long? =
        BenchmarkMetrics.kilobytesToBytes(readText(statusFile)?.let(::parseVmHwmKilobytes))

    /** Current RSS of this process in bytes. */
    public fun currentRssBytes(statusFile: File = File(SELF_STATUS)): Long? =
        BenchmarkMetrics.kilobytesToBytes(readText(statusFile)?.let(::parseVmRssKilobytes))

    /** `MemAvailable` in bytes, recorded at the start of a run. */
    public fun memAvailableBytes(meminfoFile: File = File(MEMINFO)): Long? =
        BenchmarkMetrics.kilobytesToBytes(readText(meminfoFile)?.let(::parseMemAvailableKilobytes))

    private fun parseKilobytes(text: String, key: String): Long? = text
        .lineSequence()
        .firstOrNull { it.startsWith(key) }
        ?.let { line ->
            val fields = line.substringAfter(':').trim().split(WHITESPACE)
            val value = fields.getOrNull(0)?.toLongOrNull() ?: return@let null
            val unit = fields.getOrNull(1)
            // A missing or unexpected unit is reported as "no reading" rather
            // than as a number in an unknown scale.
            if (unit != null && !unit.equals(KILOBYTES_UNIT, ignoreCase = true)) return@let null
            value
        }

    private fun readText(file: File): String? = try {
        if (file.canRead()) file.readText() else null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private val WHITESPACE = Regex("\\s+")
    private const val VM_HWM_KEY = "VmHWM:"
    private const val VM_RSS_KEY = "VmRSS:"
    private const val MEM_AVAILABLE_KEY = "MemAvailable:"
    private const val KILOBYTES_UNIT = "kB"
    private const val SELF_STATUS = "/proc/self/status"
    private const val MEMINFO = "/proc/meminfo"
}
