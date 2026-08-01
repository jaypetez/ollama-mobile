# Metrics

What the harness captures, why each one is there, and where the naive way of
measuring it gives the wrong answer.

!!! warning "No values appear on this page"
    The project has no arm64 device and the harness is not implemented yet.
    This describes what is measured, not what was measured. Any number you see
    in this repository's documentation that looks like a benchmark result is a
    bug — report it.

## Throughput

**Prompt processing rate (tokens/sec).** How fast a prompt is ingested. Batched,
compute-bound, scales with cores and with `n_batch`. This is what dominates
time-to-first-token on a long prompt, so it is the number that matters for RAG,
where the retrieved context can be thousands of tokens.

**Token generation rate (tokens/sec).** How fast tokens come out after the prompt
is processed. Batch size 1, memory-bandwidth-bound, saturates at a modest thread
count. This is the number users perceive as "speed".

Report them separately, always. A single combined figure conflates two regimes
that respond oppositely to nearly every tuning change, and a change that improves
the combined number can make the experience worse.

When the source is a remote server, both rates come from the response's own
statistics rather than from client-side timing: `eval_count / eval_duration`
and `prompt_eval_count / prompt_eval_duration`, with durations in **int64
nanoseconds**, so the conversion is `× 1e9`. Both fields are `omitempty` and can
be absent. See [the Ollama API page](../remote/ollama-api.md).

## Load time, cold and warm

**Cold** — first load after install or after the page cache has been dropped.
The model is faulted in from flash. This is what a user experiences the first
time they select a model, and it is dominated by storage throughput, which is why
models live on internal storage rather than FUSE-backed external storage; see
[storage](../models/storage.md).

**Warm** — a subsequent load with the file still in page cache. Much faster, and
also real: it is what happens when a user switches back to a model they used
earlier in the session.

Measuring "cold" that is actually warm is the easiest mistake to make here and
produces a number wrong by a large factor. Drop caches, reboot, or
uninstall-reinstall between cold measurements, and record which method was used.

Load time also includes ggml's weight repacking, which is not free and scales
with model size — see [quantisation](../local-inference/quantization.md).

## Time to first token

From request submission to the first token reaching the UI. This is the latency
users actually feel, and it is a composite: model load (if not resident), prompt
tokenisation, chat template rendering, prompt processing, and the first decode
step.

Measure it end-to-end at the application boundary, not inside the engine.
Engine-internal timing omits the JNI transition, the coroutine dispatch and the
Compose recomposition that turns a token into a visible character — and those are
part of what the user waits for.

Report it separately for a resident model and a cold one. They differ by the
whole load time and averaging them produces a figure that describes neither.

## Peak memory: `VmHWM`, not the native heap counter

!!! danger "`Debug.getNativeHeapAllocatedSize()` cannot see mmapped weights"
    The obvious way to measure a native memory footprint on Android is
    `Debug.getNativeHeapAllocatedSize()` or `android.os.Debug.MemoryInfo`. For an
    LLM this is **wrong, by gigabytes**.

    llama.cpp `mmap`s the model file. Mapped pages are not malloc'd; they never
    pass through the allocator, so the native heap counter does not count them.
    A 4 GB model can be fully resident while the native heap counter reports a
    few tens of megabytes. The number is not slightly low — it is unrelated to
    the quantity you care about.

The right measurement is the kernel's own high-water mark for resident set size:

```
$ cat /proc/self/status
...
VmHWM:      3921488 kB      ← peak resident set size
VmRSS:      3874112 kB      ← current resident set size
...
```

`VmHWM` is the peak RSS since the process started, maintained by the kernel,
covering anonymous memory *and* file-backed pages *and* mapped regions. It is
exactly "how much physical memory did this process ever hold", which is what
determines whether the low-memory killer takes an interest.

Read it from inside the app (`/proc/self/status`) or from the host
(`adb shell cat /proc/<pid>/status`). Sample `VmRSS` periodically during a run to
get the shape over time, and read `VmHWM` at the end for the peak.

Two caveats. `VmHWM` is a high-water mark and never decreases, so reset it
between configurations by restarting the process, or record it per-process-launch
rather than per-iteration. And RSS includes shared pages counted in full, so
summing RSS across processes double-counts — irrelevant for a single-process
measurement, but worth knowing.

Record alongside it:

* `MemAvailable` from `/proc/meminfo` at the start of the run, which is what the
  [memory verdict](../local-inference/memory.md) budgets against.
