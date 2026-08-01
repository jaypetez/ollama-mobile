package io.github.jaypetez.ollamamobile.remote.dto

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.SamplingParams
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The wire contract for the native API, asserted against literal bodies.
 *
 * The bodies below are copied from real Ollama responses rather than
 * round-tripped through our own encoder, because a round trip proves only that
 * the DTO agrees with itself.
 */
@RunWith(JUnit4::class)
class OllamaDtosTest {
    @Test
    fun `a response with no timing fields yields nulls and a null tokens per second`() {
        // Trap 1: every counter is `omitempty`. A cache hit or a load-only call
        // arrives exactly like this.
        val body = """{"model":"qwen3:1.7b","created_at":"2026-07-31T10:00:00Z","done":true}"""

        val stats = RemoteJson.decodeFromString(ChatResponse.serializer(), body).toGenerationStats()

        assertThat(stats.promptTokens).isNull()
        assertThat(stats.completionTokens).isNull()
        assertThat(stats.promptEvalNanos).isNull()
        assertThat(stats.evalNanos).isNull()
        assertThat(stats.loadNanos).isNull()
        assertThat(stats.totalNanos).isNull()
        // The whole point: absent must not read as "0 tok/s", which is a number
        // a user would believe.
        assertThat(stats.tokensPerSecond).isNull()
        assertThat(stats.isEmpty).isTrue()
    }

    @Test
    fun `durations are nanoseconds so 100 tokens in two seconds is 50 tokens per second`() {
        // Trap 2: eval_duration is an int64 nanosecond count. Reading it as
        // milliseconds would give 50_000_000 tok/s and still look like a number.
        val body = """
            {"model":"qwen3:1.7b","done":true,"done_reason":"stop",
             "total_duration":2500000000,"load_duration":300000000,
             "prompt_eval_count":9,"prompt_eval_duration":500000000,
             "eval_count":100,"eval_duration":2000000000}
        """.trimIndent()

        val stats = RemoteJson.decodeFromString(ChatResponse.serializer(), body).toGenerationStats()

        assertThat(stats.evalNanos).isEqualTo(2_000_000_000L)
        assertThat(stats.tokensPerSecond).isWithin(1e-9).of(50.0)
        assertThat(stats.promptTokensPerSecond).isWithin(1e-9).of(18.0)
    }

    @Test
    fun `the final chat chunk parses when it omits message entirely`() {
        // Trap 3: the terminal chunk carries done plus the statistics and no
        // `message` key. A non-null `message` loses exactly the chunk that has
        // the numbers.
        val body = """{"model":"qwen3:1.7b","created_at":"2026-07-31T10:00:00Z","done":true,"eval_count":7}"""

        val response = RemoteJson.decodeFromString(ChatResponse.serializer(), body)

        assertThat(response.message).isNull()
        assertThat(response.done).isTrue()
        assertThat(response.evalCount).isEqualTo(7)
    }

    @Test
    fun `an unknown field in a response does not throw`() {
        // Trap 9: Ollama adds fields between point releases.
        val body = """
            {"model":"qwen3:1.7b","done":true,"a_field_from_the_future":{"nested":[1,2,3]},
             "message":{"role":"assistant","content":"hi","some_new_channel":"x"}}
        """.trimIndent()

        val response = RemoteJson.decodeFromString(ChatResponse.serializer(), body)

        assertThat(response.message?.content).isEqualTo("hi")
    }

    @Test
    fun `nulls are never written into a request`() {
        // Trap 9's other half: Ollama rejects `"format": null` outright, so an
        // unset optional must vanish rather than serialise.
        val request = ChatRequest(
            model = "qwen3:1.7b",
            messages = listOf(OllamaMessage(role = "user", content = "hi")),
        )

        val encoded = RemoteJson.encodeToString(ChatRequest.serializer(), request)

        assertThat(encoded).doesNotContain("null")
        assertThat(encoded).doesNotContain("format")
        assertThat(encoded).doesNotContain("keep_alive")
        assertThat(encoded).contains(""""stream":true""")
    }

    @Test
    fun `options are encoded with their snake_case wire names`() {
        val options = SamplingParams(
            temperature = 0.7,
            topP = 0.9,
            topK = 40,
            minP = 0.05,
            repeatPenalty = 1.1,
            repeatLastN = 64,
            seed = 42L,
            numPredict = 256,
            numCtx = 4096,
            stop = listOf("<|im_end|>"),
        ).toOllamaOptions()

        val encoded = RemoteJson.encodeToString(OllamaOptions.serializer(), requireNotNull(options))

        assertThat(encoded).contains(""""top_p":0.9""")
        assertThat(encoded).contains(""""top_k":40""")
        assertThat(encoded).contains(""""min_p":0.05""")
        assertThat(encoded).contains(""""repeat_penalty":1.1""")
        assertThat(encoded).contains(""""repeat_last_n":64""")
        assertThat(encoded).contains(""""num_predict":256""")
        assertThat(encoded).contains(""""num_ctx":4096""")
    }

    @Test
    fun `unset sampling params produce no options object at all`() {
        assertThat(SamplingParams.Default.toOllamaOptions()).isNull()
    }

