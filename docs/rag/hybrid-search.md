# Hybrid search

Query time. Two retrievers run over the same chunks, and their results are fused
into one ranking.

!!! warning "Status"
    Not implemented. This is the design; the int8 kernel belongs to `:core-ml`
    and the FTS5 tables to `:core-storage`.

## Why two retrievers

They fail in opposite directions, and each one's failures are the other's easy
cases.

**Dense vector search** matches meaning. It finds the paragraph about "restoring
from a snapshot" when the query says "how do I roll back", with no shared
vocabulary. It is also confidently wrong about exact tokens: identifiers, error
codes, version numbers, surnames, and any term the embedding model never saw
during training get mapped to a vague neighbourhood of the space. Ask for
`ENOSPC` and a dense retriever returns things about disks being full, which may
or may not include the document that actually mentions `ENOSPC`.

**Lexical search** matches words. It finds `ENOSPC` exactly, and it finds nothing
at all when the user's phrasing shares no terms with the document — which is most
natural-language questions.

Running both and fusing is not hedging; it is the standard result that hybrid
retrieval beats either component. On a phone the cost is low, because both
retrievers are fast at the scale a phone indexes.

## The dense half: brute force is correct here

No ANN index. No HNSW, no IVF, no product quantisation.

At the scale a phone realistically indexes — thousands to low hundreds of
thousands of chunks — a linear scan over int8 vectors with a NEON dot product is
fast, and it is **exact**. An approximate index would add build time, memory,
tuning parameters, and a recall cliff that manifests as "the app sometimes can't
find a document I know is there" — the single most damaging retrieval failure,
because it destroys trust in a way that a merely mediocre ranking does not.

The scan, per candidate vector:

```
score = dot_int8(query_q, chunk_q) × query_scale × chunk_scale
```

`dot_int8` accumulates `int8 × int8` products into `int32`. On arm64 this is
`SDOT`, four multiply-accumulates per lane per instruction, so a 768-dimensional
dot product is a short sequence of vector operations. Since both vectors were
L2-normalised before quantisation ([indexing](indexing.md)), the result is cosine
similarity directly — no per-candidate normalisation, no square roots.

Implementation notes that matter more than the kernel itself:

* **Store vectors contiguously.** A single blob of `n × dim` int8 bytes, scanned
  linearly, with the scales in a parallel `float32` array. The scan is
  memory-bandwidth-bound, so layout dominates; per-row allocations or a
  pointer-chasing structure will cost more than any instruction selection saves.
* **Keep a top-k heap**, do not sort all scores. k is small.
* **Filter before scanning where you can.** If the query is scoped to a
  collection or a document set, restricting the candidate range turns the scan
  into a smaller scan. This is where the relational tables earn their place.
* **The kernel lives in `:core-ml`**, not `:core-llm`, so retrieval works with
  `-Pollama.nativeSource=none`. There is a Kotlin fallback for correctness
  testing and for the case where the native library is absent — the same code
  path runs on the JVM in unit tests. See
  [backends](../local-inference/backends.md).

If the corpus ever grows past what a linear scan can serve within a reasonable
query latency, the fix is a coarse pre-filter (cluster centroids, scan only the
nearest few clusters) rather than a full ANN library. That is a change to make
when there is a measurement demanding it, not before.

The query must be embedded with the **query** task prefix, which is different
from the document prefix and is model-specific. Getting this wrong is the silent
quality bug described in [indexing](indexing.md).

## The lexical half: FTS5 and bm25

SQLite's FTS5 provides a `bm25()` ranking function.

```sql
SELECT rowid, bm25(chunk_fts) AS score
FROM chunk_fts
WHERE chunk_fts MATCH :query
ORDER BY score
LIMIT :k;
```

!!! warning "FTS5's bm25 is negative, and lower is better"
    SQLite returns bm25 scores **negated**, so a better match is a *more
    negative* number. `ORDER BY score` — ascending — puts the best results
    first. `ORDER BY score DESC`, which is what you write by reflex for a
    relevance score, returns the worst matches, and it returns them without
    error.

    This is well documented and still caught out approximately everyone. Order
    ascending, or negate at the boundary and be consistent about it. Whichever
    you choose, write a test with a known-best document asserting it comes first.

