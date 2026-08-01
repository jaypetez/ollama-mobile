package io.github.jaypetez.ollamamobile.remote.dto

import kotlinx.serialization.json.Json

/**
 * The single [Json] every remote DTO in this module is encoded and decoded with.
 *
 * One instance, not one per call site: a `Json { }` block builds and caches a
 * serializers module and a descriptor cache, so a fresh one per request is both
 * wasteful and — far worse — a second, silently divergent contract with the
 * server. Every one of the four settings below is load-bearing.
 */
val RemoteJson: Json = Json {
    // Ollama adds response fields between point releases (`thinking`,
    // `done_reason`, `tool_name`, ...). Failing a whole response because a newer
    // server told us more than we asked for would turn every upstream release
    // into a client outage.
    ignoreUnknownKeys = true

    // Requests are built from DTOs whose optional fields default to null, and a
    // null means "not set", never "set to nothing". Writing `"format": null`
    // makes Ollama reject the request outright, so nulls are dropped instead of
    // serialised. This is also why every optional field here is nullable rather
    // than carrying a non-null default.
    explicitNulls = false

    // Strict parsing. Lenient mode accepts unquoted keys and unquoted string
    // values, which is exactly what a truncated or half-written NDJSON line
    // looks like — so leniency would turn "this line is corrupt" into "this
    // line means something else". Corruption must surface as a typed error.
    isLenient = false

    // With nulls already meaning "unset", a non-null Kotlin default is a value
    // this client chose — `stream = true`, `type = "function"` — and choosing it
    // has to reach the server. Without this, kotlinx omits any field that still
    // equals its default, so the request silently relies on the server's
    // default matching ours. It does today for `stream`; it is not a contract.
    encodeDefaults = true

    // Coercion replaces a null sent for a non-nullable property, and an
    // unrecognised enum constant, with this client's own default. Both are the
    // server disagreeing with us, and both would be silently rewritten into
    // agreement: `"done": null` would read as `done = false` — a finished
    // generation reported as still running — and an unknown `done_reason`
    // would become whichever constant happens to be declared first. A
    // disagreement has to surface. (Note that this setting does *not* affect
    // the timing counters: those are nullable, so a null decodes as null with
    // or without it. Their protection is the nullability itself.)
    coerceInputValues = false
}
