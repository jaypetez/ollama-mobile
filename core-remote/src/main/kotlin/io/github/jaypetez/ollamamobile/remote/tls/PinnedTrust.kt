package io.github.jaypetez.ollamamobile.remote.tls

import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.ServerUrls
import java.security.cert.X509Certificate
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex

/**
 * Per-host certificate pinning for user-accepted certificates.
 *
 * ## There is no trust-all `TrustManager` here, and there will not be one
 *
 * The advice the internet gives for "my Ollama server has a self-signed
 * certificate" is an `X509TrustManager` whose `checkServerTrusted` is empty.
 * That does not enable self-signed certificates; it disables certificate
 * validation for every connection the client makes, permanently, so any machine
 * on the path can present any certificate and be believed — while the user
 * still sees `https://` and reasonably concludes they are protected. A Konsist
 * test in `:core-common` fails the build on the mere mention of the type, and
 * that test is the reason this file uses [CertificatePinner] and nothing else.
 *
 * ## How a user accepts a pin
 *
 * 1. The app connects normally. If the platform trust store is happy — a real
 *    certificate, or a private CA the user installed in Android settings —
 *    nothing below happens.
 * 2. The handshake fails. The failure is mapped to
 *    `AppError.Network.Tls`, whose `fingerprintSha256` carries the
 *    `sha256/…` SubjectPublicKeyInfo hash of what the server presented when one
 *    could be recovered from the failure.
 * 3. The UI shows that fingerprint, formatted for comparison by eye against
 *    what the server operator can print with
 *    `openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
 *    Nothing is stored yet and the request is not retried.
 * 4. The user accepts. The fingerprint is written to
 *    [ServerRef.pinnedCertSha256] and [ServerRef.allowPinnedSelfSignedTls] is
 *    set — two fields, because a stored pin that is not yet permitted is a
 *    perfectly reasonable intermediate state and because the user can revoke
 *    the permission from settings without losing the value.
 * 5. Every later connection to that host carries [pinnerFor]. If the presented
 *    key changes, the connection fails and step 2 runs again — a renewal and an
 *    attack look identical to software, so the human decides.
 *
 * ## The honest limitation
 *
 * [CertificatePinner] constrains *which* key is acceptable; it does not create
 * a trust anchor. OkHttp checks the platform trust manager first, so a
 * certificate signed by nobody the device knows still fails the handshake even
 * with a pin configured. Making a genuinely self-signed certificate usable
 * needs the certificate itself as an additional anchor, which means storing the
 * DER the user accepted (not just its hash) and handing it to OkHttp's
 * `okhttp-tls` `HandshakeCertificates`. That is deliberately not done here:
 * [ServerRef] carries only a hash today, and adding the anchor is a change to
 * the storage schema and to the acceptance UI, not to this file. Until then the
 * supported deployments are plain HTTP on a LAN, a real certificate, or a
 * private CA installed at the OS level — and the pin is defence in depth on top
 * of whichever of those applies.
 */
object PinnedTrust {
    /**
     * The pinner for [server], or null when it has no usable pin.
     *
     * Both halves of [ServerRef.usesPinnedCertificate] matter: a pin that the
     * user has not permitted must not silently take effect.
     */
    fun pinnerFor(server: ServerRef): CertificatePinner? {
        if (!server.usesPinnedCertificate) return null
        val host = ServerUrls.baseUrlOrNull(server)?.host ?: return null
        val pin = normalisePin(server.pinnedCertSha256 ?: return null) ?: return null
        return CertificatePinner.Builder().add(host, pin).build()
    }

    /**
     * Applies [pinnerFor] to a client derived from the shared one. Returns
     * [builder] unchanged when there is no pin.
     */
    fun apply(builder: OkHttpClient.Builder, server: ServerRef): OkHttpClient.Builder =
        pinnerFor(server)?.let(builder::certificatePinner) ?: builder

    /**
     * The pin to show the user and store, computed from a certificate.
     *
     * SPKI rather than the whole certificate: the public key survives a
     * renewal, so the common case of "the operator regenerated the certificate
     * with the same key" does not train the user to click through a warning.
     */
    fun pinFor(certificate: X509Certificate): String = CertificatePinner.pin(certificate)

    /**
     * Accepts the three spellings a fingerprint arrives in and returns the one
     * OkHttp wants, or null when it is not a SHA-256 at all.
     *
     * Users paste what `openssl` printed, which is uppercase hex with colons;
     * our own capture produces `sha256/base64`; a config file might hold bare
     * base64. Rejecting two of the three would be a support burden for no gain.
     *
     * Validation is by *decoding*, not by counting characters, and that is the
     * point: `CertificatePinner.Builder.add` throws `IllegalArgumentException`
     * on a pin whose base64 it cannot read, and it throws from inside
     * [pinnerFor] — which runs while a request is being built, on a path whose
     * whole contract is that it returns an `AppResult` and never throws. A
     * length check alone lets `sha256/` plus 43 characters of anything through,
     * and 43 characters of anything is exactly what a truncated paste looks
     * like.
     */
    fun normalisePin(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(PIN_PREFIX)) return digestOrNull(trimmed.removePrefix(PIN_PREFIX))

        val hex = trimmed.removePrefix("sha256:").replace(":", "").replace(" ", "")
        if (hex.length == HEX_LENGTH && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return PIN_PREFIX + hex.lowercase().decodeHex().base64()
        }

        return digestOrNull(trimmed)
    }

    /** `sha256/…` for base64 that really decodes to 32 bytes; null for anything else. */
    private fun digestOrNull(base64: String): String? {
        val decoded = base64.decodeBase64() ?: return null
        return if (decoded.size == SHA256_BYTES) PIN_PREFIX + base64 else null
    }

    private const val PIN_PREFIX = "sha256/"
    private const val HEX_LENGTH = 64
    private const val SHA256_BYTES = 32
}
