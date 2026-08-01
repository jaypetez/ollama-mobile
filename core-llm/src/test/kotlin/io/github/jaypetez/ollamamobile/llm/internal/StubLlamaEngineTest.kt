package io.github.jaypetez.ollamamobile.llm.internal

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The engine the **default** build gets.
 *
 * `-Pollama.nativeSource=none` is the default, so this is what every CI job and
 * every fresh clone actually runs. It is worth pinning that it fails
 * informatively rather than silently.
 */
class StubLlamaEngineTest {
    private val model = ModelRef(
        id = ModelId("m"),
        displayName = "M",
        name = "m.gguf",
        origin = ModelOrigin.Local("/m.gguf"),
    )

    @Test
    fun `it reports itself unavailable rather than pretending`() {
        assertThat(StubLlamaEngine().isAvailable).isFalse()
    }

    @Test
    fun `generate fails with a specific error instead of returning nothing`() = runTest {
        // An empty stream would reach the UI as a blank assistant bubble, which
        // looks like the model having nothing to say.
        val events = StubLlamaEngine()
            .generate(InferenceRequest(model, listOf(InferenceMessage.user("hi"))))
            .toList()

        val failure = events.single() as InferenceEvent.Failed
        assertThat(failure.error).isInstanceOf(AppError.Engine.NotAvailable::class.java)
    }

    @Test
    fun `load throws a typed error`() = runTest {
        val thrown = runCatching {
            StubLlamaEngine().load(ModelLoadSpec(model = model, path = "/m.gguf"))
        }.exceptionOrNull()

        assertThat((thrown as AppErrorException).error)
            .isInstanceOf(AppError.Engine.NotAvailable::class.java)
    }

    @Test
    fun `unload succeeds, because unloading nothing is not a failure`() = runTest {
        StubLlamaEngine().unload()
    }

    @Test
    fun `tokenCount is zero rather than a plausible-looking estimate`() = runTest {
        assertThat(StubLlamaEngine().tokenCount("a fairly long sentence")).isEqualTo(0)
    }

    @Test
    fun `nothing is ever reported as loaded`() = runTest {
        assertThat(StubLlamaEngine().loadedModel.value).isNull()
    }
}
