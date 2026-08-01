# Memory budgeting

The most common failure mode for an on-device LLM is not slowness — it is the
app being killed by the low-memory killer partway through loading, which the
user experiences as the app "crashing" with no explanation. Refusing to start a
load that cannot succeed is a better product than attempting it.

This page describes the estimate OllamaMobile computes before loading a model,
and the verdict it turns that estimate into.

!!! warning "Status"
    The estimator is specified here but not yet implemented. `Quantization` in
    `:core-model` exists and provides `estimateWeightBytes()`; the KV cache,
    compute buffer and `MemAvailable` pieces do not exist in code yet.

## The estimate

```
total ≈ weights + kv_cache + compute_buffer + headroom
```

Every term matters, and skipping any of them produces an estimate that is wrong
in the direction that gets you killed.

### Weights

The model file size, essentially. If the GGUF is on disk, use the actual file
length — it is exact and free. If you are sizing a model before downloading it,
use `Quantization.estimateWeightBytes(parameterCount)`, which multiplies by the
effective bits-per-weight from [the quantisation table](quantization.md).

Weights are `mmap`ed, so in principle they are file-backed pages the kernel can
evict under pressure rather than anonymous memory that must be swapped or
killed. In practice you cannot budget as though they are free: every weight is
touched on every token, so the whole file becomes hot, and eviction just means
re-reading from flash on the next token. Budget the full size. See the `mmap`
discussion in [tuning](tuning.md).

### KV cache

This is the term people forget, and it is the one that scales with something the
user controls.

```
kv_bytes = 2 × n_layer × n_ctx × n_embd_kv × bytes_per_element
```

* The leading **2** is K and V.
* **`n_layer`** and **`n_ctx`** are what they look like — layer count and the
  context length you configure, not the model's maximum.
* **`n_embd_kv`** is the per-token key (or value) width, and it is **not**
  `n_embd`. With grouped-query attention it is
  `n_embd_head_k × n_head_kv`, where `n_head_kv` is usually much smaller than
  `n_head`. Using `n_embd` instead overestimates by the GQA ratio, which on
  modern models is commonly 4× to 8×. Both values are in the GGUF metadata
  (`*.attention.head_count_kv`, `*.attention.key_length`,
  `*.attention.value_length`); read them rather than guessing. Models with
  differing K and V head dimensions exist, so compute the K and V halves
  separately rather than assuming symmetry.
* **`bytes_per_element`** depends on KV cache quantisation:

    | Cache type | Bytes per element |
    | --- | --- |
    | `F32` | 4 |
    | `F16` / `BF16` | 2 |
    | `Q8_0` | 34 bytes per 32 elements ≈ 1.0625 |
    | `Q5_1` | 24 bytes per 32 ≈ 0.75 |
    | `Q4_0` | 18 bytes per 32 ≈ 0.5625 |

    Quantising the cache is the largest single lever available for long
    contexts; it is discussed as a tuning decision in [tuning](tuning.md).

A worked shape, to show why it matters rather than to claim a number: a 32-layer
model with 8 KV heads of width 128 has `n_embd_kv = 1024`, so at F16 the cache
costs `2 × 32 × 1024 × 2 = 131,072` bytes **per token of context** — 128 KiB per
token, or about 512 MiB at 4096 tokens and 2 GiB at 16384. The same model at
`Q8_0` cache costs roughly half that. On a phone, a long context can easily cost
more than the weights.

!!! note "Some architectures do not follow this formula"
    Models with sliding-window or hybrid attention (only some layers holding a
    full-length cache), or with MLA-style compressed caches, allocate
    differently. Where the metadata indicates such an architecture the estimate
    should be treated as an upper bound, and the honest thing is to say so
    rather than to invent a per-architecture correction.

### Compute buffer

Scratch space for activations during a forward pass. It scales with the
micro-batch size (`n_ubatch`), the hidden size and the number of layers held
simultaneously, and — often dominant — with the logits buffer, which is
`n_vocab × n_batch × 4` bytes. Vocabularies of 128k–256k tokens are now normal,
so a 256k-vocab model with a batch of 512 wants 512 MiB just for logits, which
is a strong argument for a modest `n_batch` on mobile.

