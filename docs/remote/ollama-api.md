# The Ollama native API

OllamaMobile speaks Ollama's own `/api/*` surface as its primary remote
protocol, falling back to [the OpenAI-compatible `/v1` surface](openai-compat.md)
only when talking to something that is not Ollama.

This page is mostly about the wire-format traps. The endpoint list is the easy
part; the parts that cost you a day are the ones where a plausible-looking DTO
silently produces wrong behaviour.

!!! warning "Status"
    `:core-remote` is registered with OkHttp and kotlinx.serialization wired up,
    but the client and its DTOs are not implemented yet. This page is the
    specification the implementation must satisfy, and the traps below are
    exactly the ones the DTO design has to account for from the start.

## Endpoints the client uses

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/version` | Liveness and version. The cheapest possible probe — used to confirm a candidate host during [discovery](discovery.md). |
| `GET` | `/api/tags` | List installed models. |
| `POST` | `/api/show` | Model metadata: parameters, template, licence, `model_info` including architecture, layer count and context length. |
| `GET` | `/api/ps` | Which models are currently loaded, their VRAM footprint and expiry. |
| `POST` | `/api/generate` | Single-turn completion. Also the only way to reach a raw prompt with `"raw": true`, bypassing the template. |
| `POST` | `/api/chat` | Multi-turn chat, tool calls, images. The primary endpoint. |
| `POST` | `/api/embed` | Embeddings. Takes `input` as a string or an array of strings, returns `embeddings` as an array of arrays. |
| `POST` | `/api/pull` | Fetch a model onto the server. Streams progress. |
| `DELETE` | `/api/delete` | Remove a model from the server. |
| `POST` | `/api/copy` | Duplicate a model under a new name. |

`/api/embeddings` (plural, singular `prompt`, returns a flat `embedding`) is the
older form. Prefer `/api/embed`; accept the old shape only if a server rejects
the new one.

`/api/create`, `/api/push` and `/api/blobs/:digest` are not used by the client.
Creating models on a remote server from a phone is not a workflow this app is
trying to support.

## Trap 1: every timing field is optional

Ollama's Go structs tag the timing and counter fields `omitempty`:

```go
TotalDuration      time.Duration `json:"total_duration,omitempty"`
LoadDuration       time.Duration `json:"load_duration,omitempty"`
PromptEvalCount    int           `json:"prompt_eval_count,omitempty"`
PromptEvalDuration time.Duration `json:"prompt_eval_duration,omitempty"`
EvalCount          int           `json:"eval_count,omitempty"`
EvalDuration       time.Duration `json:"eval_duration,omitempty"`
```

`omitempty` means the field is **absent from the JSON when it is zero**, not
present-and-zero. Combined with the fact that intermediate streaming chunks carry
no statistics at all, this means:

* Every timing and count field in your DTO must be **nullable** (`Long?`, `Int?`).
  A non-null `Long` with no default throws `MissingFieldException` on the first
  chunk. A non-null `Long = 0` compiles and runs, and then silently reports a
  zero token rate.
* `prompt_eval_count` is legitimately absent when the prompt was served entirely
  from the server's prompt cache. That is not an error; it means zero new prompt
  tokens were evaluated.
* Absent is not the same as zero for display purposes. Show "—", not "0 tok/s".

Configure the serializer with `ignoreUnknownKeys = true` as well. Ollama adds
fields between releases, and a client that throws on an unrecognised key breaks
every time the server is upgraded.

## Trap 2: durations are int64 nanoseconds

Go's `time.Duration` is an `int64` count of **nanoseconds**, and it serialises as
a bare JSON integer with no unit anywhere in the payload.

`"total_duration": 4883583458` is 4.88 seconds. Read as milliseconds it is 56
days; read as seconds it is 154 years. Nothing in the response tells you which,
so the mistake produces a number that is merely wrong rather than obviously
wrong.

So tokens per second is:

```kotlin
val tokensPerSecond: Double? =
    if (evalCount != null && evalDuration != null && evalDuration > 0L) {
        evalCount.toDouble() / evalDuration.toDouble() * 1_000_000_000.0
    } else {
        null
    }
