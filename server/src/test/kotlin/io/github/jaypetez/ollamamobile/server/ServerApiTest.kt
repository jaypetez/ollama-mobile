package io.github.jaypetez.ollamamobile.server

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.remote.dto.EmbedResponse
import io.github.jaypetez.ollamamobile.remote.dto.EmbeddingsResponse
import io.github.jaypetez.ollamamobile.remote.dto.ModelsResponse
import io.github.jaypetez.ollamamobile.remote.dto.OllamaErrorResponse
import io.github.jaypetez.ollamamobile.remote.dto.PsResponse
import io.github.jaypetez.ollamamobile.remote.dto.PullProgress
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.github.jaypetez.ollamamobile.remote.dto.ShowResponse
import io.github.jaypetez.ollamamobile.remote.dto.TagsResponse
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The endpoints a real client touches before it will talk to this server at
 * all, plus the two error shapes that decide whether it retries or gives up.
 */
class ServerApiTest {
    @Test
    fun `root answers the exact liveness body every client probes for`() = runTest {
        withServer { http ->
            val response = http.get("/")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).isEqualTo("Ollama is running")
            assertThat(response.contentType()?.withoutParameters())
                .isEqualTo(ContentType.Text.Plain)
        }
    }

    @Test
    fun `root answers HEAD, because a cheap reachability check uses it`() = runTest {
        withServer { http ->
            assertThat(http.head("/").status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `version reports a modern API level`() = runTest {
        withServer { http ->
            val body = http.get("/api/version").bodyAsText()

            assertThat(body).contains("\"version\"")
            assertThat(body).contains(REPORTED_OLLAMA_VERSION)
        }
    }

    @Test
    fun `tags unions local and remote models`() = runTest {
        withServer { http ->
            val tags = RemoteJson.decodeFromString(TagsResponse.serializer(), http.get("/api/tags").bodyAsText())

            assertThat(tags.models.map { it.name }).containsExactly("llama3.2:3b", "qwen3:1.7b").inOrder()
            // Every field a picker renders has to be populated, or the model
            // shows up as a nameless row with no size.
            val local = tags.models.single { it.name == LOCAL_MODEL.name }
            assertThat(local.size).isEqualTo(LOCAL_MODEL.sizeBytes)
            assertThat(local.digest).startsWith("sha256:")
            assertThat(local.details?.parameterSize).isEqualTo("1.7B")
        }
    }

    @Test
    fun `v1 models lists the same union in OpenAI's shape`() = runTest {
        withServer { http ->
            val models = RemoteJson.decodeFromString(ModelsResponse.serializer(), http.get("/v1/models").bodyAsText())

            assertThat(models.objectType).isEqualTo("list")
            assertThat(models.data.map { it.id }).containsExactly("llama3.2:3b", "qwen3:1.7b").inOrder()
            assertThat(models.data.map { it.objectType }.toSet()).containsExactly("model")
        }
    }

    @Test
    fun `show reports capabilities, because clients gate tool support on them`() = runTest {
        withServer { http ->
            val response = http.post("/api/show") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3:1.7b"}""")
            }

            val show = RemoteJson.decodeFromString(ShowResponse.serializer(), response.bodyAsText())
            assertThat(show.capabilities).containsExactly("completion", "tools")
            assertThat(show.details?.quantizationLevel).isNull()
        }
    }

    @Test
    fun `show accepts the legacy name field older clients still send`() = runTest {
        withServer { http ->
            val response = http.post("/api/show") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"qwen3:1.7b"}""")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `an unknown tag is a 404 with Ollama's not-found wording`() = runTest {
        withServer { http ->
            val response = http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"nope:1b","messages":[{"role":"user","content":"hi"}]}""")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), response.bodyAsText())
            // The CLI offers to pull only when it sees "not found".
            assertThat(error.error).contains("not found")
        }
    }

    @Test
    fun `a tag without a version resolves to the latest-suffixed model`() = runTest {
        val gateway = FakeGateway(models = listOf(LOCAL_MODEL.copy(name = "qwen3:latest")))
        withServer(environment(gateway = gateway)) { http ->
            val response = http.post("/api/show") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3"}""")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `a wrong verb is 405 with an Allow header, not 404`() = runTest {
        withServer { http ->
            val response = http.get("/api/chat")

            assertThat(response.status).isEqualTo(HttpStatusCode.MethodNotAllowed)
            assertThat(response.headers[HttpHeaders.Allow]).isEqualTo("POST")
            val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), response.bodyAsText())
            assertThat(error.error).isEqualTo(ServerErrors.METHOD_NOT_ALLOWED)
        }
    }

    @Test
    fun `posting to a GET-only endpoint is also 405`() = runTest {
        withServer { http ->
            val response = http.post("/api/tags")

            assertThat(response.status).isEqualTo(HttpStatusCode.MethodNotAllowed)
            assertThat(response.headers[HttpHeaders.Allow]).isEqualTo("GET, HEAD")
        }
    }

    @Test
    fun `an unknown path is still a 404, not a 405 from the root fallback`() = runTest {
        withServer { http ->
            // The root route carries a method-less fallback for its own 405s.
            // If that fallback ever started swallowing unmatched paths, every
            // typo would look like a supported endpoint with the wrong verb.
            assertThat(http.get("/api/does-not-exist").status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(http.get("/v1/nope").status).isEqualTo(HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `posting to the liveness probe is 405`() = runTest {
        withServer { http ->
            val response = http.post("/")

            assertThat(response.status).isEqualTo(HttpStatusCode.MethodNotAllowed)
            assertThat(response.headers[HttpHeaders.Allow]).isEqualTo("GET, HEAD")
        }
    }

    @Test
    fun `malformed JSON is a 400 in Ollama's envelope, never an HTML page`() = runTest {
        withServer { http ->
            val response = http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody("{ this is not json")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), response.bodyAsText())
            assertThat(error.error).startsWith("invalid request")
        }
    }

    @Test
    fun `embed answers the plural shape and embeddings the legacy singular`() = runTest {
        withServer { http ->
            val plural = http
                .post("/api/embed") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","input":["a","b"]}""")
                }.bodyAsText()
            val singular = http
                .post("/api/embeddings") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b","prompt":"a"}""")
                }.bodyAsText()

            assertThat(RemoteJson.decodeFromString(EmbedResponse.serializer(), plural).embeddings).hasSize(2)
            // The legacy key is `embedding`, flat. Decoding one with the other's
            // DTO yields an empty result rather than an error, so this asserts
            // on the raw key too.
            assertThat(singular).contains("\"embedding\"")
            assertThat(RemoteJson.decodeFromString(EmbeddingsResponse.serializer(), singular).embedding).hasSize(2)
        }
    }

    @Test
    fun `ps reports expires_at derived from keep_alive`() = runTest {
        val clock = FakeClock()
        withServer(environment(clock = clock)) { http ->
            http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3:1.7b","messages":[],"stream":false,"keep_alive":"10m"}""")
            }

            val ps = RemoteJson.decodeFromString(PsResponse.serializer(), http.get("/api/ps").bodyAsText())
            assertThat(ps.models.map { it.name }).containsExactly("qwen3:1.7b")
            assertThat(ps.models.single().expiresAt).isEqualTo("2023-11-14T22:23:20Z")
        }
    }

    @Test
    fun `keep_alive of zero means the model is gone by the next ps`() = runTest {
        withServer { http ->
            http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3:1.7b","messages":[],"stream":false,"keep_alive":0}""")
            }

            val ps = RemoteJson.decodeFromString(PsResponse.serializer(), http.get("/api/ps").bodyAsText())
            assertThat(ps.models).isEmpty()
        }
    }

    @Test
    fun `pull streams NDJSON and ends with the exact success status the CLI waits for`() = runTest {
        val admin = object : ModelAdmin by UnsupportedModelAdmin {
            override fun pull(model: String, insecure: Boolean) = flowOf(
                PullProgress(status = "pulling manifest"),
                PullProgress(status = "pulling 5f0c", total = 100L, completed = 50L),
            )
        }
        withServer(environment(admin = admin)) { http ->
            val body = http
                .post("/api/pull") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"qwen3:1.7b"}""")
                }.bodyAsText()

            val lines = body.trim().lines()
            assertThat(lines).hasSize(3)
            // The CLI's progress bar only completes on this exact final line.
            assertThat(lines.last()).isEqualTo("""{"status":"success"}""")
        }
    }

    @Test
    fun `model management answers in Ollama's vocabulary when unavailable`() = runTest {
        withServer { http ->
            val response = http.post("/api/delete") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3:1.7b"}""")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), response.bodyAsText())
            assertThat(error.error).isEqualTo(UnsupportedModelAdmin.MESSAGE)
        }
    }
}
