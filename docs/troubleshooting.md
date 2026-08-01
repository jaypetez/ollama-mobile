# Troubleshooting

Real failures, in the order they tend to happen. Each one says what the symptom
looks like, why it happens, and what to do.

## Build

### The build fails because I do not have the NDK

**It should not.** If `./gradlew assembleDebug` is asking you for an NDK on a
clean checkout, something is wrong with the invocation, not with your machine.

The default is `-Pollama.nativeSource=none`, set in `gradle.properties`. In that
mode the native convention plugin configures no `externalNativeBuild` and no
CMake at all: `BuildConfig.NATIVE_ENABLED` is `false`, `StubLlamaEngine` is
bound, and the app builds as a pure remote Ollama client. That is exactly how CI
builds it, on runners with no NDK installed.

Check, in order:

```bash
# What is the property actually resolving to?
grep ollama.nativeSource gradle.properties      # expect: none
```

- Are you passing `-Pollama.nativeSource=build` — perhaps from an IDE run
  configuration, a shell alias, or a `~/.gradle/gradle.properties` that
  overrides the project file? A user-level `gradle.properties` wins over the
  project one.
- Is `ollama.requireNative=true` set somewhere? That deliberately fails the
  build when native code resolves to `none`, with a message saying so.
- Are you building `:benchmark`? It targets `:app` but is only meaningful in its
  `benchmark` variant.

The error text tells you which case you are in — the plugin throws with an
explicit message rather than letting CMake fail obscurely later.

### `third_party/llama.cpp is not initialised`

```text
third_party/llama.cpp is not initialised. Run:
  git submodule update --init --depth 1 third_party/llama.cpp
```

You asked for `-Pollama.nativeSource=build`. At version 0.1.0 the submodule is
**not in the repository yet** — the command in the message will not find
anything to initialise, because the native stage has not landed.

Build without that flag. Native inference is a later stage; the switch exists so
nothing else has to change when it arrives.

### `Could not find platforms;android-37.0`

`compileSdk` is 37 and the platform is installed as `platforms;android-37.0` —
note the `.0`, which is the minor version. Installing `platforms;android-37` is
not the same package.

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
```

### Kotlin plugin errors, or duplicate-plugin complaints on a new module

AGP 9.3.1 has Kotlin support built in. Applying `org.jetbrains.kotlin.android`
on top of it is wrong and will misbehave in ways that read as unrelated. Use the
convention plugins in `build-logic/` — `ollamamobile.android.library` or
`ollamamobile.jvm.library`. See [Module map](architecture/module-map.md).

### `OutOfMemoryError` during `kspDebugKotlin`

KSP plus Room plus Hilt across thirteen modules is memory hungry.
`gradle.properties` already sets `-Xmx6g`; if you have overridden
`org.gradle.jvmargs` in `~/.gradle/gradle.properties`, your value wins and is
probably smaller.

### `checkModuleGraph` fails

The message names the offending edge and the rule. The three rules and the
reasoning behind each are in [Module map](architecture/module-map.md). Do not
suppress it — every one of the three protects a property the project depends on.

### `spotlessCheck` fails

```bash
./gradlew spotlessApply
```

## Connecting to a server

### "No servers found" after a network scan

Discovery probes the local subnet for Ollama's default port. It finding nothing
usually means one of these, roughly in order of likelihood:

**Ollama is bound to loopback.** This is by far the most common cause. Ollama
listens on `127.0.0.1:11434` by default and will not answer anything from the
network. On the server:

```bash
# systemd
sudo systemctl edit ollama      # add: Environment="OLLAMA_HOST=0.0.0.0:11434"
sudo systemctl restart ollama

# or, running it directly
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

Confirm from another machine, not from the server itself:

```bash
curl http://<server-ip>:11434/api/tags
```

**The phone and the server are on different networks.** Guest Wi-Fi, a
2.4 GHz/5 GHz split with client isolation, or the phone on cellular. Check that
the phone's IP and the server's IP share a subnet.

**Client isolation (AP isolation) is on.** Many routers and most guest networks
block client-to-client traffic entirely. Discovery cannot work through it and
neither can a manual connection. This is a router setting.

**A firewall on the server.** `ufw allow 11434/tcp`, or the equivalent.

**A non-standard port, or reachable only over a VPN.** The scan probes the
default port on the local subnet; it does not port-scan and it does not walk a
tailnet. Add the server by hand.

Manual entry is always available and is the right answer whenever discovery is
not: **Servers → Add server**, then `host:port`.

More detail in [Discovery](remote/discovery.md).

### The connection is refused or blocked as cleartext

If the app reports that a connection was blocked rather than that it failed, the
LAN-only guard rejected it. It permits plain HTTP to private ranges and refuses
it to public addresses.

Cases that get blocked, and what to do:

| Situation | Fix |
| --- | --- |
| A public hostname or address over `http://` | Use `https://`, or a reverse proxy that terminates TLS. |
| A hostname that resolves to a public address | Same. The guard classifies after resolution, so the name looking local does not help. |
| A `100.64.x.x` address with no VPN up | Bring the VPN up. That range is permitted only over a VPN transport — see [Security model](security-model.md). |
| Offline mode or LAN-only mode enabled in settings | Turn it off, if you meant to. |

