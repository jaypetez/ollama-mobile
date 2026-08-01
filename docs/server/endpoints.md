# Embedded server endpoints

OllamaMobile can expose the phone's local model over HTTP using Ollama's own
protocol, so that anything already written against Ollama — the official
clients, the OpenAI SDK, a script, a desktop app — can talk to it unchanged.

The server lives in `:server`, built on Ktor with the CIO engine. It depends on
the `InferenceGateway` *interface* from `:core-llm-api` and on `:core-remote` for
DTO reuse, and never on `:core-data`, `:core-storage` or `:core-llm`;
`checkModuleGraph` fails the build if that is violated. The concrete gateway is
bound at `:app` assembly. That layering is what keeps the server hostable without
dragging Room, WorkManager and the downloader into it.

!!! warning "Status"
    Not implemented. `:server` exists with its Ktor dependencies declared; the
    routes below are the specification. Nothing here has been served to a real
    client yet.

!!! danger "Loopback only unless you opt in"
    By default the server binds `127.0.0.1` and is reachable only from the phone
    itself (or through `adb forward`). Exposing it to the network is a separate,
    explicit decision with real consequences — see
    [enabling LAN access](enabling-lan.md).

Default port 11434, matching Ollama, so existing client configuration works
without modification.

## Native API

| Method | Path | Behaviour |
| --- | --- | --- |
| `GET` | `/` | Returns `Ollama is running` as `text/plain`. Trivial, and load-bearing: several clients and health checks probe exactly this. |
| `GET` | `/api/version` | `{"version":"…"}`. The version reported is OllamaMobile's own (from `version.txt`, currently 0.1.0) with an identifying suffix — pretending to be a specific upstream Ollama release would be a lie that clients could act on. |
| `GET` | `/api/tags` | Lists models installed on the device, in Ollama's shape: `name`, `model`, `modified_at`, `size`, `digest`, and a `details` object carrying `family`, `parameter_size` and `quantization_level`. Sourced from the on-device model store. |
| `POST` | `/api/show` | Metadata for one model: `details`, `model_info` (architecture, block count, context length, KV head counts — the same fields the [memory estimate](../local-inference/memory.md) uses), the chat template if the GGUF carries one, and the licence. |
| `GET` | `/api/ps` | Which model is currently loaded, its size and its expiry. On a phone the answer is zero or one model; the array shape is preserved so clients parse it. |
| `POST` | `/api/generate` | Single-turn completion. Streams NDJSON when `stream` is absent or true, returns a single object when false. Honours `raw`, `system`, `template`, `images`, `format`, `options` and `keep_alive`. |
| `POST` | `/api/chat` | Multi-turn chat. The primary endpoint. Streams NDJSON. Supports `tools`, `images` and `format`. |
| `POST` | `/api/embed` | Embeddings. `input` accepts a string or an array of strings; returns `embeddings` as an array of arrays. Served by the embedding model if one is loaded. |
| `POST` | `/api/embeddings` | The legacy singular form (`prompt` in, flat `embedding` out). Implemented because older clients still use it. |

### Streaming

Streaming responses are newline-delimited JSON with `Content-Type:
application/x-ndjson`, one object per line, terminated by an object with
`"done": true` carrying the statistics.

The server must reproduce the wire conventions the client side documents as
traps, because clients depend on them:

* **Durations are int64 nanoseconds.** `total_duration`, `load_duration`,
  `prompt_eval_duration`, `eval_duration`. Not milliseconds. A client computing
  `eval_count / eval_duration × 1e9` must get the right answer.
* **Omit zero-valued timing fields**, matching Go's `omitempty`. A client
  written against real Ollama tolerates absence; one written against a server
  that always emits zeros may not, and either way emitting a fabricated zero is
  worse than omitting.
* **On a mid-stream failure, emit a line with a top-level `error` key** and close
  the stream. The status is already 200 and cannot be changed. Doing anything
  else — closing silently, or emitting a malformed line — produces a truncation
  the client cannot distinguish from a normal stop. This is
  [the trap](../remote/ollama-api.md) from the other side; we must not be the
  server that gets it wrong.
* **Flush per chunk.** Buffering defeats the point of streaming, and Ktor will
  happily buffer if you let it.
