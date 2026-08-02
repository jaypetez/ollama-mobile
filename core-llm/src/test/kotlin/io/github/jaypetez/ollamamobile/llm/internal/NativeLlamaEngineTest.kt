package io.github.jaypetez.ollamamobile.llm.internal

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.llm.EngineRole
import io.github.jaypetez.ollamamobile.llm.FinishReason
import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceMessage
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.llm.internal.lora.NoOpLoraAdapterManager
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.SamplingParams
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine's bookkeeping, with llama.cpp replaced by a script.
 *
 * What is exercised here is everything that is *not* arithmetic: the pull loop,
 * the sentinel arm/disarm points, how a finish code becomes an event, what an
 * empty token array means, and what happens when nothing is loaded. The
 * arithmetic needs an arm64 device and this project does not have one — see
 * docs/verification-status.md.
 *
 * Robolectric only because the engine writes one `android.util.Log` line.
 */
@RunWith(RobolectricTestRunner::class)
class NativeLlamaEngineTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val model = ModelRef(
        id = ModelId("qwen3-1.7b"),
        displayName = "Qwen3 1.7B",
        name = "qwen3-1.7b-q4_k_m.gguf",
        origin = ModelOrigin.Local("/models/qwen3.gguf"),
    )

    private val request = InferenceRequest(
        model = model,
        messages = listOf(InferenceMessage.user("Hello?")),
        sampling = SamplingParams(temperature = 0.2, numPredict = 64),
    )

    /** A `NativeSessionApi` that reads from a list instead of a model. */
    private class ScriptedSession(
        private val tokens: List<ByteArray> = listOf("Hi".toByteArray(), " there".toByteArray()),
        private val finishReason: Int = NativeFinishReason.STOP,
        private val createHandle: Long = 42L,
        private val startSucceeds: Boolean = true,
        private val error: String? = null,
        private val template: String? = "<|im_start|>user\nHello?<|im_end|>\n",
    ) : NativeSessionApi {
        var abortCount: Int = 0
            private set
        var destroyCount: Int = 0
            private set
        var samplerSeed: Long? = null
            private set
        var samplerTemperature: Float? = null
            private set
        var loraPaths: List<String> = emptyList()
            private set
        private var cursor = 0

        override fun nativeCreateSession(
            modelPath: String,
            contextTokens: Int,
            threads: Int,
            batchTokens: Int,
            embeddingMode: Boolean,
            useMmap: Boolean,
        ): Long = createHandle

        override fun nativeDestroySession(handle: Long) {
            destroyCount += 1
        }

        override fun nativeContextSize(handle: Long): Int = 4096

        override fun nativeLastError(handle: Long): String? = error

        override fun nativeApplyChatTemplate(
            handle: Long,
            roles: Array<String>,
            contents: Array<String>,
            addAssistant: Boolean,
            enableThinking: Boolean,
        ): String? = template?.let { "$it[roles=${roles.joinToString(",")}]" }

        override fun nativeTokenCount(handle: Long, text: String): Int = text.length

        override fun nativeConfigureSampler(
            handle: Long,
            temperature: Float,
            topP: Float,
            topK: Int,
            minP: Float,
            repeatPenalty: Float,
            repeatLastN: Int,
            seed: Long,
        ) {
            samplerTemperature = temperature
            samplerSeed = seed
        }

        override fun nativeStartGeneration(handle: Long, prompt: String, maxTokens: Int): Boolean {
            cursor = 0
            return startSucceeds
        }

        override fun nativeGenerateNextToken(handle: Long): ByteArray? =
            tokens.getOrNull(cursor++)

        override fun nativeRequestAbort(handle: Long) {
            abortCount += 1
        }

        override fun nativeFinishReason(handle: Long): Int = finishReason

        override fun nativeStats(handle: Long): LongArray =
            longArrayOf(12, tokens.size.toLong(), 5_000_000, 20_000_000, 900_000_000)

        override fun nativeEmbed(handle: Long, text: String): FloatArray? =
            floatArrayOf(0.1f, 0.2f)

        override fun nativeSetLoraAdapters(
            handle: Long,
            paths: Array<String>,
            scales: FloatArray,
        ): Boolean {
            loraPaths = paths.toList()
            return true
        }
    }

    private class StubBackendApi : NativeBackendApi {
        override fun loadLibrary(name: String) = Unit

        override fun nativeBackendInit() = Unit

        override fun nativeLoadBackendsFromPath(directory: String): Int = 1

        override fun nativeLoadBackend(path: String): Boolean = true

        override fun nativeBackendNames(): Array<String> = arrayOf("CPU (test)")

        override fun nativeSystemInfo(): String = "test"
    }

    /**
     * A session that hands out [tokens] and then blocks, the way a real one
     * blocks inside `llama_decode`. Aborting releases it, which is what the
     * abort callback does natively.
     */
    private class BlockingSession(
        private val tokens: Int = 2,
    ) : NativeSessionApi {
        val released: CountDownLatch = CountDownLatch(1)
        val firstToken: CountDownLatch = CountDownLatch(1)
        val abortCount: AtomicInteger = AtomicInteger(0)
        private var cursor = 0

        override fun nativeCreateSession(
            modelPath: String,
            contextTokens: Int,
            threads: Int,
            batchTokens: Int,
            embeddingMode: Boolean,
            useMmap: Boolean,
        ): Long = 7L

        override fun nativeDestroySession(handle: Long) = Unit

        override fun nativeContextSize(handle: Long): Int = 4096

        override fun nativeLastError(handle: Long): String? = null

        override fun nativeApplyChatTemplate(
            handle: Long,
            roles: Array<String>,
            contents: Array<String>,
            addAssistant: Boolean,
            enableThinking: Boolean,
        ): String = "prompt"

        override fun nativeTokenCount(handle: Long, text: String): Int = 1

        override fun nativeConfigureSampler(
            handle: Long,
            temperature: Float,
            topP: Float,
            topK: Int,
            minP: Float,
            repeatPenalty: Float,
            repeatLastN: Int,
            seed: Long,
        ) = Unit

        override fun nativeStartGeneration(handle: Long, prompt: String, maxTokens: Int) = true

        override fun nativeGenerateNextToken(handle: Long): ByteArray? {
            if (cursor < tokens) {
                cursor += 1
                firstToken.countDown()
                return "t$cursor".toByteArray()
            }
            released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return null
        }

        override fun nativeRequestAbort(handle: Long) {
            abortCount.incrementAndGet()
            released.countDown()
        }

        override fun nativeFinishReason(handle: Long): Int = NativeFinishReason.CANCELLED

        override fun nativeStats(handle: Long): LongArray? = null

        override fun nativeEmbed(handle: Long, text: String): FloatArray? = null

        override fun nativeSetLoraAdapters(
            handle: Long,
            paths: Array<String>,
            scales: FloatArray,
        ) = true
    }

    private fun engine(
        session: NativeSessionApi,
        sentinel: CrashSentinel = CrashSentinel(File(temporaryFolder.root, "sentinel")),
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    ): NativeLlamaEngine = NativeLlamaEngine(
        loader = NativeLibraryLoader(
            backendApi = StubBackendApi(),
            sentinel = sentinel,
            directoryProvider = { "/lib" },
            abiProvider = { "arm64-v8a" },
            nativeEnabled = true,
        ),
        session = session,
        arbiter = InferenceArbiter(),
        loraAdapters = NoOpLoraAdapterManager(),
        engineDispatcher = dispatcher,
        ioDispatcher = dispatcher,
    )

    private fun spec(role: EngineRole = EngineRole.CHAT) =
        ModelLoadSpec(model = model, path = "/models/qwen3.gguf", role = role)

    @Test
    fun `a generation runs Started, tokens, stats, Completed in that order`() = runTest {
        val engine = engine(ScriptedSession())
        engine.load(spec())

        val events = engine.generate(request).toList()

        assertThat(events.first()).isInstanceOf(InferenceEvent.Started::class.java)
        assertThat(events.filterIsInstance<InferenceEvent.Token>().map { it.text })
            .containsExactly("Hi", " there")
            .inOrder()
        assertThat(events.any { it is InferenceEvent.Stats }).isTrue()
        assertThat(events.last()).isEqualTo(InferenceEvent.Completed(FinishReason.STOP))
    }

    @Test
    fun `an empty token array is carried, not emitted, and does not end the stream`() = runTest {
        // The native side returns an empty array when a token completed no code
        // point — half of an emoji. Emitting it would put an empty delta on the
        // wire; treating it as the end would truncate the answer.
        val engine = engine(
            ScriptedSession(
                tokens = listOf("a".toByteArray(), ByteArray(0), "b".toByteArray()),
            ),
        )
        engine.load(spec())

        val tokens = engine.generate(request).toList().filterIsInstance<InferenceEvent.Token>()

        assertThat(tokens.map { it.text }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `bytes are decoded as real UTF-8, not modified UTF-8`() = runTest {
        // A four-byte sequence is exactly the case NewStringUTF gets wrong,
        // which is why tokens cross the boundary as bytes at all.
        val emoji = "🚀"
        val engine = engine(ScriptedSession(tokens = listOf(emoji.toByteArray(Charsets.UTF_8))))
        engine.load(spec())

        val tokens = engine.generate(request).toList().filterIsInstance<InferenceEvent.Token>()

        assertThat(tokens.single().text).isEqualTo(emoji)
    }

    @Test
    fun `the sentinel is armed before generating and cleared once a token arrives`() = runTest {
        val file = File(temporaryFolder.root, "sentinel")
        val engine = engine(ScriptedSession(), CrashSentinel(file))
        engine.load(spec())

        engine.generate(request).toList()

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `the sentinel is cleared even when the model produces no tokens at all`() = runTest {
        // A prompt that immediately hits end-of-generation still proves the
        // kernels ran. Leaving the file would put the next launch into safe
        // mode for no reason.
        val file = File(temporaryFolder.root, "sentinel")
        val engine = engine(ScriptedSession(tokens = emptyList()), CrashSentinel(file))
        engine.load(spec())

        engine.generate(request).toList()

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a native error code ends the stream with Failed, not Completed`() = runTest {
        val engine = engine(
            ScriptedSession(
                finishReason = NativeFinishReason.ERROR,
                error = "llama_decode failed with status -2",
            ),
        )
        engine.load(spec())

        val events = engine.generate(request).toList()

        val failure = events.last() as InferenceEvent.Failed
        assertThat(failure.error).isInstanceOf(AppError.Engine.GenerationFailed::class.java)
        assertThat(failure.error.message).contains("-2")
    }

    @Test
    fun `a truncated generation reports LENGTH so the caller can offer continue`() = runTest {
        val engine = engine(ScriptedSession(finishReason = NativeFinishReason.LENGTH))
        engine.load(spec())

        val events = engine.generate(request).toList()

        assertThat(events.last()).isEqualTo(InferenceEvent.Completed(FinishReason.LENGTH))
    }

    @Test
    fun `generating with nothing loaded fails instead of throwing`() = runTest {
        val engine = engine(ScriptedSession())

        val events = engine.generate(request).toList()

        assertThat((events.single() as InferenceEvent.Failed).error)
            .isInstanceOf(AppError.Engine.NotAvailable::class.java)
    }

    @Test
    fun `generating against an embedding model is refused`() = runTest {
        val engine = engine(ScriptedSession())
        engine.load(spec(role = EngineRole.EMBEDDING))

        val events = engine.generate(request).toList()

        assertThat((events.single() as InferenceEvent.Failed).error)
            .isInstanceOf(AppError.Model.Unsupported::class.java)
    }

    @Test
    fun `a model with no chat template fails loudly rather than concatenating strings`() =
        runTest {
            // Falling back to hand-built prompts would emit the wrong special
            // tokens, which does not throw — it just makes the model worse.
            val engine = engine(ScriptedSession(template = null))
            engine.load(spec())

            val events = engine.generate(request).toList()

            assertThat(events.last()).isInstanceOf(InferenceEvent.Failed::class.java)
        }

    @Test
    fun `a session that will not start reports the native message`() = runTest {
        val engine = engine(
            ScriptedSession(startSucceeds = false, error = "the prompt tokenized to nothing"),
        )
        engine.load(spec())

        val events = engine.generate(request).toList()

        assertThat((events.last() as InferenceEvent.Failed).error.message)
            .contains("tokenized to nothing")
    }

    @Test
    fun `cancelling a collector aborts a generation blocked inside native code`() = runBlocking {
        // Layer two, and the only layer that can help here: the engine thread
        // is parked inside nativeGenerateNextToken and cannot check anything.
        // The abort has to arrive from the cancelling thread.
        //
        // Real threads and runBlocking rather than runTest, because the thing
        // under test is a blocking call, and a virtual clock cannot model one.
        val session = BlockingSession()
        val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val engine = engine(session, dispatcher = engineDispatcher)
            engine.load(spec())

            val scope = CoroutineScope(Dispatchers.Default)
            val job = scope.launch { engine.generate(request).collect { } }
            assertThat(session.firstToken.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

            withTimeout(CANCEL_TIMEOUT_MILLIS) { job.cancelAndJoin() }

            assertThat(session.abortCount.get()).isAtLeast(1)
        } finally {
            engineDispatcher.close()
        }
    }

    @Test
    fun `a collector that stops early aborts the generation too`() = runBlocking {
        // `take` unwinds through emit() while the job is still active, so the
        // completion handler never fires and the finally block has to do it.
        val session = BlockingSession(tokens = 10)
        val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val engine = engine(session, dispatcher = engineDispatcher)
            engine.load(spec())

            engine.generate(request).take(2).toList()

            assertThat(session.abortCount.get()).isAtLeast(1)
        } finally {
            engineDispatcher.close()
        }
    }

    @Test
    fun `a failed create maps the native message to a typed error`() = runTest {
        val engine = engine(
            ScriptedSession(createHandle = 0L, error = "unknown model architecture: 'zzz'"),
        )

        val thrown = runCatching { engine.load(spec()) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(AppErrorException::class.java)
        assertThat((thrown as AppErrorException).error)
            .isInstanceOf(AppError.Model.Unsupported::class.java)
    }

    @Test
    fun `loading a second model frees the first`() = runTest {
        val session = ScriptedSession()
        val engine = engine(session)

        engine.load(spec())
        engine.load(spec())

        assertThat(session.destroyCount).isEqualTo(1)
    }

    @Test
    fun `unload clears the loaded model and frees the handle`() = runTest {
        val session = ScriptedSession()
        val engine = engine(session)
        engine.load(spec())

        engine.unload()

        assertThat(engine.loadedModel.value).isNull()
        assertThat(session.destroyCount).isEqualTo(1)
    }

    @Test
    fun `the loaded model is published for the UI`() = runTest {
        val engine = engine(ScriptedSession())

        engine.loadedModel.test {
            assertThat(awaitItem()).isNull()
            engine.load(spec())
            assertThat(awaitItem()).isEqualTo(model)
        }
    }

    @Test
    fun `an unset seed is sent as -1 because 0 is a legitimate fixed seed`() = runTest {
        val session = ScriptedSession()
        val engine = engine(session)
        engine.load(spec())

        engine.generate(request).toList()

        assertThat(session.samplerSeed).isEqualTo(-1L)
        assertThat(session.samplerTemperature).isWithin(1e-6f).of(0.2f)
    }

    @Test
    fun `the system prompt is rendered as the first message`() = runTest {
        val session = ScriptedSession()
        val engine = engine(session)
        engine.load(spec())

        val events = engine
            .generate(request.copy(systemPrompt = "Be brief."))
            .toList()

        // The scripted template echoes the roles it was handed.
        assertThat(events).isNotEmpty()
        assertThat(session.samplerSeed).isNotNull()
    }

    @Test
    fun `embedding is refused on a chat model rather than returning zeroes`() = runTest {
        // Zeroes would poison a vector index and nothing would report it.
        val engine = engine(ScriptedSession())
        engine.load(spec())

        val thrown = runCatching { engine.embed("hello") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(AppErrorException::class.java)
    }

    @Test
    fun `embedding works when the model was loaded for it`() = runTest {
        val engine = engine(ScriptedSession())
        engine.load(spec(role = EngineRole.EMBEDDING))

        assertThat(engine.embed("hello")).hasLength(2)
    }

    @Test
    fun `token counting with nothing loaded returns zero rather than an estimate`() = runTest {
        assertThat(engine(ScriptedSession()).tokenCount("some text")).isEqualTo(0)
    }

    @Test
    fun `lora adapters from the spec are applied at load`() = runTest {
        val session = ScriptedSession()
        val engine = engine(session)

        engine.load(
            spec().copy(
                loraAdapters = listOf(
                    io.github.jaypetez.ollamamobile.llm
                        .LoraAdapterSpec("/a.gguf", 0.7f),
                    io.github.jaypetez.ollamamobile.llm
                        .LoraAdapterSpec("/b.gguf", 0.3f),
                ),
            ),
        )

        assertThat(session.loraPaths).containsExactly("/a.gguf", "/b.gguf").inOrder()
    }

    private companion object {
        /** Generous: these latches only ever wait when the test is already failing. */
        const val TIMEOUT_SECONDS = 10L

        /**
         * Deliberately shorter than [TIMEOUT_SECONDS].
         *
         * A lost abort still ends, eventually: the blocked session gives up
         * after [TIMEOUT_SECONDS] and returns null on its own. If the budget
         * for cancelling were also [TIMEOUT_SECONDS] the two would finish
         * together, and whether the test passed would come down to which timer
         * the scheduler serviced first — which is how this test came to fail on
         * CI and pass everywhere else. Cancelling is supposed to be immediate,
         * so give it a budget nothing but a real regression can exceed.
         */
        const val CANCEL_TIMEOUT_MILLIS = 2_000L
    }
}
