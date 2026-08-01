# Tuning local inference

The knobs that actually change behaviour on a phone, what each one trades away,
and which of them are worth exposing to a user.

!!! warning "No numbers here are measured"
    OllamaMobile has no physical arm64 test device and none is planned. Nothing
    below quotes a throughput figure, a latency, or a "best" value, because
    there is no measurement to support one. Every recommendation is either
    arithmetic or a structural argument. If you want numbers for your device,
    the harness in [benchmarking](../benchmarking/how-to-run.md) is how to get
    them.

## Thread count

The naive choice is `Runtime.availableProcessors()`. On a phone this is usually
wrong, for a specific structural reason: Android SoCs are heterogeneous.

A typical arm64 phone has some combination of a prime core, a few performance
cores and four or more efficiency cores, with substantially different clocks and
different cache hierarchies. ggml's threadpool splits work into equal shares and
then waits for all threads at each barrier. Handing an equal share to a little
core and a big core means the big core finishes and spins while the little core
grinds — the whole model runs at little-core speed, plus synchronisation
overhead, plus the power cost of having every core awake.

So the sensible default is **the number of performance-class cores**, not the
total. `:core-ml`'s CPU probe reads core topology (via `/sys/devices/system/cpu/`
frequency and capacity data) precisely so this can be computed rather than
guessed.

Other things that hold on every device:

* **Prompt processing and token generation want different thread counts.**
  Prompt processing is compute-bound matrix work and scales with cores. Token
  generation at batch size 1 is memory-bandwidth-bound and saturates early —
  adding threads past the saturation point costs power and heat for no
  throughput. llama.cpp exposes `n_threads` and `n_threads_batch` separately;
  use both.
* **More threads make thermal throttling arrive sooner.** A configuration that
  wins on the first fifty tokens and loses on the next five hundred is not a
  win.
* **The UI thread must not be starved.** Inference runs in a foreground service,
  but it competes with Compose recomposition and the render thread for the same
  cores. A visibly stuttering UI while tokens stream is a worse experience than
  slightly slower tokens.

## Batch and micro-batch

`n_batch` is how many prompt tokens are submitted for processing at once;
`n_ubatch` is how many are actually computed in one forward pass. They only
affect prompt processing — during generation you are producing one token at a
time regardless.

The trade is memory against prompt-processing throughput. Larger batches amortise
weight reads across more tokens, which is exactly the right thing when you are
memory-bandwidth-bound. But the compute buffer scales with `n_ubatch`, and the
logits buffer scales as `n_vocab × n_batch × 4` bytes — with today's 128k–256k
vocabularies that term alone can reach hundreds of megabytes at a desktop-default
batch size. See [memory](memory.md).

On mobile the defaults inherited from desktop llama.cpp are too large. Reduce
until the compute buffer is a small fraction of the budget, then stop. There is
also a latency argument for smaller batches: a long prompt processed in smaller
chunks gives finer-grained cancellation and progress reporting, which matters
when the user can close the app mid-prompt.

## Context length

`n_ctx` is the single biggest memory lever the user controls, because the KV
cache is linear in it:

```
kv_bytes = 2 × n_layer × n_ctx × n_embd_kv × bytes_per_element
```

Doubling the context doubles the cache. On a 32-layer model with 1024-wide KV
that is 128 KiB per token at F16, so going from 4K to 16K costs about 1.5 GiB —
often more than the weights.

Set it to what the conversation needs, not to the model's advertised maximum. A
model that supports 128K context does not oblige you to allocate 128K, and
allocating it costs the memory whether or not a single token is used. Growing
the context when a conversation actually gets long is better than reserving for
the worst case up front, at the cost of a reallocation and a cache rebuild.

Two secondary effects worth knowing: attention cost grows with context length so
long conversations get slower per token, and pushing a model beyond its trained
context length degrades quality regardless of what the cache can hold.

## KV cache quantisation

**This is the biggest lever for long contexts.** Storing the K and V tensors at
`Q8_0` rather than `F16` roughly halves the cache; `Q4_0` roughly quarters it.
For a 16K context on the shape above, that is the difference between about
2 GiB and about 0.5 GiB — the difference between "refuse" and "fits" in the
[memory verdict](memory.md).

The cost is quality, and the two halves are not equally sensitive. Quantising V
is generally more forgiving than quantising K; K participates in the attention
score computation where small errors are amplified by the softmax, while V is
averaged. llama.cpp lets you set the two types independently
(`cache_type_k` / `cache_type_v`), and a mixed configuration — K at `F16` or
`Q8_0`, V more aggressively quantised — is the standard compromise.

