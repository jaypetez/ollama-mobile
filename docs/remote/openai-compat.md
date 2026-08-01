# OpenAI-compatible `/v1`

Ollama exposes an OpenAI-shaped surface under `/v1` alongside
[its native API](ollama-api.md). OllamaMobile prefers the native API when it
knows it is talking to Ollama, and uses `/v1` when the endpoint is something else
that speaks the OpenAI protocol — llama.cpp's own server, vLLM, LM Studio, a
proxy.

Two protocols means two chances to get the framing wrong, and the framing is
genuinely different: NDJSON on the native API, Server-Sent Events here.

!!! warning "Status"
    Not implemented yet. This is the specification `:core-remote` must satisfy.

## The surface

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/v1/chat/completions` | The endpoint that matters. Streaming and non-streaming. |
| `POST` | `/v1/completions` | Legacy text completion. Supported for compatibility; the app does not use it. |
| `POST` | `/v1/embeddings` | Returns `data[].embedding`. |
| `GET` | `/v1/models` | Model list in OpenAI's shape (`{"object":"list","data":[…]}`). |
| `GET` | `/v1/models/{model}` | Single model. |

The compatibility layer is a translation over the same engine, so it is a strict
subset in places: Ollama-specific options (`keep_alive`, `num_ctx`, the full
`options` block) have no representation here, and the timing statistics from the
native API are not present at all. If you want tokens/sec, use the native API.
`usage` gives you token counts but no durations.

## SSE framing

A streaming `/v1/chat/completions` response is `text/event-stream`, not NDJSON.
The format is:

```
data: {"id":"chatcmpl-…","object":"chat.completion.chunk","created":1785500000,"model":"qwen3:4b","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

data: {"id":"chatcmpl-…","object":"chat.completion.chunk","created":1785500000,"model":"qwen3:4b","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: [DONE]

```

The rules that matter:

* Each event is one or more lines followed by a **blank line**. The blank line is
  the delimiter, not decoration.
* Payload lines are prefixed `data: ` — that is `data`, a colon, and by the
  specification a single optional space, which every implementation in practice
  emits. Strip the prefix; do not assume a fixed offset of six characters
  without checking, and do not `trim()` the remainder, because leading
  whitespace inside the JSON is irrelevant but leading whitespace inside a
  content delta is not.
* **`data: [DONE]` is a literal sentinel, not JSON.** Feeding it to a JSON
  parser throws. Check for it before parsing. It is also not guaranteed by every
  OpenAI-compatible server, so a stream that ends without it should be treated as
  complete-if-`finish_reason`-was-seen and as truncated otherwise.
* Lines beginning with `:` are comments — some servers and many proxies emit
  `: keep-alive` or `: ping` periodically to stop an intermediary from closing an
  idle connection. Skip them silently.
* `event:`, `id:` and `retry:` fields may appear. The chat completions protocol
  does not use them, but a proxy may add them. Ignore unknown field names rather
  than failing.

The terminal chunk has `finish_reason` set: `"stop"`, `"length"`, `"tool_calls"`,
or `"content_filter"`. As on the native API, `"length"` means truncation and
should be visible to the user.

## Byte-level buffering is mandatory

!!! danger "Frames split across TCP reads"
    An SSE event is not a network packet. A single `data:` line can arrive as
    three separate reads, and one read can contain two and a half events. This
    is not an edge case that occurs under adverse conditions — it is the normal
    behaviour of a stream, and it becomes reliably visible as soon as the
    content deltas get long or the network gets slow.

    Any code that does "read a chunk of bytes, split on `\n\n`, parse each part"
    works perfectly against a fast local server and then corrupts output over
    real Wi-Fi.

Buffer at the byte level, extract complete events, and keep the remainder for
the next read. With OkHttp, `response.body.source()` gives an Okio `BufferedSource`
and `readUtf8Line()` already does the buffering correctly — it returns `null`
only at end of stream and blocks until a full line is available. That is the
easy path and it should be the default:

```kotlin
val source = response.body.source()
val payload = StringBuilder()

while (!source.exhausted()) {
    val line = source.readUtf8Line() ?: break

    when {
        line.isEmpty() -> {                       // event boundary
            if (payload.isNotEmpty()) {
                val data = payload.toString()
                payload.clear()
                if (data == "[DONE]") return@flow
                emit(json.decodeFromString<ChatCompletionChunk>(data))
            }
        }
        line.startsWith(":") -> Unit               // comment / keep-alive
        line.startsWith("data:") -> {
            // Multi-line data fields concatenate with a newline between them.
            if (payload.isNotEmpty()) payload.append('\n')
            payload.append(line.removePrefix("data:").removePrefix(" "))
        }
        else -> Unit                               // event:, id:, retry:, unknown
    }
}
```

Two subtleties in that loop. First, a UTF-8 multi-byte character can be split
across reads too — `readUtf8Line` handles this, a naive
`String(bytes, Charsets.UTF_8)` per read does not, and the symptom is
intermittent replacement characters in the middle of non-ASCII text. Second,
accumulating multi-line `data:` fields with a newline separator is what the SSE
specification requires; OpenAI-shaped servers send single-line payloads, but a
proxy that re-wraps is entitled not to.

If you implement the buffering yourself rather than using Okio, the invariant is:
never parse until you have seen the event-terminating blank line, and carry any
partial trailing bytes into the next iteration.

## Errors

Errors before the response starts come back as an ordinary HTTP status with an
OpenAI-shaped body:

```json
{"error":{"message":"model 'nope' not found","type":"invalid_request_error","param":null,"code":null}}
```

Note the nesting — `error` is an **object** here, whereas on the native API the
mid-stream error line has `error` as a **string**. Sharing an error DTO between
the two clients does not work.

Mid-stream, the same problem as the native API applies: the status is already
200. Some servers emit an SSE event whose payload is an error object; some
simply close the connection. A stream that terminates without a `finish_reason`
and without `[DONE]` is a truncation and must be reported as one, not presented
as a complete answer.

## Tool calls: `arguments` is a string here

!!! warning "The difference that breaks shared domain models"
    On the **native** `/api/chat`, a tool call's `arguments` is a **JSON
    object**:

    ```json
    {"function":{"name":"get_weather","arguments":{"city":"Nairobi","unit":"c"}}}
    ```

    On **`/v1/chat/completions`**, it is a **JSON string containing JSON**:

    ```json
    {"function":{"name":"get_weather","arguments":"{\"city\":\"Nairobi\",\"unit\":\"c\"}"}}
    ```

    Same server, same model, same tool — different representation. This is
    OpenAI's historical shape and Ollama reproduces it faithfully in the
    compatibility layer.

The consequence is that a single `ToolCall` data class cannot deserialise both.
Model `arguments` in the domain as a parsed `JsonObject`, and let each transport
adapt:

* Native: decode the object directly.
* `/v1`: decode the string, then `Json.parseToJsonElement(it).jsonObject`.

And in the other direction, when sending a tool *result* back, the native API
expects the content in the message's `content` field while `/v1` expects a
message with `"role": "tool"` and a `tool_call_id` matching the call. The
`tool_call_id` has no native-API equivalent; when adapting a native conversation
to `/v1` you must synthesise stable ids and thread them through.

There is a third wrinkle specific to streaming. Over `/v1`, tool call arguments
arrive **incrementally across chunks**, keyed by `index`:

```json
{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"ci"}}]}}]}
{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"Nai"}}]}}]}
```

Each fragment is a piece of the argument string and is **not valid JSON on its
own**. Accumulate per `index` until `finish_reason` is `"tool_calls"`, then parse
once. Attempting to parse each fragment produces a stream of exceptions that
look like a malformed server response.

## Choosing between the two protocols

Prefer the native API when `/api/version` responds, because it gives timing
statistics, `keep_alive`, the full `options` block, and object-shaped tool
arguments. Fall back to `/v1` when it does not — that is the signal that the
endpoint is OpenAI-compatible but not Ollama.

The [embedded server](../server/endpoints.md) implements both surfaces for the
same reason, from the other side.

## Related

* [The Ollama native API](ollama-api.md)
* [Auth and TLS](auth-tls.md) — `Authorization: Bearer` applies to both.
* [Client examples](../server/client-examples.md) — pointing the OpenAI SDK at
  the phone.
