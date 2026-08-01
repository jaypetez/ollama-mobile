# Quantisation

GGUF quantisation is the single biggest lever over whether a model runs on a
phone at all. This page covers the families, what the bits-per-weight numbers
actually mean, and the one piece of folklore that is wrong often enough to
deserve a banner.

!!! danger "KleidiAI accelerates Q4_0, Q8_0, F16 and F32 — and nothing else"
    The most widely recommended quantisation, **`Q4_K_M`, is not
    KleidiAI-accelerated.** Neither is any other k-quant, nor any i-quant.
    Enabling `GGML_CPU_KLEIDIAI` does not make your `Q4_K_M` model faster.

    K-quants are still fast on ARM, but the speed comes from a different
    mechanism — ggml's runtime weight repacking (`GGML_CPU_REPACK`) feeding
    dotprod/i8mm kernels — not from KleidiAI. Conflating the two leads people to
    pick `Q4_0` "for the ARM acceleration" and accept worse output quality for a
    benefit they have not measured.

    This is encoded in the type system so the UI cannot get it wrong:
    `Quantization.kleidiAiAccelerated` in `:core-model` returns `true` for
    exactly `Q4_0`, `Q8_0`, `F16`, `F32`.

## Bits per weight

`Quantization` in `:core-model` is the source of truth. The `bitsPerWeight`
figure is the **effective average over a whole model**, including the per-block
scale and min metadata that k-quants carry, and including the fact that real
GGUF files mix types — `Q4_K_M` keeps some tensors at `Q6_K`, embeddings and
output are often kept at higher precision, and so on. That is why `Q4_K_M`
averages ~4.85 bpw rather than 4.0.

Multiply parameter count by this to size a model. `estimateWeightBytes()` does
exactly that.

| Format | Family | bpw | KleidiAI | Notes |
| --- | --- | --- | --- | --- |
| `Q2_K` | k-quant | 3.35 | no | Severe quality loss. Only worth considering to squeeze a much larger model onto a small device, and often loses to a smaller model at `Q4_K_M`. |
| `Q3_K_S` | k-quant | 3.50 | no | |
| `Q3_K_M` | k-quant | 3.89 | no | Noticeable degradation, especially on instruction following and code. |
| `Q3_K_L` | k-quant | 4.27 | no | |
| `Q4_0` | legacy linear | 4.55 | **yes** | Older scheme, single scale per 32-weight block, no min. Worse quality than `Q4_K_M` at similar size. Its case is the KleidiAI path. |
| `Q4_K_S` | k-quant | 4.58 | no | |
| `Q4_K_M` | k-quant | 4.85 | no | The default recommendation almost everywhere, and a genuinely good quality/size point. |
| `Q5_0` | legacy linear | 5.54 | no | Largely superseded by `Q5_K_*`. |
| `Q5_K_S` | k-quant | 5.52 | no | |
| `Q5_K_M` | k-quant | 5.69 | no | Small further quality gain over Q4_K_M for ~17% more memory. |
| `Q6_K` | k-quant | 6.56 | no | Very close to F16 quality. |
| `Q8_0` | legacy linear | 8.50 | **yes** | Effectively lossless for chat purposes. Twice the memory of Q4_K_M. |
| `F16` | float | 16.0 | **yes** | No quantisation. Rarely sensible on a phone above ~1B parameters. |
| `F32` | float | 32.0 | **yes** | Only ever seen for very small models or individual tensors. |

The enum is ordered smallest-to-largest so `sorted()` and `compareTo` behave
sensibly in a model picker, and `Quantization.fromFileName()` parses the format
out of either a GGUF filename (`qwen3-1.7b-instruct-q4_k_m.gguf`) or an Ollama
tag (`llama3.2:3b-instruct-q8_0`), matching longest-label-first so `Q4_K_M` is
never mistaken for `Q4_0`.

## The families, briefly

**Legacy linear quants** (`Q4_0`, `Q5_0`, `Q8_0`) store a block of 32 weights as
one `fp16` scale plus 32 low-precision integers. Simple, uniform, and easy to
write fast kernels for — which is exactly why the ARM-optimised paths target
them.

**K-quants** (`Q2_K` … `Q6_K`) use a two-level block structure: a super-block of
256 weights carrying an `fp16` scale and min, subdivided into blocks with their
own quantised scale and min. Spending a few more bits on metadata buys
substantially better reconstruction, which is why `Q4_K_M` beats `Q4_0` at
roughly the same file size. The `_S` / `_M` / `_L` suffixes select which tensors
get bumped to a higher type, not a different core algorithm.

