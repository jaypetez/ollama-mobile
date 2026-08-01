package io.github.jaypetez.ollamamobile.remote.tls

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.remote.testServer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The base64 of 32 zero bytes: a well-formed SHA-256 pin, and not any real key. */
private const val BASE64_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
private const val HEX_PIN = "0000000000000000000000000000000000000000000000000000000000000000"

@RunWith(JUnit4::class)
class PinnedTrustTest {
    @Test
    fun `a pin is only applied when the user has permitted it`() {
        val stored = testServer("https://ollama.example.org").copy(
            pinnedCertSha256 = "sha256/$BASE64_PIN",
            allowPinnedSelfSignedTls = false,
        )

        // A stored-but-not-permitted pin is a real state — the user can revoke
        // the permission without losing the value — and it must not take effect.
        assertThat(PinnedTrust.pinnerFor(stored)).isNull()
        assertThat(PinnedTrust.pinnerFor(stored.copy(allowPinnedSelfSignedTls = true))).isNotNull()
    }

    @Test
    fun `a server with no pin gets no pinner`() {
        assertThat(PinnedTrust.pinnerFor(testServer("https://ollama.example.org"))).isNull()
    }

    @Test
    fun `the pinner is scoped to the configured host`() {
        val server = testServer("https://ollama.example.org").copy(
            pinnedCertSha256 = BASE64_PIN,
            allowPinnedSelfSignedTls = true,
        )

        val pinner = PinnedTrust.pinnerFor(server)!!

        assertThat(pinner.findMatchingPins("ollama.example.org")).isNotEmpty()
        // A pin is a statement about one host. Applying it anywhere else would
        // either break every other connection or mean nothing.
        assertThat(pinner.findMatchingPins("another.example.org")).isEmpty()
    }

    @Test
    fun `all three spellings of a fingerprint are accepted`() {
        val expected = "sha256/$BASE64_PIN"

        assertThat(PinnedTrust.normalisePin(expected)).isEqualTo(expected)
        assertThat(PinnedTrust.normalisePin(BASE64_PIN)).isEqualTo(expected)
        // What `openssl` prints, which is what a user pastes.
        assertThat(PinnedTrust.normalisePin(HEX_PIN.chunked(2).joinToString(":").uppercase()))
            .isEqualTo(expected)
    }

    @Test
    fun `something that is not a SHA-256 is rejected rather than passed to OkHttp`() {
        assertThat(PinnedTrust.normalisePin("")).isNull()
        assertThat(PinnedTrust.normalisePin("not a fingerprint")).isNull()
        // An MD5, or a truncated paste.
        assertThat(PinnedTrust.normalisePin("00112233445566778899aabbccddeeff")).isNull()
    }

    @Test
    fun `a pin of the right shape that is not a digest is rejected too`() {
        // 44 characters of valid base64 alphabet, so a length check waves it
        // through — but it decodes to 33 bytes, not 32.
        assertThat(PinnedTrust.normalisePin("A".repeat(44))).isNull()
        // The prefix is not a licence to skip validation: what follows it still
        // has to be a digest.
        assertThat(PinnedTrust.normalisePin("sha256/not-a-real-digest")).isNull()
        assertThat(PinnedTrust.normalisePin("sha256/" + "!".repeat(43) + "=")).isNull()
    }

    @Test
    fun `an unusable stored pin yields no pinner instead of throwing mid-request`() {
        val server = testServer("https://ollama.example.org").copy(
            pinnedCertSha256 = "sha256/not-a-real-digest",
            allowPinnedSelfSignedTls = true,
        )

        // CertificatePinner.Builder.add throws IllegalArgumentException on a pin
        // whose base64 it cannot read, and it would throw from inside the client
        // derivation — on a path whose entire contract is that it returns an
        // AppResult and never throws.
        assertThat(PinnedTrust.pinnerFor(server)).isNull()
    }
}