```

`eval_count / eval_duration × 1e9`. The same applies to prompt processing rate
with `prompt_eval_count / prompt_eval_duration`.

Guard the division. `eval_duration` can be absent (trap 1) and can be zero on a
response that produced no tokens, and dividing by zero in Kotlin's `Double`
arithmetic yields `Infinity` rather than throwing — which will render as
"Infinity tok/s" in the UI rather than crashing where you would notice it.

Keep the raw nanosecond values in the DTO and convert at the presentation layer.
Converting on parse loses precision and makes the field's meaning depend on where
you look at it. `kotlin.time.Duration.Companion.nanoseconds` is the right type
for the domain model.

Note also that the durations are not disjoint: `total_duration` includes
`load_duration`, `prompt_eval_duration` and `eval_duration`, plus queueing and
overhead. Do not present them as a stacked breakdown that sums to the total.

## Trap 3: the final `/api/chat` chunk may omit `message`

A streaming `/api/chat` response is newline-delimited JSON. Intermediate chunks
look like:

```json
{"model":"qwen3:4b","created_at":"2026-07-31T10:00:00.123456789Z","message":{"role":"assistant","content":" the"},"done":false}
```

The final chunk carries `"done": true` and the statistics — and its `message`
may be absent, or present with empty content, depending on server version and on
what finished the generation.

```json
{"model":"qwen3:4b","created_at":"2026-07-31T10:00:04.881234567Z","done":true,"done_reason":"stop","total_duration":4883583458,"load_duration":1067138958,"prompt_eval_count":26,"prompt_eval_duration":325953000,"eval_count":298,"eval_duration":4535599000}
```

A DTO declaring `message` as non-nullable throws on the terminal chunk. The
failure is nasty because the entire response has already streamed successfully —
the user sees the full answer appear and then an error toast, or the flow
completes exceptionally after emitting everything, depending on where the
exception lands.

Model it as `message: ChatMessage? = null` and treat a chunk with
`done == true` as the statistics carrier, reading content from it only if
present.

`/api/generate` has the analogous shape with `response` instead of `message`. In
current Ollama the terminal chunk emits `"response": ""` rather than omitting the
key — but model it as `String? = null` anyway. It costs nothing, and the client
also talks to non-Ollama servers that implement this protocol with varying
fidelity.

`done_reason` is worth capturing: `"stop"`, `"length"` (context or `num_predict`
exhausted), and `"load"` on responses that only loaded the model. A truncation
caused by hitting the length limit should be visible to the user, not silently
indistinguishable from a natural stop.

## Trap 4: mid-stream errors arrive at HTTP 200

!!! danger "This one looks like a bug in your app, not in the server"
    Once the response headers are sent, the status code is committed. If
    something fails *after* streaming has begun — the model is evicted, the
    server runs out of memory, a GPU fault occurs — Ollama cannot retroactively
    return a 500. It emits an NDJSON line with a top-level `error` key and
    closes the stream, **with the HTTP status still 200**.

```json
{"error":"an error was encountered while running the model: context canceled"}
```

A client that only checks `response.isSuccessful` and then parses each line into
its chunk DTO will, depending on serializer configuration, either throw an
opaque parse error at an arbitrary point in the stream or — with
`ignoreUnknownKeys = true` and every field nullable, which is what traps 1 and 3
push you towards — deserialise this into an entirely empty chunk and discard it.

That second outcome is the dangerous one. The stream ends, `done` was never
`true`, no exception was raised, and the user sees an answer that stops
mid-sentence with no indication that anything went wrong. It looks exactly like
the model deciding to stop early.

The rule: **inspect every line for a top-level `error` key before attempting to
parse it as a chunk.**

```kotlin
source.buffer().use { buffered ->
    while (true) {
        val line = buffered.readUtf8Line() ?: break
        if (line.isBlank()) continue

        val element = json.parseToJsonElement(line).jsonObject
        element["error"]?.jsonPrimitive?.contentOrNull?.let { message ->
            throw OllamaStreamException(message)
        }

        emit(json.decodeFromJsonElement(ChatChunk.serializer(), element))
    }
}
```

Parse once into a `JsonElement`, check for the error key, then decode. Parsing
the line twice works but doubles the cost on every token.

Two related checks belong in the same loop:

* **A stream that ends without `done == true` is a truncation**, even with no
  error line — the connection dropped, or the server was killed. Track it and
  surface it as an incomplete response rather than a complete one.
* **Blank lines happen.** Skip them rather than failing on them.

## Other wire notes

**`created_at` is RFC 3339 with nanosecond precision.** `2026-07-31T10:00:00.123456789Z`
has nine fractional digits. `java.time.Instant.parse` handles it;
`SimpleDateFormat` does not, and several JSON date decoders truncate to
milliseconds silently. Keep it as a string in the DTO unless you need to order by
it.

**`images` are base64 strings without a data URI prefix.** A `data:image/png;base64,`
prefix is not stripped by the server and produces a decode error.

**Tool calls on the native API carry `arguments` as a JSON object**, not a
string. This differs from `/v1`, and it is the single most common source of bugs
when the two clients share a domain model. See
[the OpenAI compatibility page](openai-compat.md).

**`keep_alive`** controls how long the server holds the model in memory after the
request. `0` unloads immediately, a negative value keeps it indefinitely, a
duration string like `"10m"` sets a timeout. For a phone client talking to a
shared home server this is a politeness setting worth exposing.

**`options`** carries the per-request generation parameters (`temperature`,
`top_p`, `num_ctx`, `num_predict`, `seed`, `stop`, …) as a nested object. Send
only the keys the user has actually changed; sending the full set with defaults
overrides the server's own configured defaults, which is rarely what anyone
wants.

**Timeouts must be asymmetric.** A connect timeout of a few seconds is right; a
read timeout of the same order will kill a legitimate generation the moment the
server pauses to load a model. Set the read timeout generously, or to zero and
rely on cancellation plus an idle-between-chunks watchdog, which is the more
precise mechanism: the meaningful failure is "no bytes for N seconds", not "the
whole request took too long".

## Related

* [OpenAI compatibility](openai-compat.md) — the `/v1` surface and where it
  diverges.
* [Discovery](discovery.md) — finding servers with `/api/version`.
* [Auth and TLS](auth-tls.md) — bearer tokens and self-signed certificates.
* [Routing](routing.md) — choosing between this and the local engine.
* [The embedded server](../server/endpoints.md) — the same protocol, served
  from the phone.
