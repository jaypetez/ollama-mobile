package io.github.jaypetez.ollamamobile.common.inspector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The inspector exists so a user can see what the app sent. The one thing it
 * must never show is the credential that made the request work, because the
 * whole point of the screen is that its output gets pasted into a bug report.
 *
 * The cURL export is the dangerous surface: a `curl` that reproduces the
 * request is exactly a `curl` that carries the token, and the temptation to
 * make the export "actually work" is how this leaks.
 */
class ApiInspectorRedactionTest {
    private val secret = "Bearer sk-live-51H8xQq2eZvKYlo0wSuperSecretValue"

    @Test
    fun `an Authorization header is never rendered, whatever its casing`() {
        listOf("Authorization", "authorization", "AUTHORIZATION", "AuThOrIzAtIoN").forEach { name ->
            val header = ApiInspector.redact(name, secret)

            assertThat(header.value).isEqualTo(ApiInspector.REDACTION)
            assertThat(header.value).doesNotContain("sk-live")
        }
    }

    @Test
    fun `every credential-bearing header name is redacted`() {
        ApiInspector.REDACTED_HEADERS.forEach { name ->
            assertThat(ApiInspector.redact(name, secret).value).isEqualTo(ApiInspector.REDACTION)
        }
    }

    @Test
    fun `a harmless header is left alone, or the screen is useless`() {
        assertThat(ApiInspector.redact("Content-Type", "application/json").value)
            .isEqualTo("application/json")
    }

    @Test
    fun `the cURL export cannot leak an Authorization header`() {
        val exchange = ApiExchange(
            id = 1,
            startedAtMillis = 0,
            method = "POST",
            url = "http://192.168.1.50:11434/api/chat",
            requestHeaders = listOf(
                ApiInspector.redact("Authorization", secret),
                ApiInspector.redact("Content-Type", "application/json"),
            ),
            requestBodyPreview = """{"model":"llama3"}""",
        )

        val curl = exchange.toCurl()

        assertThat(curl).doesNotContain("sk-live")
        assertThat(curl).doesNotContain(secret)
        assertThat(curl).contains("Authorization: ${ApiInspector.REDACTION}")
        // Still a recognisable, runnable-shaped command — redaction must not
        // mangle the export into something nobody can read.
        assertThat(curl).contains("curl -X POST")
        assertThat(curl).contains("http://192.168.1.50:11434/api/chat")
    }

    @Test
    fun `a token embedded in a URL query is not something the export invents`() {
        // Guards the shell quoting: a single quote in a value must not be able
        // to close the quoting and splice the rest of the string into the
        // command a reader then copies and runs.
        val exchange = ApiExchange(
            id = 2,
            startedAtMillis = 0,
            method = "GET",
            url = "http://nas.local/api/tags?name=it's",
            requestHeaders = emptyList(),
        )

        assertThat(exchange.toCurl()).contains("""'http://nas.local/api/tags?name=it'\''s'""")
    }
}
