# The model catalogue

The catalogue is the curated list of models the app offers to download. It exists
because the alternative — a search box against Hugging Face — hands the user
thousands of GGUF files, most of which will not fit on their phone, many of which
are base models that cannot hold a conversation, and some of which are broken
uploads.

!!! note "Status"
    Partly implemented. The schema and the reader exist —
    `:core-download`'s `ModelCatalog`, `CatalogEntry` and `ModelCatalogSource`,
    backed by the bundled asset `core-download/src/main/assets/models_catalog.json`.

    **Every entry in that asset is marked `"verified": false`.** Repository ids
    and filename conventions were written from each publisher's established
    naming; sizes, SHA-256 hashes and commit revisions were deliberately left
    null rather than guessed, because a wrong hash presents to the user as a
    corrupted download of a perfectly good file. Filling them in, one model per
    PR, is the process described under [Adding an entry](#adding-an-entry).

## What a catalogue entry is

An entry is a *model at a specific quantisation from a specific repository* —
not a model family. `qwen3:1.7b` is a family; `Qwen3-1.7B-Instruct-Q4_K_M.gguf`
from a named repository at a named revision is an entry, because that is the
granularity at which size, memory fit and download URL are determined.

The fields an entry needs:

| Field | Why |
| --- | --- |
| Family, display name, parameter count | Identity and UI. |
| Repository and file path | Where to fetch it. See [downloading](downloading.md). |
| Revision | A commit SHA, not `main`. A branch pointer means the bytes can change under you and the recorded hash stops matching. |
| Quantisation | A `Quantization` enum value, which carries bits-per-weight and the KleidiAI fact. |
| Exact file size in bytes | Needed for the [memory verdict](../local-inference/memory.md) *before* download, and for progress reporting. |
| SHA-256 | From the repository's LFS metadata. The only real integrity check. |
| Trained context length | So the app does not offer a context the model cannot use. |
| Capabilities | Tool calling, vision, embedding-only, reasoning. Drives which models appear for which task and feeds [routing](../remote/routing.md). |
| Chat template source | Whether the GGUF carries `tokenizer.chat_template`. It should; an entry whose template is missing needs an override, and that is a red flag about the upload. |
| Licence | Displayed before download. Some weights carry acceptable-use terms that are not OSI licences. |
| Shard list | For [sharded GGUF](downloading.md), every part, each with its own size and hash. |

Two model kinds share the schema but are used differently. **Chat models** are
what the entry list is mostly for. **Embedding models** are a separate section
because they are selected for RAG rather than for conversation, and swapping one
invalidates the whole index — see [RAG indexing](../rag/indexing.md). An
embedding entry additionally records its output dimensionality and, critically,
its **required task prefixes**, which are model-specific and mandatory.

## Curation criteria

A model goes in the catalogue if all of the following hold. These are deliberately
strict; the catalogue's value is that everything in it works.

**It fits on a real phone.** The practical ceiling is around 8B parameters at
`Q4_K_M`, and that is already tight on an 8 GB device once a context is
allocated. Anything larger belongs on a remote server, and the app already knows
how to talk to one.

**It is instruction-tuned or chat-tuned.** A base model in a chat UI produces
document continuation and users read that as the app being broken.

**The GGUF carries a working chat template.** Check `tokenizer.chat_template` in
the metadata. A missing or wrong template produces subtly degraded output rather
than an error, which is the worst failure mode there is.

**The quantisation is `Q4_K_M` or better.** Below about 4 bits per weight the
quality loss on small models is severe, and small models are all that fit. See
[quantisation](../local-inference/quantization.md). Offer a second, higher quant
for models small enough that a 12 GB device could run `Q6_K` or `Q8_0`.

**The repository is a known-good source.** The model's own organisation
(`Qwen/`, `google/`, `microsoft/`), or a quantiser with a track record.
`bartowski` and `unsloth` are the usual answer for community quants. An anonymous
one-off upload with no download history is not.

**The licence permits redistribution to end users**, and the licence text is
available to display.

Beyond that, prefer models with recent activity and multiple independent
recommendations over benchmark leaderboard position. Leaderboard rank at the 1–4B
scale is noisy and contaminated; how a model behaves in an actual multi-turn
conversation is what users notice.

## Families worth considering

Not the catalogue — a starting point for whoever assembles it. Verify everything
here against the current repository before adding it, because model releases move
faster than documentation.

For chat, the families that ship well-supported small GGUF builds with working
templates are Qwen (the 0.6B–8B range), Llama 3.x at 1B and 3B, Gemma at 1B–4B,
Phi's mini variants, SmolLM, and IBM Granite's small models. All of them have
official or well-established community GGUF conversions.

For embeddings, the usual candidates are the `nomic-embed-text` family, the E5
family, BGE small/base, and EmbeddingGemma. Each has different and **mandatory**
prefix requirements, which is the single most important thing to record when
adding one.

No sizes, speeds or quality rankings are given here on purpose. Sizes vary by
quantiser, and speeds have not been measured because the project has no arm64
device.

## Adding an entry

1. **Find the file.** Identify the repository, the exact file path, and a commit
   SHA to pin. Prefer the model author's own repository; fall back to a
   reputable quantiser.
2. **Get the real size and hash.** The Hugging Face tree API returns both for LFS
   files without downloading anything:

    ```bash
    curl -s "https://huggingface.co/api/models/<org>/<repo>/tree/<sha>?recursive=1" \
      | python -m json.tool
    ```

    LFS entries carry `size` and `oid` (the SHA-256). Use those; do not use the
    `ETag` from a download response, for reasons set out in
    [downloading](downloading.md).

3. **Read the GGUF metadata.** Confirm architecture, `block_count`,
   `context_length`, `attention.head_count_kv`, and the presence of
   `tokenizer.chat_template`. The KV-head figures feed the
   [memory estimate](../local-inference/memory.md) and getting them wrong makes
   the estimate wrong by the GQA ratio.
4. **Record capabilities honestly.** "Supports tool calling" means you have seen
   it emit a well-formed tool call, not that the model card claims it.
5. **For embedding models, record the exact prefix strings** from the model card,
   verbatim, including trailing spaces and punctuation. Omitting or mangling a
   prefix is a silent quality bug —
   [see the indexing page](../rag/indexing.md).
6. **Add the licence text or a link to it.**
7. **Open a PR.** One model per PR, with the tree-API output pasted into the
   description so a reviewer can verify the size and hash without repeating the
   work.

## Distribution and hosting

OllamaMobile does not host model weights. The app is distributed through GitHub
Releases only, and the APK contains no model. The catalogue is a list of pointers
to third-party repositories, and downloads go directly from the device to those
repositories.

This is not incidental. Hosting weights would mean bandwidth costs, a licence
compliance obligation for each model, and a mirror that goes stale. Pointing at
the upstream repository keeps the app a client and keeps the responsibility where
it belongs — with a clear statement in the UI of who is being downloaded from
before the download starts.

The catalogue itself ships as an asset inside the APK. It can be refreshed from
the repository without a full app update, but the bundled copy must always be
usable, because a first run on a device with no connectivity should still show
the user what exists.

## Related

* [Downloading](downloading.md) — resolution, resumption, integrity.
* [Storage](storage.md) — where files land and how they are cleaned up.
* [Quantisation](../local-inference/quantization.md) — choosing which quant to
  list.
* [Memory](../local-inference/memory.md) — turning a catalogue entry into a
  Fits/Tight/Refuse verdict.
