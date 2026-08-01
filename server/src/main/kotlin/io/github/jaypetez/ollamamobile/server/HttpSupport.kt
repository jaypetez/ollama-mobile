package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.remote.dto.RemoteJson
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import kotlinx.serialization.KSerializer

/*
 * Body handling, done by hand rather than through ContentNegotiation.
 *
 * Negotiation would decide the response type from an `Accept` header, and the
 * clients this server exists for send `Accept: * / *` and then insist on
 * NDJSON or SSE framing. Reading and writing the bytes here means the framing
 * is a property of the route, not of a header the client did not think about.
 */

/** Decodes the request body, letting a malformed one become a 400 upstream. */
suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>): T =
    RemoteJson.decodeFromString(serializer, receiveText())

suspend fun <T> ApplicationCall.respondJson(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(
        text = RemoteJson.encodeToString(serializer, value),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

/**
 * Registers [methods] on [path] and answers every other verb with a 405.
 *
 * Ktor's default for "path matched, method did not" is a 404, which tells a
 * client the endpoint does not exist — so it stops trying the API rather than
 * fixing its verb. The trailing method-less `handle` is a lower-priority match
 * than any of the method selectors above it, so it only fires for the verbs
 * that were not registered.
 */
fun Route.endpoint(
    path: String,
    vararg methods: HttpMethod,
    body: suspend RoutingContext.() -> Unit,
) {
    route(path) {
        methods.forEach { verb ->
            method(verb) { handle { body() } }
        }
        handle { call.respondMethodNotAllowed(methods.toList()) }
    }
}
