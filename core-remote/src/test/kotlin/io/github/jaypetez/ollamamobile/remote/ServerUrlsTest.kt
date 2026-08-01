package io.github.jaypetez.ollamamobile.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Every case here is a deployment somebody actually has.
 *
 * The trailing slash and the path prefix are the two that produce a 404 from a
 * reverse proxy rather than an error anyone can read; the IPv6 literal is the
 * one that throws before a request is made at all.
 */
@RunWith(JUnit4::class)
class ServerUrlsTest {
    @Test
    fun `a trailing slash does not produce a double slash`() {
        val server = testServer("http://192.168.1.40:11434/")

        assertThat(ServerUrls.resolveOrNull(server, "api/tags").toString())
            .isEqualTo("http://192.168.1.40:11434/api/tags")
    }

    @Test
    fun `a leading slash on the path is also tolerated`() {
        val server = testServer("http://192.168.1.40:11434")

        assertThat(ServerUrls.resolveOrNull(server, "/api/tags").toString())
            .isEqualTo("http://192.168.1.40:11434/api/tags")
    }

    @Test
    fun `a bare private address defaults to http on 11434`() {
        assertThat(ServerUrls.parseOrNull("192.168.1.40").toString())
            .isEqualTo("http://192.168.1.40:11434/")
    }

    @Test
    fun `an explicit port is honoured`() {
        val url = ServerUrls.parseOrNull("192.168.1.40:8080")

        assertThat(url?.port).isEqualTo(8080)
        assertThat(url?.scheme).isEqualTo("http")
    }

    @Test
    fun `an explicit port on a full url is honoured`() {
        assertThat(ServerUrls.parseOrNull("http://nas.local:11435/")?.port).isEqualTo(11435)
    }

    @Test
    fun `a public hostname defaults to https and its own port`() {
        val url = ServerUrls.parseOrNull("ollama.example.org")

        assertThat(url?.scheme).isEqualTo("https")
        assertThat(url?.port).isEqualTo(443)
    }

    @Test
    fun `a bare IPv6 literal is bracketed`() {
        val url = ServerUrls.parseOrNull("::1")

        assertThat(url.toString()).isEqualTo("http://[::1]:11434/")
        assertThat(url?.host).isEqualTo("::1")
    }

    @Test
    fun `a bracketed IPv6 literal with a port keeps the port`() {
        val url = ServerUrls.parseOrNull("[fd00::1]:11435")

        assertThat(url?.host).isEqualTo("fd00::1")
        assertThat(url?.port).isEqualTo(11435)
    }

    @Test
    fun `an IPv6 literal survives path resolution`() {
        val server = testServer("http://[fd00::1]:11434")

        assertThat(ServerUrls.resolveOrNull(server, "api/version").toString())
            .isEqualTo("http://[fd00::1]:11434/api/version")
    }

    @Test
    fun `a path prefix behind a proxy is preserved`() {
        val server = testServer("https://ollama.example.org/ollama")

        assertThat(ServerUrls.resolveOrNull(server, "api/chat").toString())
            .isEqualTo("https://ollama.example.org/ollama/api/chat")
    }

    @Test
    fun `a path prefix with a trailing slash is preserved exactly once`() {
        val server = testServer("https://ollama.example.org/ollama/")

        assertThat(ServerUrls.resolveOrNull(server, "api/chat").toString())
            .isEqualTo("https://ollama.example.org/ollama/api/chat")
    }

    @Test
    fun `unusable addresses are rejected rather than guessed at`() {
        assertThat(ServerUrls.parseOrNull("")).isNull()
        assertThat(ServerUrls.parseOrNull("   ")).isNull()
        // A scheme we do not speak is a typo, not a server.
        assertThat(ServerUrls.parseOrNull("ws://192.168.1.40:11434")).isNull()
    }

    @Test
    fun `a malformed base url produces a typed error rather than an exception`() {
        val server = testServer("ws://nope")

        assertThat(ServerUrls.resolveOrNull(server, "api/tags")).isNull()
        assertThat(ServerUrls.malformed(server).message).contains("Test server")
    }
}