    @Test
    fun `embed input accepts a bare string and an array through one field`() {
        val single = RemoteJson.decodeFromString(EmbedRequest.serializer(), """{"model":"m","input":"one"}""")
        val batch = RemoteJson.decodeFromString(EmbedRequest.serializer(), """{"model":"m","input":["a","b"]}""")

        assertThat(single.input).isEqualTo(EmbedInput.Text("one"))
        assertThat(batch.input).isEqualTo(EmbedInput.Batch(listOf("a", "b")))

        assertThat(RemoteJson.encodeToString(EmbedRequest.serializer(), single)).contains(""""input":"one"""")
        assertThat(RemoteJson.encodeToString(EmbedRequest.serializer(), batch)).contains(""""input":["a","b"]""")
    }

    @Test
    fun `the modern embed response is plural and the legacy one is singular`() {
        val modern = RemoteJson.decodeFromString(
            EmbedResponse.serializer(),
            """{"model":"nomic","embeddings":[[0.1,0.2],[0.3,0.4]],"prompt_eval_count":4}""",
        )
        val legacy = RemoteJson.decodeFromString(
            EmbeddingsResponse.serializer(),
            """{"embedding":[0.1,0.2,0.3]}""",
        )

        assertThat(modern.embeddings).hasSize(2)
        assertThat(legacy.embedding).hasSize(3)

        // The two keys really are different: decoding one body with the other
        // DTO yields an empty vector rather than an error, which is why the
        // types must not be merged.
        val crossed = RemoteJson.decodeFromString(EmbeddingsResponse.serializer(), """{"embeddings":[[0.1]]}""")
        assertThat(crossed.embedding).isEmpty()
    }

    @Test
    fun `tags and show carry the details, capabilities and model_info blocks`() {
        val tags = RemoteJson.decodeFromString(
            TagsResponse.serializer(),
            """
            {"models":[{"name":"qwen3:1.7b","model":"qwen3:1.7b","modified_at":"2026-07-01T09:00:00Z",
             "size":1117320512,"digest":"sha256:abc",
             "details":{"parent_model":"","format":"gguf","family":"qwen3","families":["qwen3"],
             "parameter_size":"1.7B","quantization_level":"Q4_K_M"}}]}
            """.trimIndent(),
        )
        val show = RemoteJson.decodeFromString(
            ShowResponse.serializer(),
            """
            {"template":"{{ .Prompt }}","capabilities":["completion","tools","thinking"],
             "model_info":{"general.architecture":"qwen3","qwen3.context_length":40960},
             "details":{"family":"qwen3","parameter_size":"1.7B","quantization_level":"Q4_K_M"}}
            """.trimIndent(),
        )

        assertThat(
            tags.models
                .single()
                .details
                ?.parameterSize,
        ).isEqualTo("1.7B")
        assertThat(
            tags.models
                .single()
                .details
                ?.quantizationLevel,
        ).isEqualTo("Q4_K_M")
        assertThat(show.capabilities).containsExactly("completion", "tools", "thinking").inOrder()
        assertThat(show.modelInfo?.get("general.architecture").toString()).contains("qwen3")
    }

    @Test
    fun `ps reports when a loaded model expires`() {
        val ps = RemoteJson.decodeFromString(
            PsResponse.serializer(),
            """{"models":[{"name":"qwen3:1.7b","size":1600000000,"size_vram":0,
               "expires_at":"2026-07-31T10:05:00Z"}]}""",
        )

        assertThat(ps.models.single().expiresAt).isEqualTo("2026-07-31T10:05:00Z")
        assertThat(ps.models.single().sizeVram).isEqualTo(0L)
    }

    @Test
    fun `pull progress is indeterminate until byte counts appear`() {
        val manifest = RemoteJson.decodeFromString(PullProgress.serializer(), """{"status":"pulling manifest"}""")
        val layer = RemoteJson.decodeFromString(
            PullProgress.serializer(),
            """{"status":"pulling abc","digest":"sha256:abc","total":1000000,"completed":250000}""",
        )

        assertThat(manifest.total).isNull()
        assertThat(manifest.fraction).isNull()
        assertThat(layer.fraction).isWithin(1e-9).of(0.25)
    }

    @Test
    fun `the version endpoint and the error envelope decode`() {
        assertThat(RemoteJson.decodeFromString(VersionResponse.serializer(), """{"version":"0.15.2"}""").version)
            .isEqualTo("0.15.2")
        assertThat(RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), """{"error":"nope"}""").error)
            .isEqualTo("nope")
    }

    @Test
    fun `a generate response keeps its context window token list`() {
        val response = RemoteJson.decodeFromString(
            GenerateResponse.serializer(),
            """{"model":"m","response":"hi","done":true,"context":[1,2,3],"eval_count":2,"eval_duration":1000000000}""",
        )

        assertThat(response.context).containsExactly(1, 2, 3).inOrder()
        assertThat(response.toGenerationStats().tokensPerSecond).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `a thinking model reports reasoning beside content, not inside it`() {
        val response = RemoteJson.decodeFromString(
            ChatResponse.serializer(),
            """{"model":"m","message":{"role":"assistant","content":"42","thinking":"let me count"},"done":true}""",
        )

        assertThat(response.message?.content).isEqualTo("42")
        assertThat(response.message?.thinking).isEqualTo("let me count")
    }
}
