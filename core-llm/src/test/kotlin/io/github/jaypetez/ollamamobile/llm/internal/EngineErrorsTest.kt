package io.github.jaypetez.ollamamobile.llm.internal

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.model.AppError
import org.junit.Test

/**
 * Native failure strings to typed errors.
 *
 * The classification matters because each arm leads somewhere different in the
 * UI: "shrink the context" is an action, "this model is not supported" is a
 * different screen, and a generic engine failure is neither.
 */
class EngineErrorsTest {
    @Test
    fun `a context that did not fit suggests shrinking it`() {
        val error = EngineErrors.loadFailure(
            "llama_init_from_model failed; the context did not fit.",
        )

        assertThat(error).isInstanceOf(AppError.Engine.LoadFailed::class.java)
        assertThat(error.message).contains("smaller")
    }

    @Test
    fun `an unknown architecture is a model problem, not an engine problem`() {
        val error = EngineErrors.loadFailure("unknown model architecture: 'mamba9'")

        assertThat(error).isInstanceOf(AppError.Model.Unsupported::class.java)
    }

    @Test
    fun `a bad magic number is reported as a damaged file`() {
        val error = EngineErrors.loadFailure("wrong magic in GGUF header")

        assertThat(error).isInstanceOf(AppError.Model.Corrupt::class.java)
    }

    @Test
    fun `an unclassified message is preserved verbatim rather than replaced`() {
        // Losing the native text would leave a bug report with nothing in it.
        val error = EngineErrors.loadFailure("tensor 'blk.0.attn_q.weight' not found")

        assertThat(error).isInstanceOf(AppError.Engine.LoadFailed::class.java)
        assertThat(error.message).contains("blk.0.attn_q.weight")
    }

    @Test
    fun `a null or blank native message still produces a sentence`() {
        assertThat(EngineErrors.loadFailure(null).message).isNotEmpty()
        assertThat(EngineErrors.loadFailure("   ").message).isNotEmpty()
        assertThat(EngineErrors.generationFailure(null).message).isNotEmpty()
    }

    @Test
    fun `a failure during generation stays a generation failure`() {
        // Deliberately not reclassified: tokens may already be on screen, and
        // "the model is corrupt" is the wrong thing to say about a stream that
        // was working a second ago.
        val error = EngineErrors.generationFailure("llama_decode failed with status -3")

        assertThat(error).isInstanceOf(AppError.Engine.GenerationFailed::class.java)
        assertThat(error.message).contains("-3")
    }

    @Test
    fun `finish codes map onto the public vocabulary`() {
        assertThat(NativeFinishReason.toFinishReason(NativeFinishReason.STOP))
            .isEqualTo(FinishReason.STOP)
        assertThat(NativeFinishReason.toFinishReason(NativeFinishReason.LENGTH))
            .isEqualTo(FinishReason.LENGTH)
        assertThat(NativeFinishReason.toFinishReason(NativeFinishReason.CANCELLED))
            .isEqualTo(FinishReason.CANCELLED)
    }

    @Test
    fun `an unexpected finish code is UNKNOWN rather than an exception`() {
        assertThat(NativeFinishReason.toFinishReason(NativeFinishReason.RUNNING))
            .isEqualTo(FinishReason.UNKNOWN)
        assertThat(NativeFinishReason.toFinishReason(9_999)).isEqualTo(FinishReason.UNKNOWN)
    }

    @Test
    fun `only the error code is a failure`() {
        assertThat(NativeFinishReason.isFailure(NativeFinishReason.ERROR)).isTrue()
        assertThat(NativeFinishReason.isFailure(NativeFinishReason.STOP)).isFalse()
        assertThat(NativeFinishReason.isFailure(NativeFinishReason.LENGTH)).isFalse()
        assertThat(NativeFinishReason.isFailure(NativeFinishReason.CANCELLED)).isFalse()
    }
}