llama.cpp reports the actual sizes at load time. Before loading, treat this as a
bounded but non-trivial allocation: it is smaller than weights and usually
smaller than a long-context KV cache, but it is not negligible and it must not
be omitted.

### Headroom

The app is more than a model. The Compose UI, the Skia render pipeline, the
Room database, the OkHttp connection pool, bitmap caches and the JVM heap itself
all need room, and Android will kill a foreground process that pushes the system
into pressure. A fixed reserve plus a proportional component is the right shape;
the reserve should be large enough that the process survives a configuration
change and a camera/keyboard app being brought up alongside it.

## Budget against `MemAvailable`, not total RAM

`ActivityManager.MemoryInfo.totalMem` tells you what the device shipped with. It
is not what you can have. Between the kernel, the vendor HAL stack, the system
server, the launcher, and whatever else the user has open, the fraction actually
obtainable varies enormously between devices and between moments on the same
device.

The right number is `MemAvailable` from `/proc/meminfo`: the kernel's own
estimate of how much memory is obtainable without pushing the system into swap
or reclaim. It already accounts for reclaimable page cache and slab.

```
MemAvailable:    3418264 kB
```

Two sanity checks belong alongside it:

* **`ActivityManager.getMemoryClass()` / `getLargeMemoryClass()`** bound the Java
  heap, not native allocations. A model loaded through JNI is not on the Java
  heap, so these do not cap it — but they do tell you how much room the UI has,
  which feeds the headroom term.
* **`ActivityManager.MemoryInfo.lowMemory` and `threshold`** tell you whether the
  system is *already* under pressure. Starting a multi-gigabyte load in that
  state is asking for it.

The device's own low-memory posture matters too. `ActivityManager.isLowRamDevice()`
being true is a strong signal to steer the user towards remote inference
entirely; see [routing](../remote/routing.md).

## The verdict

The estimate is reduced to one of three outcomes, and the UI shows the
arithmetic rather than just the answer — a user who can see "weights 2.1 GB + KV
cache 0.5 GB at 8K context" understands immediately that shortening the context
is the lever.

**Fits** — the estimate is comfortably below `MemAvailable` after headroom. Load
without comment.

**Tight** — the estimate is below `MemAvailable` but within the margin where a
background app waking up could tip it over. Load, but warn, and say what to
change: reduce context length, quantise the KV cache, or pick a smaller
quantisation. This is the state where a user gets a working model and an
occasional kill, so the warning has to be specific enough to act on.

**Refuse** — the estimate exceeds what is available. Do not attempt the load.
Explain the shortfall in the same terms as the estimate and offer the concrete
alternatives: a smaller quant of the same model, a smaller model, a shorter
context, or the remote server they already have configured. A refusal with a
number and a fix is a good experience; an OOM kill is not.

The thresholds separating these are policy, and they should be conservative on
`isLowRamDevice()` hardware. They are not yet chosen, because choosing them
sensibly needs measurement the project cannot currently do.

## RAG keeps two models resident

This is easy to miss when sizing. Retrieval-augmented generation needs an
**embedding model loaded at the same time as the chat model** — the query has to
be embedded with the same model that produced the index, at query time, while
the chat model is still holding its weights and KV cache.

So the budget for a RAG-enabled session is:

```
chat_weights + chat_kv + chat_compute
  + embed_weights + embed_kv + embed_compute
  + headroom
```

Embedding models are small relative to chat models — typically a few hundred
megabytes at `F16` or `Q8_0` — and they run with a very short context, so their
KV term is minor. But "small" is not "free" on a 6 GB device, and the estimate
must include them or RAG will appear to work right up until the moment a long
chat pushes the process over.

Two mitigations are available and both have costs. Unloading the embedding model
between queries frees its weights but pays a reload on every retrieval, which is
the slowest part of an otherwise fast operation. Choosing a smaller embedding
model reduces the resident cost permanently but changes the index — embeddings
from different models are not comparable, so switching models means reindexing
every document. See [RAG indexing](../rag/indexing.md).

## Related

* [Quantisation](quantization.md) — the bits-per-weight table behind the
  weights term.
* [Tuning](tuning.md) — context length, KV quantisation and `mmap`, i.e. the
  levers a user actually has.
* [RAG overview](../rag/overview.md) — why two models are resident.
