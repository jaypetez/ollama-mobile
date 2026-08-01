# OllamaMobile

OllamaMobile is an Android application designed to do three related things, and
it is worth being precise about which is which, because they have very different
maturity and very different risk profiles.

1. **Run language models on the device.** GGUF weights, `llama.cpp`, CPU
   inference, no cloud round trip.
2. **Act as a full client for a remote Ollama server.** Point it at the Ollama
   instance on your desktop, your home server or your Raspberry Pi and it will
   behave like a native front end for it.
3. **Serve an Ollama-compatible HTTP API from the phone.** Other machines on
   your network will be able to treat the handset as an inference endpoint.

The three modes are designed to share one conversation store, one model
catalogue and one router, so a chat can start against a remote server and finish
against the local engine without the user re-typing anything.

!!! warning "Status"
    **None of the three modes above exists in the repository at 0.1.0.** What
    exists is the Gradle build, the module layout, the layering gate and one
    pure-Kotlin type (`Quantization`) with its unit tests. `:core-remote`,
    `:core-llm`, `:server`, `:core-download`, `:core-storage`, `:core-data` and
    the rest are empty — build files and nothing else.

    This site documents the **design contract**: what each subsystem is meant to
    do, and why it is shaped the way it is. That is genuinely useful — it is
    what the implementation will be held to — but it is not a description of
    working software. Every page that describes unbuilt behaviour carries a
    `Status` block like this one. The authoritative inventory of what is and is
    not present is [Verification status](verification-status.md) and the
    `Not yet present` block in
    [CHANGELOG.md](https://github.com/jaypetez/ollama-mobile/blob/main/CHANGELOG.md).

!!! warning "Read this before you trust anything on this site"
    There is **no physical arm64 test device** for this project, and none is
    planned. Everything that requires real ARM hardware to prove — CPU-variant
    selection, KleidiAI kernels, tokens per second, sustained thermal behaviour,
    peak resident memory, low-memory-killer survival — is **unverified**. This
    project therefore publishes **no performance numbers at all**.

    [**Verification status**](verification-status.md) lists every significant
    claim, how it was checked, and whether it holds. Start there if you are
    evaluating the project rather than using it.

## Current state

Version 0.1.0. The repository is a complete, buildable 13-module Gradle project
with the layering rules enforced in CI. That is what is finished; it is also all
that is finished.

The default build produces an APK that **installs and starts and then does
nothing useful** — the only UI is a placeholder composable. It is not a working
remote Ollama client, because `:core-remote` contains no source files. What the
default build does prove, and what it was designed to prove, is the *absence
path*: the whole project compiles, packages and passes its gates on a machine
with no NDK, no CMake and no `llama.cpp` submodule (see
[the native build switch](#the-native-build-switch) below). That property is
what will keep contribution cheap once the features are written.

The Kotlin actually in the repository at 0.1.0 is: `MainActivity`,
`OllamaMobileApplication`, an instrumentation test *runner* with no tests behind
it, and `Quantization` in `:core-model` with 11 passing unit tests. Nothing
else.

## Why it is built this way

Three constraints shaped nearly every decision, and most of the architecture
pages are downstream of them.

**A fresh clone must build with no NDK.** Requiring a 2 GB toolchain and a
`llama.cpp` submodule before a contributor can run `assembleDebug` is a
contribution killer, and it makes every CI job slow. So the native engine will
sit behind an interface (`LlamaEngine`, to be declared in `:core-llm-api`), only
one module will implement it against `llama.cpp`, and a stub implementation will
be bound when native code is absent. The module boundaries and the layering gate
that make this possible exist today; the interface and the two implementations
do not. See [Module map](architecture/module-map.md).

**Nothing leaves the device unless the user asked for it.** No telemetry, no
analytics SDK, no crash-reporting service, ever. The only outbound traffic is to
servers the user added and model files the user chose to download. See
[Privacy](privacy-policy.md) and [Security model](security-model.md).

**Honesty about what is proven.** An on-device inference project is unusually
easy to lie about, because the interesting claims are performance claims and
nobody can check them from a README. Where a claim is not verified, it says so
inline, on the page where it would otherwise mislead.

## The native build switch

One Gradle property decides whether native code exists at all:

```bash
./gradlew assembleDebug                              # no native code (default)
./gradlew assembleDebug -Pollama.nativeSource=prebuilt
./gradlew assembleDebug -Pollama.nativeSource=build   # needs the NDK
```

| Value | Behaviour |
| --- | --- |
| `none` *(default)* | No native code and no CMake invocation. `BuildConfig.NATIVE_ENABLED` is `false`; once an engine exists, `StubLlamaEngine` will be bound in place of the llama.cpp one. This is what lets CI build with no NDK installed. |
| `prebuilt` | No CMake. `.so` files are consumed from `core-llm/prebuilt/<abi>/`, so pull-request jobs never pay the `llama.cpp` compile cost. |
| `build` | Compiles `llama.cpp` from the `third_party/llama.cpp` submodule via CMake. Requires the NDK and an initialised submodule. |

Pass `-Pollama.requireNative=true` to make the build fail rather than silently
degrade to `none`. Full detail in [Native build](local-inference/native-build.md).

!!! note "third_party/llama.cpp is not in the repository yet"
    The submodule lands in a later stage. `-Pollama.nativeSource=build` will
    fail today with a clear message telling you so. That is deliberate: it keeps
    CI green while the native work is in flight.

## Where to go next

<div class="grid cards" markdown>

- **Install it** — [Installation](getting-started/installation.md) and
  [First run](getting-started/first-run.md).
- **Check whether your phone can run a model locally** —
  [Requirements](getting-started/requirements.md).
- **Understand the codebase** — [Architecture overview](architecture/overview.md),
  then [Module map](architecture/module-map.md).
- **Judge the claims** — [Verification status](verification-status.md).
- **Contribute** — [Contributing](contributing.md) and [CI](ci.md).

</div>

## Distribution and licensing

Distribution is **GitHub Releases only**. There is no Google Play listing, no
F-Droid entry, and no plan for either. See [Release process](release-process.md).

OllamaMobile is MIT licensed, Copyright (c) 2026 Jayson Petersen. It is designed
to link `llama.cpp`, which is MIT licensed, Copyright (c) 2023-2024 The ggml
authors. The attribution lives in `NOTICE` and `THIRD_PARTY_LICENSES.md` today;
an in-app about screen will carry it once there is a UI to carry it in.

Security reports go to <jayson@shoe4africa.org>.
