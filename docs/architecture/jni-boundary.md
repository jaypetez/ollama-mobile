# JNI boundary

`:core-llm` is the only module that sees `llama.cpp`, and inside it the JNI
layer is deliberately small and deliberately boring. Four rules govern it. Each
one exists because the obvious alternative breaks in a specific, reproducible
way.

## Rule 1 — handles are `jlong`

Native objects are represented on the Kotlin side as an opaque `Long`. Nothing
else crosses.

```kotlin
external fun nativeCreateSession(
    modelPath: String,
    contextTokens: Int,
    threads: Int,
    batchTokens: Int,
    embeddingMode: Boolean,
    useMmap: Boolean,
): Long

external fun nativeDestroySession(handle: Long)
```

The alternative — a Java object whose fields native code reaches into with
`GetFieldID` / `SetLongField` — couples the C++ to the exact shape of a Kotlin
class. Rename the field, change its nullability, let R8 shrink it away, and you
get a `NoSuchFieldError` at runtime rather than a compile error. With a `jlong`
the only contract is "this integer is meaningful to native code", which no
refactoring tool can quietly invalidate.

Three consequences worth stating:

- **A zero handle is the null handle.** Every native entry point rejects `0`
  rather than dereferencing it. Double-free is prevented by zeroing the field
  first, as above.
- **Ownership is explicit.** The Kotlin object owns the handle and is the only
  thing that may free it. There is no finalizer; `Closeable` and structured
  lifecycle scoping do the work, because relying on the GC to release
  multi-gigabyte native allocations is how you get an out-of-memory kill.
- **Handles are validated, not trusted.** The `jlong` is a monotonically
  increasing *registry key*, not a cast pointer, and every entry point looks it
  up under a mutex before touching anything. A stale handle is a lookup miss
  that returns a status; a stale pointer would be a wild dereference. The
  registry holds `shared_ptr<Session>`, so a `nativeRequestAbort` racing a
  `nativeDestroySession` extends the session's life rather than reading freed
  memory.

## Rule 2 — `RegisterNatives` in `JNI_OnLoad`

Natives are bound explicitly at library load, not discovered by symbol name.

```cpp
const JNINativeMethod kMethods[] = {
    {"nativeCreateSession", "(Ljava/lang/String;IIIZZ)J", (void*) NativeCreateSession},
    {"nativeGenerateNextToken", "(J)[B", (void*) NativeGenerateNextToken},
    {"nativeDestroySession", "(J)V", (void*) NativeDestroySession},
    // ...
};

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass(
        "io/github/jaypetez/ollamamobile/llm/internal/LlamaBridge");
    if (bridge == nullptr) return JNI_ERR;
    if (env->RegisterNatives(bridge, kMethods, std::size(kMethods)) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
```

**Why this matters more on Android than elsewhere.** Implicit binding works by
mangling the fully qualified Java method name into a C symbol —
`Java_io_github_jaypetez_ollamamobile_llm_NativeBridge_nativeLoadModel`. That
name embeds the package and class. R8 in full mode — which this project enables
(`android.enableR8.fullMode=true`) — renames and repackages classes in release
builds. The mangled symbol no longer matches, and you get an
`UnsatisfiedLinkError` **only in release**, only after shrinking, typically
discovered by a user.

The usual workaround is a `-keep` rule on every class holding a native method,
which means an obfuscation exemption that must be maintained forever and that
silently rots when someone moves a class. `RegisterNatives` needs one `FindClass`
call with one string, so at most one `-keep` rule for one bridge class, and if
that string ever goes stale the failure is immediate and loud at library load
rather than at first inference.

Secondary benefits: binding fails fast and completely at load time instead of
lazily at first call; the JNI method table is a single readable list of the
entire native surface; and the C++ function names are free to be sensible rather
than 60-character mangled monsters.

## Rule 3 — no file-static globals

There is no `static llama_model* g_model;` anywhere in this layer, and no
process-wide "current context".

