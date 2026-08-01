package io.github.jaypetez.ollamamobile.common.crash

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The parser for the line the signal handler writes.
 *
 * Every case here is one the handler can actually produce: it is writing into a
 * fixed buffer from a dying process, so truncation is normal rather than
 * exceptional and must never throw on the launch path.
 */
class NativeCrashRecordTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `parses a complete record`() {
        val record = NativeCrashRecord.parse(
            "v1 signal=SIGILL signo=4 code=2 fault=0x7f8a1c0000 pid=4211 tid=4288 epoch=1754006400 phase=decode",
        )

        assertThat(record).isNotNull()
        requireNotNull(record)
        assertThat(record.signalName).isEqualTo("SIGILL")
        assertThat(record.signalNumber).isEqualTo(4)
        assertThat(record.code).isEqualTo(2)
        assertThat(record.faultAddress).isEqualTo("0x7f8a1c0000")
        assertThat(record.pid).isEqualTo(4211)
        assertThat(record.tid).isEqualTo(4288)
        assertThat(record.epochSeconds).isEqualTo(1_754_006_400L)
        assertThat(record.phase).isEqualTo("decode")
        assertThat(record.summary).isEqualTo("SIGILL during decode")
    }

    @Test
    fun `names a null dereference, because that is the most informative fault address`() {
        val record = NativeCrashRecord.parse(
            "v1 signal=SIGSEGV signo=11 code=1 fault=0x0 pid=1 tid=1 epoch=1 phase=model-load",
        )

        assertThat(record?.summary).isEqualTo("SIGSEGV during model-load (null dereference)")
    }

    @Test
    fun `a truncated record yields null instead of throwing`() {
        // The process was being killed mid-write. Entirely expected.
        assertThat(NativeCrashRecord.parse("v1 signal=SIGSEGV signo=")).isNull()
        assertThat(NativeCrashRecord.parse("v1 sig")).isNull()
        assertThat(NativeCrashRecord.parse("")).isNull()
    }

    @Test
    fun `a record from a future format version is ignored`() {
        assertThat(NativeCrashRecord.parse("v2 signal=SIGSEGV signo=11")).isNull()
    }

    @Test
    fun `consuming deletes the file so one crash is reported once`() {
        val file = File(temporaryFolder.root, NativeCrashRecord.FILE_NAME)
        file.writeText("v1 signal=SIGABRT signo=6 code=0 fault=0x0 pid=9 tid=9 epoch=5 phase=decode\n")

        assertThat(NativeCrashRecord.consume(file)?.signalName).isEqualTo("SIGABRT")

        assertThat(file.exists()).isFalse()
        // A leftover file would re-report the same crash on every launch, which
        // reads to a user as a crash loop that is not happening.
        assertThat(NativeCrashRecord.consume(file)).isNull()
    }

    @Test
    fun `a missing file is not a crash`() {
        assertThat(NativeCrashRecord.consume(File(temporaryFolder.root, "absent"))).isNull()
    }
}
