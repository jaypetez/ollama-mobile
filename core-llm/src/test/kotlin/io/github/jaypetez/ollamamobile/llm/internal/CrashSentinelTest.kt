package io.github.jaypetez.ollamamobile.llm.internal

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sentinel file itself.
 *
 * Plain JUnit with a temporary directory rather than Robolectric: nothing here
 * touches Android, and a test that starts a Robolectric sandbox to assert on a
 * three-field text file is a slow test for no reason.
 */
class CrashSentinelTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private fun sentinelFile(): File = File(temporaryFolder.root, "sentinel")

    @Test
    fun `a fresh install has no record`() {
        assertThat(CrashSentinel(sentinelFile()).read()).isNull()
    }

    @Test
    fun `arm then read round-trips every field`() {
        val sentinel = CrashSentinel(sentinelFile())
        val record = SentinelRecord(
            attempt = 2,
            mode = BackendMode.SAFE_BASELINE,
            backend = "CPU (armv8.0)",
        )

        sentinel.arm(record)

        assertThat(sentinel.read()).isEqualTo(record)
    }

    @Test
    fun `disarm removes the record, which is what a clean run leaves behind`() {
        val sentinel = CrashSentinel(sentinelFile())
        sentinel.arm(SentinelRecord(1, BackendMode.FULL_SCAN, "CPU"))

        sentinel.disarm()

        assertThat(sentinel.read()).isNull()
        assertThat(sentinelFile().exists()).isFalse()
    }

    @Test
    fun `disarming twice is not an error`() {
        val sentinel = CrashSentinel(sentinelFile())
        sentinel.disarm()
        sentinel.disarm()

        assertThat(sentinel.read()).isNull()
    }

    @Test
    fun `a record surviving a simulated crash escalates the plan`() {
        // The whole scenario, end to end: run one arms the sentinel and dies
        // before disarming; run two reads it and drops to the baseline backend.
        val file = sentinelFile()
        CrashSentinel(file).arm(SentinelRecord(1, BackendMode.FULL_SCAN, "CPU (i8mm)"))

        val plan = BackendSelection.plan(CrashSentinel(file).read())

        assertThat((plan as BackendPlan.Load).mode).isEqualTo(BackendMode.SAFE_BASELINE)
    }

    @Test
    fun `unparseable content still counts as a crash`() {
        // The file existing at all is the evidence. Returning null for garbage
        // would report a crash as a clean start and never escalate.
        val file = sentinelFile()
        file.writeText("this is not a record")

        val record = CrashSentinel(file).read()

        assertThat(record).isNotNull()
        assertThat(record!!.attempt).isEqualTo(1)
        assertThat(BackendSelection.plan(record)).isInstanceOf(BackendPlan.Load::class.java)
    }

    @Test
    fun `a record with an unknown backend mode is treated as garbage, not ignored`() {
        val file = sentinelFile()
        file.writeText("1|WARP_DRIVE|CPU")

        assertThat(CrashSentinel(file).read()?.mode).isEqualTo(BackendMode.FULL_SCAN)
    }

    @Test
    fun `arming creates the parent directory when it is missing`() {
        val file = File(temporaryFolder.root, "nested/deeper/sentinel")
        val sentinel = CrashSentinel(file)

        sentinel.arm(SentinelRecord(1, BackendMode.FULL_SCAN, "CPU"))

        assertThat(file.exists()).isTrue()
    }
}