The reason is concrete rather than stylistic: **a chat model and an embedding
model must be loaded at the same time.** RAG needs an embedding model resident
while a conversation is running against a chat model. Those are two independent
`llama_model` objects, two contexts, two KV caches, two engine threads (see
[Threading](threading.md)) — and one global slot cannot hold two of anything.

The moment a global exists, someone writes `g_ctx` into a helper because it is
convenient, and the second model silently clobbers the first. The bug does not
appear until RAG is enabled, and when it does it looks like the chat model
producing garbage, which is the last place anyone thinks to look.

So every piece of state hangs off the handle. Native functions take a handle,
resolve it, and operate on that instance. The only process-wide state is the
handle registry itself and the ggml backend registration that `llama.cpp` does
internally at init.

```mermaid
flowchart LR
    subgraph kotlin["Kotlin — :core-llm"]
        chat["ChatEngine<br/>handle = 0x7f...a0"]
        embed["EmbeddingEngine<br/>handle = 0x7f...c8"]
    end
    subgraph native["Native"]
        reg["handle registry"]
        c1["llama_model + ctx (chat)"]
        c2["llama_model + ctx (embedding)"]
    end
    chat -->|jlong| reg
    embed -->|jlong| reg
    reg --> c1
    reg --> c2
```

This also means unloading one model cannot disturb the other, and a crash in one
backend does not require tearing down everything to recover.

## Rule 4 — models load by real path; SAF URIs are an import path only

`nativeLoadModel` takes a filesystem path. It never takes a content URI, and
there is no code path that streams a model into memory.

Weights are memory-mapped. `mmap` is not an optimisation here, it is the reason
loading a multi-gigabyte model on a phone is possible at all: the pages are file
-backed and clean, so the kernel can evict and re-read them under pressure
instead of the process holding the whole model as dirty anonymous memory. Read
the file into a buffer instead and you have committed every byte to RAM, doubled
peak usage during load, and made the low-memory killer's decision for it.

A Storage Access Framework URI cannot be mapped. It resolves through a
`ContentProvider`; what you can get from it is a `ParcelFileDescriptor`, and a
document provider is free to back that with a pipe, a network stream, or a file
in another app's sandbox that may be revoked between calls. Nothing about it
guarantees a stable, seekable, mappable region for the lifetime of the mapping.

Therefore:

- **Import** accepts a SAF URI. The file is copied — streamed once, with
  progress and integrity checking — into app-private storage.
- **Load** takes the resulting real path.

The copy is not a workaround waiting to be optimised away; it is the price of a
stable mapping, and it is paid once per model rather than once per load. The
cost is disk: an imported model exists twice until the user deletes the
original, which the import flow tells them. [Storage](../models/storage.md)
covers the layout and the cleanup.

## Rule 5 — tokens are pulled, and they cross as bytes

`nativeGenerateNextToken(handle)` returns the next token or `null` at the end of
generation. A dedicated OS thread on the Kotlin side calls it in a loop and
offers each result into a `Flow`.

The obvious design is the opposite: hand native code a listener and have it call
`onToken()`. That is worse here, specifically. ggml runs `llama_decode` on its
own worker threads, none of which is attached to the JVM, so a callback means
`AttachCurrentThread` on every one of them, a `GlobalRef` on the listener that
must outlive the generation and be deleted on exactly one path, and a `JNIEnv`
that must never leak across threads. Each of those is a crash rather than an
exception when it is wrong, in a thread with no Java frames, so the tombstone
names ggml. Pulling removes all of it: native code only ever runs inside a JNI
call it was invoked from.

The return type is a **`ByteArray`**, not a `String`. `NewStringUTF` consumes
*modified* UTF-8, in which a character outside the BMP is a six-byte surrogate
pair rather than the four-byte sequence real UTF-8 uses. Model output is full of
emoji. Passing four-byte sequences to `NewStringUTF` is undefined behaviour that
ART sometimes turns into an abort, so the bytes cross raw and Kotlin decodes
them. An **empty** array is a valid, non-terminal result: that token completed
no code point, and its bytes are being carried into the next one by the native
side rather than being handed over half-formed.