`bm25()` also accepts per-column weights, which is worth using if the chunk's
title or heading is a separate column — a query term matching a heading is a
stronger signal than one matching body text.

The other thing to get right is **query construction**. The `MATCH` operator
takes FTS5 query syntax, not free text. A user question containing a quote, a
hyphen, a colon or the word `AND` will either be interpreted as operators or
raise a syntax error. Sanitise: tokenise the user's query, quote each term, and
join them. Do not hand raw user input to `MATCH`.

Prefix queries (`term*`) improve recall on partial words and are cheap. Whether
to add them per term is a tuning decision.

## Fusion: reciprocal rank fusion

The two retrievers produce scores that are not comparable — cosine similarity in
`[-1, 1]`, bm25 an unbounded negative magnitude that depends on corpus
statistics. Normalising them onto a common scale requires knowing each
distribution, and those distributions shift with the corpus and with the query.

**Reciprocal rank fusion** sidesteps the problem by discarding the scores and
using only the ranks:

```
RRF(d) = Σ over retrievers r:  1 / (k + rank_r(d))
```

`rank_r(d)` is the 1-based position of document `d` in retriever `r`'s result
list; a document absent from a list contributes nothing from it. `k` is a
smoothing constant, conventionally **60** — it damps the influence of the very
top ranks so that a document ranked first by one retriever does not automatically
beat a document ranked second and third by both.

Why it is the right choice here:

* **No score calibration.** Nothing to normalise, nothing to tune per corpus,
  nothing that drifts as documents are added.
* **Robust to a broken retriever.** If the dense half returns nonsense for a
  query, its contributions are spread across the tail and the lexical half still
  determines the ranking. Score-based fusion has no such property — one retriever
  producing large scores dominates.
* **Cheap.** Two sorted lists and a hash map.
* **It rewards agreement.** A chunk that both retrievers rank highly beats a
  chunk that one retriever loves and the other has never heard of, which is
  usually the right instinct.

The cost is that genuine score information is thrown away: a chunk at rank 1 with
cosine 0.95 and a chunk at rank 1 with cosine 0.31 contribute identically. In
practice this is a good trade, and it is why a **relevance floor** on the dense
side before fusion is worth having — if nothing is above the floor, the honest
answer is that the corpus does not contain an answer, and a small model handed
irrelevant context will confabulate rather than say so.

A weighted variant (`w_r / (k + rank_r(d))`) allows leaning on one retriever.
Weights should not be invented; they need evaluation data, which means a set of
queries with known-correct chunks. Building that set is the prerequisite for any
tuning claim, and until it exists, unweighted RRF with `k = 60` is the defensible
default.

## After fusion

**Deduplicate.** Overlapping chunks ([indexing](indexing.md)) mean near-identical
text can occupy several top slots, wasting context on repetition. Collapse chunks
that are adjacent in the same document, and prefer merging them into one longer
span over dropping one.

**Budget the context.** Take chunks in fused order until the token budget is
spent, not a fixed k. The budget comes from the KV cache arithmetic in
[memory](../local-inference/memory.md), and on a phone it is tight — a handful of
chunks, not twenty.

**Order for the model, not for the ranking.** Models attend unevenly across a long
context, with the beginning and end favoured. Placing the highest-ranked chunk
adjacent to the user's question rather than buried in the middle is the usual
mitigation.

**Cite.** Each chunk carries its document and offsets. The answer shows sources,
and a user can open the original. Without this, RAG is just a model claiming
things with extra steps.

**Say when nothing was found.** Zero results above the floor should produce "I
don't have anything about that in your documents", not an unaugmented answer
dressed up as a retrieved one.

## Related

* [Overview](overview.md) — the pipeline this sits in.
* [Indexing](indexing.md) — where the vectors and the FTS5 table come from.
* [Backends](../local-inference/backends.md) — the int8 kernel's home in
  `:core-ml`.
