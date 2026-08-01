# Verification status

This is the page to read before you believe anything else on this site.

An on-device inference project is unusually easy to overclaim about, because the
interesting properties are performance properties and a reader cannot check them
from a README. This project has **no physical arm64 test device and none
planned**. Everything that requires real ARM hardware to demonstrate is
therefore unverified, and is marked as such here and inline on the pages where
it appears.

**As a direct consequence, OllamaMobile publishes no performance numbers.** No
tokens per second, no time to first token, no memory figures, no battery
figures, anywhere — not in the README, not in release notes, not in the app.
There are none that could be stood behind.

!!! danger "A green `./gradlew test` is not evidence of coverage — read this first"
    `failOnNoDiscoveredTests` is set to **`false`** in the convention plugin.
    A module containing no tests therefore reports **success** rather than
    failing. At 0.1.0 exactly one module has any tests at all: `:core-model`,
    with 11. The other twelve contribute a green tick and nothing else.

    So "`./gradlew test` passes" — a phrase that appears in several rows below
    and in CI — means "nothing that is tested is broken". It does not mean the
    code is tested. Wherever this page marks something **Verified** on the
    strength of the test task, that is what is being claimed and no more.

    The setting is deliberate: with twelve empty modules, failing on no tests
    would make `check` unrunnable and the gate would be switched off entirely,
    which is worse. It should be flipped to `true` module by module as each one
    acquires its first test.

!!! danger "Most of this repository does not exist yet"
    Beyond `MainActivity`, `OllamaMobileApplication`, an instrumentation test
    *runner* with no tests behind it, and `Quantization` in `:core-model`, there
    is **no Kotlin in this repository**. `:core-common`, `:core-remote`,
    `:core-storage`, `:core-download`, `:core-data`, `:core-ml`, `:core-llm`,
    `:core-llm-api`, `:core-llm-testing`, `:server` and `:benchmark` hold build
    files and `consumer-rules.pro` only. There is no C or C++ anywhere: no
    `.gitmodules`, no `third_party/llama.cpp`, no `core-llm/src/main/cpp/`.

    Rows below marked **Not applicable yet** are the normal case, not the
    exception. A row marked **Verified** is a claim about the *build*, about
    configuration, or about `Quantization` — those are the only things there are
    to verify.

Last reviewed: **2026-07-31**, against version **0.1.0**.

## Legend

| Status | Meaning |
| --- | --- |
| **Verified** | Observed to be true on the development machine, by running the stated command or reading the stated artefact. Reproducible by anyone with the repository. |
| **Partial** | The claim is verified for the part that does not need hardware, and unverified for the part that does. The row says which is which. |
| **Unverified** | Not demonstrated. It is a design intent or an arithmetic estimate. It may well be true. It has not been shown to be. |
| **Not applicable yet** | The code or dependency the claim is about does not exist in the repository at this version. |

## Build and toolchain

| Claim | How verified | Status |
| --- | --- | --- |
| The project configures and builds with Gradle 9.6.1, AGP 9.3.1, Kotlin 2.3.21, JDK 21 toolchain, jvmTarget 17 | `./gradlew assembleDebug` on the development machine | **Verified** |
| `./gradlew assembleDebug` produces an APK with **no NDK installed** | Run on a machine with no NDK package present; default `ollama.nativeSource=none` | **Verified** |
| `compileSdk` 37 resolves against the SDK package `platforms;android-37.0` | Build succeeds with that package installed and no other platform 37 variant | **Verified** |
| The `org.jetbrains.kotlin.android` plugin is never applied; Kotlin comes from AGP 9 | Read of every `build.gradle.kts` and of `build-logic/`; build succeeds | **Verified** |
| All 13 modules plus the `build-logic/` included build configure | `./gradlew assembleDebug` configures the whole graph | **Verified** |
| The Gradle configuration cache is stored and reused | `org.gradle.configuration-cache=true`; observed on repeat invocations | **Verified** |
| `-Pollama.nativeSource=build` compiles `llama.cpp` | — | **Not applicable yet** — `third_party/llama.cpp` is not in the repository; the build fails with an explicit message, which *is* verified |
| `-Pollama.nativeSource=prebuilt` consumes `.so` from `core-llm/prebuilt/<abi>/` | — | **Not applicable yet** — no prebuilt artefacts exist to consume |
| `-Pollama.requireNative=true` fails the build when native code resolves to `none` | `./gradlew assembleDebug -Pollama.requireNative=true` fails with the expected message | **Verified** |
| Release builds package `arm64-v8a` only; debug packages `arm64-v8a` and `x86_64` | Configuration in `build-logic` (`Abis`); no native artefacts exist yet to inspect in the APK | **Partial** — configuration verified, packaged output not |
| `./gradlew assembleRelease` / `bundleRelease` succeed | Run with debug-key fallback signing | **Verified** — the artefacts produced are unsigned-for-release and must not be published |

