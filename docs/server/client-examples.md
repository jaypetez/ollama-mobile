# Talking to the phone

Working examples for the embedded server, over USB and over the LAN. The point of
implementing Ollama's protocol is that none of these clients need to know they
are talking to a phone.

!!! warning "Status"
    `:server` is not implemented yet, so these examples have not been run
    against it. They are written against the protocol
    [the endpoints page](endpoints.md) specifies. Where a response body is
    shown it is illustrative of the shape, not a captured transcript.

Throughout: `MODEL` is a model name as returned by `/api/tags`, and `TOKEN` is
the bearer token the app generates when
[LAN access is enabled](enabling-lan.md).

## Over USB with `adb forward`

The recommended path for development. The server stays bound to loopback, no
token is needed, and nothing touches the network.

```bash
adb forward tcp:11434 tcp:11434
```

This forwards the desktop's `127.0.0.1:11434` to the phone's `127.0.0.1:11434`.
From the desktop's point of view Ollama is running locally, so every tool that
defaults to `http://localhost:11434` works with no configuration at all.

```bash
adb forward --list          # confirm
adb forward --remove tcp:11434
```

If port 11434 is taken on the desktop — for instance by a real Ollama install —
forward from a different local port:

```bash
adb forward tcp:11500 tcp:11434
```

and use `http://127.0.0.1:11500` below.

!!! note "`adb reverse` is the other direction"
    `adb reverse` forwards a port on the *phone* to the *desktop*. That is what
    you want for pointing the app at a desktop Ollama server, not for reaching
    the phone's server. Mixing them up produces a connection refused that looks
    like the server is not running.

## Over the LAN

Requires LAN access to be enabled in the app, which generates the token. The
phone's address and port are shown in the server notification.

```bash
export OLLAMA_HOST=http://192.168.1.55:11434
export TOKEN=<the token the app generated>
```

Every request needs the header:

```bash
curl -H "Authorization: Bearer $TOKEN" "$OLLAMA_HOST/api/version"
```

Without it you get a 401. With a `Host` header the server does not recognise you
get a 403 — that is [the rebinding guard](enabling-lan.md), and it is why you
should address the server by the IP or hostname it is bound to rather than
through an arbitrary alias.

## curl

Version and liveness:

```bash
curl http://127.0.0.1:11434/api/version
```

List models:

```bash
curl -s http://127.0.0.1:11434/api/tags | python -m json.tool
```

Non-streaming chat:

```bash
curl -s http://127.0.0.1:11434/api/chat -d '{
  "model": "'"$MODEL"'",
  "messages": [{"role": "user", "content": "Explain GQA in two sentences."}],
  "stream": false
}' | python -m json.tool
```

Streaming chat — NDJSON, one object per line:

```bash
curl -N -s http://127.0.0.1:11434/api/chat -d '{
  "model": "'"$MODEL"'",
  "messages": [{"role": "user", "content": "Count to five."}]
}'
```

`-N` disables curl's output buffering. Without it you see nothing until the
generation finishes, which looks like streaming being broken.

Extracting just the text, and separately the timing line:

```bash
curl -N -s http://127.0.0.1:11434/api/chat -d "$BODY" \
  | while read -r line; do
      echo "$line" | python -c 'import json,sys; d=json.load(sys.stdin); print(d.get("message",{}).get("content",""), end="")'
    done
```

