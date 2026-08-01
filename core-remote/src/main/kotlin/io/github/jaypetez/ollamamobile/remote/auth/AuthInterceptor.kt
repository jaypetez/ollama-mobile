package io.github.jaypetez.ollamamobile.remote.auth

import io.github.jaypetez.ollamamobile.model.ServerAuth
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.SecretResolver
import io.github.jaypetez.ollamamobile.remote.ServerUrls
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** The header name, spelled once. */
private const val AUTHORIZATION = "Authorization"

/**
 * A credential that has already been resolved out of the secret store and is
 * ready to be written as a header value.
 *
 * It is a separate type from [ServerAuth] because [ServerAuth] holds a
 * `SecretRef` — an alias — and this holds the actual secret. Keeping the two
 * apart makes it obvious at every call site which one is safe to log (the
 * alias) and which is not (this). [toString] is overridden for the same
 * reason: a data class here would print the token the first time this object
 * ended up inside another data class's generated `toString`.
 */
class ResolvedCredential(
    val scheme: String,
    private val headerValue: String,
) {
    internal fun headerValue(): String = headerValue

    override fun toString(): String = "ResolvedCredential($scheme, ****)"

    companion object {
        fun bearer(token: String): ResolvedCredential = ResolvedCredential("Bearer", "Bearer $token")

        fun basic(username: String, password: String): ResolvedCredential =
            ResolvedCredential("Basic", Credentials.basic(username, password))
    }
}

/**
 * Resolves [ServerRef.auth] into a header value, or null when the server needs
 * no credential.
 *
 * Returns null rather than failing when the secret has gone missing: a server
 * whose token was invalidated by a lock-screen change should produce a 401 the
 * user can act on, not a storage error they cannot.
 */
suspend fun ServerRef.resolveCredential(resolver: SecretResolver): ResolvedCredential? = when (val serverAuth = auth) {
    is ServerAuth.None -> {
        null
    }

    is ServerAuth.BearerToken -> {
        resolver.resolve(serverAuth.tokenRef)?.let(ResolvedCredential::bearer)
    }

    is ServerAuth.BasicAuth -> {
        resolver
            .resolve(serverAuth.passwordRef)
            ?.let { password -> ResolvedCredential.basic(serverAuth.username, password) }
    }
}

/**
 * Attaches a resolved credential to every request for one server.
 *
 * ## Why an interceptor and not a header at the call site
 *
 * Every call site has to remember, and the one that forgets produces a 401 that
 * looks like a bad token rather than like a missing header. There are a dozen
 * endpoints and two protocol surfaces; that is a dozen chances to be wrong,
 * against one place to be right.
 *
 * ## Why the host check
 *
 * [ServerRef.baseUrl]'s host is the only host this credential is for. OkHttp
 * strips `Authorization` when it follows a redirect to a different host, and
 * this interceptor must not put it back: a server (or a proxy in front of one,
 * or an attacker who can influence a redirect) that answers `302` to
 * `https://collector.example` would otherwise be handed the user's token. The
 * check is on the *current* request's host, which is the redirected one by the
 * time the chain runs again.
 *
 * ## Logging
 *
 * The value is never logged, never put in an exception message, and never
 * recorded by `RequestHistory` — that type stores method, path, timing and
 * outcome and does not accept headers at all. `ApiInspectorInterceptor` in
 * `:core-common` redacts the header for the developer-tools screen.
 */
class AuthInterceptor(
    private val expectedHost: String,
    private val credential: ResolvedCredential?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(if (shouldAttach(request)) request.withCredential() else request)
    }

    private fun shouldAttach(request: Request): Boolean = credential != null &&
        request.header(AUTHORIZATION) == null &&
        request.url.host.equals(expectedHost, ignoreCase = true)

    private fun Request.withCredential(): Request = newBuilder()
        .header(AUTHORIZATION, requireNotNull(credential).headerValue())
        .build()

    companion object {
        /**
         * Builds the interceptor for [server], scoped to the host in its base
         * URL. A base URL that does not parse yields an interceptor that
         * attaches nothing rather than one that attaches to every host.
         */
        fun forServer(server: ServerRef, credential: ResolvedCredential?): AuthInterceptor = AuthInterceptor(
            expectedHost = ServerUrls.baseUrlOrNull(server)?.host.orEmpty(),
            credential = credential,
        )
    }
}
