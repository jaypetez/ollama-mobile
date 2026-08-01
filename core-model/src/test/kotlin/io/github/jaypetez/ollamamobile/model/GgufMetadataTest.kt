package io.github.jaypetez.ollamamobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class GgufMetadataTest {
    @ParameterizedTest
    @CsvSource(
        "0, F32",
        "1, F16",
        "2, Q4_0",
        "7, Q8_0",
        "8, Q5_0",
        "10, Q2_K",
        "11, Q3_K_S",
        "12, Q3_K_M",
        "13, Q3_K_L",
        "14, Q4_K_S",
        "15, Q4_K_M",
        "16, Q5_K_S",
        "17, Q5_K_M",
        "18, Q6_K",
        "21, Q2_K",
    )
    fun `maps llama_ftype to a quantisation`(fileType: Int, expected: Quantization) {
        assertThat(GgufMetadata.quantizationFromFileType(fileType)).isEqualTo(expected)
    }

    @Test
    fun `ftype numbering is not ggml_type numbering`() {
        // ggml type 15 is Q8_K; ftype 15 is Q4_K_M. Reading one enum with the
        // other's constants mislabels every model in the picker.
        assertThat(GgufMetadata.quantizationFromFileType(15)).isEqualTo(Quantization.Q4_K_M)
        assertThat(GgmlType.fromId(15)).isEqualTo(GgmlType.Q8_K)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 3, 9, 19, 30, 32, 1025])
    fun `returns null for file types we do not model`(fileType: Int) {
        // IQ formats, BF16, Q4_1/Q5_1 and the "guessed" sentinel: unknown is
        // the honest answer and the caller falls back to the filename.
        assertThat(GgufMetadata.quantizationFromFileType(fileType)).isNull()
    }

    @Test
    fun `metadata resolves its own file type`() {
        val metadata = GgufMetadata(architecture = "qwen3", name = "Qwen3 1.7B", fileType = 15)

        assertThat(metadata.quantization).isEqualTo(Quantization.Q4_K_M)
    }

    @Test
    fun `metadata with no file type has no quantisation`() {
        assertThat(GgufMetadata(architecture = "llama").quantization).isNull()
    }

    @Test
    fun `only the architecture is required`() {
        val metadata = GgufMetadata(architecture = "gemma3")

        assertThat(metadata.architecture).isEqualTo("gemma3")
        assertThat(metadata.name).isNull()
        assertThat(metadata.parameterCount).isNull()
        assertThat(metadata.contextLength).isNull()
        assertThat(metadata.embeddingLength).isNull()
        assertThat(metadata.blockCount).isNull()
        assertThat(metadata.headCount).isNull()
        assertThat(metadata.headCountKv).isNull()
        assertThat(metadata.ropeFreqBase).isNull()
        assertThat(metadata.chatTemplate).isNull()
        assertThat(metadata.tokenizerModel).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [4, 5, 31, 32, 33, 36, 37, 38])
    fun `removed ggml types are typed rejections, not crashes`(id: Int) {
        val type = GgmlType.fromId(id)

        assertThat(type).isNotNull()
        assertThat(type!!.removed).isTrue()
        assertThat(type.isSupported).isFalse()
        assertThat(GgmlType.removedTypes).contains(type)
    }

    @Test
    fun `the repacked Q4_0 variants are the removed ids 31 to 33`() {
        // These are the ones a real file still carries: they were written by
        // converters before llama.cpp moved to runtime repacking, and current
        // ggml refuses them. Naming them is what lets the loader say why.
        assertThat(GgmlType.fromId(31)).isEqualTo(GgmlType.Q4_0_4_4)
        assertThat(GgmlType.fromId(32)).isEqualTo(GgmlType.Q4_0_4_8)
        assertThat(GgmlType.fromId(33)).isEqualTo(GgmlType.Q4_0_8_8)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 8, 12, 14, 30, 34, 39])
    fun `current types stay supported`(id: Int) {
        val type = GgmlType.fromId(id)

        assertThat(type).isNotNull()
        assertThat(type!!.isSupported).isTrue()
    }

    @Test
    fun `an unknown id is null so it can be refused too`() {
        assertThat(GgmlType.fromId(40)).isNull()
        assertThat(GgmlType.fromId(-1)).isNull()
    }

    @Test
    fun `ids are unique and match declaration order`() {
        val ids = GgmlType.entries.map { it.id }

        assertThat(ids).containsNoDuplicates()
        assertThat(ids).isInOrder()
    }

    @Test
    fun `a rejected type maps onto the error vocabulary`() {
        val type = GgmlType.Q4_0_4_8
        val error = AppError.Model.Unsupported(reason = "tensor type ${type.name} (${type.id}) was removed from ggml")

        assertThat(error.message).contains("Q4_0_4_8")
        assertThat(error.cause).isNull()
    }
}