* **Cancellation must propagate.** When the HTTP client disconnects, the
  inference job must be cancelled. Otherwise a client that hangs up leaves the
  phone generating tokens into nothing, burning battery and heat until the
  context is exhausted.

### Model management: 501, deliberately

| Method | Path | Behaviour |
| --- | --- | --- |
| `POST` | `/api/pull` | `501 Not Implemented` |
| `POST` | `/api/push` | `501 Not Implemented` |
| `POST` | `/api/create` | `501 Not Implemented` |
| `POST` | `/api/copy` | `501 Not Implemented` |
| `DELETE` | `/api/delete` | `501 Not Implemented` |
| `HEAD`/`POST` | `/api/blobs/:digest` | `501 Not Implemented` |

Allowing a network client to pull a multi-gigabyte model onto someone's phone —
consuming their storage, their battery and possibly their mobile data — is not a
capability this server should have. Model management stays in the app's UI, where
a human sees the size, the source and the network cost before agreeing. See
[downloading](../models/downloading.md).

The 501 body carries a JSON `error` explaining this, so a client shows something
better than a bare status code:

```json
{"error":"model management is not available on OllamaMobile; download models in the app"}
```

`501` rather than `404` because the route exists and the semantics are
understood; it is the capability that is withheld.

## OpenAI-compatible surface

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/v1/chat/completions` | SSE when streaming, JSON otherwise. |
| `POST` | `/v1/completions` | Legacy text completion. |
| `POST` | `/v1/embeddings` | `data[].embedding`. |
| `GET` | `/v1/models` | `{"object":"list","data":[…]}` |
| `GET` | `/v1/models/{model}` | Single model object. |

The framing differences from the native API are not cosmetic and are described in
detail in [OpenAI compatibility](../remote/openai-compat.md). From the server
side, the obligations are:

* `Content-Type: text/event-stream`, each payload prefixed `data: ` and each
  event terminated by a **blank line**.
* Emit the literal `data: [DONE]` before closing. Clients look for it.
* **Tool call `arguments` must be a JSON string** on `/v1`, and a JSON object on
  `/api/chat`. Same call, two encodings. Getting this backwards breaks every
  OpenAI-SDK client.
* Set `finish_reason` on the final choice — `stop`, `length`, or `tool_calls`.
* Populate `usage` where the information exists.
* Optionally emit a periodic `: keep-alive` comment line on long generations so
  intermediaries do not drop an idle-looking connection.

## Cross-cutting behaviour

**Authentication.** When bound to anything other than loopback, a bearer token is
required on every route including `/` and `/api/version`; requests without it get
401. On loopback the token is not required by default, because anything that can
reach loopback is already running on the device. See
[enabling LAN access](enabling-lan.md).

**Host header guard.** Requests are rejected unless the `Host` header matches an
expected value. This is the defence against DNS rebinding, and it applies in
loopback mode too.

**CORS.** Closed by default. If browser access is wanted it is an explicit
allow-list of origins, never `*` — a wildcard on a server holding a bearer token
means any web page the user visits can drive their phone's model.

**Errors** use Ollama's shape, `{"error": "..."}`, at the appropriate status:
400 for a malformed request, 401 for auth, 404 for an unknown model, 501 for the
withheld routes, 503 when no model is loaded or the device is under memory
pressure and the [memory verdict](../local-inference/memory.md) is Refuse.

**Concurrency of one.** The phone can serve one generation at a time. A second
concurrent request queues, with a bounded queue depth and a 503 beyond it.
Attempting genuine concurrency on a single-model, memory-constrained device
produces two slow answers and a thermal problem rather than throughput.

**Lifecycle.** The server runs in a foreground service with a persistent
notification showing the bind address and offering a stop action. It stops when
the service stops. It does not survive a reboot unless the user has explicitly
asked it to start on boot, and it should shut itself down when the device
disconnects from the network it was bound to — an interface disappearing out from
under a bound socket is exactly the moment to stop listening rather than to
silently rebind.

## Related

* [Enabling LAN access](enabling-lan.md) — the opt-in and its consequences.
* [Client examples](client-examples.md) — curl, the Python client, the OpenAI
  SDK.
* [The Ollama API](../remote/ollama-api.md) and
  [OpenAI compatibility](../remote/openai-compat.md) — the same protocols as
  consumed by the client.
