# Nightly benchmark CI

!!! danger "Nightly runs on an EMULATOR. It is a relative regression signal only."
    Every number the nightly job produces comes from an **x86_64 emulator on a
    hosted GitHub Actions runner**. It is not a phone.

    An emulator has no ARM dotprod and no i8mm, so the
    [CPU variant dispatch](../local-inference/backends.md) that the shipping
    arm64 build depends on selects nothing meaningful. It does not thermally
    throttle, so [sustained-throughput](metrics.md) figures are absent by
    construction. It has no power rails, so energy per token is empty. Its
    memory bandwidth and storage characteristics are the host's, not a phone's.
    The runner it executes on is shared, virtualised hardware whose neighbours
    change between runs.

    What nightly is good for: **detecting that something changed.** A 30%
    regression in prompt processing between two consecutive nights, on the same
    harness and the same runner class, is a real signal worth investigating.

    What nightly is not good for: any statement about how fast OllamaMobile is.
    Do not put nightly numbers in a README, a release note, an issue comment as
    evidence of performance, or a comparison against another project. They are
    not that kind of number, and repeating them as though they were is how a
    project acquires a reputation for dishonest benchmarks.

    There is no physical arm64 device in this project and none is planned. That
    is the constraint that makes this caveat permanent rather than temporary.

!!! warning "Status"
    **The workflow exists and is scheduled. The harness it would run does not,
    so every nightly run is a clean skip.**

    `.github/workflows/nightly-benchmark.yml` is real, scheduled and running. It
    fires at **04:17 UTC every day** (off the hour, to miss the top-of-hour
    scheduling backlog) and on `workflow_dispatch`.

    It has **never measured anything**, and cannot yet. Its first job, `guard`,
    checks whether `benchmark/src` exists. It does not — `:benchmark` is a
    scaffolded module with a build file and no sources — so `guard` emits a
    notice, sets `ready=false`, and the `benchmark` job that `needs:` it is
    skipped. Every nightly run to date is a green skip.

    That is deliberate. Exiting cleanly rather than failing means a red nightly
    always means a real regression, instead of meaning "still not built yet",
    which is the state in which people stop reading nightly results.

    The sections below describe **the workflow as it actually is**. Where the
    design differs from what is implemented, it says so.

## Why have it at all

Because the alternative is finding out about a regression from a user, and
because a useful subset of regressions does not require device fidelity to
detect. With the job running `-Pollama.nativeSource=none`, that subset is
app-level:

* A startup regression — cold start, application-class work, dependency-graph
  construction.
* A memory regression: peak `VmHWM` climbing after a change to context handling,
  caching or the storage layer.
* A load-time regression from a change to file handling or `mmap` behaviour.
* A scrolling or frame-timing regression in the chat UI.
* A regression in the remote client path measured against a local fake server.
* The harness itself breaking, which is worth knowing before you need it.

None of these need a real phone. They need a stable environment and a baseline,
which is what a nightly emulator job provides.

Two things nightly explicitly **cannot** catch, because it compiles no native
code: a llama.cpp bump that halves inference throughput, and a build change that
silently drops a CMake flag such as `GGML_CPU_ALL_VARIANTS`. Those are real
risks and they need a different answer — reading the upstream diff on the bump
pull request, and eventually hardware. Do not let the existence of a nightly job
create the impression that they are covered.

## Gating

The workflow is guarded rather than conditional on anything native, because at
0.1.0 there are two independent reasons it cannot do real work: `:benchmark` has
no sources, and `third_party/llama.cpp` is not vendored.

The `guard` job tests only the first of those — `[ -d benchmark/src ]` — because
it is the one that gates every path. Without a harness there is nothing to run
whether or not native code exists.

**The workflow does not build native code, and does not intend to.** It runs
with `-Pollama.nativeSource=none`, passed explicitly on the Gradle command line.
Two reasons, and the second is the important one:

* llama.cpp is not vendored, so `build` could not succeed anyway.
* Even once it is, an **x86_64 emulator build tells you nothing about the
  arm64-v8a release ABI**. Compiling llama.cpp for x86_64 to measure it on a
  virtualised x86_64 host would produce numbers with no relationship to the
  thing that ships. What this job can legitimately measure is app-level work —
  startup, scrolling, storage, the remote client path — not inference.

This is a change of intent from an earlier draft of this page, which specified
`-Pollama.nativeSource=build -Pollama.requireNative=true`. That would have been
right for a job measuring inference on representative hardware. It is wrong for
an emulator job, and `requireNative` in particular would only convert "we cannot
build native here" into a nightly failure that tells nobody anything new.

## Shape of the job — as implemented

Two jobs. Concurrency group `nightly-benchmark` with `cancel-in-progress: false`,
so a manual dispatch does not kill a scheduled run mid-flight.

**`guard`** — checks out, tests for `benchmark/src`, and outputs `ready`.
Currently `false`.

**`benchmark`** — `needs: guard`, `if: needs.guard.outputs.ready == 'true'`,
90-minute timeout. Currently skipped in full. Its steps:

1. Enable KVM via a udev rule. Nested virtualisation is off by default on hosted
   runners and the emulator is unusably slow without it.
2. Check out; set up JDK 21 (temurin) and Gradle.
3. Restore the benchmark history from the Actions cache — key
   `benchmark-history-<run_id>`, with `restore-keys: benchmark-history-` pulling
   the previous night forward.
4. Run `./gradlew :benchmark:connectedBenchmarkAndroidTest
   -Pollama.nativeSource=none` inside `reactivecircus/android-emulator-runner`:
   API 34, `google_apis`, `x86_64`, `pixel_6` profile, headless with
   `swiftshader_indirect`, animations disabled.
