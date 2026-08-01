package io.github.jaypetez.ollamamobile.feature.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The scrubber is the last thing between a token and a public bug report, so
 * each rule gets an explicit case rather than one happy-path assertion.
 */
@RunWith(JUnit4::class)
class CredentialScrubberTest {
    @Test
    fun `an authorization header loses its value`() {
        val scrubbed = CredentialScrubber.scrub("Authorization: Bearer sk-live-abcdef123456")
        assertThat(scrubbed).doesNotContain("sk-live-abcdef123456")
        assertThat(scrubbed).startsWith("Authorization:")
    }

    @Test
    fun `header matching is case-insensitive because HTTP header names are`() {
        assertThat(CredentialScrubber.scrub("authorization: Basic dXNlcjpwYXNz"))
            .doesNotContain("dXNlcjpwYXNz")
        assertThat(CredentialScrubber.scrub("Proxy-Authorization: Bearer nope"))
            .doesNotContain("nope")
    }

    @Test
    fun `a bearer token in prose is caught too`() {
        assertThat(CredentialScrubber.scrub("retrying with bearer eyJhbGciOiJIUzI1NiJ9xyz"))
            .doesNotContain("eyJhbGciOiJIUzI1NiJ9xyz")
    }

    @Test
    fun `key-value credentials in a JSON body are caught`() {
        val body = """{"model":"qwen3","api_key":"sk-1234567890","token":"t-abc"}"""
        val scrubbed = CredentialScrubber.scrub(body)
        assertThat(scrubbed).doesNotContain("sk-1234567890")
        assertThat(scrubbed).doesNotContain("t-abc")
        // The parts that make the exchange useful survive.
        assertThat(scrubbed).contains("qwen3")
    }

    @Test
    fun `credentials embedded in a URL are removed but the host is kept`() {
        val scrubbed = CredentialScrubber.scrub("http://alice:hunter2@192.168.1.40:11434/api/tags")
        assertThat(scrubbed).doesNotContain("hunter2")
        assertThat(scrubbed).contains("192.168.1.40:11434/api/tags")
        assertThat(scrubbed).contains("alice")
    }

    @Test
    fun `ordinary text is left alone`() {
        val text = "GET /api/tags -> 200 in 12 ms"
        assertThat(CredentialScrubber.scrub(text)).isEqualTo(text)
    }

    @Test
    fun `null passes through`() {
        assertThat(CredentialScrubber.scrubOrNull(null)).isNull()
    }
}
