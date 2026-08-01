# FAQ

## About the project

### Is this the official Ollama app?

No. OllamaMobile is an independent MIT-licensed project by @jaypetez. It speaks
Ollama's HTTP API and implements an Ollama-compatible server, but it is not
affiliated with or endorsed by Ollama.

### Does it need an Ollama server to be useful?

No, but it is the most useful mode today. The app can run GGUF models on the
device with no server at all, and it can serve its own API to other machines.
The default build has no native code compiled in, so a stock `assembleDebug`
APK is a remote client only — see [the native build switch](../index.md#the-native-build-switch).

### Where do I get it?

GitHub Releases only. No Google Play, no F-Droid. See
[Installation](installation.md).

### Why is there no Play Store listing?

Play distribution means a developer account, a review pipeline, a signing key
held by Google, and a data-safety declaration process, in exchange for
discoverability the project does not need. GitHub Releases costs nothing and the
audience for a sideloaded local-LLM app is already comfortable with it.

## Privacy and networking

### Does the app phone home?

No. There is no telemetry, no analytics SDK and no crash-reporting service in
the binary, and there never will be. The app makes exactly two categories of
outbound request: to servers you added, and to fetch model files you chose to
download. [Privacy](../privacy-policy.md) is specific about both.

### Why does it allow plain HTTP?

Because your Ollama server almost certainly speaks plain HTTP on port 11434 and
has no certificate. Android's `networkSecurityConfig` cannot express "permit
private ranges only" — `<domain>` takes hostnames and IP literals, never CIDR
ranges — and the hosts you add are not known at build time, so there is nothing
to enumerate. Cleartext is therefore permitted at the platform layer and
restricted in code by the LAN-only guard. The full reasoning, including why the
CGNAT range is allowed over a VPN, is in
[Security model](../security-model.md).

### Does it work over Tailscale or WireGuard?

Yes, and this is an explicitly supported case rather than an accident. Reaching
a home Ollama server from outside the house over Tailscale is the most common
way people do it, so the LAN-only guard treats the CGNAT range `100.64.0.0/10`
as permitted when the transport is a VPN interface. See
[Security model](../security-model.md).

### Can other people on my Wi-Fi reach the phone?

Only if you turn the embedded server on. It is off by default and never binds
automatically. When it is on, it is a listening HTTP service on your local
network and you should read [Enabling LAN access](../server/enabling-lan.md)
before enabling it.

## Local inference

### How fast is it?

Unknown, and the project will not guess. There is no physical arm64 device to
measure on, so no tokens-per-second figure appears anywhere on this site. When
hardware exists, the exact commands to produce real numbers are listed in
[Verification status](../verification-status.md) and the harness is described in
[How to run benchmarks](../benchmarking/how-to-run.md).

### Which model should I start with?

Something in the 1–3 B parameter range at `Q4_K_M`. It is the size that fits on
ordinary phones with room for a usable context.
[Requirements](requirements.md) has the memory arithmetic and
[Catalog](../models/catalog.md) lists what the app suggests.

### Why won't a model load even though the file downloaded fine?

Because the app calculated it will not fit in available memory and refused,
rather than letting Android kill the process mid-load. See
[Memory](../local-inference/memory.md) and
[Troubleshooting](../troubleshooting.md).

### Can it use the GPU or the NPU?

Not for GGUF inference, no, and this is a limitation worth stating plainly
rather than hedging. NNAPI is deprecated, and neither NNAPI nor LiteRT can
execute GGUF — there is no format bridge. `:core-ml` exists for CPU feature
probing, thermal hints and vector kernels, not as an accelerator.
[Backends](../local-inference/backends.md) explains what is and is not on the
table.

### Does KleidiAI make my model faster?

For `Q4_0`, `Q8_0`, `F16` and `F32`, KleidiAI kernels are compiled in. K-quants,
including the very common `Q4_K_M`, are **not** KleidiAI-accelerated; their
speed-up comes from ggml's own runtime repacking instead. Whether either path
engages on a real device has not been observed.

### Can I import a GGUF file I already have?

Yes, through the system file picker. The file is copied into app storage rather
than read in place, because the inference engine memory-maps weights by real
path and a SAF URI is not a path. [Storage](../models/storage.md) covers the
import flow and [JNI boundary](../architecture/jni-boundary.md) explains why the
copy is unavoidable.

## Building and contributing

### Do I need the Android NDK?

No. The default build compiles no native code — that is the whole point of
`-Pollama.nativeSource=none`, and it is why CI can build the app on a runner
with no NDK installed. You need the NDK only for
`-Pollama.nativeSource=build`.

### The build says `third_party/llama.cpp is not initialised`.

The submodule genuinely is not in the repository yet at 0.1.0. Build without
`-Pollama.nativeSource=build`. See [Troubleshooting](../troubleshooting.md).

### Why does `detekt` report problems but CI stays green?

Because detekt is deliberately non-blocking here. The only detekt release line
that understands this project's Kotlin is an alpha, and an alpha static
analyser must not be able to break a merge. Spotless and Android Lint are the
formatting and correctness gates. See [CI](../ci.md).

### Which Kotlin plugin should a new module apply?

None. AGP 9.3.1 has Kotlin support built in, and applying
`org.jetbrains.kotlin.android` on top of it is wrong. Use the convention plugins
in `build-logic/` — `ollamamobile.android.library`, `ollamamobile.jvm.library`
and friends. See [Module map](../architecture/module-map.md).

### How do I report a security issue?

Email <jayson@shoe4africa.org>. Do not open a public issue for a vulnerability.
[Security model](../security-model.md) describes the threat model this project
does and does not defend against.
