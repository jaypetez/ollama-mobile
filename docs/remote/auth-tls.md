# Authentication, TLS and secrets

Ollama itself has no authentication. A bare `ollama serve` on a LAN is open to
anyone who can reach the port, which is fine on a trusted home network and not
fine anywhere else. In practice people put it behind something — a reverse proxy
with a bearer token, Tailscale, a self-signed certificate — and the client has to
cope with all of those without becoming a security hazard itself.

!!! note "Status"
    Not implemented yet. This page is the specification. The one piece that
    exists today is the backup exclusion for the secrets store, in
    `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`.

## Bearer tokens

The mechanism is a header:

```
Authorization: Bearer <token>
```

applied to every request to a server that has one configured, on both
[the native API](ollama-api.md) and [`/v1`](openai-compat.md). Nothing clever is
required, but several small things must be right:

* **Attach it via an OkHttp `Interceptor` bound to that server's client**, not by
  adding a header at each call site. A call site that forgets produces a 401 that
  looks like a bad token.
* **Scope it to the host.** A token for `ollama.example.org` must never be sent
  to `192.168.1.40`. Redirect following makes this easy to get wrong: OkHttp
  strips `Authorization` on cross-host redirects by default, and you should not
  re-add it in a custom `Authenticator` without checking the host.
* **Never log it.** The logging interceptor must have the header redacted
  (`redactHeader("Authorization")`), and error messages that echo the request
  must not include headers. A token in `logcat` is a token on any machine the
  phone has ever been plugged into.
* **A 401 is a distinct, actionable state.** "Server rejected the token" is a
  different message from "server unreachable" and leads to a different screen.

Some deployments use a proxy that wants a different header — `X-Api-Key`, or
basic auth. Supporting a user-named custom header is a small amount of code and
avoids a class of "your app doesn't work with my setup" reports. Basic auth
credentials embedded in a URL (`https://user:pass@host/`) should be parsed out
and stored as credentials rather than kept in the URL, because URLs end up in
logs and in the UI.

## Self-signed certificates, done safely

A self-signed or private-CA certificate is normal for a home server. Android's
default trust store rejects it, and the response the internet will give you is
"install a trust-all `X509TrustManager`".

!!! danger "Never ship a trust-all TrustManager"
    An `X509TrustManager` whose `checkServerTrusted` does nothing does not
    "allow self-signed certificates". It disables certificate validation
    entirely, for every connection that client makes, permanently. Any machine
    on the path can present any certificate and be believed. This turns an
    HTTPS URL into something strictly worse than plain HTTP, because the user
    sees a padlock and reasonably concludes the connection is protected.

    Google Play rejects apps that do this. We do not ship on Play, which removes
    the enforcement, not the reason.

The safe construction is **trust-on-first-use pinning against a specific
certificate, for a specific host, with explicit user consent.**

The flow:

1. Connect normally. If the handshake succeeds against the system trust store,
   there is nothing to do.
2. If it fails with a certificate error, capture the presented chain — via a
   `TrustManager` that records the chain and still throws, or by re-connecting
   with a recording trust manager under an explicit "inspect certificate" action.
   Do not complete the connection.
3. Show the user the certificate's subject, issuer, validity window, and the
   **SHA-256 fingerprint of the SubjectPublicKeyInfo**, formatted so it can be
   compared by eye against what the server operator can print with `openssl`.
4. Only if the user explicitly accepts, store that pin **against that hostname**
   and retry.
5. On every subsequent connection to that host, validate against the stored pin.
   If the certificate changes, fail and re-prompt — do not silently accept the
   new one. A changed certificate is either a legitimate renewal or an attack,
   and only the user can tell you which.

In OkHttp this is a `CertificatePinner` for the accepted host combined with a
custom `X509TrustManager` that accepts the pinned chain and delegates everything
else to the platform default. The key properties: the exception is per-host, it
is per-certificate, it required a human to approve it, and it is revocable from
settings.

Pin the SPKI hash rather than the whole certificate where possible — it survives
certificate renewal with the same key, which is the common case and avoids
training users to click through the re-prompt.

Two notes on the surrounding configuration. `minSdk` is 29, so TLS 1.3 and
modern cipher suites are available on every supported device; there is no reason
to enable a compatibility connection spec. And user-installed CAs are trusted in
**debug builds only** (`app/src/debug/res/xml/network_security_config.xml`), so a
developer can proxy traffic with mitmproxy — release builds trust the system
store plus explicit user-approved pins, and nothing else.

## Cleartext HTTP

Permitted, deliberately, and constrained in code rather than in the manifest.

An Ollama server on a Raspberry Pi is `http://192.168.1.40:11434`. Refusing
cleartext would make the app useless for its most common deployment. But a
network security config cannot express "plaintext to RFC 1918 addresses only" —
`<domain>` takes hostnames and IP literals, not CIDR ranges — so the config
permits cleartext broadly and the actual restriction lives in `:core-common`,
where it can resolve the destination, consult the live network state, and reject
a DNS answer that points outside the local subnet after resolution.

The user-visible rule should be: cleartext to a private address is normal and
unremarked; cleartext to a public address is a warning the user has to
acknowledge, because that conversation is readable by every hop between the
phone and the server.

## Secrets at rest

What is secret: bearer tokens, custom header values, basic-auth credentials, and
certificate pins (the last being integrity-critical rather than confidential —
an attacker who can rewrite a pin can substitute their own server).

**Storage.** `EncryptedSharedPreferences` and the rest of `androidx.security-crypto`
are deprecated and should not be used for new code. The current approach is
DataStore holding ciphertext, with the encryption key generated in and never
leaving the **Android Keystore**: an AES-256-GCM key created with
`setUserAuthenticationRequired(false)` (so background sync still works) and,
where the hardware supports it, `setIsStrongBoxBacked(true)` with a graceful
fallback since StrongBox is not universal. Each value is encrypted with a fresh
random IV, stored alongside the ciphertext. The Keystore key is device-bound and
non-exportable, so the ciphertext is useless off the device.

Optionally, gate *revealing* a token in the UI behind `androidx.biometric`. Gating
*use* of the token behind biometrics breaks background operation, so the two
should not be conflated.

**Backup.** Already handled, and worth understanding rather than copying. Both
`backup_rules.xml` and `data_extraction_rules.xml` exclude
`secrets.preferences_pb`. Two files because Android 12+ uses the newer
`<data-extraction-rules>` while older versions use `<full-backup-content>`, and
within the newer file the exclusions must appear under **both** `<cloud-backup>`
and `<device-transfer>` — they are evaluated independently, and omitting one
silently ships the excluded data through that path.

Excluding the secrets is not merely prudent: the ciphertext is encrypted with a
device-bound Keystore key, so restoring it onto a new device produces
undecryptable garbage. Better to have the user re-enter a token than to restore
something that fails mysteriously.

**Logging and diagnostics.** Redact in the logging interceptor. Redact in
exception messages that include the request. Redact in any "copy diagnostics"
feature, and redact in benchmark output — a benchmark JSON containing a server
URL with an embedded token, committed to a repository by a nightly job, is a
real way to leak a credential.

**Deletion.** Removing a server must delete its token, its pin, and its cached
model list. A "forget server" that leaves the credential behind is a bug.

## Related

* [Discovery](discovery.md) — a server that answers but rejects you.
* [The Ollama native API](ollama-api.md) — where the header is attached.
* [Enabling LAN access on the phone](../server/enabling-lan.md) — the same
  problems from the server side, including the token we generate.