## Quality gates

| Claim | How verified | Status |
| --- | --- | --- |
| `./gradlew spotlessCheck` passes | Run on the development machine | **Verified** |
| `./gradlew lintDebug` passes | Run on the development machine | **Verified** |
| `./gradlew test` completes successfully across all modules | Run on the development machine. Only `:core-model` has tests (11, all passing); the other twelve modules discover none and report success because `failOnNoDiscoveredTests` is `false` in the convention plugin. See the warning at the top of this page | **Verified** that the task succeeds. **This is not a coverage claim** — twelve of thirteen modules are untested. |
| `./gradlew checkModuleGraph` passes and the three layering rules are enforced | Run on the development machine; rules read from `ModuleGraphConventionPlugin` | **Verified** |
| `./gradlew detekt` runs and never fails the build | `ignoreFailures = true` in the root build; run on the development machine | **Verified** |
| `./gradlew koverXmlReport` produces an aggregated report | Run; report at `build/reports/kover/report.xml` | **Verified** |
| CI enforces the above as a merge gate via the `ci-ok` job | Workflow definition read: `ci-ok` needs `validate-wrapper`, `format`, `static-analysis`, `unit-tests` and `build`, runs `if: always()` and fails on any non-`success` result. See [CI](ci.md) | **Verified** by inspection of the workflow — no run has been observed on this repository |
| Instrumentation tests pass on an x86_64 emulator | — | **Not applicable yet** — there are no instrumentation tests. `app/src/androidTest/` contains `OllamaMobileTestRunner`, a runner, and no `@Test`. `instrumentation.yml`'s guard job detects this and skips the emulator job. |

## Architecture

| Claim | How verified | Status |
| --- | --- | --- |
| Nothing depends on `:app` | `checkModuleGraph` | **Verified** — the rule is enforced |
| Only `:app`, `:core-llm` itself and `:benchmark` MAY depend on `:core-llm` | `checkModuleGraph`; rule read from `ModuleGraphConventionPlugin` | **Verified** — this is a permission the gate enforces, not a description of the current graph. No module declares that dependency today. |
| `:server` may not depend on `:core-data`, `:core-storage`, `:core-download` or `:core-llm` | `checkModuleGraph` | **Verified** — the prohibition is enforced |
| `:core-llm-api` and `:core-model` are pure JVM with no Android dependency | Module type (`ollamamobile.jvm.library`) and dependency declarations | **Verified** |
| Consumers can be unit-tested against `FakeLlamaEngine` with no device or NDK | — | **Not applicable yet** — the code does not exist in the repository at 0.1.0; `:core-llm-testing` has no sources |
| The app runs as a pure remote Ollama client with no native code | — | **Not applicable yet** — `:core-remote` has no sources, so there is no client to run. What *is* verified is narrower and is the row above the section: `assembleDebug` produces an APK containing no native code. The APK starts to a placeholder composable. |
| One dedicated OS thread per engine; no `:llm` isolated process | Single-process is verifiable from the manifest; no engine implementation exists | **Partial** — the manifest declares no `android:process`, which is verified. Thread ownership is **Not applicable yet** — there is no engine. |
| Two-layer cancellation (cooperative between tokens, abort callback during prefill) | — | **Not applicable yet** — the code does not exist in the repository at 0.1.0 |
| Tokens are pulled (`nativeGenerateNextToken`), never pushed via a JVM callback | Design contract; no `AttachCurrentThread` or `GlobalRef` in the JNI layer | **Not applicable yet** — the native layer is not in the repository at 0.1.0 |
| `RegisterNatives` in `JNI_OnLoad` survives R8 full-mode renaming | — | **Unverified** — requires a release build containing native code |
| Model files are loaded by real path and memory-mapped; SAF URIs are import-only | — | **Not applicable yet** — the code does not exist in the repository at 0.1.0 |