If you believe a connection should be permitted and is not, that is worth an
issue — include the address form and whether a VPN was active. Do not work
around it by disabling the guard.

### It connects but returns 403, or hangs behind a proxy

A reverse proxy in front of Ollama often needs `Host` handling and streaming
support configured; buffering proxies break token streaming specifically, so the
symptom is a response that arrives all at once at the end. See
[Auth and TLS](remote/auth-tls.md).

## Local models

### The model refuses to load — "not enough memory"

The app calculated that the model plus its KV cache plus compute buffers will not
fit in available memory, and refused rather than letting Android kill the process
mid-load. That refusal is intentional: a killed process loses your conversation
and tells the user nothing.

What to do, in order of how much quality you give up:

1. **Reduce the context length.** The KV cache scales with it, and a 32 K
   context on a phone is usually the thing that pushed you over.
2. **Use a smaller quantisation.** `Q5_K_M` → `Q4_K_M` is a large saving for a
   modest quality cost. `Q4_K_M` → `Q3_K_M` saves less and costs more.
3. **Use a smaller model.** A 3 B model at `Q4_K_M` will beat a 7 B model that
   cannot load.
4. **Close other apps.** Nominal RAM is not your budget; whatever else is
   resident competes with you.

The arithmetic is in [Requirements](getting-started/requirements.md) and the
detail in [Memory](local-inference/memory.md).

!!! note "The check is an estimate"
    It is arithmetic against reported memory with a headroom margin, and it has
    never been validated against a physical arm64 device. It can be wrong in
    both directions — refusing a model that would have fitted, or allowing one
    that then fails. See [Verification status](verification-status.md).

### Generation is slow

There is no baseline to compare against — this project publishes no
tokens-per-second figures because it has measured none. What follows is a list
of causes, not a promise of a fix.

- **Thermal throttling.** Sustained generation heats a phone, and a hot phone is
  a slow phone. If the first minute is noticeably faster than the fifth, this is
  it. Nothing in the app can prevent it.
- **The model is too big for comfort.** If it barely fitted, the system is under
  memory pressure and pages backing the weights are being evicted and re-read.
  A smaller model can be dramatically faster, not just slightly.
- **Long context.** Attention cost grows with the conversation. A long chat gets
  slower as it goes; starting a fresh conversation is a real fix.
- **Prefill versus decode.** A long prompt takes seconds to ingest before the
  first token appears. That pause is not slow generation, and it happens once
  per prompt.
- **Thread count.** More threads than performance cores usually makes things
  worse, not better. See [Tuning](local-inference/tuning.md).
- **Battery saver.** Aggressive power modes clamp the CPU governor and will cut
  throughput.
- **Quantisation choice.** `Q4_0` and `Q8_0` are the formats KleidiAI kernels
  cover; k-quants like `Q4_K_M` rely on ggml's runtime repacking instead. Whether
  either path helps on your device has not been observed here.

If the *UI* feels laggy while the tokens themselves seem fine, that is a
different problem — the app coalesces token updates at roughly 25 Hz precisely to
avoid it, so it is worth an issue. See [Threading](architecture/threading.md).

### The app is killed during generation

Android's low-memory killer terminated the process. It happens when a loaded
model plus everything else on the device exceeds what the system will tolerate,
and it is more likely when you switch away from the app, because a background
process is a cheaper thing for the system to reap.

- Keep the app in the foreground while generating. It runs a foreground service
  precisely so the system treats it as user-visible work, but that raises the
  bar, it does not remove it.
- Use a smaller model or a shorter context. If you are being killed, you were
  near the edge.
- Close other apps first.
- Expect the in-flight answer to be lost. Your prompt is not — the user turn is
  persisted before generation starts, and only the assistant's reply is written
  at completion. That trade is described in
  [Data flow](architecture/data-flow.md).

!!! warning "Unverified"
    Low-memory-killer behaviour with a large mapping resident has never been
    observed on a physical arm64 device. The guidance above follows from how
    Android is documented to behave, not from a measurement here.

### A download stalls or fails partway

Downloads are resumable and run in a foreground service, so backgrounding the
app is safe. Retry — it resumes from where it stopped. If it fails immediately
and repeatedly, check free storage: a model needs its full size available, and
an *imported* GGUF temporarily needs twice, because the import copies the file
into app storage rather than reading it in place. That copy is unavoidable; see
[JNI boundary](architecture/jni-boundary.md).

## The embedded server

### Other machines cannot reach the phone

- The server is off by default. Confirm it is running and note the address and
  port it reports.
- Client isolation on the access point blocks it, exactly as it blocks
  discovery.
- Android may assign the phone a new IP after a network change; the address is
  not stable.
- Some OEM battery managers stop background network services aggressively. Keep
  the app in the foreground while serving.

See [Enabling LAN access](server/enabling-lan.md).

## Still stuck

Open an issue at
<https://github.com/jaypetez/ollama-mobile/issues> with: the app version, the
device and Android version, whether the build includes native inference, and the
exact command or steps. For build problems include the full Gradle output with
`--stacktrace`.

For a suspected vulnerability, email <jayson@shoe4africa.org> instead. Do not
open a public issue. See [Security model](security-model.md).
