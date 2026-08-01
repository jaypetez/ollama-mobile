package io.github.jaypetez.ollamamobile.remote

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ServerClientFactoryTest {
    private lateinit var server: MockWebServer
    private lateinit var clock: MutableClock
    private lateinit var factory: ServerClientFactory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        clock = MutableClock()
        val http = testHttp(history = RequestHistory(MutableClock()))
        factory = ServerClientFactory(OllamaClientImpl(http), OpenAiCompatClientImpl(http), clock)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a server that answers api-version gets the native client`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())

        val selected = factory.clientFor(server.serverRef())

        assertThat(selected.protocol).isEqualTo(ServerProtocol.NATIVE)
        assertThat(selected.client).isInstanceOf(OllamaClient::class.java)
        assertThat(selected.version).isEqualTo("0.12.3")
    }

    @Test
    fun `a server that does not gets the OpenAI-compatible client`() = runBlocking {
        // llama-server, vLLM, LM Studio: no /api/version, but a working /v1.
        repeat(3) {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("Not Found")
                    .build(),
            )
        }

        val selected = factory.clientFor(server.serverRef())

        assertThat(selected.protocol).isEqualTo(ServerProtocol.OPENAI_COMPATIBLE)
        assertThat(selected.client).isInstanceOf(OpenAiCompatClient::class.java)
    }

    @Test
    fun `the decision is cached, then re-probed once it goes stale`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())
        val ref = server.serverRef()

        factory.clientFor(ref)
        factory.clientFor(ref)
        assertThat(server.requestCount).isEqualTo(1)

        clock.advance(120_000)
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.4"}""").build())
        assertThat(factory.clientFor(ref).version).isEqualTo("0.12.4")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `editing the address invalidates the cached decision`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.3"}""").build())
        val ref = server.serverRef()
        factory.clientFor(ref)

        // Same server id, different URL: the old answer describes a different
        // machine, which is exactly the case a user hits after fixing a typo.
        server.enqueue(MockResponse.Builder().body("""{"version":"0.12.5"}""").build())
        val moved = ref.copy(baseUrl = server.url("/proxy/").toString())

        assertThat(factory.clientFor(moved).version).isEqualTo("0.12.5")
        assertThat(server.takeRequest().target).isEqualTo("/api/version")
        assertThat(server.takeRequest().target).isEqualTo("/proxy/api/version")
    }
}
