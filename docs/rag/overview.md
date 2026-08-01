# Retrieval-augmented generation, offline

RAG on a phone exists to solve a specific problem: a 3B model that fits on the
device knows very little, and cannot be told everything in a prompt. Retrieval
lets it answer from documents the user actually has, without those documents ever
leaving the device and without a network connection.

Everything described here runs locally. There is no hosted vector database, no
embedding API, and no telemetry.

!!! info "Status"
    Implemented. The pipeline lives in `:core-data` under `data/rag/` — extraction,
    chunking, task prefixing, embedding, the int8 vector store, hybrid retrieval
    and citation injection — on top of the `rag_documents` / `rag_chunks` /
    `message_citations` tables in `:core-storage` and the `VectorKernels` dot
    product in `:core-ml`.

    Two scope limits apply to v1:

    * **Only `.txt` and `.md` can be imported.** PDF extraction is deferred
      pending a licence review of the available Android extractors; the format is
      refused at import with a message saying so, rather than decoded into
      mojibake and indexed.
    * **No performance figures are claimed anywhere.** There is no arm64 device in
      this project's CI, so the NEON kernel is exercised only against the Kotlin
      reference for agreement, never timed.

## The shape of it

```
documents → chunk → embed (with a task prefix) → int8 vectors + FTS5 index
                                                        │
query → embed (different prefix) ─────────┐             │
      → tokenise for FTS5 ────────────────┤             │
                                          ▼             ▼
                              vector similarity    bm25 lexical
                                          └──── RRF ────┘
                                                 │
                                          top-k chunks
                                                 │
                                       prompt + context → model
```

Two retrieval paths, fused. Dense vector similarity finds semantically related
text that shares no words with the query; sparse lexical search finds exact
terms, identifiers, error codes and proper nouns that embeddings routinely miss.
Neither is sufficient on its own and the failure modes are complementary, which
is why the fusion is worth the extra machinery. See
[hybrid search](hybrid-search.md).

## Why local retrieval is a good fit for a small model

A 1–4B model is weak at recall and reasonably good at reading comprehension.
Retrieval plays to that: instead of asking the model what it knows, you put the
relevant paragraph in front of it and ask it to answer from that. This is the
main mechanism by which a small on-device model becomes useful for real work
rather than a toy.

It is also the case where the context length arithmetic bites hardest. Retrieved
chunks go into the prompt, and prompt tokens cost KV cache — linearly, and
permanently for the rest of the conversation. Retrieving eight 512-token chunks
adds 4096 tokens of context before the user's question, which on the shapes in
[memory](../local-inference/memory.md) can be hundreds of megabytes. Retrieval
quality therefore matters more here than on a server: you cannot compensate for a
mediocre retriever by stuffing twenty chunks into a 128K window.

## Two models resident at once

!!! warning "Budget for both"
    RAG requires an **embedding model loaded at the same time as the chat
    model**. The query has to be embedded at query time, with the same model
    that produced the index, while the chat model still holds its weights and
    KV cache.

    Embedding models are small — typically a few hundred megabytes at `F16` or
    `Q8_0` — and run with a very short context, so their own KV cost is minor.
    But "small" is not "free" on a 6 GB device, and the
    [memory estimate](../local-inference/memory.md) must include them or RAG
    appears to work until a long conversation pushes the process over and the
    system kills it.

Unloading the embedding model between queries frees its weights at the cost of a
reload on every retrieval, which is the slowest part of an otherwise fast
operation. Choosing a smaller embedding model is a permanent saving but changes
the index — see below.

## The index is tied to the embedding model

Embeddings from different models are not comparable. Different dimensionality,
different geometry, different notion of similarity. There is no conversion.

So the embedding model is effectively **part of the index format**. Changing it —
even to a newer version of the same family — invalidates every stored vector and
requires reindexing every document. The consequences:

* The model identity and its dimensionality are stored in the index metadata, and
  a mismatch at query time is a hard error, not a silent degradation. Comparing
  vectors from two models produces plausible-looking similarity scores and
  nonsense results, which is far worse than a failure.
* Changing the model is a user-visible operation with a cost ("this will reindex
  247 documents"), not a settings toggle.
* Indexes are not backed up. `rag-vectors` is excluded in
  `backup_rules.xml` — see [storage](../models/storage.md) — because the index is
  large, derived, and tied to a model that may not be present after a restore.

This is also why the [catalogue](../models/catalog.md) treats embedding models as
a distinct kind of entry, recording dimensionality and the required task prefixes
alongside the usual fields.

## Storage

`:core-storage` owns the persistence, on Room over SQLite with the bundled
`androidx.sqlite` build so FTS5 is guaranteed present rather than dependent on
the device's system SQLite.

Three things are stored: document and chunk metadata in ordinary tables; the
embedding vectors as **int8 with a per-vector scale**, in a blob column; and an
FTS5 virtual table over the chunk text for the lexical half. There is no separate
vector database — at the scale a phone indexes, brute-force scan over int8
vectors with a NEON dot product is fast enough that an ANN structure would add
complexity, approximation error and index-build time for no benefit. The vector
layout and the search are covered in [hybrid search](hybrid-search.md).

## What this is not

**Not a general document management system.** The index serves retrieval. The
user's files stay where they are; the app stores chunks, vectors and enough
metadata to cite a source.

**Not fine-tuning.** Retrieval puts facts in the context window. It does not
change the model's behaviour, style or capabilities.

**Not a substitute for a bigger model.** Retrieval fixes "the model does not know
this". It does not fix "the model cannot reason about this". A 1B model handed
the correct paragraph will still misread it more often than a 7B would.

**Not networked.** No document leaves the device at any point, whether or not the
chat itself is answered locally. If [routing](../remote/routing.md) sends a
RAG-augmented request to a remote server, the retrieved text goes with it — and
that must be surfaced to the user, because it means their documents are being
transmitted. Retrieval being local does not make the whole pipeline local.

## Related

* [Indexing](indexing.md) — chunking, task prefixes, int8 quantisation.
* [Hybrid search](hybrid-search.md) — vector search, FTS5 bm25, and RRF.
* [Memory](../local-inference/memory.md) — budgeting for two resident models.
