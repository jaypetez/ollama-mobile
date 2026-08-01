package io.github.jaypetez.ollamamobile.ml

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendQuarantineTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val ledgerFile: File get() = File(temporaryFolder.root, BackendQuarantine.FILE_NAME)

    private fun quarantine() = BackendQuarantine(ledgerFile)

    @Test
    fun `a fresh ledger quarantines nothing`() {
        val ledger = quarantine()

        assertThat(ledger.entries()).isEmpty()
        assertThat(ledger.quarantinedVariants()).isEmpty()
        assertThat(ledger.isQuarantined(I8MM_LIBRARY)).isFalse()
    }

    @Test
    fun `one crash is recorded but does not quarantine`() {
        val ledger = quarantine()

        val entry = ledger.recordCrash(I8MM_LIBRARY, nowMillis = 1_000L)

        assertThat(entry.crashCount).isEqualTo(1)
        assertThat(entry.lastCrashAtMillis).isEqualTo(1_000L)
        // A single crash can be an OOM kill or a corrupt model file. Giving up
        // throughput permanently on one event is an overreaction.
        assertThat(ledger.isQuarantined(I8MM_LIBRARY)).isFalse()
    }

    @Test
    fun `a second crash on the same variant quarantines it`() {
        val ledger = quarantine()

        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 1_000L)
        val entry = ledger.recordCrash(I8MM_LIBRARY, nowMillis = 2_000L)

        assertThat(entry.crashCount).isEqualTo(2)
        assertThat(ledger.isQuarantined(I8MM_LIBRARY)).isTrue()
        assertThat(ledger.quarantinedVariants())
            .containsExactly(GgmlCpuVariant.ANDROID_ARMV8_2_I8MM)
    }

    @Test
    fun `the recorded demotion target is one tier down`() {
        val entry = quarantine().recordCrash(I8MM_LIBRARY, nowMillis = 1L)

        assertThat(entry.demoteTo).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_2_FP16.libraryName)
    }

    @Test
    fun `the baseline records no demotion target`() {
        val entry = quarantine()
            .recordCrash(GgmlCpuVariant.ANDROID_ARMV8_0.libraryName, nowMillis = 1L)

        assertThat(entry.demoteTo).isNull()
    }

    @Test
    fun `crashes on different variants are tracked independently`() {
        val ledger = quarantine()

        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 1L)
        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 2L)
        ledger.recordCrash(GgmlCpuVariant.ANDROID_ARMV9_2_SVE.libraryName, nowMillis = 3L)

        assertThat(ledger.quarantinedVariants())
            .containsExactly(GgmlCpuVariant.ANDROID_ARMV8_2_I8MM)
        assertThat(ledger.entries()).hasSize(2)
    }

    @Test
    fun `the ledger survives being reopened`() {
        quarantine().recordCrash(I8MM_LIBRARY, nowMillis = 5L)
        quarantine().recordCrash(I8MM_LIBRARY, nowMillis = 6L)

        // A fresh instance reading the same file is exactly the next-launch case
        // this class exists for.
        assertThat(quarantine().isQuarantined(I8MM_LIBRARY)).isTrue()
    }

    @Test
    fun `entries come back most recent first`() {
        val ledger = quarantine()
        ledger.recordCrash(GgmlCpuVariant.ANDROID_ARMV8_2_FP16.libraryName, nowMillis = 10L)
        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 99L)

        assertThat(ledger.entries().first().libraryName).isEqualTo(I8MM_LIBRARY)
    }

    @Test
    fun `a corrupt line is dropped and the rest of the ledger survives`() {
        ledgerFile.parentFile?.mkdirs()
        ledgerFile.writeText(
            """
            $I8MM_LIBRARY|2|1234|libggml-cpu-android_armv8.2_2.so
            this is not a record
            |7|1|
            libggml-cpu-android_armv8.2_1.so|notanumber|1|
            """.trimIndent(),
        )

        val ledger = quarantine()

        // A partially written ledger still carries what was flushed, which is
        // the whole point of a line-per-entry format.
        assertThat(ledger.entries()).hasSize(1)
        assertThat(ledger.isQuarantined(I8MM_LIBRARY)).isTrue()
    }

    @Test
    fun `clear empties the ledger`() {
        val ledger = quarantine()
        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 1L)
        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 2L)

        ledger.clear()

        assertThat(ledger.entries()).isEmpty()
        assertThat(ledger.isQuarantined(I8MM_LIBRARY)).isFalse()
    }

    @Test
    fun `an unknown library name is recorded without a demotion target`() {
        // A variant name from a future llama.cpp still has to be recordable —
        // refusing to record it would lose the crash entirely.
        val entry = quarantine().recordCrash("libggml-cpu-from-the-future.so", nowMillis = 1L)

        assertThat(entry.crashCount).isEqualTo(1)
        assertThat(entry.demoteTo).isNull()
        assertThat(quarantine().quarantinedVariants()).isEmpty()
    }

    @Test
    fun `the policy honours the ledger`() {
        val ledger = quarantine()
        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 1L)
        ledger.recordCrash(I8MM_LIBRARY, nowMillis = 2L)

        val choice = BackendPolicy.select(
            DeviceCapabilities.Unknown.copy(
                abi = "arm64-v8a",
                features = setOf(CpuFeature.DOTPROD, CpuFeature.FP16, CpuFeature.I8MM),
            ),
            quarantined = ledger.quarantinedVariants(),
        )

        assertThat(choice.variant).isEqualTo(GgmlCpuVariant.ANDROID_ARMV8_2_FP16)
    }

    private companion object {
        val I8MM_LIBRARY = GgmlCpuVariant.ANDROID_ARMV8_2_I8MM.libraryName
    }
}