## Rule 6 — cancellation is two layers, because one cannot work

A collector that stops has to stop a generation blocked inside a multi-second
prefill, on a thread that cannot check anything while it is in there.

1. **Between tokens.** `ensureActive()` at the top of each pull-loop iteration.
   Enough for the token-by-token phase, where each `llama_decode` is
   milliseconds.
2. **Inside a decode.** `llama_context_params.abort_callback` reads a
   `std::atomic<bool>` on the session; ggml polls it between graph nodes and
   `llama_decode` returns status 2. The flag is set by `nativeRequestAbort`,
   which deliberately does *not* take the session lock — the whole point is that
   it is answerable while the engine thread holds it.

On the Kotlin side the trigger is a watchdog coroutine suspended in
`awaitCancellation()` on another dispatcher, not `Job.invokeOnCompletion`.
`invokeOnCompletion` fires when the job *completes*, and a job whose body is
blocked in a JNI call cannot complete — the abort would arrive after the decode
it was meant to interrupt, which is to say never.

## Rule 7 — a crash sentinel, because a SIGILL is not catchable

A file is written naming the chosen backend immediately before the first
`llama_decode` of a session, and deleted after the first token. Finding it at
startup means exactly one thing: the previous run entered native code and never
came out.

The response is to skip ggml's directory scan and `ggml_backend_load` the
baseline CPU variant (`libggml-cpu-android_armv8.0_1.so`) by name, which is
built with no optional ARM extensions and therefore cannot execute the
i8mm/SVE/SME instructions a SIGILL comes from. If *that* run also leaves the
sentinel behind, native inference is disabled and the app degrades to
remote-only rather than looping. The policy is a pure function of the sentinel
record and is unit-tested exhaustively.

This matters more here than it usually would: with no arm64 test device, the
first real hardware to run these kernels belongs to a user.

## Error handling across the boundary

Native code never throws C++ exceptions across the JNI frame. Every entry point
is wrapped so that a `std::exception` becomes a thrown Java exception via
`ThrowNew`, and a failure that is expected rather than exceptional (out of
memory for the requested context, an unsupported GGUF version) becomes a status
code the Kotlin side maps to a typed error.

Note that `ThrowNew` sets a *pending* exception and does not unwind: the C++
function must `return` immediately afterwards. Every wrapper does this in one
place so no individual entry point has to remember.

The one thing native code never does is call back into Java. See
[Threading](threading.md) for why that is a load-bearing property rather than a
coincidence.

## Build-time context

The JNI layer compiles only when `-Pollama.nativeSource` is `build` or
`prebuilt`. With the default `none`, `BuildConfig.NATIVE_ENABLED` is `false`,
`StubLlamaEngine` is bound, and nothing above is loaded. The CMake flags used
for the `build` mode, including `GGML_BACKEND_DL` and `GGML_CPU_ALL_VARIANTS`,
are documented in [Native build](../local-inference/native-build.md).

!!! warning "Built, not run"
    The layer described here exists and compiles: `core-llm/src/main/cpp/jni/llama_jni.cpp`
    builds cleanly for arm64-v8a and x86_64 against llama.cpp `b10150`, and the
    Kotlin half — the pull loop, the sentinel state machine, the arbiter, error
    mapping — is covered by 73 unit tests that need no native code.

    None of it has executed on a physical arm64 device. `RegisterNatives`
    surviving a real R8 release output, mmap behaviour under memory pressure,
    two models resident at once, and whether the safe-mode fallback actually
    rescues a device that SIGILLs are all **unverified**. The `androidTest`
    smoke test would establish the load-and-bind half on an emulator; it has not
    been executed either, because no emulator was started. See
    [Verification status](../verification-status.md).
