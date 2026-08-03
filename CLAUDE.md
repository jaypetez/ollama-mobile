# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app that does three things over one shared model catalogue, chat surface and routing
layer: runs GGUF models on-device via llama.cpp, acts as a full client for remote Ollama servers,
and exposes its own Ollama-compatible HTTP API from the phone.

Thirteen Gradle modules plus an included build at `build-logic/` holding the convention plugins.

**The prose docs lag the code — verify before you trust.** `README.md` and `CHANGELOG.md` still
describe 0.1.0 as "the build, not the app" with empty core modules. That is stale: all 13 modules
have sources (257 main + 111 test `.kt` files), the `third_party/llama.cpp` submodule is present and
pinned at tag `b10150` (`dee2a846`), and `core-llm/src/main/cpp/` holds the JNI layer plus a native
crash handler. Read the code, not the status sections.

Four more `docs/` pages lag the same way, and they are the ones that sound authoritative:

- `docs/security-model.md` still says eight modules contain no Kotlin sources and the repo has no C
  or C++ at all. Those eight hold 154 main + 80 test files.
- `docs/ci.md` and `docs/benchmarking/nightly.md` still say `third_party/llama.cpp` and
  `benchmark/src` do not exist, so their native and nightly jobs are described as permanent skips.
  Both now exist, and both jobs now do real work.
