package io.github.jaypetez.ollamamobile.common.inspector

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Redaction is the whole reason this store is allowed to exist.
 *
 * The inspector holds request bodies, which for this app means prompt text, and
 * the curl export is designed to be pasted into a bug report. A bearer token
 * reaching either of those is a credential disclosure, so the assertion here is
 * the strong one: the secret must not appear *anywhere* in the record.
 */
@RunWith(JUnit4::class)
class ApiInspectorTest {
    private lateinit var server: MockWebServer
    private lateinit var inspector: ApiInspector

    /**
     * The one place in the repository outside `HttpClientModule` that builds a
     * client. The Konsist architecture test scopes its prohibition to
     * `src/main`, because the point of that rule is that no *shipping* code
     * path escapes `LanOnlyGuard`; a test that never leaves loopback does not.
     */
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        inspector = ApiInspector().apply { enabled = true }
        client = OkHttpClient
            .Builder()
            .addInterceptor(ApiInspectorInterceptor(inspector))
            .build()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `never captures an Authorization header`() {
        val secret = "Bearer sk-do-not-log-me-0123456789"
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "session=super-secret-cookie")
                .body("""{"ok":true}""")
                .build(),
        )

        client
            .newCall(
                Request
                    .Builder()
                    .url(server.url("/api/chat"))
                    .addHeader("Authorization", secret)
                    .addHeader("Cookie", "session=another-secret")
                    .addHeader("X-Api-Key", "key-secret")
                    .post("""{"model":"llama3"}""".toRequestBody())
                    .build(),
            ).execute()
            .use { it.body.string() }

        val exchange = inspector.recorded.value.single()

        val authorization = exchange.requestHeaders.single { it.name.equals("Authorization", ignoreCase = true) }
        assertThat(authorization.value).isEqualTo(ApiInspector.REDACTION)
        assertThat(exchange.toString()).doesNotContain("sk-do-not-log-me")
        assertThat(exchange.toString()).doesNotContain("super-secret-cookie")
        assertThat(exchange.toString()).doesNotContain("another-secret")
        assertThat(exchange.toString()).doesNotContain("key-secret")
        assertThat(exchange.toCurl()).doesNotContain("sk-do-not-log-me")
        assertThat(exchange.toCurl()).contains(ApiInspector.REDACTION)
    }

    @Test
    fun `redacts case-insensitively`() {
        assertThat(ApiInspector.redact("authorization", "x").value).isEqualTo(ApiInspector.REDACTION)
        assertThat(ApiInspector.redact("AUTHORIZATION", "x").value).isEqualTo(ApiInspector.REDACTION)
        assertThat(ApiInspector.redact("Proxy-Authorization", "x").value).isEqualTo(ApiInspector.REDACTION)
        assertThat(ApiInspector.redact("Set-Cookie", "x").value).isEqualTo(ApiInspector.REDACTION)
        // A header that merely contains an allowed name must not be redacted,
        // and one that merely resembles a secret must not be either.
        assertThat(ApiInspector.redact("Content-Type", "application/json").value).isEqualTo("application/json")
    }

    @Test
    fun `captures the request line, status and timing`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(404)
                .body("nope")
                .build(),
        )

        client
            .newCall(Request.Builder().url(server.url("/api/tags")).build())
            .execute()
            .use { it.body.string() }

        val exchange = inspector.recorded.value.single()
        assertThat(exchange.method).isEqualTo("GET")
        assertThat(exchange.url).endsWith("/api/tags")
        assertThat(exchange.statusCode).isEqualTo(404)
        assertThat(exchange.responseBodyPreview).isEqualTo("nope")
        assertThat(exchange.durationMillis).isNotNull()
        assertThat(exchange.isComplete).isTrue()
    }

    @Test
    fun `captures nothing while disabled`() {
        inspector.enabled = false
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("hi")
                .build(),
        )

        client
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .use { it.body.string() }

        assertThat(inspector.recorded.value).isEmpty()
    }

    @Test
    fun `evicts the oldest exchange once the store is full`() {
        inspector.capacity = 3
        repeat(5) { index ->
            inspector.record(
                ApiExchange(
                    id = inspector.nextExchangeId(),
                    startedAtMillis = index.toLong(),
                    method = "GET",
                    url = "http://192.168.1.40/$index",
                    requestHeaders = emptyList(),
                ),
            )
        }

        val urls = inspector.recorded.value.map { it.url }
        assertThat(urls).hasSize(3)
        assertThat(urls.first()).endsWith("/4")
        assertThat(urls.last()).endsWith("/2")
    }

    @Test
    fun `curl export quotes and redacts`() {
        val exchange = ApiExchange(
            id = 1,
            startedAtMillis = 0,
            method = "POST",
            url = "http://192.168.1.40:11434/api/chat",
            requestHeaders = listOf(ApiInspector.redact("Authorization", "Bearer nope")),
            requestBodyPreview = """{"model":"llama3"}""",
        )

        val curl = exchange.toCurl()

        assertThat(curl).startsWith("curl -X POST")
        assertThat(curl).contains("-H 'Authorization: REDACTED'")
        assertThat(curl).contains("""--data-raw '{"model":"llama3"}'""")
        assertThat(curl).contains("'http://192.168.1.40:11434/api/chat'")
        assertThat(curl).doesNotContain("Bearer nope")
    }
}
