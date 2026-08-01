package io.github.jaypetez.ollamamobile.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/*
 * The cross-cutting layer: CORS, the two guards, bearer auth and the error
 * envelope. Everything here runs before routing, and the order below is the
 * order it runs in.
 */

/**
 * Endpoints that are never authenticated.
 *
 * These are exactly what a discovery probe hits — the scanner this project
 * ships does `GET /` then `GET /api/version` — so authenticating them turns a
 * reachable server into an invisible one. Neither leaks anything: one is a
 * fixed string, the other a version number.
 */
val AUTH_EXEMPT_PATHS: Set<String> = setOf("/", "/api/version")

/** The literal body of `GET /`. Clients match on it, so it is exact. */
const val LIVENESS_BODY: String = "Ollama is running"

fun Application.installServerPlugins(env: ServerEnvironment) {
    installCors(env.config)
    installErrorEnvelope()
    installGuards(env)
}

/**
 * `OLLAMA_ORIGINS` in Ktor's vocabulary.
 *
 * Without this the server is unusable from Open WebUI, LibreChat, or anything
 * else running in a browser: the fetch succeeds on the wire and the browser
 * throws the response away. `Authorization` and `Content-Type` have to be
 * allow-listed explicitly — they are not "simple" headers — and
 * [allowNonSimpleContentTypes] is what permits `application/json` on a POST.
 */
private fun Application.installCors(config: ServerConfig) {
    install(CORS) {
        if (ServerConfig.WILDCARD_ORIGIN in config.allowedOrigins) {
            anyHost()
        } else {
            config.allowedOrigins.forEach { origin ->
                val scheme = origin.substringBefore("://", "")
                val host = if (scheme.isEmpty()) origin else origin.substringAfter("://")
                allowHost(host, schemes = if (scheme.isEmpty()) listOf("http", "https") else listOf(scheme))
            }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Head)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowNonSimpleContentTypes = true
        allowCredentials = false
    }
}

/**
 * Turns every uncaught failure into Ollama's `{"error": …}` shape.
 *
 * A client that receives an HTML error page from a server it believes is Ollama
 * reports "invalid JSON" and hides what actually went wrong, so the envelope
 * matters more than the status code.
 */
private fun Application.installErrorEnvelope() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondOllamaError(HttpStatusCode.BadRequest, ServerErrors.invalidRequest(cause.shortReason()))
        }
        exception<SerializationException> { call, cause ->
            call.respondOllamaError(HttpStatusCode.BadRequest, ServerErrors.invalidRequest(cause.shortReason()))
        }
        exception<Throwable> { call, cause ->
            // Cancellation is the client hanging up mid-stream. It is not an
            // error and there is nobody left to tell, so it must not be
            // rewritten into a 500 that pollutes the log.
            if (cause is CancellationException) throw cause
            call.respondOllamaError(HttpStatusCode.InternalServerError, cause.shortReason())
        }
    }
}

/** A one-line reason with no stack trace and no class names leaking to the wire. */
private fun Throwable.shortReason(): String = message?.takeIf { it.isNotBlank() }?.lineSequence()?.first()
    ?: "unexpected server error"

/**
 * `Via`, the loop guard, the Host guard and bearer auth — in that order.
 *
 * Order is load-bearing. The Host guard runs before auth so that a browser
 * being used as a confused deputy is refused without the server ever comparing
 * a token; and `Via` is stamped first so it is present even on a rejection,
 * which is what lets the *other* phone notice the loop too.
 */
private fun Application.installGuards(env: ServerEnvironment) {
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(HttpHeaders.Via, VIA_HEADER_VALUE)

        if (LoopGuard.isLoop(
                call.request.headers
                    .getAll(HttpHeaders.Via)
                    .orEmpty(),
            )
        ) {
            call.respondOllamaError(HttpStatusCode.Forbidden, ServerErrors.PROXY_LOOP)
            return@intercept finish()
        }

        if (!HostGuard.isAllowed(call.request.headers[HttpHeaders.Host])) {
            call.respondOllamaError(HttpStatusCode.Forbidden, ServerErrors.FORBIDDEN_HOST)
            return@intercept finish()
        }

        if (!call.isAuthorised(env.config)) {
            // The challenge header is what makes `curl -u` and the SDKs' retry
            // logic behave; without it a 401 looks like an application error.
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"ollama\"")
            call.respondOllamaError(HttpStatusCode.Unauthorized, ServerErrors.UNAUTHORIZED)
            return@intercept finish()
        }

        env.recordRequest()
    }
}

/** `Via: 1.1 ollamamobile`. The `1.1` is the protocol version RFC 9110 wants. */
const val VIA_HEADER_VALUE: String = "1.1 $VIA_TOKEN"

private fun ApplicationCall.isAuthorised(config: ServerConfig): Boolean {
    val expected = config.bearerToken ?: return true
    // A CORS preflight carries no Authorization header by definition — the
    // browser is asking whether it may send one. Rejecting it here would make
    // every authenticated browser client fail before its first real request.
    if (request.httpMethod == HttpMethod.Options) return true
    if (request.path() in AUTH_EXEMPT_PATHS) return true

    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    val presented = header.removePrefix("Bearer ").trim().takeIf { header.startsWith("Bearer ") } ?: return false
    return constantTimeEquals(presented, expected)
}

/**
 * Compares without an early return on the first differing byte.
 *
 * The token is 32 random bytes so a timing oracle is not the realistic threat,
 * but a constant-time compare costs one line and removes the question.
 */
private fun constantTimeEquals(a: String, b: String): Boolean {
    val left = a.toByteArray(Charsets.UTF_8)
    val right = b.toByteArray(Charsets.UTF_8)
    if (left.size != right.size) return false
    var diff = 0
    for (index in left.indices) diff = diff or (left[index].toInt() xor right[index].toInt())
    return diff == 0
}

/** Responds Ollama-shaped `405`, with the `Allow` header a correct 405 needs. */
suspend fun ApplicationCall.respondMethodNotAllowed(allowed: List<HttpMethod>) {
    response.header(HttpHeaders.Allow, allowed.joinToString(", ") { it.value })
    respondOllamaError(HttpStatusCode.MethodNotAllowed, ServerErrors.METHOD_NOT_ALLOWED)
}
