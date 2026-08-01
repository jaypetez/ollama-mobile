# Where models live on disk

## The location

```
context.filesDir/models/
```

Internal app storage. Not `getExternalFilesDir()`, not the shared Downloads
directory, not the MediaStore.

`filesDir` on a modern Android device is a directory on the app's private data
partition, an ordinary ext4 or f2fs filesystem accessed directly by the kernel.
Every path the app takes to a model file — `open`, `read`, and above all `mmap` —
goes straight to the block layer.

## Why not external storage

The obvious argument for `getExternalFilesDir()` is space: on a device with a
small data partition and a large "SD card" volume, the model would fit somewhere
it does not otherwise. The argument against is FUSE, and it wins.

Since Android 11 the emulated external volume is backed by **FUSE** rather than
the old in-kernel `sdcardfs`. Every filesystem operation on `/sdcard` is routed
out to a userspace daemon (`MediaProvider`) and back. For ordinary file I/O this
is tolerable — Android added a kernel bypass for common read/write paths
specifically because the regression was severe.

`mmap` does not get the same treatment. A memory-mapped file on FUSE resolves
page faults by round-tripping to the userspace daemon, and llama.cpp's access
pattern is precisely the pathological one: the entire model is mapped, and every
token touches every weight, so page faults are continuous rather than
occasional. On top of the per-fault cost, the page cache behaviour differs, so
pages that should stay resident do not reliably do so.

Since [mmap is how models are loaded](../local-inference/tuning.md) and load time
plus steady-state token throughput are the two numbers a user notices, putting
weights on FUSE-backed storage trades the app's core performance for capacity.
Internal storage it is.

We have not measured the difference on a device, because there is no device. The
mechanism is the argument.

Two secondary reasons that would justify the same decision on their own: files in
`filesDir` are private to the app and removed on uninstall, so a 4 GB model does
not outlive the app that downloaded it; and scoped storage makes writing large
files to shared locations require either MediaStore (which is designed for media,
not for `mmap`-able blobs) or the all-files-access permission, which is an
egregious thing to request for a chat app.

## Layout

```
filesDir/
  models/
    <model-id>/
      model.gguf                       # or model-00001-of-00003.gguf, …
      metadata.json                    # source repo, revision, sha256, sizes
  downloads/
    <model-id>/
      model.gguf.part                  # in-flight; renamed on hash success
      model.gguf.digest                # running SHA-256 state
  rag-vectors/
  logs/
  crashes/
```

A directory per model rather than a flat namespace, because sharded models need
their siblings adjacent and correctly named — see
[downloading](downloading.md) — and because deleting a model should be a
recursive delete of one directory rather than a pattern match over filenames.

Downloads live in a separate top-level directory, not inside `models/`, so that
the model scanner can treat everything under `models/` as complete. An
interrupted download must never be discoverable as a usable model.

`metadata.json` records where the file came from: repository, revision SHA,
expected size, expected SHA-256, the quantisation, and the download timestamp. It
is what lets the app tell the user what they have, verify integrity later, and
re-download the same bytes rather than whatever `main` points at today.

## Backup exclusion

Already implemented, in `app/src/main/res/xml/backup_rules.xml` and
`app/src/main/res/xml/data_extraction_rules.xml`.

!!! warning "One un-excluded model disables backup for the whole app"
    Android's backup quota is 25 MB. A single GGUF is 500 MB to 8 GB. If the
    models directory is not excluded, the backup transport aborts — and it does
    so for the *entire app*, so the settings and conversation history that
    genuinely should be backed up are not, silently.

Excluded: `models`, `downloads`, `rag-vectors`, `logs`, `crashes`, everything
under the external domain, and `secrets.preferences_pb`.

Two files are needed because Android 12+ reads `<data-extraction-rules>` while
older versions read `<full-backup-content>`, and within the newer file every
exclusion must be repeated under **both** `<cloud-backup>` and
`<device-transfer>` — they are evaluated independently, and omitting one silently
ships multi-gigabyte models through that path.

The secrets exclusion is [covered separately](../remote/auth-tls.md); the short
version is that the ciphertext is bound to a device-specific Keystore key and is
useless after a restore.

Re-downloadable data does not belong in a backup. `rag-vectors` is the one
arguable case — an index is expensive to rebuild — but it is also large and tied
to a specific embedding model, so rebuilding is the honest answer. See
[RAG indexing](../rag/indexing.md).

## Cleanup

**Orphaned partial downloads.** A `.part` file whose worker no longer exists is
dead weight, potentially gigabytes of it. Sweep `downloads/` at startup, remove
anything with no corresponding active or enqueued work request, and remove
anything older than a threshold regardless. This is safe: the download restarts
from a valid resume point or from zero, and either way the bytes are recoverable
from the network.

**Failed integrity checks.** Deleted immediately, at the point of failure. Never
retained "in case".

**Models the user has deleted.** Recursive delete of the model directory, plus
its `metadata.json`, plus any cached derived data. If the model is currently
loaded, unload first — deleting the file out from under an active `mmap` leaves
the mapping valid but the file unlinked, which wastes the space until the process
exits and produces confusing free-space arithmetic.

**Nothing else is deleted automatically.** No LRU eviction, no "we freed up space
for you". A model represents a deliberate multi-gigabyte download, often over a
metered or slow connection, and reclaiming it without being asked is hostile. The
right behaviour when storage is low is to *tell* the user, show what is using
space with per-model sizes, and let them choose.

Check free space before starting a download with `StatFs` on `filesDir`, and
require the file size plus a margin — the filesystem behaves badly when nearly
full, and Android's own storage manager will start deleting things. `StorageManager`
also offers `getAllocatableBytes()` and `allocateBytes()`, which account for
space the system is willing to reclaim from caches; using those gives a more
accurate answer than raw free space and reserves the space up front.

## Related

* [Downloading](downloading.md) — how files get here.
* [The catalogue](catalog.md) — what `metadata.json` records.
* [Tuning](../local-inference/tuning.md) — the mmap behaviour this location
  protects.
