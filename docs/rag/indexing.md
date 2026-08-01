# Indexing

Turning documents into something retrievable: chunking, embedding, and storing.
Two of the three steps have a trap in them that produces no error and degrades
results quietly.

!!! warning "Status"
    Not implemented. `:core-ml` (the int8 kernel) and `:core-storage` (the
    tables) are registered but empty.

## Chunking

Chunks are the retrieval unit. Chunk badly and no amount of embedding quality
rescues you.

**Size.** Long enough to be self-contained, short enough that a handful fit in the
context window. A few hundred tokens is the usual range. The binding constraint on
a phone is the KV cache: retrieved chunks are prompt tokens, and prompt tokens
cost memory linearly for the rest of the conversation. See
[memory](../local-inference/memory.md). Chunks also cannot exceed the embedding
model's own context length — most embedding models are 512 tokens, some 2048, and
text beyond that is **silently truncated**, so half a chunk gets embedded and the
vector represents something other than what you stored.

**Overlap.** A modest overlap between adjacent chunks so a fact that straddles a
boundary is fully present in at least one of them. It costs storage and creates
near-duplicate results, which the fusion step has to deal with.

**Respect structure.** Split on paragraph and section boundaries in preference to
a fixed token count. A chunk that begins mid-sentence embeds poorly and reads
badly when shown as a citation. Markdown headings, PDF page breaks and code block
boundaries are all better split points than "every 400 tokens".

**Keep context in the chunk.** A chunk from the middle of a document loses the
document title and section heading that made it interpretable. Prepending them —
`Document title > Section heading\n\n<chunk text>` — measurably helps both halves
of retrieval and costs a few tokens.

**Store what you need to cite.** Document id, chunk ordinal, character offsets,
and enough to render a source reference. A RAG answer without a citation is
unverifiable, and unverifiable is the failure mode users are most upset by.

Measure chunk length in **tokens using the embedding model's own tokeniser**, not
in characters. Character heuristics vary by three or four times across languages
and are wildly wrong for code.

## Task prefixes are mandatory

!!! danger "Omitting the prefix is a silent quality bug"
    Most modern embedding models are trained with **task-specific instruction
    prefixes**, and they use different prefixes for documents and for queries.
    Embedding a document with the query prefix, or with no prefix at all, is not
    an error. It produces a perfectly well-formed vector of the right
    dimensionality in the wrong region of the space.

    Nothing fails. The index builds, queries return results, and the results are
    worse — sometimes much worse — than they should be. There is no log line and
    no exception. You find out by evaluating retrieval quality, which almost
    nobody does, which is why this bug is so common in hobbyist RAG code.

The prefixes are **model-specific**. There is no universal convention and no way
to infer them from the model file. Shapes you will encounter:

* The `nomic-embed-text` family uses `search_document: ` when embedding a passage
  and `search_query: ` when embedding a query, with further prefixes
  (`clustering: `, `classification: `) for other tasks.
* The E5 family uses `passage: ` and `query: `.
* BGE models apply an instruction to the **query only** — a sentence such as
  "Represent this sentence for searching relevant passages: " — and embed
  passages bare.
* EmbeddingGemma uses a structured prompt with distinct forms for query and
  document.

The exact strings, including trailing spaces and punctuation, must come from the
model card. Do not reconstruct them from memory or from another model's
convention.

Because this cannot be inferred, the prefixes are **recorded in the catalogue
entry** for every embedding model — see [the catalogue](../models/catalog.md) —
and they are stored in the index metadata alongside the model identity. Three
consequences:

1. The document prefix and the query prefix are applied automatically, from
   configuration, never hardcoded at a call site.
2. Adding an embedding model to the catalogue without its prefixes should be
   impossible; make the field non-optional.
3. If a model genuinely needs no prefix, record that explicitly as an empty
   prefix rather than leaving the field unset. "No prefix" and "we forgot to look
   it up" must be distinguishable.

The prefix is applied **before tokenisation** and counts against the model's
context length. On a 512-token model with a long instruction-style query prefix,
that is a non-trivial fraction of the budget.

