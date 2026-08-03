# Contributing to OllamaMobile

Thanks for looking. This document is the working agreement: what to install, how to branch and
commit, what has to be green before you push, and how to do the three things contributors most often
want to do. Participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and security
issues go through [SECURITY.md](SECURITY.md) rather than a public issue.

The project is pre-1.0 and moves quickly. If this document and the build disagree, the build is
right — please open a PR fixing the document.

## Getting set up

You need:

* **JDK 21.** Gradle runs on it and the toolchain requires it. Bytecode target stays at 17 because
  that is what AGP wants; you do not need a second JDK for that.
* **Android SDK**, with exactly these packages:
  * `platforms;android-37.0` — the `.0` matters. compileSdk is 37 and the platform is installed
    minor-versioned; `platforms;android-37` is a different (missing) package.
  * `build-tools;36.0.0`
  * `cmdline-tools` rev 22.0
* **Only for native work:** `ndk;29.0.14206865` and `cmake;3.31.0`. You do not need either to build,
  test or contribute to anything outside `:core-llm`.

`scripts/setup-dev-env.ps1` checks all of the above and tells you what is missing and how to install
it. Run it first; it is faster than reading a Gradle stack trace. It is read-only — it inspects and
reports, it never installs.

### Enable the git hooks — do this before your first commit

```sh
git config core.hooksPath scripts/hooks
```

**This is the single most important line in this document.** It points git at the version-controlled
hooks in `scripts/hooks` instead of the per-clone `.git/hooks`, which is the only way everyone gets
the same ones. The pre-commit hook runs `scripts/check-repo-size.sh`, and that is the gate that stops
a multi-gigabyte GGUF, a `.so`, an APK or a signing keystore from entering the object database.

The reason it matters more than the other gates is that this damage is not undoable. A bad format
commit is fixed by a follow-up commit. A committed GGUF or keystore is in the history forever: every
future clone pays for it, removing it means rewriting history for everyone who has a copy, and a
committed key has to be treated as compromised and rotated regardless. `git config` is cheap; the
alternative is not.

The hook also runs `./gradlew spotlessCheck`, but only when Kotlin or Gradle-script files are
staged. To undo: `git config --unset core.hooksPath`.

Then:

```sh
./gradlew assembleDebug
```

Use the wrapper (Gradle 9.6.1); do not install Gradle separately. On Windows, `gradlew.bat`.

That command succeeds with no NDK installed, because `-Pollama.nativeSource` defaults to `none`. See
[README.md](README.md#build-from-source) for what the other values do.

Two build facts that will otherwise cost you an afternoon:

* **Never apply `org.jetbrains.kotlin.android`.** AGP 9 has Kotlin support built in and applying the
  standalone plugin is a hard error. Modules take the convention plugins
  (`ollamamobile.android.library`, `ollamamobile.jvm.library`, and so on) from `build-logic/`.
* **Room is 2.8.x, not "room3".** The coordinates are `androidx.room`.

## Branching and pull requests

* Short-lived branches off `main`. Branch, do one thing, open a PR, get it merged, delete the
  branch. Long-running branches against a codebase moving at pre-1.0 speed are not worth the merge
  cost.
* **Every change goes through a pull request.** `main` is protected by a ruleset: no direct pushes,
  no force-pushes, no deletion, one approving review, and five status checks green. The maintainer
  holds an admin bypass — a single-maintainer project cannot require a review otherwise, since
  GitHub does not let anyone approve their own PR — and it is for a stuck gate, not for ordinary
  changes. Everything the ruleset contains is in `scripts/apply-branch-protection.sh` and explained
  in [CI](docs/ci.md).
* **Squash merge.** It is the only merge method the ruleset permits. The PR becomes exactly one
  commit on `main`, and the PR title becomes that commit's subject. This is why the title matters
  (see below). GitHub signs that commit, which is how the "signed commits" rule is satisfied without
  you needing a signing key.
* Keep PRs reviewable. A 2,000-line PR that touches six modules will sit unreviewed; three focused
  PRs will not.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/). Allowed types:

`feat` `fix` `perf` `refactor` `docs` `test` `build` `ci` `chore` `revert`

Optionally scoped with a module or area, e.g. `feat(core-remote):`. Breaking changes take a `!`
before the colon and a `BREAKING CHANGE:` footer.

```text
feat(core-remote): support per-server bearer tokens
fix(server): reject Host headers that are not private addresses
refactor(core-llm-api)!: fold GenerationEvent.Error into GenerationEvent
```

