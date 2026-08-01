package io.github.jaypetez.ollamamobile.download

import android.app.ActivityManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.GgmlType
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.storage.MemoryEstimator
import io.github.jaypetez.ollamamobile.storage.gguf.ByteArrayGgufSource
import io.github.jaypetez.ollamamobile.storage.gguf.GgufHeaderParser
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The two gates a model has to pass, and the honesty of the middle answer.
 *
 * `Tight` is a warning the user may override; `Refuse` is not. Collapsing them
 * into a boolean either lies to people with borderline devices or lets them
 * OOM-kill themselves.
 */
@RunWith(RobolectricTestRunner::class)
class CompatibilityCheckerTest {
    private val estimator = MemoryEstimator(ApplicationProvider.getApplicationContext())
    private val checker = CompatibilityChecker(GgufHeaderParser(), estimator)

    private fun setAvailableMemory(bytes: Long) {
        val manager = ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSystemService(ActivityManager::class.java)
        shadowOf(manager).setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                availMem = bytes
                threshold = 0
                lowMemory = false
                totalMem = bytes * 2
            },
        )
    }

    private fun check(bytes: ByteArray, fileSizeBytes: Long, validateTensors: Boolean = true) = runBlocking {
        checker.check(
            source = ByteArrayGgufSource(bytes),
            fileName = "model-Q4_K_M.gguf",
            requestedContextLength = 4096,
            fileSizeBytes = fileSizeBytes,
            kvCacheType = io.github.jaypetez.ollamamobile.storage.KvCacheType.F16,
            validateTensorTypes = validateTensors,
        )
    }

    @Test
    fun `a tensor using a removed ggml type is refused before it reaches native code`() {
        // A file carrying a retired id looks perfectly well-formed all the way to
        // the JNI boundary, where ggml aborts the process and no Kotlin `try`
        // can catch it.
        val bytes = tinyGguf { tensor("blk.0.attn_q.weight", GgmlType.Q4_0_4_4.id) }

        val report = check(bytes, fileSizeBytes = 500L * 1024 * 1024)

        assertThat(report.isAllowed).isFalse()
        val blocker = report.blocker
        assertThat(blocker).isInstanceOf(DownloadError.Incompatible::class.java)
        assertThat((blocker as DownloadError.Incompatible).reason).contains("Q4_0_4_4")
    }

    @Test
    fun `a supported tensor type passes the format gate`() {
        setAvailableMemory(8L * 1024 * 1024 * 1024)
        val bytes = tinyGguf { tensor("blk.0.attn_q.weight", GgmlType.Q4_K.id) }

        val report = check(bytes, fileSizeBytes = 500L * 1024 * 1024)

        assertThat(report.blocker).isNull()
        assertThat(report.metadata.architecture).isEqualTo("qwen3")
        assertThat(report.verdict).isInstanceOf(MemoryVerdict.Fits::class.java)
    }

    @Test
    fun `a model larger than the device can hold is refused, not merely warned about`() {
        setAvailableMemory(2L * 1024 * 1024 * 1024)
        val bytes = tinyGguf { tensor("blk.0.attn_q.weight", GgmlType.Q4_K.id) }

        val report = check(bytes, fileSizeBytes = 6L * 1024 * 1024 * 1024)

        assertThat(report.verdict).isInstanceOf(MemoryVerdict.Refuse::class.java)
        assertThat(report.blocker).isInstanceOf(DownloadError.MemoryRefused::class.java)
        assertThat(report.isAllowed).isFalse()
        assertThat(report.explanation).isNotEmpty()
    }

    @Test
    fun `a tight fit is a warning the user may override, not a refusal`() {
        // Sized so that weights plus cache plus the 256 MiB runtime reserve land
        // just inside the tight band — under a hundred megabytes of headroom,
        // which is where a background app waking up tips the process over.
        setAvailableMemory(3_440_000_000L)
        val bytes = tinyGguf { tensor("blk.0.attn_q.weight", GgmlType.Q4_K.id) }

        val report = check(bytes, fileSizeBytes = 2_200L * 1024 * 1024)

        assertThat(report.verdict).isInstanceOf(MemoryVerdict.Tight::class.java)
        assertThat(report.blocker).isNull()
        assertThat(report.isAllowed).isTrue()
        assertThat(report.isTight).isTrue()
    }

    @Test
    fun `bytes that are not a GGUF are refused with a reason rather than a memory verdict`() {
        val report = check("<html>502 Bad Gateway</html>".toByteArray(), fileSizeBytes = 28)

        assertThat(report.blocker).isInstanceOf(DownloadError.Incompatible::class.java)
        assertThat(report.metadata.architecture).isEqualTo("unknown")
    }

    @Test
    fun `the budgeted context never exceeds what the model was trained for`() {
        setAvailableMemory(8L * 1024 * 1024 * 1024)
        val bytes = tinyGguf(contextLength = 2048) { tensor("blk.0.attn_q.weight", GgmlType.Q4_K.id) }

        val report = check(bytes, fileSizeBytes = 500L * 1024 * 1024)

        assertThat(report.contextLength).isEqualTo(2048)
    }
}

/**
 * The smallest GGUF the parser will accept, with the shape fields the memory
 * estimate needs.
 *
 * Hand-rolled rather than built on the production writer — there is none — and
 * deliberately not shared with `:core-storage`'s fixture: a test that encodes
 * with the same code it decodes with cannot catch an endianness mistake.
 */
private fun tinyGguf(contextLength: Int = 32768, tensors: TinyGgufBuilder.() -> Unit = {}): ByteArray =
    TinyGgufBuilder(contextLength).apply(tensors).build()

private class TinyGgufBuilder(
    private val contextLength: Int,
) {
    private val tensors = mutableListOf<ByteArray>()

    fun tensor(name: String, ggmlTypeId: Int) {
        tensors += bytes {
            lengthPrefixed(name)
            uint32(1)
            uint64(4096)
            uint32(ggmlTypeId)
            uint64(0)
        }
    }

    fun build(): ByteArray {
        val kv = listOf(
            kvString("general.architecture", "qwen3"),
            kvString("general.name", "Tiny"),
            kvUint32("qwen3.context_length", contextLength),
            kvUint32("qwen3.embedding_length", 2048),
            kvUint32("qwen3.block_count", 28),
            kvUint32("qwen3.attention.head_count", 16),
            kvUint32("qwen3.attention.head_count_kv", 8),
            kvUint32("general.file_type", 15),
        )
        return bytes {
            write(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            uint32(3)
            uint64(tensors.size.toLong())
            uint64(kv.size.toLong())
            kv.forEach { write(it) }
            tensors.forEach { write(it) }
        }
    }

    private fun kvString(key: String, value: String) = bytes {
        lengthPrefixed(key)
        uint32(TYPE_STRING)
        lengthPrefixed(value)
    }

    private fun kvUint32(key: String, value: Int) = bytes {
        lengthPrefixed(key)
        uint32(TYPE_UINT32)
        uint32(value)
    }

    private fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()

    private fun ByteArrayOutputStream.uint32(value: Int) {
        repeat(4) { index -> write((value ushr (index * 8)) and 0xFF) }
    }

    private fun ByteArrayOutputStream.uint64(value: Long) {
        repeat(8) { index -> write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }

    private fun ByteArrayOutputStream.lengthPrefixed(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        uint64(encoded.size.toLong())
        write(encoded)
    }

    private companion object {
        const val TYPE_UINT32 = 4
        const val TYPE_STRING = 8
    }
}
