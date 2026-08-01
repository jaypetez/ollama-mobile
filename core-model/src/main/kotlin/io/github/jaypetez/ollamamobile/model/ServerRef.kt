package io.github.jaypetez.ollamamobile.model

/**
 * A configured remote server.
 *
 * NO SECRET IS STORED IN THIS TYPE, and that is structural rather than
 * stylistic. This object is passed between modules, held in Compose state, put
 * in saved-instance bundles, copied into log lines and rendered by `toString()`
 * on any data class that contains it. A token living here would end up in
 * logcat and in a crash report eventually, no matter how careful each call site
 * intends to be. Instead it holds a [SecretRef] — an opaque alias — and
 * `:core-storage` resolves it against the Keystore-backed store at the moment
 * the request is signed, in the one place that is allowed to see the value.
 */
public data class ServerRef(
    public val id: ServerId,
    public val label: String,
    /**
     * Base URL including scheme and port, e.g. `http://192.168.1.40:11434`.
     * A plain `String` rather than a URL type so `:core-model` stays free of
     * both `java.net` parsing quirks and OkHttp; `:core-remote` parses it.
     */
    public val baseUrl: String,
    public val auth: ServerAuth = ServerAuth.None,
    /**
     * SHA-256 of the server certificate's SubjectPublicKeyInfo, captured and
     * accepted by the user at first connection.
     */
    public val pinnedCertSha256: String? = null,
    /**
     * Whether the default trust store may be bypassed *in favour of*
     * [pinnedCertSha256] for this one host.
     *
     * This is not "trust all certificates" — a trust-all `X509TrustManager`
     * would disable validation for every connection the client makes. Honour
     * this flag only when a pin is present, and only for this host.
     */
    public val allowPinnedSelfSignedTls: Boolean = false,
    public val enabled: Boolean = true,
    /** Epoch milliseconds of the last successful health check; null if never. */
    public val lastSeenAt: Long? = null,
) {
    /** True when a pin is configured and permitted to be used. */
    public val usesPinnedCertificate: Boolean
        get() = allowPinnedSelfSignedTls && pinnedCertSha256 != null
}

/** How requests to a [ServerRef] are authenticated. */
public sealed interface ServerAuth {
    /** Bare `ollama serve` on a trusted network. */
    public data object None : ServerAuth

    /** `Authorization: Bearer <resolved token>`. */
    public data class BearerToken(
        public val tokenRef: SecretRef,
    ) : ServerAuth

    /** `Authorization: Basic <base64(username:resolved password)>`. */
    public data class BasicAuth(
        public val username: String,
        public val passwordRef: SecretRef,
    ) : ServerAuth
}

/**
 * An opaque handle to a value in the Keystore-backed secret store.
 *
 * The alias is safe to log, persist in Room and show in a debug screen; the
 * value it points at is not, and never leaves `:core-storage`.
 */
@JvmInline
public value class SecretRef(
    public val alias: String,
) {
    public override fun toString(): String = alias

    public companion object {
        /**
         * The alias convention for server credentials. Centralised here so the
         * storage layer and the settings UI cannot drift into two spellings and
         * silently lose a saved token.
         */
        public fun forServer(serverId: ServerId, purpose: String): SecretRef =
            SecretRef("server.${serverId.value}.$purpose")
    }
}