## Local inference — the unverified block

Everything in this section needs a physical arm64 Android device. None of it has
been observed. It is listed exhaustively rather than summarised, because a
summary is where overclaiming hides.

| Claim | Why it cannot be verified here | Status |
| --- | --- | --- |
| The correct ggml CPU variant is selected at runtime from reported CPU features | `GGML_CPU_ALL_VARIANTS` selection happens on the target CPU; an x86_64 emulator exercises a different code path entirely | **Unverified** |
| KleidiAI kernels are engaged for `Q4_0`, `Q8_0`, `F16`, `F32` | Requires an ARM CPU with the relevant feature bits and a way to observe kernel dispatch | **Unverified** |
| K-quants are *not* KleidiAI-accelerated and rely on ggml runtime repacking | Documented upstream behaviour. That `Quantization.kleidiAiAccelerated` *derives* `false` for every k-quant is verified by unit test; whether that matches what ggml does on a real ARM CPU is not | **Partial** — the Kotlin is tested, the upstream behaviour it encodes is **Unverified** |
| Any tokens-per-second figure | No device | **Unverified — and no figure is published** |
| Any time-to-first-token or prefill-latency figure | No device | **Unverified — and no figure is published** |
| Sustained-load thermal behaviour and throttling | Requires a real SoC under sustained load | **Unverified** |
| Peak resident set (`VmHWM`) for a loaded model | Requires reading `/proc/<pid>/status` on a device running real inference | **Unverified** |
| Low-memory-killer behaviour with a large mapping resident | Requires real memory pressure on a real device | **Unverified** |
| The memory fit check correctly predicts what will load | The check is arithmetic; its accuracy is exactly the open question | **Unverified** |
| Battery cost of a generation session | No device | **Unverified** |
| Generation continues correctly with the screen off | Wake-lock behaviour is device- and OEM-specific | **Unverified** |
| Macrobenchmark and baseline profile generation work | — | **Not applicable yet** — `:benchmark` has no sources, so there is nothing to execute. `nightly-benchmark.yml` runs on schedule and skips for exactly this reason; see [Nightly benchmark CI](benchmarking/nightly.md) |

## Networking, server and privacy

| Claim | How verified | Status |
| --- | --- | --- |
| The app contains no telemetry, analytics or crash-reporting SDK | Dependency inventory across all modules; nothing of the sort is declared | **Verified** |
| The only outbound requests are to user-added servers and user-chosen model downloads | Dependency inventory shows no analytics or crash-reporting SDK; there is no networking code yet to inspect | **Partial** — the dependency claim is verified, the runtime claim **Not applicable yet** — the code does not exist in the repository at 0.1.0 |
| Cleartext is permitted at the platform layer and restricted in code by `LanOnlyGuard` | `network_security_config.xml` is present and verified; `LanOnlyGuard` is not written yet | **Partial** — the platform half is verified, the code half **Not applicable yet** — the code does not exist in the repository at 0.1.0 |
| A network security config cannot express "permit RFC1918" | Android platform behaviour: `<domain>` takes hostnames and IP literals, not CIDR ranges | **Verified** by platform documentation |
| Architecture tests forbid a second `OkHttpClient`, a custom `TrustManager` and bare sockets | Konsist is declared in `:core-common` but no test sources exist yet | **Not applicable yet** — the code does not exist in the repository at 0.1.0 |
| Discovery finds Ollama servers on a real subnet | Not exercised on a real network | **Unverified** |
| The embedded server is reachable from another machine and streams correctly | — | **Not applicable yet** — the code does not exist in the repository at 0.1.0; `:server` has no sources |
| The embedded server is off by default and never binds automatically | — | **Not applicable yet** — the code does not exist in the repository at 0.1.0 |