Two operational notes:

* Quantised KV cache and Flash Attention interact. Depending on the build and
  the pinned llama.cpp commit, some cache type combinations require Flash
  Attention to be enabled, or are simply not supported. Verify against the
  submodule rather than assuming.
* The choice is fixed for the lifetime of a context. Changing it means
  reallocating and reprocessing the conversation.

Expose this to users as a named trade ("longer conversations, slightly lower
quality") rather than as a raw enum, and default it based on the memory verdict
rather than shipping one value for all devices.

## mmap

llama.cpp `mmap`s the model file by default, and on Android that default is
right.

The weights become file-backed pages. They are not counted against the process's
anonymous memory, they are shared if the file is mapped twice, and the kernel can
reclaim them under pressure instead of killing the process. Load time also drops
dramatically, because loading becomes "map the file" rather than "read four
gigabytes into a buffer" — pages fault in as they are touched. This is why cold
and warm load time are measured separately in
[benchmarking metrics](../benchmarking/metrics.md): the second load is served
largely from page cache.

The caveats:

* **Reclaimed pages come back from flash.** Under memory pressure the kernel will
  evict model pages, and the next token has to fault them in again. This shows up
  as sporadic multi-hundred-millisecond stalls mid-generation, not as a uniform
  slowdown. It is the main reason the memory estimate budgets the full model size
  even though the pages are technically reclaimable.
* **Repacking undoes some of the benefit.** ggml's runtime weight repacking (see
  [quantisation](quantization.md)) rewrites tensor layouts after mapping, which
  copies those pages into anonymous memory. The more of the model is repacked,
  the less of it stays cleanly file-backed.
* **The filesystem matters.** This is the reason models live in internal
  `filesDir/models` rather than external storage: the emulated external volume is
  FUSE-backed on modern Android, and `mmap` through FUSE has materially worse
  behaviour than a direct ext4/f2fs mapping. See
  [storage](../models/storage.md).

Disabling `mmap` (loading into anonymous memory) is occasionally the right call —
it makes memory usage predictable and avoids mid-generation fault stalls — but it
makes cold start much slower and makes the process a much bigger low-memory-killer
target. It should be a diagnostic option, not a default.

`mlock` is not a realistic option on Android: the `RLIMIT_MEMLOCK` available to
an ordinary app is far too small to pin a model.

## Thermal throttling

On a phone, sustained inference is a thermal problem before it is anything else.
There is no fan and no heatsink; the SoC dumps heat into a slab of glass held
against a hand. Every core running flat out will hit the thermal governor, and
the governor's response is to drop clocks — so a benchmark's first hundred tokens
and its thousandth token are measuring different hardware.

Practical consequences:

* **Subscribe to `PowerManager.addThermalStatusListener`.** The statuses run
  from `THERMAL_STATUS_NONE` through `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`,
  `EMERGENCY`, `SHUTDOWN`. `MODERATE` is where throttling becomes noticeable;
  `SEVERE` and above means the system is actively fighting you.
* **Degrade deliberately rather than being degraded.** Reducing thread count at
  `MODERATE` and pausing at `SEVERE` gives a predictable, explainable experience.
  Letting the governor do it produces a model that gets mysteriously slower and a
  phone that gets uncomfortably hot.
* **Report thermal state with every benchmark repetition.** A throughput number
  without the thermal status it was captured at is not comparable to anything.
  The harness records it per repetition for this reason; see
  [metrics](../benchmarking/metrics.md).
* **Charging makes it worse.** Charging generates its own heat, so the "plugged
  in so it must be fine" intuition is backwards for sustained load.

## What to expose to users

Not all of the above. A settings screen with eight numeric fields is a support
burden, and most of the values interact.

The defensible split is: **context length** and **KV cache quantisation** are
user-facing, because they trade something the user understands (conversation
length) against something they also understand (memory, quality). **Thread
count** is user-facing as an advanced override with a computed default, because
device topology detection can be wrong. **Batch sizes** and **`mmap`** are
diagnostics, surfaced in a developer screen and in benchmark configuration, not
in ordinary settings.

## Related

* [Memory budgeting](memory.md) — how these settings feed the Fits/Tight/Refuse
  verdict.
* [Quantisation](quantization.md) — the weight-side counterpart of KV cache
  quantisation.
* [Backends](backends.md) — CPU variant selection and thermal policy ownership.