**Your PR title must be a valid Conventional Commit.** CI validates it, and because merges are
squashed, that title is what lands on `main` — which is what release-please reads to decide the next
version and to write [CHANGELOG.md](CHANGELOG.md). A sloppy title produces a wrong version bump and
a useless changelog entry, so this is enforced rather than encouraged. Individual commits within a
branch are less critical (they get squashed away), but keeping them conventional makes review
easier.

## Sign-off (DCO, not a CLA)

There is no CLA. This project uses the
[Developer Certificate of Origin](https://developercertificate.org/): by signing off, you state that
you wrote the contribution or otherwise have the right to submit it under the project's MIT licence.

Sign every commit:

```sh
git commit -s -m "fix(core-download): resume from the correct byte offset"
```

That appends `Signed-off-by: Your Name <your@email>` using your git identity, which must be a real
name and a reachable address. To fix a branch you already wrote:

```sh
git rebase --signoff main
```

## The local gate

Run this before you push. It is the same set of checks CI blocks on, and it is far quicker to fix
locally than in a PR round trip.

```sh
./gradlew spotlessApply
./gradlew spotlessCheck lintDebug test checkModuleGraph
```

`spotlessApply` first, always — it rewrites the formatting that `spotlessCheck` would otherwise fail
on, so running the two together in the wrong order just wastes a build.

What each one is:

| Task | Blocking | What it catches |
| ---- | -------- | --------------- |
| `spotlessCheck` | yes | Formatting. ktlint engine, plus the Compose rule set. |
| `lintDebug` | yes | Android Lint, `abortOnError = true`, `checkDependencies = true`, no baseline. |
| `test` | yes | Unit tests across all modules. |
| `checkModuleGraph` | yes | Module layering violations. |
| `detekt` | **no** | Advisory static analysis. |
| `koverXmlReport` | n/a | Aggregated coverage at `build/reports/kover/report.xml`. |

Do not invent task names. If something you want does not appear above or in the README, it does not
exist yet.

## Scripts

Everything in `scripts/` and what it is for. The `.sh` files are bash and are expected to work
identically under Git Bash on Windows and on a Linux runner; the `.ps1` files are PowerShell because
every path they touch is a Windows-side concern (`JAVA_HOME`, `%LOCALAPPDATA%`, `local.properties`)
and they must run before Git Bash can be assumed to be on `PATH`. CI never runs the PowerShell ones.

| Script | Shell | What it does |
| ------ | ----- | ------------ |
| `hooks/pre-commit` | bash | **The git hook. Enable it with `git config core.hooksPath scripts/hooks`.** Runs `check-repo-size.sh` on every commit, plus `spotlessCheck` when Kotlin or Gradle scripts are staged. Escape hatches: `OLLAMA_SKIP_HOOKS=1`, `OLLAMA_SKIP_SPOTLESS=1`, `--no-verify` — use them consciously. |
| `check-repo-size.sh` | bash | Fails if a tracked file exceeds 10 MB, or if a file that must never be tracked (GGUF weights, `.so`, APK/AAB, signing keys) has been staged or committed. Judges the git *index*, not the working tree. Called by the hook and runnable on its own. |
| `apply-branch-protection.sh` | bash | The version-controlled copy of everything protecting `main`: the branch ruleset, the `v*` tag ruleset, and the merge-method, workflow-token and SHA-pinning settings GitHub otherwise keeps only in its settings UI, where they have no history and nothing to restore them from. `--check` verifies and exits 1 on drift; with no argument it re-applies and then verifies. Needs `gh` authenticated as a repository admin. Rename a required job and this is the file that has to change with it. |
| `dependabot-triage.sh` | bash | Audits the open pull requests and merges the ones you name. `--audit` is the default and is read-only: one row per pull request with a verdict, plus `--json`. `--merge <pr>` re-runs every check immediately before squash-merging, because an audit is evidence about the moment it ran. It gates the things a ruleset structurally cannot express — that the pull request is genuinely Dependabot's (by user id, not just login), that every commit on the branch is the bot's *and* signed, that the changed paths are ones a configured ecosystem could legitimately rewrite, and that each action pin it adds resolves to a real released tag. Refuses a major bump unless `--notes-reviewed` says the release notes were read. Never touches `third_party/` — that is `update-llamacpp.sh` and `llamacpp-bump.yml` only. |
| `setup-dev-env.ps1` | PowerShell | Read-only readiness check for a development machine: JDK, `ANDROID_HOME`, the SDK packages, `local.properties`, the Gradle wrapper. Prints what is missing and the exact command that installs it. Installs nothing. `-SkipGradle` avoids the wrapper-download probe. |
| `setup-ndk.ps1` | PowerShell | Installs the native toolchain — `ndk;29.0.14206865` and `cmake;3.31.0`, versions read from `gradle/libs.versions.toml`. Bootstraps `cmdline-tools/latest` first if `sdkmanager` itself is missing. Idempotent; `-Force` reinstalls. Only needed for `-Pollama.nativeSource=build`. |
| `gen-dev-keystore.ps1` | PowerShell | Creates a throwaway keystore plus `keystore.properties` so `assembleRelease`/`bundleRelease` behave like the real thing locally. **The key it produces must never sign a published artefact** — the release key lives in Actions secrets. Output is gitignored. |
| `run-emulator.ps1` | PowerShell | Builds, installs and launches the app on an emulator for hands-on testing: boots an AVD, waits for `sys.boot_completed` rather than for `adb wait-for-device`, runs `:app:installDebug`, grants `POST_NOTIFICATIONS` and starts `MainActivity`. `-Native` for real llama.cpp, `-SkipBuild` to relaunch, `-WipeData` for first-run testing, `-ForwardServerPort` to curl the app's own server. Debug only — release is arm64-v8a and will not install on an x86_64 emulator. |
| `format.sh` | bash | Formats everything the repository knows how to format: `spotlessApply` for Kotlin and Gradle scripts, `clang-format` for C/C++, `gersemi` for CMake. Only Spotless is a merge gate; a missing clang-format or gersemi is reported and skipped, not an error. `--check` for a dry run. |
| `build-native.sh` | bash | Thin wrapper around `./gradlew :core-llm:assemble* -Pollama.nativeSource=build -Pollama.requireNative=true`, with `--abi` and `--clean`. Needs the NDK and the submodule; neither exists at 0.1.0. |
| `verify-16kb-alignment.sh` | bash | Reads ELF program headers with `llvm-readelf` and asserts every `LOAD` segment in every `.so` is aligned to at least 16 KB, which is what Android 15's 16 KB page size requires. Distinct from zip alignment — see the comment at the top of the script for why. |
| `update-llamacpp.sh` | bash | Moves `third_party/llama.cpp` to a given upstream tag, stages the bump and prints the upstream log for the range. Does not commit and does not push. This is the **local** path for a llama.cpp bump; see below. |
| `fetch-models.sh` | bash | Downloads the first ~64 KB of real GGUF files as parser test fixtures, via HTTP range requests. Header, metadata block and tensor index all live at the front of the file, so 64 KB is all the parser needs. Requires network. **No GGUF is ever committed** — `.gitignore` and `check-repo-size.sh` both enforce that. |
| `bench.sh` | bash | Runs the benchmark harness over `adb` and collects the result JSON. Stops at a guard today: `:benchmark` has no sources. Anything it could measure now is x86_64 emulator behaviour — never quote it as device performance. |

### Bumping llama.cpp

There are exactly two paths, and they are deliberately not allowed to race:

* **Automated** — `.github/workflows/llamacpp-bump.yml`. Monthly on a schedule, or on demand with a
  tag input. It opens a pull request that names the upstream tag, links the upstream compare view
  and spells out the ABI review a human has to do before merging. This is the only mechanism
  permitted to bump the submodule on `main`.
* **Local** — `scripts/update-llamacpp.sh <tag>`, for when you need to build and test a specific
  upstream commit yourself. It stages the gitlink and prints the upstream log; you write the commit.

Dependabot's `gitsubmodule` ecosystem is **deliberately absent** from `.github/dependabot.yml`, and
must stay absent. Dependabot would open a bare gitlink bump with no upstream release notes and no
changed-symbol summary, and two bots proposing the same bump is how a JNI ABI break gets merged as
"just a dependency update". There is no physical arm64 device here to catch that before users do.

Both paths are inert at 0.1.0: there is no submodule to move.

## Code style

**ktlint via Spotless is the gate.** One tool, one Kotlin parser, deterministic output, and
`spotlessApply` fixes essentially everything it complains about. There is no per-file licence header
— the repository is MIT and the root `LICENSE` covers it; a banner on every file adds noise without
adding legal effect.

**detekt is advisory only** (`ignoreFailures = true`), and that is deliberate rather than laziness.
The only detekt release line that understands the Kotlin version this project uses is still an alpha
built against a different compiler. An alpha analyser that can break the build is a build that
breaks for reasons unrelated to your change. Read its output — it finds real things — but a detekt
finding will never block a merge. If detekt flags something worth fixing, fix it in the PR; if it
flags a false positive, ignore it and move on.

Android Lint, by contrast, *is* a gate, and there is deliberately **no lint baseline**. A baseline
file is how a clean lint report quietly stops meaning anything. If you hit a genuinely unfixable
warning, suppress it in `config/lint/lint.xml` with a comment saying why.

## Testing requirements

Which framework you use is determined by the module, not by preference:

| Module kind | Framework |
| ----------- | --------- |
| Pure-JVM: `:core-model`, `:core-llm-api`, `:core-llm-testing` | **JUnit 5** (Jupiter, via the JUnit 6 platform BOM) |
| Android library and app modules | **JUnit 4 + Robolectric** |
| Anything with an HTTP client | **MockWebServer** — no network in unit tests |
| Anything exposing a `Flow` | **Turbine** |

Truth for assertions, MockK for mocking, `kotlinx-coroutines-test` for time control, Konsist in
`:core-common` for architecture tests (no second `OkHttpClient`, no custom `TrustManager`, no bare
`Socket` anywhere).

The split exists for two concrete reasons. The AGP integration needed to run JUnit 5 on Android
modules is a community plugin that lags AGP releases, and this project sits on the current AGP;
adopting it would mean the test framework decides when we can upgrade the build. And Robolectric's
runner is JUnit 4, so an Android module that wanted Jupiter would need both platforms wired up
anyway. The reason this costs nothing is the module layout: essentially all business logic lives in
`:core-model` and `:core-llm-api`, which are pure JVM. If you find yourself wanting Jupiter in an
Android module, that is usually a signal the logic under test belongs in a JVM module.

Two more rules:

* **Test against `FakeLlamaEngine`** (`:core-llm-testing`), never against the native engine. It is
  published as a normal artifact, not a test-only one, so any module — and the app's debug build —
  can depend on it. Inference-path tests must pass with no NDK and no device.
* **Every new HTTP client gets MockWebServer fixtures**, including the error and malformed-response
  cases. A client that has only been tested against a happy path has not been tested.

## Module layering

Thirteen modules; the dependency directions are rules, not guidelines. **Three of the four below are
machine-enforced** — `checkModuleGraph` runs per module as part of `check` and fails the build on a
violation. The fourth is a convention that only review catches, which is worth knowing before you
rely on it.

1. **Nothing may depend on `:app`.** If a core or server module needs a type from `:app`, the type
   is in the wrong place — move it down into `:core-model` or `:core-common`.
2. **Only `:app`, `:core-llm` itself and `:benchmark` MAY depend on `:core-llm`.** Everything else
   depends on `:core-llm-api`. Note that this is a permission, not a description: none of the three
   declares that dependency today. This is the rule that keeps the app buildable and testable with
   `-Pollama.nativeSource=none`, and keeps the JNI surface inside one module.
3. **`:server` may not depend on `:core-data`, `:core-storage`, `:core-download` or `:core-llm`.**
   It talks to the `InferenceGateway` interface declared in `:core-llm-api`; the concrete
   implementation is bound at `:app` assembly. That is what lets the server be hosted without
   dragging in Room, WorkManager and the downloader.
4. **`:core-model` stays pure Kotlin.** No Android, no I/O, no native. Adding an Android dependency
   there breaks `:core-llm-api` and `:server`. **Not enforced by `checkModuleGraph`** — that task
   only checks project-to-project edges, and this is a constraint on external dependencies. What
   actually enforces it today is the convention plugin the module applies
   (`ollamamobile.jvm.library`, which has no Android plugin to add an `android {}` block with), plus
   review. Do not assume a green build has checked it.

The full map is in `docs/architecture/module-map.md`. If you believe a rule is wrong, change the
rule in `ModuleGraphConventionPlugin` in the same PR and say why in the description — do not work
around it.

## How to: add a model to the catalogue

1. **Confirm the arithmetic first.** `Quantization` in `:core-model` carries the effective
   bits-per-weight for each GGUF format, including the per-block scale and min metadata k-quants
   carry (`Q4_K_M` is 4.85 bpw, not 4.0). Use `estimateWeightBytes(parameterCount)` for the size
   estimate rather than typing a number in; if the value you get differs from the published file
   size by more than a few percent, something is wrong with your parameter count.
2. **Add the catalogue entry** alongside the existing ones (the catalogue is a static list in the
   data layer — follow the type, not this path, if it has moved). Fill in the display name, the
   parameter count, the quantisation as a `Quantization` value, the download URL, the expected size
   and the checksum. Get the checksum from the actual file you downloaded, not from a README.
3. **Do not claim acceleration you cannot support.** `Quantization.kleidiAiAccelerated` is derived,
   not declared, and it is `false` for every k-quant. Do not add a "fast on ARM" flag or copy a
   speed claim from a model card.
4. **Say what the model actually needs.** The RAM guidance in the catalogue is weights plus KV cache
   plus headroom, and it is an estimate. Do not add a tokens-per-second figure — this project
   publishes none, because there is no arm64 test hardware.
5. **Test it** in `:core-model` (pure JVM, JUnit 5): the entry parses, the size estimate is within
   tolerance of the real file size, and the quantisation round-trips through
   `Quantization.fromFileName` for the filename you specified.

## How to: add a remote server backend

Adding support for a server that is not stock Ollama — a compatible gateway, a different API
dialect:

1. **Implement the client interface in `:core-remote`.** Do not add a parallel abstraction beside
   it; if the existing interface cannot express your backend, change the interface in the same PR
   and update the other implementation.
2. **Use the shared `OkHttpClient`.** There is exactly one, provided by `:core-common`, and it
   carries the network policy — offline mode, LAN-only enforcement, timeouts, logging. Constructing
   your own bypasses `LanOnlyGuard`, and a Konsist architecture test in `:core-common` will fail the
   build if you do. Configure per-call behaviour with an interceptor or a request tag instead.
3. **Add DTOs as `@Serializable` types with explicit `@SerialName`s**, kept separate from the domain
   models in `:core-model` and mapped at the boundary. Wire formats change on someone else's
   schedule; domain types should not. Make the JSON tolerant of unknown fields — a server that adds
   a field must not break the client.
4. **Add MockWebServer fixtures** covering: a normal response, a streamed response including a
   partial final chunk, an HTTP error with a body, an HTTP error with an empty body, malformed JSON,
   and a mid-stream disconnect. Assert on the request the client *sent* (path, headers, body) as
   well as on what it parsed. Backend tests live in `:core-remote` and run on JUnit 4 + Robolectric.
5. **Register it** so the server-configuration UI can offer it, and make sure an unreachable server
   of the new type produces the same user-visible failure as an unreachable Ollama server.

## How to: add an inference backend

Adding an execution engine beside llama.cpp — a different runtime, a different accelerator:

1. **Implement `LlamaEngine` from `:core-llm-api`.** That interface is the whole contract: load,
   generate as a `Flow` of `GenerationEvent`, cancel, release. It is pure JVM on purpose. Do not
   widen it with backend-specific parameters; if your engine needs configuration the interface
   cannot express, add it to the request type so every engine sees the same shape.
2. **Keep the implementation in a module that only `:app` binds.** `:core-llm` is the precedent:
   it is the only module allowed to see native code, and `checkModuleGraph` will fail any other
   module that depends on it. A new native backend either lives in `:core-llm` or gets the same
   treatment in the graph rules.
3. **Bind it in Hilt at the app layer.** The engine is selected where the graph is assembled, not by
   whichever module happens to need it. Consumers inject `LlamaEngine` or `InferenceGateway` and
   must not know which implementation they got.
4. **Keep it behind `-Pollama.nativeSource`.** With `none` the build must still succeed, produce no
   `.so`, set `BuildConfig.NATIVE_ENABLED=false` and bind `StubLlamaEngine`. This is not optional:
   it is what lets contributors without an NDK build the app, and what lets CI stay fast. Follow
   `AndroidNativeConventionPlugin` — the mode comes from the Gradle property alone and never from
   `file(...).exists()`, because a filesystem probe at configuration time makes the configuration
   cache go stale as soon as a submodule is initialised.
5. **Add a CI job that is gated.** Native jobs must be `workflow_dispatch`-only or guarded by a
   check that the submodule exists, so the default CI run stays green on a checkout that has no
   `third_party/llama.cpp`.
6. **Test the Kotlin side against a fake**, on the JVM. Anything only provable on hardware goes in
   `docs/verification-status.md` as unverified — do not describe it as working, and do not attach a
   performance number to it.

## No telemetry

Pull requests must not add telemetry, analytics or crash-reporting SaaS. No Firebase Analytics or
Crashlytics, no Sentry, no Bugsnag, no App Center, no ad or attribution SDK, no "anonymous usage
statistics" toggle, no remote logging endpoint, no unique install identifier, no phone-home on first
run. This includes transitive additions: if a dependency you are adding pulls in an analytics
client, say so in the PR description, because it will be caught in review either way.

Local diagnostics are fine and welcome — crash capture to app storage the user can read and delete,
structured logs, the in-app API inspector. The distinction is simple: data may not leave the device
except to a server the user configured.