* The model file size and the computed KV cache size, so measured peak RSS can be
  compared against the estimate. **This comparison is the whole point** — it is
  how the estimator gets validated, and a systematic gap between predicted and
  measured is a bug in the estimator that would otherwise go unnoticed until a
  user's app was killed.

## Energy per token

The metric that matters on a battery and is almost always omitted.

`androidx.benchmark`'s `PowerMetric` reports energy from on-device power rails on
supported hardware (API 31+, and only on devices exposing the rails — Pixels do,
many others do not). Where it is available, capture energy over the measured
window and divide by tokens produced to get joules or milliwatt-hours per token.

Where it is not available, `BatteryManager`'s
`BATTERY_PROPERTY_CURRENT_NOW` and `BATTERY_PROPERTY_CHARGE_COUNTER` give a
coarser picture over a longer window. Coarse and honest beats absent.

Two rules: **measure on battery, not while charging** — a charging device's power
draw tells you about the charger — and record it per configuration, because the
configuration with the highest throughput is frequently not the one with the best
energy per token. Running every core flat out wins on tokens per second and loses
on tokens per joule, and on a phone the second number is often the one that
should decide.

**An emulator reports nothing meaningful here.** There are no power rails. The
field is empty in nightly output and that is correct.

## Thermal state per repetition

Recorded at the start of **every** repetition, not once per run:
`PowerManager.getCurrentThermalStatus()`, one of `NONE`, `LIGHT`, `MODERATE`,
`SEVERE`, `CRITICAL`, `EMERGENCY`, `SHUTDOWN`.

A throughput number without its thermal state is not comparable to anything. A
sustained benchmark on a phone will pass through several of these states, and the
same configuration measured at `NONE` and at `SEVERE` produces materially
different results. Recording it per repetition turns "the numbers got worse
halfway through" from a mystery into a fact.

It also enables the derived metric that actually characterises a phone:
**sustained versus burst throughput**. The first repetitions measure what the SoC
can do; the later ones measure what it can do continuously. The gap between them
is a device property worth reporting on its own.

An emulator does not throttle. Its thermal status stays `NONE` throughout, which
is precisely why emulator results say nothing about sustained performance.

## The quantisation comparison matrix

A sweep over quantisations of the same model on the same device, so the
size/speed/quality trade can be seen rather than argued about. Per cell:

| Column | Source |
| --- | --- |
| Quantisation | The `Quantization` enum value |
| Bits per weight | `Quantization.bitsPerWeight` |
| KleidiAI accelerated | `Quantization.kleidiAiAccelerated` |
| File size | On disk |
| Peak RSS | `VmHWM` |
| Prompt processing rate | Measured |
| Token generation rate | Measured |
| Cold / warm load time | Measured |
| Energy per token | Where power rails exist |
| Thermal states observed | Per repetition |

The `kleidiAiAccelerated` column is there deliberately. It is the only way to
test the widespread claim that `Q4_0` is faster on ARM because of KleidiAI while
`Q4_K_M` is not — a claim that is *structurally* true (KleidiAI covers `Q4_0`,
`Q8_0`, `F16`, `F32` and no k-quant) but whose *magnitude* is unknown, because
k-quants get their own speed-up from ggml's runtime repacking. See
[quantisation](../local-inference/quantization.md). Nobody in this project has
measured which wins on real hardware, and the matrix is how that would be
settled.

Quality is not in the matrix. It is not measurable by a throughput harness, and a
perplexity number computed on a phone against an arbitrary text sample would be
worse than no number. Treat the matrix as answering "what does this cost", and
consult the quantisation page for "what does this cost you in quality".

## Output format

One JSON document per run, machine-readable, containing the full environment —
device model, SoC, ABI, Android version, app version and build type, the
`ollama.nativeSource` mode, the llama.cpp submodule SHA, the selected ggml CPU
variant, and `MemAvailable` at start — followed by an array of results, each
carrying its complete configuration and its measurements including the
per-repetition thermal states.

Self-contained, so a result file is interpretable without the run that produced
it, and stable in shape, because [the nightly job](nightly.md) diffs consecutive
files. Adding a field is fine; renaming one breaks the comparison silently.

Nothing in the output may contain a server URL with an embedded credential. A
benchmark JSON committed by an automated job is a real way to leak a token; see
[auth and TLS](../remote/auth-tls.md).

## Related

* [How to run](how-to-run.md) — the harnesses and the methodology.
* [Nightly CI](nightly.md) — consuming the JSON.
* [Memory](../local-inference/memory.md) — the estimate that `VmHWM` validates.
