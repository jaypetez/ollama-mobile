package io.github.jaypetez.ollamamobile.feature.benchmark

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `VmHWM` parsing against a fixture `/proc/self/status`.
 *
 * The fixture is inline rather than a resource so the exact bytes under test are
 * visible next to the assertion — tab-separated keys, right-aligned values, and
 * the trailing ` kB` that the parser must not treat as optional.
 */
class ProcStatusTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `VmHWM is parsed from a realistic status file`() {
        assertThat(ProcStatus.parseVmHwmKilobytes(STATUS_FIXTURE)).isEqualTo(3_921_488L)
    }

    @Test
    fun `VmRSS is parsed independently of VmHWM`() {
        // The two lines are adjacent and similarly named; a sloppy prefix match
        // returns the wrong one and nothing complains.
        assertThat(ProcStatus.parseVmRssKilobytes(STATUS_FIXTURE)).isEqualTo(3_874_112L)
    }

    @Test
    fun `a status file without VmHWM yields null`() {
        assertThat(ProcStatus.parseVmHwmKilobytes("Name:\tzygote64\nState:\tS (sleeping)\n")).isNull()
    }

    @Test
    fun `a value in an unexpected unit is rejected rather than misread`() {
        // A kernel reporting bytes here would make the harness under-report peak
        // memory by 1024x, which is exactly the kind of wrongness that survives
        // review because the number still looks like a memory figure.
        assertThat(ProcStatus.parseVmHwmKilobytes("VmHWM:\t 3921488 B\n")).isNull()
    }

    @Test
    fun `a non-numeric value yields null`() {
        assertThat(ProcStatus.parseVmHwmKilobytes("VmHWM:\t unknown kB\n")).isNull()
    }

    @Test
    fun `MemAvailable is parsed from meminfo`() {
        val meminfo = """
            MemTotal:       11534336 kB
            MemFree:          204800 kB
            MemAvailable:    4194304 kB
            Buffers:           12345 kB
        """.trimIndent()

        // MemAvailable, not MemFree: free memory on a healthy Android device is
        // near zero because the page cache holds the rest.
        assertThat(ProcStatus.parseMemAvailableKilobytes(meminfo)).isEqualTo(4_194_304L)
    }

    @Test
    fun `peak rss reads a file and converts to bytes`() {
        val file = File(temporaryFolder.root, "status").apply { writeText(STATUS_FIXTURE) }

        assertThat(ProcStatus.peakRssBytes(file)).isEqualTo(3_921_488L * 1_024L)
        assertThat(ProcStatus.currentRssBytes(file)).isEqualTo(3_874_112L * 1_024L)
    }

    @Test
    fun `an unreadable file yields null rather than throwing`() {
        val missing = File(temporaryFolder.root, "does-not-exist")

        assertThat(ProcStatus.peakRssBytes(missing)).isNull()
        assertThat(ProcStatus.memAvailableBytes(missing)).isNull()
    }

    private companion object {
        /**
         * Trimmed from a real `/proc/<pid>/status`, keeping the neighbours of
         * the two fields under test so the parser is exercised against the
         * lines it actually has to skip.
         */
        val STATUS_FIXTURE = buildString {
            appendLine("Name:\tollamamobile")
            appendLine("Umask:\t0077")
            appendLine("State:\tS (sleeping)")
            appendLine("Tgid:\t12345")
            appendLine("VmPeak:\t 4213764 kB")
            appendLine("VmSize:\t 4198400 kB")
            appendLine("VmLck:\t       0 kB")
            appendLine("VmPin:\t       0 kB")
            appendLine("VmHWM:\t 3921488 kB")
            appendLine("VmRSS:\t 3874112 kB")
            appendLine("RssAnon:\t  245760 kB")
            appendLine("RssFile:\t 3628352 kB")
            appendLine("RssShmem:\t       0 kB")
            appendLine("Threads:\t42")
        }
    }
}
