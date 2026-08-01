package io.github.jaypetez.ollamamobile.storage.gguf

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.Quantization
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufHeaderParserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val parser = GgufHeaderParser()

    @Test
    fun `reads a normal header`() = runTest {
        val bytes = normalHeader().build()

        val metadata = parser.parse(ByteArrayGgufSource(bytes))

        assertThat(metadata.architecture).isEqualTo("llama")
        assertThat(metadata.name).isEqualTo("Test Model")
        assertThat(metadata.parameterCount).isEqualTo(1_500_000_000L)
        assertThat(metadata.contextLength).isEqualTo(8192)
        assertThat(metadata.embeddingLength).isEqualTo(2048)
        assertThat(metadata.blockCount).isEqualTo(28)
        assertThat(metadata.headCount).isEqualTo(16)
        assertThat(metadata.headCountKv).isEqualTo(8)
        assertThat(metadata.ropeFreqBase).isWithin(1e-3).of(10000.0)
        assertThat(metadata.tokenizerModel).isEqualTo("gpt2")
    }

    @Test
    fun `file_type is an llama_ftype, not a ggml_type`() = runTest {
        // ftype 15 is MOSTLY_Q4_K_M. ggml type 15 is Q8_K. Reading the field
        // with the ggml table would report an 8.5 bpw model as 4.85 bpw and
        // mis-size every estimate downstream.
        val metadata = parser.parse(ByteArrayGgufSource(normalHeader().build()))

        assertThat(metadata.fileType).isEqualTo(15)
        assertThat(metadata.quantization).isEqualTo(Quantization.Q4_K_M)
    }

    @Test
    fun `head_count_kv is accepted as a scalar`() = runTest {
        val metadata = parser.parse(ByteArrayGgufSource(normalHeader().build()))

        assertThat(metadata.headCountKv).isEqualTo(8)
    }

    @Test
    fun `head_count_kv is accepted as an int32 array`() = runTest {
        val bytes = GgufHeaderBuilder()
            .string("general.architecture", "jamba")
            .int32("jamba.block_count", 32)
            .int32("jamba.embedding_length", 4096)
            .int32("jamba.attention.head_count", 32)
            // Per-layer KV head counts: attention layers have 8, the state
            // layers have none. The estimate has to be an upper bound, so the
            // maximum is the right reading, not element zero.
            .int32Array("jamba.attention.head_count_kv", listOf(0, 8, 0, 8, 4))
            .build()

        val metadata = parser.parse(ByteArrayGgufSource(bytes))

        assertThat(metadata.headCountKv).isEqualTo(8)
    }

    @Test
    fun `a chat template of hundreds of kilobytes is read whole`() = runTest {
        val template = "{% for message in messages %}${"x".repeat(300_000)}{% endfor %}"
        val bytes = normalHeader()
            .string("tokenizer.chat_template", template)
            .build()

        val metadata = parser.parse(ByteArrayGgufSource(bytes))

        assertThat(metadata.chatTemplate).hasLength(template.length)
    }

    @Test
    fun `a value beyond the first window triggers a regrow`() = runTest {
        // The tokeniser's token array is 128k entries with no total length
        // recorded, so it can only be skipped by walking it — which pushes
        // everything after it well past a 64 KiB window.
        val bytes = GgufHeaderBuilder()
            .string("general.architecture", "llama")
            .stringArray("tokenizer.ggml.tokens", List(40_000) { "token$it" })
            .string("tokenizer.ggml.model", "gpt2")
            .build()
        assertThat(bytes.size).isGreaterThan(64 * 1024)
        val source = ByteArrayGgufSource(bytes)

        val metadata = parser.parse(source)

        assertThat(metadata.tokenizerModel).isEqualTo("gpt2")
        assertThat(source.requestedLengths).containsExactly(64 * 1024, 1024 * 1024).inOrder()
    }

    @Test
    fun `a truncated header is reported as corrupt without exhausting every window`() = runTest {
        val full = normalHeader().build()
        val source = ByteArrayGgufSource(full.copyOf(full.size / 2))

        val error = parseFailure(source)

        assertThat(error).isInstanceOf(AppError.Model.Corrupt::class.java)
        // The source declares its size, so the parser knows on the first read
        // that no larger window exists and stops asking.
        assertThat(source.requestedLengths).hasSize(1)
    }

    @Test
    fun `a bad magic is rejected`() = runTest {
        val bytes = GgufHeaderBuilder(magic = byteArrayOf(0x47, 0x47, 0x55, 0x47))
            .string("general.architecture", "llama")
            .build()

        val error = parseFailure(ByteArrayGgufSource(bytes))

        assertThat(error).isInstanceOf(AppError.Model.Corrupt::class.java)
        assertThat(error.message).contains("not a GGUF file")
    }

    @Test
    fun `an unsupported version is rejected`() = runTest {
        val bytes = GgufHeaderBuilder(version = 1)
            .string("general.architecture", "llama")
            .build()

        val error = parseFailure(ByteArrayGgufSource(bytes))

        assertThat(error).isInstanceOf(AppError.Model.Unsupported::class.java)
    }

    @Test
    fun `a header with no architecture is rejected`() = runTest {
        val bytes = GgufHeaderBuilder().string("general.name", "Nameless").build()

        val error = parseFailure(ByteArrayGgufSource(bytes))

        assertThat(error).isInstanceOf(AppError.Model.Corrupt::class.java)
    }

    @Test
    fun `a removed ggml type is refused when tensor types are validated`() = runTest {
        // Q4_0_4_8 (32) was retired when llama.cpp replaced offline repacking
        // with runtime GGML_CPU_REPACK. The file still looks well-formed.
        val bytes = normalHeader()
            .tensor("token_embd.weight", ggmlTypeId = 12)
            .tensor("blk.0.attn_q.weight", ggmlTypeId = 32)
            .build()

        val error = parseFailure(ByteArrayGgufSource(bytes), validateTensorTypes = true)

        assertThat(error).isInstanceOf(AppError.Model.Unsupported::class.java)
        assertThat(error.message).contains("Q4_0_4_8")
        assertThat(error.message).contains("blk.0.attn_q.weight")
    }

    @Test
    fun `supported tensor types pass validation`() = runTest {
        val bytes = normalHeader()
            .tensor("token_embd.weight", ggmlTypeId = 12)
            .tensor("blk.0.attn_q.weight", ggmlTypeId = 14)
            .build()

        val metadata = parser.parse(ByteArrayGgufSource(bytes), validateTensorTypes = true)

        assertThat(metadata.architecture).isEqualTo("llama")
    }

    @Test
    fun `unknown keys of every type are skipped`() = runTest {
        val bytes = GgufHeaderBuilder()
            .bool("general.some_flag", true)
            .float32("general.some_float", 1.5f)
            .uint64("general.some_long", 42)
            .stringArray("general.some_strings", listOf("a", "bb", "ccc"))
            .int32Array("general.some_ints", listOf(1, 2, 3))
            .string("general.architecture", "gemma3")
            .int32("gemma3.block_count", 26)
            .build()

        val metadata = parser.parse(ByteArrayGgufSource(bytes))

        assertThat(metadata.architecture).isEqualTo("gemma3")
        assertThat(metadata.blockCount).isEqualTo(26)
    }

    @Test
    fun `parsing stops once every wanted key has been seen`() = runTest {
        val bytes = everyWantedKey()
            // 200 keys after the last interesting one. If the scan ran to the
            // end of the KV block it would still succeed, so the assertion is
            // on where the parser stopped, not on the result.
            .also { builder -> repeat(200) { builder.string("general.padding_$it", "x".repeat(500)) } }
            .build()
        val trimmed = bytes.copyOf(bytes.size - 60_000)

        val metadata = parser.parse(ByteArrayGgufSource(trimmed))

        assertThat(metadata.chatTemplate).isEqualTo("{{ messages }}")
    }

    @Test
    fun `reads from a file on disk`() = runTest {
        val file = File(temporaryFolder.root, "model.gguf")
        file.writeBytes(normalHeader().build())

        val metadata = LocalFileGgufSource(file).use { parser.parse(it) }

        assertThat(metadata.architecture).isEqualTo("llama")
    }

    /** Asserts the parse failed with an [AppError] and returns it. */
    private suspend fun parseFailure(source: GgufSource, validateTensorTypes: Boolean = false): AppError {
        val thrown = runCatching { parser.parse(source, validateTensorTypes) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(AppErrorException::class.java)
        return (thrown as AppErrorException).error
    }

    private fun normalHeader() = GgufHeaderBuilder()
        .string("general.architecture", "llama")
        .string("general.name", "Test Model")
        .uint64("general.parameter_count", 1_500_000_000L)
        .uint32("general.file_type", 15)
        .int32("llama.context_length", 8192)
        .int32("llama.embedding_length", 2048)
        .int32("llama.block_count", 28)
        .int32("llama.attention.head_count", 16)
        .int32("llama.attention.head_count_kv", 8)
        .float32("llama.rope.freq_base", 10_000f)
        .string("tokenizer.ggml.model", "gpt2")

    private fun everyWantedKey() = normalHeader().string("tokenizer.chat_template", "{{ messages }}")
}
