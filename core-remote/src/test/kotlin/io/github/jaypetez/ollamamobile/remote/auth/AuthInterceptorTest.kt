package io.github.jaypetez.ollamamobile.remote.auth

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerAuth
import io.github.jaypetez.ollamamobile.remote.FakeSecretResolver
import io.github.jaypetez.ollamamobile.remote.MutableClock
import io.github.jaypetez.ollamamobile.remote.OllamaClientImpl
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import io.github.jaypetez.ollamamobile.remote.health.RequestOutcome
import io.github.jaypetez.ollamamobile.remote.serverRef
import io.github.jaypetez.ollamamobile.remote.testHttp
import io.github.jaypetez.ollamamobile.remote.testOkHttpClient
import io.github.jaypetez.ollamamobile.remote.testServer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private const val TOKEN = "sk-super-secret-token-value"
private const val PASSWORD = "hunter2-not-in-the-history"

@RunWith(JUnit4::class)
class AuthInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var history: RequestHistory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        history = RequestHistory(MutableClock())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(auth: ServerAuth, secrets: Map<String, String>) = OllamaClientImpl(
        testHttp(history = history, resolver = FakeSecretResolver(secrets)),
    ) to server.serverRef(auth)

    @Test
    fun `a bearer token is attached to every request for that server`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())
        val (client, ref) = client(
            auth = ServerAuth.BearerToken(SecretRef("server.1.token")),
            secrets = mapOf("server.1.token" to TOKEN),
        )

        client.version(ref)

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer $TOKEN")
    }

    @Test
    fun `basic credentials are encoded as OkHttp does it`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())
        val (client, ref) = client(
            auth = ServerAuth.BasicAuth(username = "ada", passwordRef = SecretRef("server.1.password")),
            secrets = mapOf("server.1.password" to PASSWORD),
        )

        client.version(ref)

        assertThat(server.takeRequest().headers["Authorization"])
            .isEqualTo(okhttp3.Credentials.basic("ada", PASSWORD))
    }

    @Test
    fun `a server with no auth sends no header`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())
        val (client, ref) = client(auth = ServerAuth.None, secrets = emptyMap())

        client.version(ref)

        assertThat(server.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `a missing secret produces an unauthenticated request rather than a storage error`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .code(401)
                .body("""{"error":"unauthorized"}""")
                .build(),
        )
        val (client, ref) = client(
            auth = ServerAuth.BearerToken(SecretRef("server.1.token")),
            secrets = emptyMap(),
        )

        client.version(ref)

        // A 401 the user can act on, not a crash they cannot.
        assertThat(server.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `the credential is scoped to the configured host`() {
        server.enqueue(MockResponse.Builder().body("{}").build())
        // The configured server is at a different address than the one the
        // request goes to — which is what a cross-host redirect looks like from
        // inside the interceptor chain.
        val configured = testServer("http://192.168.1.40:11434")
        val http = testOkHttpClient()
            .newBuilder()
            .addInterceptor(AuthInterceptor.forServer(configured, ResolvedCredential.bearer(TOKEN)))
            .build()

        http.newCall(Request.Builder().url(server.url("/api/chat")).build()).execute().close()

        // OkHttp strips Authorization across hosts; re-adding it here would
        // hand the user's token to whoever controlled the redirect.
        assertThat(server.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `the credential is attached when the host does match`() {
        server.enqueue(MockResponse.Builder().body("{}").build())
        val configured = testServer(server.url("/").toString())
        val http = testOkHttpClient()
            .newBuilder()
            .addInterceptor(AuthInterceptor.forServer(configured, ResolvedCredential.bearer(TOKEN)))
            .build()

        http.newCall(Request.Builder().url(server.url("/api/chat")).build()).execute().close()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer $TOKEN")
    }

    @Test
    fun `the credential redacts itself in toString`() {
        val credential = ResolvedCredential.bearer(TOKEN)

        assertThat(credential.toString()).doesNotContain(TOKEN)
        assertThat(credential.toString()).contains("Bearer")
    }

    @Test
    fun `credentials never appear in the request history`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())
        val (client, ref) = client(
            auth = ServerAuth.BearerToken(SecretRef("server.1.token")),
            secrets = mapOf("server.1.token" to TOKEN),
        )

        client.version(ref)

        val records = history.forServer(ref.id)
        assertThat(records).hasSize(1)
        val record = records.single()
        assertThat(record.path).isEqualTo("/api/version")
        assertThat(record.outcome).isEqualTo(RequestOutcome.Answered(200))
        // The record has nowhere to put a header, and its rendering — which is
        // what ends up pasted into a bug report — must show that.
        assertThat(record.toString()).doesNotContain(TOKEN)
        assertThat(history.records.value.toString()).doesNotContain(TOKEN)
    }

    @Test
    fun `the history keeps the path only, never the query string`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())
        val ref = server.serverRef().copy(baseUrl = server.url("/?api_key=$TOKEN").toString())
        val client = OllamaClientImpl(testHttp(client = testOkHttpClient(), history = history))

        client.version(ref)

        assertThat(history.forServer(ref.id).single().path).isEqualTo("/api/version")
        assertThat(history.records.value.toString()).doesNotContain(TOKEN)
    }
}