- `docs/verification-status.md` — which `README.md` names as the authoritative detail — is stale on
  which modules have sources and on per-module test counts, and self-contradicts (":core-model, with
  11" vs "648 tests, all passing").

Read those four as the specification they were written as, not as a description of the tree.
**`docs/architecture/` is the accurate part** and is the best prose in the repo for design rationale
— but it predates a rename, so check every symbol against the code before grepping for it. Known
drifts: `GenerationRequest`/`GenerationEvent` are now `InferenceRequest`/`InferenceEvent`; the
gateway entry point is `InferenceGateway.chat()`, not `generate(...)` (only `LlamaEngine.generate()`
kept the old name); `data-flow.md` and `jni-boundary.md` name a `nativeLoadModel` that does not exist
(it is `nativeCreateSession`); `threading.md` declares `nativeGenerateNextToken(handle: Long): Int`
when it returns `ByteArray?` (JNI `(J)[B`), as `jni-boundary.md` itself explains; and `data-flow.md`
still claims in-flight assistant text lives only in the ViewModel until completion, which is not how
it works (see Architecture below).

## Commands

```sh
./gradlew assembleDebug              # works with no NDK installed (nativeSource defaults to none)
./gradlew test                       # unit tests, all modules
./gradlew spotlessApply              # fix formatting — run this BEFORE spotlessCheck
./gradlew detekt                     # advisory only, never fails the build
./gradlew koverXmlReport             # aggregated coverage -> build/reports/kover/report.xml
```

The local gate — everything CI blocks on, in the order that avoids wasted builds:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck lintDebug test checkModuleGraph assembleDebug
bash scripts/check-repo-size.sh      # CI's other blocking check; no Gradle task covers it
```

`assembleDebug` is part of the gate — `ci-ok` needs the `build` job. Leaving it out is how a branch
passes locally and fails CI.

On Windows use `gradlew.bat`. Use the wrapper (Gradle 9.6.1); do not install Gradle.

Single test:

```sh
./gradlew :core-model:test --tests '*QuantizationTest'
./gradlew :core-remote:testDebugUnitTest --tests '*OllamaClientImplTest.*stream*'
```

Two traps here, both of which fail before a single test runs:

- **`--tests` only exists on real `Test` tasks.** That is `test` in the three pure-JVM modules
  (`:core-model`, `:core-llm-api`, `:core-llm-testing`). In every Android module `test` is a plain
  lifecycle task that aggregates variants, and passing `--tests` to it fails with "Unknown
  command-line option '--tests'". Filter on `testDebugUnitTest` there.
- **Test methods are backticked sentences with spaces**, so a method filter has to be a substring
  match (`.*stream*`). A prefix like `.stream*` matches nothing and the build fails with
  `No tests found for given includes: [...](--tests filter)`.

A filter that matches nothing does fail loudly, so a green filtered run is trustworthy. What
`failOnNoDiscoveredTests.set(false)` in the convention plugin buys is different: a module with *no
tests at all* does not fail `./gradlew test`, so a newly added module cannot break the repo-wide run.

Native builds:

```sh
git submodule update --init --depth 1 third_party/llama.cpp
./gradlew assembleDebug -Pollama.nativeSource=build
```

Do not invent task names. If a task is not listed here, in the README, or in `docs/ci.md`, it does
not exist.

## Build switches

| Property | Values | Effect |
| --- | --- | --- |
| `-Pollama.nativeSource` | `none` (default), `build`, `prebuilt` | `none`: no C++, no `.so`, `BuildConfig.NATIVE_ENABLED=false`, `StubLlamaEngine` bound. `build`: CMake against the submodule, needs NDK. `prebuilt`: consume `.so` from `core-llm/prebuilt/<abi>/`. |
| `-Pollama.requireNative` | `false` (default), `true` | Turns a silent fallback to the stub into a build failure. Use in release pipelines. |
| `-Pollama.testBuildType` | `debug` (default), `release` | Which variant `connectedAndroidTest` installs. `MinifiedReleaseSmokeTest` is only meaningful with `release`. |
| `-Pollama.warningsAsErrors` | `false` (default), `true` | Kotlin `allWarningsAsErrors`. |

`NativeSource` is derived from the Gradle property **only** — never from `file(...).exists()`. A
filesystem probe at configuration time goes stale the moment a submodule is initialised and poisons
the configuration cache.

## Build facts that will otherwise cost an afternoon

- **Never apply `org.jetbrains.kotlin.android`.** AGP 9 has Kotlin support built in; applying the
  standalone plugin is a hard error. Modules take convention plugins from `build-logic/`:
  `ollamamobile.android.application` (`:app` only — it owns the ABI filters, the
  `debug`/`release`/`benchmark` build types and the `ndkVersion` pin), `ollamamobile.android.library`,
  `ollamamobile.jvm.library`, `ollamamobile.android.compose`, `ollamamobile.android.hilt`,
  `ollamamobile.android.room`, `ollamamobile.android.native`, `ollamamobile.serialization`.
  `ollamamobile.quality` — and `ollamamobile.module.graph` beneath it — is applied *for* you by the
  application, library and jvm-library plugins; never apply either by hand.
- **Room is `androidx.room` 2.8.x**, not "room3".
- compileSdk 37 installs as `platforms;android-37.0` — the `.0` matters (`compileSdkMinor = 0`).
  targetSdk stays 36 deliberately: 37 makes the runtime local-network permission mandatory, which
  would gate LAN discovery behind a prompt before onboarding explains it.
- JDK 21 toolchain, JVM 17 bytecode target.
- Configuration cache is on (`org.gradle.configuration-cache` + `.parallel`). **Isolated Projects is
  not enabled anywhere**, so nothing fails the build when IP rules are broken — treat them as a
  hand-maintained invariant. Still **never reach across projects at configuration time** (no
  `subprojects { configurations }`): that is why `checkModuleGraph` is per-project and why the root
  Kover aggregation lists its children explicitly. One live violation already exists — root
  `detekt { source.setFrom(... subprojects.map { it.projectDir } ...) }` in `build.gradle.kts` — which
  would have to be rewritten before IP could be turned on. It does not break the configuration cache,
  which permits cross-project reads at configuration time.
- `maxParallelForks = 1` on every test task, on purpose. `org.gradle.parallel=true` already runs
  modules' test tasks concurrently; forking within each oversubscribes the machine and makes the
  MockWebServer suites fail intermittently.
- Release builds are `arm64-v8a` only. Debug adds `x86_64` solely so instrumentation tests can run
  on a hosted-CI emulator. Never released.
- `assembleRelease` without `keystore.properties` or `OLLAMA_KEYSTORE_*` env vars falls back to the
  **debug key** with a warning. That artefact must never be published.

## Module layering — machine-enforced

`checkModuleGraph` runs per module as part of `check` and fails the build on a violation. Rules live
in `ModuleGraphConventionPlugin.kt`; rationale in `docs/architecture/module-map.md`.

1. **Nothing may depend on `:app`.** Move the shared type down to `:core-model` (pure data) or
   `:core-common` (Android-flavoured, cross-cutting).
2. **Only `:app`, `:core-llm` and `:benchmark` may depend on `:core-llm`.** Everything else depends
   on `:core-llm-api`. This is the rule that makes `-Pollama.nativeSource=none` work, keeps the JNI
   blast radius to one module, and lets every consumer be unit-tested against `FakeLlamaEngine` on
   the JVM.
3. **`:server` may not depend on `:core-data`, `:core-storage`, `:core-download` or `:core-llm`.**
   It talks to the `InferenceGateway` interface from `:core-llm-api`; the concrete implementation is
   bound at `:app` assembly. This is a security property, not just build hygiene — `:server` is the
   only inbound network surface, and a handler that cannot see the downloader cannot be tricked into
   starting a download.
4. **`:core-model` stays pure Kotlin** — no Android, no I/O. **Not** enforced by `checkModuleGraph`
   (that task only checks project-to-project edges). What holds it today is the `ollamamobile.jvm.library`
   convention plugin plus review. A green build has not checked this.

If you believe a rule is wrong, change `ModuleGraphConventionPlugin` in the same PR and say why. Do
not work around it.

`:core-model` and `:core-llm-api` use `explicitApi()` — public declarations need explicit visibility
and return types.

## Repository-wide invariants enforced by tests

`core-common/src/test/.../ArchitectureTest.kt` (Konsist) scans **every module's** `src/main` and
fails on:

- Any `OkHttpClient` construction outside `HttpClientModule`. There is exactly one client and it
  carries the network policy (offline / LAN-only). Derive with `newBuilder()`; configure per-call
  behaviour with an interceptor or request tag.
- Any *mention* of `X509TrustManager`, `X509ExtendedTrustManager`, `HostnameVerifier` or
  `TrustManagerFactory`. The match is on the bare identifier, so an import — or a
  `TrustManagerFactory.getInstance()` lookup of the *platform* manager — trips it too, not just a
  custom implementation. Comments are stripped first, so KDoc explaining why there is no trust-all
  manager is fine (`ServerRef`, `PinnedTrust` do exactly that). Leave OkHttp's platform trust alone;
  pin per server with `CertificatePinner` if genuinely needed.
- Any bare `java.net.Socket` / `ServerSocket`. A raw socket bypasses `LanOnlyGuard`'s Dns,
  Interceptor and EventListener layers entirely.

`NoTelemetryClasspathTest` is **much narrower than it looks** and does not belong in the same
category. It `Class.forName`s 17 SDK entry points against `:core-common`'s own unit-test classpath
only (`:core-common` → `:core-model`, plus their libraries). An analytics artefact pulled into
`:app` — where a Play Services, Firebase or in-app-update dependency would actually land — leaves it
green. Nothing else guards it, and `CONTRIBUTING.md` is honest that review is the real gate. When
adding a dependency, read `./gradlew :app:dependencies` yourself and say so in the PR description.

## Architecture

Read bottom-up: `:core-model` knows nothing; `:app` knows everything and is the only module allowed to.

| Module | Type | Owns |
| --- | --- | --- |
| `:app` | Android app | Compose UI, navigation, ViewModels, Hilt wiring, foreground services. The only place the engine, server and data stack are assembled together. |
| `:core-model` | Pure JVM | The vocabulary: `Quantization`, `ModelRef`, `ChatMessage`, `ServerRef`, `AppError`, `MemoryVerdict`. |
| `:core-common` | Android lib | Substrate: the single `OkHttpClient`, `LanOnlyGuard` / `NetworkPolicyController`, structured logging, crash capture, API inspector. |
| `:core-llm-api` | Pure JVM | The inference contract: `LlamaEngine`, `InferenceGateway`, `InferenceRequest`, `InferenceEvent`, `RoutingPolicy`. Android-free. |
| `:core-llm` | Android lib | The only module that sees llama.cpp: JNI, `NativeLlamaEngine`, `StubLlamaEngine`, `InferenceArbiter`, crash sentinel, LoRA. |
| `:core-llm-testing` | Pure JVM | `FakeLlamaEngine`, published as a normal artefact so any module (and `:app`'s debug build) can depend on it. |
| `:core-ml` | Android lib | Device capability: CPU feature probing, feature-set → ggml variant policy, backend crash quarantine, thermal hints, int8 vector kernels for RAG. **Not an accelerator** — NNAPI/LiteRT cannot execute GGUF. |
| `:core-remote` | Android lib | Ollama + OpenAI-compatible HTTP clients, DTOs, NDJSON/SSE stream parsers, health monitor, subnet discovery, TLS pinning. |
| `:core-storage` | Android lib | Room database, DAOs, entities, FTS5 schema, DataStore, encrypted secrets store, app lock, memory estimator, GGUF header parser (parses a file it is handed; it does not decide where files live). **Not** the on-disk model layout — that is `:core-download`'s `ModelStorage`. |
| `:core-download` | Android lib | WorkManager downloads: resumable transfers, integrity checks, HuggingFace API, sharded models, quota, GC, and the on-disk model layout (`ModelStorage`: `filesDir/models/<dir>/<file>.gguf`, `metadata.json` as the atomic completion marker, `.part`/`.resume` sidecars under `filesDir/downloads/`). |
| `:core-data` | Android lib | Aggregation: repositories, `InferenceGatewayImpl`, `SmartRouter` + `CircuitBreaker`, model lifecycle/keep-alive, RAG orchestration, export. Chat, conversation and model-library state reach the UI through its repositories — but it is **not** a chokepoint, despite what the older docs imply: `:app` also depends on `:core-remote`, `:core-storage`, `:core-download` and `:core-ml` directly, and the discovery, servers, settings and benchmark ViewModels inject their types (`DownloadRepository`, `SubnetScanner`, `OllamaClient`, `RequestHistory`, `BackendQuarantine`). `checkModuleGraph` permits that; those are not violations. |
| `:server` | Android lib | Embedded Ollama-compatible HTTP server (Ktor CIO): routes, SSE/NDJSON writers, `HostGuard`, admission control, foreground service. |
| `:benchmark` | `com.android.test` | Macrobenchmark + baseline profile against `:app`'s `benchmark` build type. |

`docs/architecture/data-flow.md` traces the three flows that carry everything. Key invariants:

- **The user's turn is persisted to Room before generation starts**; it appears because the database
  emitted, not because a ViewModel optimistically appended.
- **The assistant's row is also inserted before the first token**, with `status = pending` and empty
  content (`beginAssistantTurn`), then grown by **incremental flush every 256 characters**
  (`FLUSH_THRESHOLD_CHARS`) — buffered rather than per token because `messages` is an FTS5
  external-content table whose trigger reindexes the whole row on every write. Process death loses only
  the last unflushed fragment: `recoverInterruptedTurns()`, run once per launch from `MainViewModel`,
  flips stranded pending rows to `failed` and keeps their text; cancellation persists the partial under
  `NonCancellable`. The ViewModel's streaming bubble is a render-time cache, never the source of truth.
  (`data-flow.md` still describes the older ViewModel-only design. It is wrong.)
- **`InferenceGateway.chat()` returns a cold flow that never throws.** Every failure arrives as
  `InferenceEvent.Failed` and the stream ends. Only `CancellationException` crosses the boundary.
  Routing happens on subscription. **At most one `Started`** (never two), then exactly one `Completed`
  or `Failed` — a failure *before dispatch* (no route, no engine in this build, a load failure, or the
  server deleted between routing and sending) emits a lone `Failed` with no preceding `Started`,
  deliberately, so no empty assistant bubble is left behind. The interface KDoc still promises exactly
  one `Started`; `InferenceGatewayImplTest` asserts the opposite, and the test is right.
- **The router decides per request, not per session.** `SmartRouter.choose` is a pure function of a
  `RoutingInput` value, so every policy and tie-break is testable without mocks. Model residency is
  the largest term in the comparison.
- **The UI coalesces tokens at ~25 Hz; the server does not coalesce.** An SSE consumer wants tokens
  as they arrive and applies its own backpressure through the socket.

### Threading / JNI rules (`docs/architecture/threading.md`, `jni-boundary.md`)

- One dedicated OS thread per engine. **Never call a native engine method from anywhere else**, and
  **never wrap a native call in `withContext(Dispatchers.IO)`** — the dispatcher may resume you on a
  different thread.
- Tokens are **pulled**, not pushed, so backpressure is the default.
- Cancellation is two layers (coroutine cancellation + a native abort callback). A new native call
  that can run longer than tens of milliseconds needs abort-callback coverage, not just a flag check
  around it.
- Handles cross as `jlong`. `RegisterNatives` in `JNI_OnLoad`, no file-static globals.
- Models load by **real path** and are mmapped; SAF content URIs are an import path only (copy into
  app storage first — a content URI cannot be mapped).

## Testing

Framework is decided by the module, not by preference:

| Module kind | Framework |
| --- | --- |
| Pure JVM (`:core-model`, `:core-llm-api`, `:core-llm-testing`) | JUnit 5 (Jupiter, via junit-bom 6.x) |
| Android library and app modules | JUnit 4 + Robolectric |
| Anything with an HTTP client | MockWebServer (`mockwebserver3`, **not** `-junit5`) — no network in unit tests |
| Anything exposing a `Flow` | Turbine |

Truth for assertions, MockK for mocking, `kotlinx-coroutines-test` for time control.

- **Test against `FakeLlamaEngine`**, never the native engine. Inference-path tests must pass with no
  NDK and no device.
- **Every new HTTP client gets MockWebServer fixtures** covering: normal response, streamed response
  with a partial final chunk, HTTP error with a body, HTTP error with an empty body, malformed JSON,
  and mid-stream disconnect. Assert on the request the client *sent* as well as what it parsed.
- Compose UI tests can run on the host under Robolectric (`:app`'s `testImplementation` carries the
  Compose test artefacts) — no emulator needed for recomposition assertions.
- If you want Jupiter in an Android module, that is usually a signal the logic belongs in a JVM module.
- Room schemas in `core-storage/schemas/` are committed so `MigrationTestHelper` can verify every
  upgrade path. **Never delete an old schema JSON — nothing enforces that yet.**
  `NoDestructiveMigrationTest` only bans `fallbackToDestructiveMigration` in this module's sources and
  asserts the schema for the *current* `OllamaDatabase.VERSION` is exported, so once `VERSION` becomes
  2, deleting `1.json` leaves the suite green. No test uses `MigrationTestHelper` yet, because
  `VERSION` is still 1 and there is nothing to migrate. When you bump it, add the `MigrationTestHelper`
  test for the new upgrade path and extend the schema assertion to cover every version from 1 up.

## Quality gates

| Task | Blocking | Notes |
| --- | --- | --- |
| `spotlessCheck` | yes | ktlint 1.8.0 engine + Compose rule set. No per-file licence header. |
| `lintDebug` | yes | `abortOnError = true`, `checkDependencies = true`, **no baseline** — a baseline is how a clean lint report quietly stops meaning anything. Suppress genuinely unfixable warnings in `config/lint/lint.xml` with a comment saying why. |
| `test` | yes | |
| `checkModuleGraph` | yes | |
| `detekt` | **no** | `ignoreFailures = true`. Its only release line understanding this Kotlin is an alpha; an alpha analyser must not break the build. Read its output, fix real findings, ignore false positives. |

The merge gate is the `ci-ok` job in `.github/workflows/ci.yml`, which `needs:` every other job.

## Git workflow

- Enable the hooks before your first commit: `git config core.hooksPath scripts/hooks`. The
  pre-commit hook runs `scripts/check-repo-size.sh`, which is what stops a multi-gigabyte GGUF, a
  `.so`, an APK or a signing keystore entering the object database — damage that is not undoable.
- Short-lived branches off `main`. **Every change goes through a PR**; `main` is protected. **Squash
  merge**, so the PR title becomes the commit on `main`.
- **Conventional Commits, validated in CI** by `.github/workflows/semantic-pr.yml`. Types: `feat` `fix`
  `perf` `refactor` `docs` `test` `build` `ci` `chore` `revert`. Optionally scoped with a module
  (`feat(core-remote):`). Two rules beyond the type list actually fail titles: the subject must **start
  lowercase and must not end with a period** (`subjectPattern`) — write `feat(core-remote): add LAN
  discovery`, never `feat(core-remote): Add LAN discovery.` — and `validateSingleCommit: true`, so on a
  one-commit PR the commit message must be valid too (GitHub seeds the squash message from that commit
  rather than from the title). Most of `git log` is Dependabot's capitalised `Bump …`, which is exempt;
  do not copy it. release-please reads the squashed title to pick the version and write `CHANGELOG.md`.
- **DCO sign-off required on every commit** (`git commit -s`). No CLA. CI checks it.
- Do not hand-edit released `CHANGELOG.md` sections — release-please owns them.

## Hard project constraints

- **No telemetry, ever.** No Firebase Analytics or Crashlytics, Sentry, Bugsnag, App Center, ad or
  attribution SDK, "anonymous usage statistics" toggle, remote logging endpoint, unique install
  identifier, or phone-home. Includes transitive additions — but **no test proves that repo-wide**;
  `NoTelemetryClasspathTest` only sees `:core-common`'s classpath (see above), so review is the real
  gate. Check `./gradlew :app:dependencies` and flag it in the PR description. Local diagnostics are
  welcome: crash capture to app storage the user can read and delete, structured logs, the in-app API
  inspector. Data may not leave the device except to a server the user configured.
- **No performance numbers.** There is no physical arm64 test device. Everything verified has run on
  the JVM or an `x86_64` emulator, which validates the JNI ABI contract, lifecycle, threading and
  cancellation — and validates **nothing** about NEON/dotprod/i8mm/SVE numerics, `GGML_CPU_ALL_VARIANTS`
  dispatch, or speed. Do not quote emulator timings. If something has not been observed, describe it
  as designed or intended, not as working, and record it in `docs/verification-status.md`.
- **No `Quantization` acceleration claims.** `kleidiAiAccelerated` is derived, not declared, and is
  `false` for every k-quant. Compute sizes with `estimateWeightBytes(parameterCount)` rather than
  typing a number — `Q4_K_M` is 4.85 bpw, not 4.0.
- **Bumping llama.cpp**: only `.github/workflows/llamacpp-bump.yml` (automated, monthly or dispatch)
  or `scripts/update-llamacpp.sh <tag>` (local). Dependabot's `gitsubmodule` ecosystem is
  deliberately absent from `dependabot.yml` and must stay absent — two bots proposing the same bump
  is how a JNI ABI break gets merged as "just a dependency update". Never
  `git -C third_party/llama.cpp pull`.
- **Docs site builds with `mkdocs build --strict`.** Add a page to `docs/` and to `nav` in `mkdocs.yml`
  in the same commit, and keep relative links resolving. Only one direction is machine-caught: a `nav`
  entry with no file is a WARNING and fails the strict build, while a file with no `nav` entry is only
  `INFO` (`validation.nav.omitted_files` defaults to `info` and is unset here), so an orphan page ships
  green — published, but reachable only by direct URL.

## Scripts

`.sh` files are bash (identical under Git Bash and on a Linux runner); `.ps1` files are PowerShell
because every path they touch is a Windows-side concern. CI never runs the PowerShell ones.

| Script | What it does |
| --- | --- |
| `hooks/pre-commit` | The git hook. `check-repo-size.sh` always; `spotlessCheck` when Kotlin/Gradle scripts are staged. Escape hatches: `OLLAMA_SKIP_HOOKS=1`, `OLLAMA_SKIP_SPOTLESS=1`, `--no-verify`. |
| `check-repo-size.sh` | Fails on a tracked file over 10 MB, or a staged/committed GGUF, `.so`, APK/AAB or signing key. Judges the git index, not the working tree. |
| `setup-dev-env.ps1` | Read-only readiness check: JDK, `ANDROID_HOME`, SDK packages, `local.properties`, wrapper. Installs nothing. |
| `setup-ndk.ps1` | Installs `ndk;29.0.14206865` and `cmake;3.31.0` (versions read from `libs.versions.toml`). |
| `gen-dev-keystore.ps1` | Throwaway keystore + `keystore.properties` for local release builds. Must never sign a published artefact. |
| `run-emulator.ps1` | Source to app-on-screen in one command: boot an AVD, wait for `sys.boot_completed`, `:app:installDebug`, grant `POST_NOTIFICATIONS`, `am start`. `-Native`, `-SkipBuild`, `-WipeData`, `-ForwardServerPort`, `-Logcat`. Debug only — release is arm64-only and cannot install on an x86_64 emulator. The `run-on-emulator` skill wraps it. |
| `format.sh` | `spotlessApply` + `clang-format` (C/C++) + `gersemi` (CMake). `--check` for a dry run. Only Spotless is a merge gate. |
| `build-native.sh` | Wraps `:core-llm:assemble* -Pollama.nativeSource=build -Pollama.requireNative=true`. `--abi`, `--clean`. |
| `verify-16kb-alignment.sh` | Asserts every `LOAD` segment in every `.so` is 16 KB-aligned (Android 15 page size). Distinct from zip alignment. |
| `update-llamacpp.sh` | Moves the submodule to a tag, stages the gitlink, prints the upstream log. Does not commit. |
| `fetch-models.sh` | Downloads the first ~64 KB of real GGUFs as parser fixtures via range requests. Requires network. No GGUF is ever committed. |
| `bench.sh` | Runs the benchmark harness over `adb` and collects result JSON. |