## Distribution

| Claim | How verified | Status |
| --- | --- | --- |
| Distribution is GitHub Releases only; no Play, F-Droid or fastlane configuration exists | Repository inspection — no such configuration is present | **Verified** |
| Release artefacts are reproducible from a tag | The release workflow has not been executed for a real tag | **Unverified** |
| The published APK installs and runs on a real arm64 device | No device | **Unverified** |

## What to run when hardware exists

These are the exact commands, in order. Everything below assumes an arm64 device
with USB debugging enabled and `adb devices` listing it.

**1. Build with native inference.**

```bash
git submodule update --init --depth 1 third_party/llama.cpp
./gradlew assembleDebug -Pollama.nativeSource=build -Pollama.requireNative=true
```

**2. Confirm the release build's native bindings survive R8.** This is the
`RegisterNatives` claim, and it can only fail in a shrunk release build.

```bash
./gradlew assembleRelease -Pollama.nativeSource=build -Pollama.requireNative=true
adb install -r app/build/outputs/apk/release/app-release.apk
# Load a model. An UnsatisfiedLinkError here means the JNI_OnLoad FindClass
# string no longer matches the class R8 produced.
adb logcat -s OllamaMobile:V AndroidRuntime:E
```

**3. Confirm the ABI split.**

```bash
unzip -l app/build/outputs/apk/release/app-release.apk | grep '\.so$'
# Expect arm64-v8a only. Any x86_64 entry is a packaging bug.
```

**4. Observe which CPU variant and which kernels are selected.** `llama.cpp`
reports backend and variant selection at init.

```bash
adb logcat -c && adb logcat | grep -iE 'ggml|kleidi|backend|repack'
```

**5. Measure peak resident memory.** `VmHWM` is the high-water mark, which is
the number that matters for the low-memory killer, not the instantaneous RSS.

```bash
PID=$(adb shell pidof io.github.jaypetez.ollamamobile)
adb shell cat /proc/$PID/status | grep -E 'VmHWM|VmRSS'
adb shell dumpsys meminfo io.github.jaypetez.ollamamobile
```

Take the reading after a long generation, not after load — the KV cache grows
with context.

**6. Observe thermal behaviour under sustained load.** Run a long generation and
sample throughout:

```bash
adb shell dumpsys thermalservice
adb shell cat /sys/class/thermal/thermal_zone*/temp
```

The question is whether throughput degrades as the device heats, and by how
much. A single short measurement will not show it.

**7. Test low-memory-killer survival.** Load the largest model the fit check
allows, start a generation, then open memory-hungry apps until the system is
under pressure.

```bash
adb logcat -s lowmemorykiller ActivityManager:I | grep -i kill
```

**8. Run the macrobenchmarks.**

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

!!! note "Task name caveat"
    This is the connected-test task AGP generates for the `benchmark` variant of
    a `com.android.test` module. It has never been executed in this project, and
    it is generated rather than declared by our build, so treat the exact name
    as unconfirmed until a device exists. Every other Gradle command on this
    page is a task that has been run.

**9. Only then, publish numbers.** With results from steps 4 through 8, on a
named device, at a named model and quantisation, with the measurement method
stated. Until all of that exists, this project's answer to "how fast is it" is
"we do not know", and that answer is more useful than a made-up one.

## How to correct this page

If you verify something listed here as unverified, change the row *and* the
inline caveat on the page that makes the claim, in the same pull request, and
state the device, the Android version, the model and the exact command in the
pull request description. A row moving from Unverified to Verified without a
reproducible procedure attached is not an improvement.
