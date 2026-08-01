# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x`,
the public surface — Gradle properties, the embedded server's endpoints, the on-disk model layout,
the database schema — may change in any minor release.

This file is maintained by [release-please](https://github.com/googleapis/release-please) from
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/). Because pull requests are
squash-merged, the PR title is the commit that release-please reads: it decides the next version
from the commit type and writes the entry below from the subject. Do not hand-edit released
sections — fix the commit message instead, or the next release will overwrite you. The `Unreleased`
section is written by hand until the first release automates it.

## [Unreleased]

Nothing has been released yet. `version.txt` reads `0.1.0` and no artifact has been published to
GitHub Releases. What exists today is the build and the skeleton it will be filled into:

### Added

- Gradle build on Gradle 9.6.1 / AGP 9.3.1 / Kotlin 2.3.21 / KSP 2.3.10, JDK 21 toolchain with a
  JVM 17 bytecode target, compileSdk 37 (installed as `platforms;android-37.0`), targetSdk 36 and
  minSdk 29 (Android 10).
- Thirteen-module layout — `:app`, `:core-model`, `:core-common`, `:core-llm-api`, `:core-llm`,
  `:core-llm-testing`, `:core-ml`, `:core-remote`, `:core-storage`, `:core-download`, `:core-data`,
  `:server`, `:benchmark` — plus an included build at `build-logic/` holding the convention plugins.
- `checkModuleGraph`, a per-module layering gate wired into `check`: nothing may depend on `:app`,
  only `:app`, `:core-llm` itself and `:benchmark` may depend on `:core-llm`, and `:server` is
  barred from `:core-data`, `:core-storage`, `:core-download` and `:core-llm`.
- The `-Pollama.nativeSource=build|prebuilt|none` build switch, defaulting to `none`, so
  `./gradlew assembleDebug` succeeds with no NDK installed, compiles no C++ and packages no `.so`.
  `-Pollama.requireNative=true` turns that silent fallback into a build failure. (`StubLlamaEngine`
  is the engine the `none` path will bind; it is not written yet.)
- Quality gates: Spotless 8.9.0 with ktlint 1.8.0 and the Compose rule set (blocking), Android Lint
  with `abortOnError` and no baseline (blocking), and Kover 0.9.9 coverage aggregation. detekt
  2.0.0-alpha.5 is configured at the root as advisory only.
- Test scaffolding: JUnit 5 (junit-bom 6.1.2) for the pure-JVM modules, JUnit 4 + Robolectric for
  Android modules, MockWebServer for HTTP clients, Turbine for flows, Konsist for architecture
  tests.
- `Quantization` in `:core-model`: effective bits-per-weight for the GGUF formats including k-quant
  block metadata, size estimation, filename parsing, and a derived (not declared) KleidiAI
  acceleration flag that is correctly `false` for k-quants.
- ABI policy: releases are `arm64-v8a` only; debug builds also carry `x86_64` purely so emulator
  instrumentation tests can run on hosted CI runners.
- Backup exclusions for models, downloads, RAG vectors, logs, crash dumps and the encrypted secrets
  store, applied under `<full-backup-content>`, `<cloud-backup>` and `<device-transfer>`.
- A network security config that permits cleartext at the platform layer and documents why the real
  LAN restriction has to live in code.
- Project documentation: `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`,
  `NOTICE`, `THIRD_PARTY_LICENSES.md`.

### Not yet present

Recorded here so the section above is not read as more than it is:

- No user-facing features. There is no chat UI, no settings screen, no model catalogue, no
  downloader, no remote client implementation and no running embedded server. Beyond
  `MainActivity`, `OllamaMobileApplication`, an instrumentation test *runner* with no tests behind
  it, and `Quantization` with its 11 tests, there is no Kotlin in this repository:
  `:core-common`, `:core-remote`, `:core-storage`, `:core-download`, `:core-data`, `:core-ml`,
  `:core-llm`, `:core-llm-api`, `:core-llm-testing`, `:server` and `:benchmark` hold build files
  and `consumer-rules.pro` only. `StubLlamaEngine`, `FakeLlamaEngine`, `LlamaEngine`,
  `InferenceGateway` and `LanOnlyGuard` are named throughout the documentation as design contract;
  none of them is written.
- No C or C++ anywhere. There is no `.gitmodules`, no `third_party/llama.cpp` submodule and no
  `core-llm/src/main/cpp/`, so `-Pollama.nativeSource=build` cannot succeed on a fresh clone and no
  native code has ever been compiled in CI.
- No meaningful test coverage. `./gradlew test` is green, but `failOnNoDiscoveredTests` is `false`
  in the convention plugin, so the twelve modules with no tests report success. The only tests that
  exist are the 11 in `:core-model`. A green `test` task is not evidence of coverage.
- No published artifact and no signing key in use.
- No on-device verification of anything: there is no physical arm64 test device, only the JVM and an
  `x86_64` emulator. Consequently no performance numbers appear in this repository, and none will
  until that changes.

[Unreleased]: https://github.com/jaypetez/ollama-mobile/commits/main