## Embedding

Run the embedding model through the same engine as everything else. It is a GGUF
model; nothing special is required beyond configuring it for pooled embedding
output rather than generation.

**Batch.** Embedding is compute-bound and parallelises well across a batch, unlike
token generation. Indexing a document is one of the few places on a phone where a
larger batch is straightforwardly better — bounded by the compute buffer, which
scales with batch size; see [tuning](../local-inference/tuning.md).

**Normalise.** L2-normalise every vector before storing. This makes cosine
similarity a plain dot product, which is what makes the int8 path below simple
and fast. Do it once at index time rather than at every query.

**Indexing is a background job.** WorkManager, with a foreground notification and
constraints — deferred while the device is hot, and it must be interruptible and
resumable at chunk granularity. Indexing a large corpus is exactly the kind of
sustained load that provokes thermal throttling, and the user should be able to
put the phone down and come back.

**Report progress in documents, not percentages of unknown work.** "142 of 247
documents" is meaningful; a progress bar that stalls is not.

## int8 vectors with a per-vector scale

Storing embeddings as `float32` costs 4 bytes per dimension. At 768 dimensions
that is 3 KB per chunk, so 100,000 chunks is roughly 300 MB — before FTS5, before
the documents themselves. On a phone that is worth reducing.

Quantise to int8 with **one scale per vector**:

```
scale  = max(|v_i|) / 127
q_i    = round(v_i / scale)          // clamped to [-127, 127]
```

Store the 768 int8 values plus the single `float32` scale — 769 bytes instead of
3072, a 4× reduction. A per-vector scale rather than a global one matters because
vector magnitudes vary across a corpus, and a global scale wastes precision on
every vector that is not the largest.

Since the vectors were L2-normalised before quantisation, cosine similarity
between two of them becomes:

```
cos(a, b) ≈ dot(qa, qb) × scale_a × scale_b
```

The integer dot product is a sum of `int8 × int8` products accumulated in
`int32` — no overflow risk at these dimensionalities, and exactly the shape ARM's
`SDOT` instruction computes four lanes at a time. This is the kernel that lives in
`:core-ml`, and it is deliberately separate from llama.cpp so retrieval works with
`-Pollama.nativeSource=none`; see [backends](../local-inference/backends.md).

The quality cost of int8 quantisation on normalised embeddings is small — the
error is a fraction of a percent of the vector norm, well below the margin
between a relevant and an irrelevant chunk. It is not zero, and if it ever
matters, storing `float32` for a small high-value subset alongside int8 for the
bulk is the escape hatch. We have not measured the impact; the argument is
arithmetic, not empirical.

## The FTS5 side

Every chunk also goes into an FTS5 virtual table for the lexical half of
[hybrid search](hybrid-search.md).

The bundled `androidx.sqlite` build guarantees FTS5 is available; relying on the
device's system SQLite does not, and the failure is at runtime on some devices
only.

Two configuration decisions worth making deliberately. The **tokeniser**:
`unicode61` with diacritic removal is the sane default, and `porter` stemming
helps English recall at the cost of precision and of being wrong for other
languages. And **`content=`**: an external-content FTS5 table avoids storing the
chunk text twice, at the cost of having to keep the shadow table in sync with
triggers. On a mobile device where the corpus may be a substantial fraction of
available storage, the saving is worth the triggers.

Index the chunk text **without** the task prefix. The prefix is an instruction to
the embedding model; putting it in the lexical index means every chunk contains
the words "search document" and bm25 has to work around it.

## Deletion and updates

A document that changes must have its old chunks and vectors removed before the
new ones are inserted, in one transaction. Stale chunks retrieved alongside fresh
ones produce answers that cite text the user has already corrected, which is a
particularly damaging kind of wrong.

Content-hash each chunk so an unchanged chunk in an edited document is not
re-embedded. Embedding is the expensive step and most edits touch a small part of
a document.

## Related

* [Overview](overview.md) — why any of this exists.
* [Hybrid search](hybrid-search.md) — what happens at query time.
* [The catalogue](../models/catalog.md) — where prefixes are recorded.
