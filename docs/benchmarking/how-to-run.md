# Running benchmarks

!!! danger "There are no published numbers, and there will not be until there is a device"
    OllamaMobile has **no physical arm64 test device** and none is planned right
    now. Everything runs on an x86_64 emulator. An emulator does not have ARM
    dotprod or i8mm, does not thermally throttle like a phone, does not have a
    phone's memory bandwidth, and executes arm64 code — if at all — through
    translation.

    Emulator results are a **relative regression signal for the same harness on
    the same runner**. They are not device performance, they cannot be compared
    to numbers from anyone's phone, and they must never be quoted as though they
    were. This applies to
    [the nightly job](nightly.md) especially.

    If you have a real arm64 device, the harness described here is exactly how
    to produce numbers that mean something — and those numbers belong in your
    own report, not in this repository's documentation as a general claim.

## The two harnesses

**Macrobenchmark** (`:benchmark`) measures the app as a user experiences it:
startup, frame timing, and the end-to-end cost of loading a model and producing a
first token. It is a `com.android.test` module targeting `:app`'s `benchmark`
build type — release-like, minified, but signed with the debug key so it is
installable without a release keystore, and `isDebuggable = false` because a
debuggable build's numbers are meaningless.

`beforeVariants` disables every variant except `benchmark`, so the module
contributes nothing to ordinary builds. Every library module gains a matching
`benchmark` build type via `configureBenchmarkBuildType` in the convention
plugins — without it, dependency resolution fails with "no matching variant
found".

**The inference harness** measures llama.cpp itself: prompt processing rate,
token generation rate, load time, peak RSS, and the quantisation comparison
matrix. It needs a loaded model, so it only produces meaningful output with
`-Pollama.nativeSource=build` or `prebuilt`. Under the default
`-Pollama.nativeSource=none` the stub engine is bound and there is nothing to
measure.

!!! warning "Status"
    `:benchmark` is registered and configured. The macrobenchmark test classes
    and the inference harness are **not written yet**. The connected-test task
    AGP generates for the `benchmark` variant exists by convention but has never
    been run, and it is not on the project's list of verified-green tasks —
    which today is `assembleDebug`, `test`, `spotlessCheck`, `lintDebug`,
    `checkModuleGraph`, `detekt`, `koverXmlReport`, `assembleRelease` and
    `bundleRelease`. Do not assume any other task name works until you have run
    it.

## Prerequisites

* An emulator or device running API 29 or above; API 31+ for power and energy
  metrics.
* For the inference harness, a model on the device and a native build.
* `adb` on `PATH`.

An x86_64 emulator image is what CI uses and what a hosted runner can run. This
is also the only reason `x86_64` is in the debug ABI set at all — release builds
are `arm64-v8a` only.

## Preparing the device

Variance is the enemy. A benchmark run on a phone doing background work measures
the background work.

```bash
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

On a real device, additionally: enable airplane mode (with Wi-Fi off), disable
adaptive brightness, close other apps, keep the screen on, and **unplug it**.
Charging generates heat, which makes the thermal picture worse rather than
better, and a benchmark that starts hot measures a different device than one that
starts cool.

Let the device idle for a minute before the first run and between quantisation
sweeps, and record the thermal status at the start of each repetition — see
[metrics](metrics.md). A run whose thermal state changed partway through is not
comparable to one that stayed cool, and the only way to know is to have recorded
it.

## Running the macrobenchmark

Build and install the `benchmark` variant of `:app`, then run the connected test
for the `:benchmark` module. AGP generates the task from the variant name; check
what it is called on your setup with:

```bash
./gradlew :benchmark:tasks --all
```

rather than guessing, because the exact name depends on the variant
configuration and the project has not verified it.

Macrobenchmark writes JSON results under the `:benchmark` module's build outputs
and also pulls them to the host. Those files are the artefact the nightly job
consumes; see [nightly](nightly.md).

## Running the inference harness

The harness needs a native build:

```bash
./gradlew :app:assembleBenchmark -Pollama.nativeSource=build -Pollama.requireNative=true
```

`-Pollama.requireNative=true` is not optional here. Without it, a machine with no
NDK silently produces a `none` build, the harness runs against `StubLlamaEngine`,
and you get a full set of plausible-looking numbers that measure nothing at all.
The flag turns that into a build failure. See
[the native build page](../local-inference/native-build.md).

Then run the harness as an instrumentation test, parameterised over the models
and configurations you want. What it must control, per configuration:

* Model file and quantisation
* Context length, `n_batch`, `n_ubatch`
* Thread count
* KV cache type for K and V
* Prompt length and number of tokens to generate
* Warm-up iterations and measured repetitions

Every one of these goes into the output alongside the results. A throughput
number without its configuration is not comparable to anything, and six months
later nobody will remember what the defaults were.

## Methodology

**Separate cold from warm.** The first load faults the model in from flash; the
second is served largely from page cache. Both are real and they measure
different things. Drop caches between cold runs, or reboot, or at minimum
uninstall-reinstall — a "cold" run that is actually warm is the most common way a
load-time number ends up wrong by an order of magnitude.

**Warm up, then measure.** Discard the first iterations. JIT, page faults and
ggml's weight repacking all make early iterations unrepresentative.

**Repeat, and report the distribution.** Median plus the spread, not a single
number and not a mean. On a thermally constrained device the distribution is the
interesting part, and a mean hides a run that fell off a cliff at repetition
four.

**Change one thing at a time.** A sweep that varies quantisation and thread count
together tells you nothing about either.

**Record everything.** Device model, SoC, ABI, Android version, app version and
build type, `nativeSource` mode, the llama.cpp submodule SHA, the selected ggml
CPU variant, and the thermal status per repetition. The submodule SHA in
particular: a change in llama.cpp is the most likely explanation for a
regression, and without the SHA you cannot tell.

## Interpreting results

Prompt processing and token generation are **different regimes**. Prompt
processing is compute-bound and scales with cores and with batch size; generation
at batch 1 is memory-bandwidth-bound and saturates early. A change that helps one
frequently hurts the other, and reporting a single "tokens per second" conflates
them. See [tuning](../local-inference/tuning.md).

Compare like with like: same device, same model, same quantisation, same context,
same thread count, same thermal starting point. Across devices, the only honest
comparison is of *ratios* within a device, never absolute throughput.

And repeat the rule at the top of this page: an emulator number is a regression
signal for that emulator. It is not a phone.

## Related

* [Metrics](metrics.md) — what is captured and why.
* [Nightly CI](nightly.md) — how the JSON is consumed, and what it does and does
  not prove.
* [Tuning](../local-inference/tuning.md) — the knobs a sweep would vary.
