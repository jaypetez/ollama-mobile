package io.github.jaypetez.ollamamobile.server

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.remote.dto.OllamaErrorResponse
import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val TOKEN = "s3cret-token"

/**
 * The three things that decide whether exposing this on a LAN is defensible:
 * who may call it, which `Host` it will answer to, and whether two phones can
 * be made to talk to each other forever.
 */
class ServerSecurityTest {
    private val lanConfig = ServerConfig(bindPolicy = BindPolicy.LAN, bearerToken = TOKEN)

    // -----------------------------------------------------------------------
    // Bearer auth
    // -----------------------------------------------------------------------

    @Test
    fun `discovery endpoints stay open, because authenticating them hides the server`() = runTest {
        withServer(environment(config = lanConfig)) { http ->
            // These two are exactly what the subnet scanner probes. A 401 here
            // makes a reachable server invisible to the app that shipped it.
            assertThat(http.get("/").status).isEqualTo(HttpStatusCode.OK)
            assertThat(http.get("/api/version").status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `everything else is 401 without a token`() = runTest {
        withServer(environment(config = lanConfig)) { http ->
            val response = http.get("/api/tags")

            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.headers[HttpHeaders.WWWAuthenticate]).startsWith("Bearer")
            val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), response.bodyAsText())
            assertThat(error.error).isEqualTo(ServerErrors.UNAUTHORIZED)
        }
    }

    @Test
    fun `a correct bearer token is accepted`() = runTest {
        withServer(environment(config = lanConfig)) { http ->
            val response = http.get("/api/tags") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `a wrong token, a wrong scheme and a bare token are all rejected`() = runTest {
        withServer(environment(config = lanConfig)) { http ->
            val wrong = http.get("/api/tags") { header(HttpHeaders.Authorization, "Bearer nope") }
            val basic = http.get("/api/tags") { header(HttpHeaders.Authorization, "Basic $TOKEN") }
            val bare = http.get("/api/tags") { header(HttpHeaders.Authorization, TOKEN) }

            assertThat(wrong.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(basic.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(bare.status).isEqualTo(HttpStatusCode.Unauthorized)
        }
    }

    @Test
    fun `loopback binding needs no token at all`() = runTest {
        withServer { http ->
            assertThat(http.get("/api/tags").status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `LAN exposure without a token is refused at construction, not at runtime`() {
        val invalid = ServerConfig(bindPolicy = BindPolicy.LAN, bearerToken = null)

        val failure = runCatching { invalid.requireValid() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a generated token is 32 bytes of base64url`() {
        val token = ServerConfig.generateToken()

        // 32 bytes, base64url, unpadded.
        assertThat(token).hasLength(43)
        assertThat(token).matches("[A-Za-z0-9_-]+")
        assertThat(token).isNotEqualTo(ServerConfig.generateToken())
    }

    // -----------------------------------------------------------------------
    // Host guard
    // -----------------------------------------------------------------------

    @Test
    fun `a public Host header is refused before the token is even considered`() = runTest {
        withServer(environment(config = lanConfig)) { http ->
            val response = http.get("/api/tags") {
                header(HttpHeaders.Authorization, "Bearer $TOKEN")
                header(HttpHeaders.Host, "evil.example.com")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), response.bodyAsText())
            assertThat(error.error).isEqualTo(ServerErrors.FORBIDDEN_HOST)
        }
    }

    @Test
    fun `private literals and localhost are accepted as Host`() {
        assertThat(HostGuard.isAllowed("192.168.1.40:11434")).isTrue()
        assertThat(HostGuard.isAllowed("10.0.0.7")).isTrue()
        assertThat(HostGuard.isAllowed("172.16.4.4:11434")).isTrue()
        assertThat(HostGuard.isAllowed("127.0.0.1:11434")).isTrue()
        assertThat(HostGuard.isAllowed("localhost:11434")).isTrue()
        assertThat(HostGuard.isAllowed("[::1]:11434")).isTrue()
        assertThat(HostGuard.isAllowed("169.254.10.1")).isTrue()
    }

    @Test
    fun `a rebindable name and a public literal are refused`() {
        // The DNS-rebinding case: a name the attacker controls that resolves to
        // the phone's LAN address. Requiring a literal is what breaks it.
        assertThat(HostGuard.isAllowed("attacker.example")).isFalse()
        assertThat(HostGuard.isAllowed("phone.attacker.example:11434")).isFalse()
        assertThat(HostGuard.isAllowed("8.8.8.8")).isFalse()
        // 100.64/10 is LAN-ish only over a VPN, and this guard cannot know the
        // transport, so it does not guess.
        assertThat(HostGuard.isAllowed("100.64.0.1")).isFalse()
    }

    // -----------------------------------------------------------------------
    // Loop guard
    // -----------------------------------------------------------------------

    @Test
    fun `a request that already passed through an OllamaMobile server is refused`() = runTest {
        withServer { http ->
            val response = http.post("/api/chat") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Via, VIA_HEADER_VALUE)
                setBody("""{"model":"qwen3:1.7b","messages":[]}""")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            val error = RemoteJson.decodeFromString(OllamaErrorResponse.serializer(), response.bodyAsText())
            assertThat(error.error).isEqualTo(ServerErrors.PROXY_LOOP)
        }
    }

    @Test
    fun `every response carries Via so the other phone can notice too`() = runTest {
        withServer { http ->
            assertThat(http.get("/").headers[HttpHeaders.Via]).isEqualTo(VIA_HEADER_VALUE)
            // Including on a rejection: the marker is stamped before the guards.
            val rejected = http.get("/api/tags") { header(HttpHeaders.Host, "evil.example.com") }
            assertThat(rejected.headers[HttpHeaders.Via]).isEqualTo(VIA_HEADER_VALUE)
        }
    }

    @Test
    fun `an upstream pointing back at this server is recognised as self-referential`() {
        val config = ServerConfig()

        assertThat(LoopGuard.isSelfReferential("http://127.0.0.1:11434", config)).isTrue()
        assertThat(LoopGuard.isSelfReferential("http://localhost:11434/api/chat", config)).isTrue()
        assertThat(LoopGuard.isSelfReferential("http://[::1]:11434", config)).isTrue()
        assertThat(LoopGuard.isSelfReferential("http://192.168.1.40:11434", config)).isFalse()
        assertThat(LoopGuard.isSelfReferential("http://127.0.0.1:8080", config)).isFalse()
        assertThat(LoopGuard.isSelfReferential(null, config)).isFalse()
    }
}
