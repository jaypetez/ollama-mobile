package io.github.jaypetez.ollamamobile.llm.testing

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The fake is shipped code, so it gets tested like shipped code.
 *
 * A fake that quietly stops matching the contract is worse than no fake: every
 * consumer's test keeps passing while the thing they model has changed.
 */
class FakeLlamaEngineTest {
    private val model = ModelRef(
        id = ModelId("fake"),
        displayName = "Fake",
        name = "fake.gguf",
        origin = ModelOrigin.Local("/fake.gguf"),
    )

    private val request = InferenceRequest(model, listOf(InferenceMessage.user("hi")))

    private fun spec() = ModelLoadSpec(model = model, path = "/fake.gguf")

    @Test
    fun `a loaded engine emits Started, the script, Stats and Completed`() = runTest {
        val engine = FakeLlamaEngine()
        engine.load(spec())

        val events = engine.generate(request).toList()

        assertThat(events.first()).isInstanceOf(InferenceEvent.Started::class.java)
        assertThat(events.filterIsInstance<InferenceEvent.Token>().map { it.text })
            .containsExactlyElementsIn(FakeLlamaEngine.DEFAULT_SCRIPT)
            .inOrder()
        assertThat(events.last()).isEqualTo(InferenceEvent.Completed(FinishReason.STOP))
    }

    @Test
    fun `the same script produces the same events every time`() = runTest {
        val engine = FakeLlamaEngine()
        engine.load(spec())

        assertThat(engine.generate(request).toList())
            .isEqualTo(engine.generate(request).toList())
    }

    @Test
    fun `generating with nothing loaded fails rather than answering`() = runTest {
        val events = FakeLlamaEngine().generate(request).toList()

        assertThat((events.single() as InferenceEvent.Failed).error)
            .isInstanceOf(AppError.Engine.NotAvailable::class.java)
    }

    @Test
    fun `a scripted failure keeps the tokens already emitted`() = runTest {
        // The contract says a partial answer plus an explanation beats a blank
        // bubble, so the fake has to behave that way too.
        val engine = FakeLlamaEngine.failing(afterTokens = 2)
        engine.load(spec())

        val events = engine.generate(request).toList()

        assertThat(events.filterIsInstance<InferenceEvent.Token>()).hasSize(2)
        assertThat(events.last()).isInstanceOf(InferenceEvent.Failed::class.java)
    }

    @Test
    fun `a slow stream can be cancelled partway and delivers what it had`() = runTest {
        val engine = FakeLlamaEngine.slow(count = 50)
        engine.load(spec())

        val events = engine.generate(request).take(4).toList()

        assertThat(events.filterIsInstance<InferenceEvent.Token>()).hasSize(3)
    }

    @Test
    fun `token delays run on virtual time, so a long stream costs nothing`() = runTest {
        val engine = FakeLlamaEngine.slow(count = 500)
        engine.load(spec())

        val events = engine.generate(request).toList()

        assertThat(events.filterIsInstance<InferenceEvent.Token>()).hasSize(500)
        assertThat(currentTimeMillisVirtual()).isAtLeast(500_000L)
    }

    @Test
    fun `an unavailable engine models a build with no native code`() = runTest {
        val engine = FakeLlamaEngine.unavailable()
        engine.load(spec())

        assertThat(engine.isAvailable).isFalse()
        assertThat(engine.generate(request).toList().single())
            .isInstanceOf(InferenceEvent.Failed::class.java)
    }

    @Test
    fun `a scripted load failure surfaces as the typed error it was given`() = runTest {
        val engine = FakeLlamaEngine(loadFailure = AppError.Model.Corrupt())

        val thrown = runCatching { engine.load(spec()) }.exceptionOrNull()

        assertThat((thrown as AppErrorException).error)
            .isInstanceOf(AppError.Model.Corrupt::class.java)
    }

    @Test
    fun `embeddings are stable for the same text and differ for different text`() = runTest {
        val engine = FakeLlamaEngine()

        assertThat(engine.embed("alpha")).isEqualTo(engine.embed("alpha"))
        assertThat(engine.embed("alpha")).isNotEqualTo(engine.embed("beta"))
    }

    @Test
    fun `loads and requests are recorded so a consumer can assert on them`() = runTest {
        val engine = FakeLlamaEngine()
        engine.load(spec())
        engine.generate(request).toList()
        engine.unload()

        assertThat(engine.loads).hasSize(1)
        assertThat(engine.requests).containsExactly(request)
        assertThat(engine.unloadCount).isEqualTo(1)
    }

    @Test
    fun `the loaded model is published`() = runTest {
        val engine = FakeLlamaEngine()

        engine.loadedModel.test {
            assertThat(awaitItem()).isNull()
            engine.load(spec())
            assertThat(awaitItem()).isEqualTo(model)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.currentTimeMillisVirtual(): Long =
        testScheduler.currentTime
}
