package io.github.jaypetez.ollamamobile.remote.modelfile

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.remote.MutableClock
import io.github.jaypetez.ollamamobile.remote.OllamaClientImpl
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import io.github.jaypetez.ollamamobile.remote.serverRef
import io.github.jaypetez.ollamamobile.remote.testHttp
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The structured round trip: an unstructured blob in, typed fields out, a
 * structured create back.
 */
@RunWith(JUnit4::class)
class ModelfileServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: ModelfileService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = ModelfileService(OllamaClientImpl(testHttp(history = RequestHistory(MutableClock()))))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the parameters blob is parsed into repeated and single values`() {
        val parsed = ModelParameters.parse(
            """
            stop                           "<|im_end|>"
            stop                           "<|im_start|>"
            temperature                    0.7
            num_ctx                        8192
            """.trimIndent(),
        )

        // Repeats must accumulate. Collapsing them keeps only the last stop
        // sequence, which shows up much later as a model that will not stop.
        assertThat(parsed.stop).containsExactly("<|im_end|>", "<|im_start|>").inOrder()
        assertThat(parsed.single("temperature")).isEqualTo("0.7")
        assertThat(parsed.single("num_ctx")).isEqualTo("8192")
    }

    @Test
    fun `an absent or empty blob is empty rather than a failure`() {
        assertThat(ModelParameters.parse(null).isEmpty).isTrue()
        assertThat(ModelParameters.parse("   \n  ").isEmpty).isTrue()
    }

    @Test
    fun `types are recovered when writing the structured form back`() {
        val wire = ModelParameters
            .parse(
                """
            temperature 0.7
            num_ctx 8192
            some_flag true
            stop "a"
            stop "b"
                """.trimIndent(),
            ).toWireMap()

        assertThat(wire["temperature"].toString()).isEqualTo("0.7")
        assertThat(wire["num_ctx"].toString()).isEqualTo("8192")
        assertThat(wire["some_flag"].toString()).isEqualTo("true")
        assertThat(wire["stop"].toString()).isEqualTo("""["a","b"]""")
    }

    @Test
    fun `loading a model produces an editable draft`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .body(
                    """{"system":"You are terse.","template":"{{ .Prompt }}","license":"MIT",""" +
                        """"parameters":"temperature 0.7\nstop \"<|end|>\""}""",
                ).build(),
        )

        val draft = (service.load(server.serverRef(), "qwen3:4b") as AppResult.Success).value

        assertThat(draft.model).isEqualTo("qwen3:4b")
        // /api/show reports no FROM, so the edited definition derives from the
        // model being edited.
        assertThat(draft.from).isEqualTo("qwen3:4b")
        assertThat(draft.system).isEqualTo("You are terse.")
        assertThat(draft.parameters.single("temperature")).isEqualTo("0.7")
    }

    @Test
    fun `saving posts a structured create, never a raw modelfile string`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"status":"success"}""").build())

        val draft = ModelfileDraft(
            model = "qwen3:4b-terse",
            from = "qwen3:4b",
            system = "You are terse.",
            parameters = ModelParameters.parse("temperature 0.2\nstop \"<|end|>\""),
        )
        service.save(server.serverRef(), draft)

        val request = server.takeRequest()
        assertThat(request.target).isEqualTo("/api/create")
        val body = request.body?.utf8().orEmpty()
        assertThat(body).contains("\"from\":\"qwen3:4b\"")
        assertThat(body).contains("\"system\":\"You are terse.\"")
        assertThat(body).contains("\"temperature\":0.2")
        // The endpoint stopped accepting one, and pretending otherwise is a 400
        // the user cannot act on.
        assertThat(body).doesNotContain("\"modelfile\"")
    }
}