Note the `.get("message", {})` — the final chunk may omit `message` entirely.
That is not a quirk of this server; it is
[Ollama's behaviour](../remote/ollama-api.md) and clients must handle it.

Tokens per second from the final chunk, remembering that durations are
**nanoseconds**:

```bash
curl -s http://127.0.0.1:11434/api/chat -d '{"model":"'"$MODEL"'","messages":[{"role":"user","content":"hi"}],"stream":false}' \
  | python -c 'import json,sys
d = json.load(sys.stdin)
n, t = d.get("eval_count"), d.get("eval_duration")
print(f"{n / t * 1e9:.1f} tok/s" if n and t else "no timing data")'
```

Embeddings:

```bash
curl -s http://127.0.0.1:11434/api/embed -d '{
  "model": "'"$EMBED_MODEL"'",
  "input": ["first document", "second document"]
}' | python -c 'import json,sys; print([len(v) for v in json.load(sys.stdin)["embeddings"]])'
```

With a token, add `-H "Authorization: Bearer $TOKEN"` to any of the above.

## The official `ollama` Python client

```bash
pip install ollama
```

Over `adb forward`, the default host already points at the phone:

```python
import ollama

print(ollama.list())

for chunk in ollama.chat(
    model=MODEL,
    messages=[{"role": "user", "content": "Explain KV cache quantisation briefly."}],
    stream=True,
):
    print(chunk["message"]["content"], end="", flush=True)
```

Over the LAN, construct a `Client` with the host and the auth header:

```python
from ollama import Client

client = Client(
    host="http://192.168.1.55:11434",
    headers={"Authorization": f"Bearer {TOKEN}"},
)

response = client.chat(
    model=MODEL,
    messages=[{"role": "user", "content": "Hello"}],
)
print(response["message"]["content"])
print(response.get("eval_count"), response.get("eval_duration"))
```

The library also honours the `OLLAMA_HOST` environment variable, so
`OLLAMA_HOST=http://192.168.1.55:11434 python script.py` works for the host, but
the header still has to be passed explicitly.

`client.pull(...)` will fail with a 501. That is deliberate — see
[endpoints](endpoints.md).

## The OpenAI SDK against `/v1`

```bash
pip install openai
```

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:11434/v1",
    api_key="unused",           # required by the SDK; ignored on loopback
)

stream = client.chat.completions.create(
    model=MODEL,
    messages=[{"role": "user", "content": "Write a haiku about page faults."}],
    stream=True,
)
for chunk in stream:
    delta = chunk.choices[0].delta.content
    if delta:
        print(delta, end="", flush=True)
```

The `base_url` **must include `/v1`**. Pointing it at the bare host produces 404s
against `/chat/completions`.

`api_key` is required by the SDK's constructor even when it is not used. Over the
LAN, put the real token there — the SDK sends it as
`Authorization: Bearer <api_key>`, which is exactly what the server wants:

```python
client = OpenAI(base_url="http://192.168.1.55:11434/v1", api_key=TOKEN)
```

Tool calls work, with the caveat that on `/v1` the `arguments` field is a **JSON
string**, not an object:

```python
import json

response = client.chat.completions.create(
    model=MODEL,
    messages=[{"role": "user", "content": "What is the weather in Nairobi?"}],
    tools=[{
        "type": "function",
        "function": {
            "name": "get_weather",
            "parameters": {
                "type": "object",
                "properties": {"city": {"type": "string"}},
                "required": ["city"],
            },
        },
    }],
)

call = response.choices[0].message.tool_calls[0]
args = json.loads(call.function.arguments)   # a string here; an object on /api/chat
```

The same call over `/api/chat` returns `arguments` already parsed. See
[OpenAI compatibility](../remote/openai-compat.md) for the full set of
differences.

## Other clients

Anything that accepts an Ollama base URL should work: `curl`, the Go and
JavaScript Ollama libraries, LangChain's `ChatOllama`, LlamaIndex's Ollama
integration, Open WebUI, and desktop chat clients with an Ollama backend. Point
them at `http://127.0.0.1:11434` over `adb forward` or at the phone's LAN address
with the token.

Two things will not work and are not bugs: anything that tries to pull models
(501), and any browser-based client, unless you have explicitly allow-listed its
origin in the CORS settings. The default is closed, deliberately.

## Troubleshooting

**Connection refused over `adb forward`** — the server is not running on the
phone, or `adb forward` is not set up. `adb forward --list` shows the mapping;
the app's notification shows whether the server is up.

**403 on every request** — the `Host` header does not match. Address the server
by the address shown in the notification.

**401** — missing or wrong token. It is required for anything not on loopback.

**Streaming arrives all at once** — buffering on the client side. `curl -N`,
`flush=True` in Python, and no proxy in between.

**503** — no model loaded, or the device is under memory pressure and the
[memory verdict](../local-inference/memory.md) refused the load. Load a model in
the app first.

**Generation is slow or stalls** — check whether the phone is thermally
throttling. Sustained inference will get there; see
[tuning](../local-inference/tuning.md).

## Related

* [Endpoints](endpoints.md) — what is implemented and what returns 501.
* [Enabling LAN access](enabling-lan.md) — the token and the risks.
