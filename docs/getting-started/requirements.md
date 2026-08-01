# Requirements

## Device

| | Requirement |
| --- | --- |
| Android version | 10 (API 29) or newer — `minSdk` is 29 |
| Target API | 36 |
| ABI (release builds) | `arm64-v8a` only |
| ABI (debug builds) | `arm64-v8a` and `x86_64` |
| Storage | The app itself is small; models are not. Budget for the model you want. |
| Network | Only needed for remote servers and model downloads. |

`targetSdk` is deliberately held at 36 rather than 37. Targeting 37 makes
Android's runtime local-network permission mandatory, which would put a system
permission dialog in front of LAN discovery before the app has had a chance to
explain why it wants the local network. That is a bad first-run experience for
the app's single most important feature, so the bump waits until the onboarding
flow is ready to justify it.

The `x86_64` debug ABI exists for exactly one reason: hosted CI runners have no
ARM hardware, and an x86_64 emulator is the only place instrumentation tests can
run. It never ships in a release.

## Running the app as a remote client

Essentially nothing. Any Android 10+ arm64 device can drive a remote Ollama
server; the phone is doing UI work and streaming text. A five-year-old handset
is fine.

What you need on the other end:

- Ollama (or an OpenAI-compatible endpoint) reachable from the phone.
- Ollama bound to something other than loopback — `OLLAMA_HOST=0.0.0.0:11434`.
- The phone and the server on the same network, or joined by a VPN such as
  Tailscale.

See [Ollama API](../remote/ollama-api.md) and
[OpenAI compatibility](../remote/openai-compat.md) for what the client speaks.

## Running a model on the phone

This is where the device actually matters, and where the honest answer is
"arithmetic, not measurement".

### The memory arithmetic

Peak memory during generation is roughly:

```text
weights + KV cache + compute buffers + the app itself
```

**Weights.** The dominant term. Multiply the parameter count by the effective
bits per weight of the quantisation, then divide by eight. The effective figure
is higher than the name suggests, because k-quants carry per-block scale and min
metadata:

| Quantisation | Effective bits/weight |
| --- | --- |
| `Q2_K` | 3.35 |
| `Q3_K_M` | 3.89 |
| `Q4_0` | 4.55 |
| `Q4_K_M` | 4.85 |
| `Q5_K_M` | 5.69 |
| `Q6_K` | 6.56 |
| `Q8_0` | 8.50 |
| `F16` | 16.0 |

So a 3 B model at `Q4_K_M` is about `3e9 × 4.85 / 8 ≈ 1.8 GB` of weights before
anything else. These are the same constants the app uses in its model picker;
they live in `Quantization` in `:core-model`.

**KV cache.** Scales with context length, layer count and head dimension. It is
small at a 2 K context and stops being small at 32 K. If a model loads and then
dies partway into a long conversation, this is usually why.

**Headroom.** A phone's "8 GB of RAM" is not 8 GB of budget. The system, the
launcher, and whatever else the user has open take a large and variable share,
and Android's low-memory killer will terminate your app rather than let the
system swap. Assume you get a fraction of nominal RAM, and that the fraction
depends on the device and on what else is running.

### Rough guidance

!!! warning "These are estimates from arithmetic, not measurements"
    No physical arm64 device has been used to test this project. The table below
    is what the size calculation implies, not what was observed. It says nothing
    about speed — the project publishes no tokens-per-second figures, because it
    has none it could stand behind. See
    [Verification status](../verification-status.md).

| Device RAM | Realistic local model |
| --- | --- |
| 4 GB | Up to roughly 1–2 B parameters at `Q4_K_M`, short contexts. Expect pressure. |
| 6 GB | 2–3 B at `Q4_K_M` comfortably enough to be usable. |
| 8 GB | 3–4 B at `Q4_K_M`, or a smaller model at a higher quantisation. |
| 12 GB+ | 7–8 B at `Q4_K_M` becomes plausible. |

The app refuses to load a model it calculates cannot fit, rather than letting
the process be killed mid-load. That check is arithmetic against reported memory
plus a headroom margin; it is a guard against the obvious failure, not a
guarantee.

[Memory](../local-inference/memory.md) covers the model in detail and
[Quantization](../local-inference/quantization.md) covers the quality tradeoff.

### CPU features

`llama.cpp` is built with all ggml CPU variants and selects one at runtime from
the features the CPU reports; KleidiAI kernels are compiled in for the formats
that support them, which is the legacy linear quants and the float formats, not
the k-quants. [Backends](../local-inference/backends.md) explains the selection
policy.

!!! warning "Variant selection and KleidiAI are unverified"
    Both the runtime CPU-variant choice and whether KleidiAI kernels are
    actually engaged require a real ARM device to observe. Neither has been.

## Build machine

Distinct from device requirements — see
[Installation](installation.md#build-from-source) for the JDK, SDK, NDK and
CMake versions.
