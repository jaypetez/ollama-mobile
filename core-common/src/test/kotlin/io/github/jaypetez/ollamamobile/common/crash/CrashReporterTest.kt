package io.github.jaypetez.ollamamobile.common.crash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.log.LogLevel
import io.github.jaypetez.ollamamobile.common.log.LogRecord
import io.github.jaypetez.ollamamobile.common.log.LogRing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {
    private lateinit var logRing: LogRing
    private lateinit var reporter: CrashReporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        logRing = LogRing(capacity = 8)
        reporter = CrashReporter(context, logRing)
        reporter.clear()
    }

    @Test
    fun `writes a JSON record containing the device, the app, the trace and the log ring`() {
        logRing.add(
            LogRecord(
                timestampMillis = 1_700_000_000_000,
                level = LogLevel.WARN,
                tag = "Inference",
                message = "backend fell back to the scalar kernel",
            ),
        )

        val file = reporter.record(Thread.currentThread(), IllegalStateException("engine exploded"))

        assertThat(file).isNotNull()
        val json = Json.parseToJsonElement(requireNotNull(file).readText()).jsonObject
        assertThat(json["schema"]?.jsonPrimitive?.content).isEqualTo("1")
        assertThat(json["device"]?.jsonObject?.get("model")).isNotNull()
        assertThat(
            json["app"]
                ?.jsonObject
                ?.get("packageName")
                ?.jsonPrimitive
                ?.content,
        ).contains("ollamamobile")
        assertThat(
            json["throwable"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("engine exploded")
        assertThat(
            json["throwable"]
                ?.jsonObject
                ?.get("stackTrace")
                ?.jsonPrimitive
                ?.content,
        ).contains("IllegalStateException")

        val log = requireNotNull(json["log"]).jsonArray
        assertThat(log).hasSize(1)
        assertThat(
            log
                .single()
                .jsonObject["message"]
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("backend fell back to the scalar kernel")
    }

    @Test
    fun `keeps only the most recent reports`() {
        repeat(CrashReporter.MAX_REPORTS + 5) { index ->
            reporter.record(Thread.currentThread(), RuntimeException("boom-$index"))
            // Filenames carry a millisecond timestamp; without a tick two
            // crashes in the same millisecond would collide.
            Thread.sleep(2)
        }

        assertThat(reporter.reports().size).isAtMost(CrashReporter.MAX_REPORTS)
    }

    @Test
    fun `installing twice does not chain the handler to itself`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            reporter.install()
            val afterFirst = Thread.getDefaultUncaughtExceptionHandler()
            reporter.install()

            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameInstanceAs(afterFirst)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