**I-quants** (`IQ2_XXS`, `IQ3_S`, `IQ4_NL`, …) use codebook/lattice methods to
reach very low bit rates. They are not currently modelled in `Quantization` —
adding one means adding an enum entry with a defensible bpw figure. They are
also more compute-heavy per weight than k-quants, which on a memory-bandwidth-
bound device is a trade that can go either way. We have not measured it.

## Why k-quants are still fast: repacking

`GGML_CPU_REPACK` is the mechanism people mistake for KleidiAI.

At model load time ggml can rewrite a quantised tensor's in-memory layout into
an interleaved, blocked form that matches what a wide SIMD kernel wants to
consume — several rows interleaved so a single `SDOT`/`SMMLA` instruction
sequence can process them without gather-style reloading. The weights are
mathematically unchanged; only the byte layout differs. The repacked tensors are
then dispatched to specialised GEMM/GEMV kernels using dotprod or i8mm.

Two consequences worth knowing:

* It costs a little extra time and memory at load. Repacking happens after
  `mmap`, so pages get touched and copied, which reduces how much of the model
  stays as clean, evictable, file-backed memory. This interacts with the
  `mmap` discussion in [tuning](tuning.md).
* Which types are repackable is a property of the pinned llama.cpp commit, not a
  fixed list. It has historically covered `Q4_0` and several k-quants and has
  grown over time. Check `ggml/src/ggml-cpu/repack.cpp` in
  `third_party/llama.cpp` at the pinned SHA rather than trusting this paragraph.

The practical upshot: **do not choose a quantisation for its acceleration
story.** Choose it for quality and for whether it fits in memory, then let the
runtime pick whatever kernel it can.

## Choosing by device RAM

The table below is arithmetic, not measurement — it is derived from
`bitsPerWeight × parameters` plus KV cache and headroom as set out in
[memory](memory.md), and from the fact that Android will kill a foreground app
long before the device runs out of physical RAM.

!!! note "No numbers here are measured"
    The project has no physical arm64 test device. These are budgeting
    recommendations about what will *fit*, not claims about how fast anything
    runs. Treat any performance intuition attached to them as unverified.

| Device RAM | Realistic budget for weights | Recommendation |
| --- | --- | --- |
| 3–4 GB | ~1.0–1.5 GB | A 1–2B model at `Q4_K_M`, short context (2–4K). Expect the system to evict the app when you switch away. Remote inference is the better experience here; see [routing](../remote/routing.md). |
| 6 GB | ~2.0–2.5 GB | 3–4B at `Q4_K_M`, or 1–2B at `Q5_K_M`/`Q6_K` if you want better quality from a smaller model. 4–8K context. |
| 8 GB | ~3.5–4 GB | 7–8B at `Q4_K_M` is on the edge and will be tight with a long context; 3–4B at `Q5_K_M`/`Q6_K` is the more comfortable choice. |
| 12 GB | ~6 GB | 7–8B at `Q4_K_M` or `Q5_K_M` with 8–16K context. Room for an embedding model resident alongside for RAG. |
| 16 GB+ | ~8 GB+ | 7–8B at `Q6_K`, or a 12–14B at `Q4_K_M`. Long contexts become the binding constraint rather than weights. |

Two rules of thumb that survive most disagreements about the table:

1. **A smaller model at a higher quant usually beats a bigger model at a lower
   one**, once you go below about 4 bpw. A 3B at `Q5_K_M` is generally a better
   assistant than a 7B at `Q2_K`, and it loads faster and throttles less.
2. **Budget against `MemAvailable`, not total RAM.** A 8 GB phone does not have
   8 GB for you. See [memory](memory.md) for how the estimate and the
   Fits / Tight / Refuse verdict are computed.

## When Q4_0 or Q8_0 is the right answer

There are legitimate cases, they are just narrower than the folklore suggests:

* **`Q8_0`** when you want a small model (≤1.5B) at near-full quality and have
  the RAM. At 8.5 bpw a 1B model is roughly a gigabyte — fine on an 8 GB device,
  and it is on the KleidiAI path.
* **`Q4_0`** when a specific device has demonstrated a real, measured advantage
  over `Q4_K_M` on the KleidiAI path that outweighs the quality loss. We have
  not demonstrated this and cannot, having no device; if you do, the harness in
  [benchmarking](../benchmarking/how-to-run.md) is where the evidence should
  come from.
* **Embedding models** are a separate case entirely — they are small, they run
  constantly during indexing, and they are usually distributed at `F16` or
  `Q8_0`. See [RAG indexing](../rag/indexing.md).

## Related

* [Build flags](native-build.md) — where `GGML_CPU_KLEIDIAI` is set.
* [Backends](backends.md) — the CPU variant dispatch that selects the kernels.
* [Memory](memory.md) — turning a bpw figure into a load/no-load decision.