5. Upload the raw result JSON and `benchmark/build/outputs/` as an artefact,
   `if: always()`, retained 30 days.
6. Compare against history with `benchmark-action/github-action-benchmark`.

The task name in step 4 is the connected-test task AGP generates for the
`benchmark` build type. It has never executed, so treat the exact invocation as
unverified until the harness lands.

!!! warning "The result JSON is a contract nothing currently satisfies"
    The workflow expects a single file at
    `benchmark/build/reports/benchmark/benchmark-results.json`, in
    github-action-benchmark's `customSmallerIsBetter` schema —
    `[{ name, unit, value }, ...]`. **No Gradle task emits it.** Writing both the
    benchmark and the converter that turns macrobenchmark's output into that
    shape is part of building the harness, not something the workflow can do for
    you. See [how to run](how-to-run.md).

    Note also that this schema is flatter than the self-describing document the
    [metrics](metrics.md) page specifies. Reconciling the two — probably by
    emitting the rich document as the artefact and deriving the flat one for the
    comparison step — is an open question for whoever writes the harness.

### Regression tracking, and what it does not do

`github-action-benchmark` runs with `external-data-json-path` pointing at
`.benchmark-history/benchmark-data.json` and `auto-push: false`. The action's
usual mode pushes history to a `gh-pages` branch; that would be dead weight
here, because this repository's Pages site deploys from an Actions artefact (see
`docs.yml`), so nothing written to `gh-pages` would ever be served. History
therefore lives in the Actions cache instead.

`alert-threshold` is **150%** — deliberately loose, because emulator noise on
shared runners routinely swings 20-30% and a threshold tight enough to catch a
small regression would fire most nights and be ignored within a week.

`fail-on-alert` is **false**: a breach is reported in the job summary and does
not fail the run. Until several weeks of nightly data exist there is no measured
noise floor, so any threshold is guesswork, and a guessed threshold that can go
red is a guessed threshold people will learn to ignore. Revisit it when there is
a distribution to look at.

**The workflow does not open an issue on a breach, and cannot.** Its
`permissions:` block is `contents: read` — no `issues: write` — so there is no
token scope to create or update one with. Reporting is the job summary
(`summary-always: true`) and the uploaded artefact, nothing else. If issue
filing is wanted later it is a deliberate change: add the scope, and accept that
a noisy detector will then be filing noise into the issue tracker.

## Consuming the JSON

The harness emits one self-contained JSON document per run
([metrics](metrics.md)) containing the environment and an array of results, each
with its full configuration and its measurements.

**Keyed comparison.** A result is identified by the tuple of model,
quantisation, context length, batch sizes, thread count and KV cache types — not
by array position. Configurations get added and reordered; positional comparison
produces spurious regressions.

**Compare against a rolling baseline, not the previous night.** Run-to-run
variance on a shared virtualised runner is substantial. A median over the last
several nights is a far more stable reference than a single prior run, and it
stops one noisy night from either firing a false alarm or, worse, silently
becoming the new baseline.

**Thresholds must exceed the noise floor.** They cannot be chosen from theory —
they need a couple of weeks of nightly data to establish what the runner's own
variance looks like. That is exactly why the implemented threshold is a loose
150% and `fail-on-alert` is `false`: the job reports trends and does not fail.
A threshold set too tight produces alerts nobody reads.

**Prefer the metrics that mean something on an emulator.** Prompt processing
rate, token generation rate, peak `VmHWM`, and cold/warm load time are all
comparable run-to-run on identical infrastructure. Energy per token and thermal
behaviour are not measurable at all and must be recorded as absent rather than as
zero — a zero will eventually be averaged into something and treated as data.

**Attribute changes.** Every result should carry the llama.cpp submodule SHA and
the app version. When a regression appears, the first question is whether the
submodule moved, and the answer should be in the artefact rather than requiring a
git archaeology session.

**Publish the artefact.** The JSON is attached to the run and retained for 30
days. Trend data is worth more than any single night, and reconstructing history
from truncated logs is not possible.

## Reporting

Reporting is the workflow run's **job summary** and the uploaded artefact, and
nothing else. `summary-always: true` posts a table of key metrics with their
deltas against the stored history on every run, breach or not.

There is deliberately no notification and no issue filing. A nightly job that
notifies unconditionally trains everyone to filter it, and the workflow has
`contents: read` only — it holds no `issues: write` scope, so it could not open
or update an issue even if that were wanted. Adding that would be a deliberate
change, and it should not happen before the threshold is grounded in measured
variance; a guessing detector with write access to the issue tracker is a
guessing detector that fills the issue tracker.

**Every report must carry the emulator caveat inline**, not as a link. Someone will
copy a table out of a job summary into a discussion, and the caveat has to travel
with it. One line is enough:

> Measured on an x86_64 emulator on a hosted CI runner. Relative regression
> signal only; not representative of device performance.

## If a real device ever appears

Nothing about the harness changes — that is the point of keeping the environment
in the JSON. A self-hosted runner with a phone attached would produce results in
the same format, keyed the same way, comparable against their own baseline and
not against the emulator's.

Until that exists, the honest position is that this project has **no measured
device performance figures at all**, and any document claiming otherwise is
wrong.

## Related

* [How to run](how-to-run.md) — the harness and the methodology. The harness does
  not exist; this is the specification for it.
* [Metrics](metrics.md) — the JSON's contents.
* [Native build](../local-inference/native-build.md) — the flags and the
  `requireNative` guard. Note that the nightly job uses neither.
* [CI](../ci.md) — the other workflows, and which of them gate a merge.
