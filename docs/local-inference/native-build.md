# Building llama.cpp for Android

This page documents how OllamaMobile compiles llama.cpp, and — more usefully —
why each build switch is set the way it is. Most of these flags exist because
the obvious default is wrong on Android specifically.

!!! warning "Status"
    The submodule, `core-llm/src/main/cpp/` and the JNI layer are all present,
    and `./gradlew :core-llm:assembleDebug -Pollama.nativeSource=build
    -Pollama.requireNative=true` has been run successfully on the development
    machine (Windows, NDK 29.0.14206865, SDK CMake 3.31.0). What that produces
    and what was checked about it is recorded in
    [Verification status](../verification-status.md).

    None of it has been executed on a physical arm64 device, because the project
    does not have one. Everything on this page about *runtime* behaviour —
    variant selection, KleidiAI dispatch, throughput — remains unverified.

## Where the source comes from

llama.cpp is a **pinned git submodule** at `third_party/llama.cpp`, not a
vendored copy and not a fetch-at-build-time download.

A submodule pins an exact commit SHA in the superproject tree. That matters more
here than in a typical dependency because llama.cpp's C API is not versioned and
does change: functions get renamed, `llama_context_params` gains and loses
fields, and CMake options are added and removed between releases. A pinned SHA
means the JNI layer in `:core-llm` is compiled against a known header set, and
bumping llama.cpp is a reviewable one-line diff that CI can prove.

```bash
git submodule update --init --depth 1 third_party/llama.cpp
```

`--depth 1` is worth using: a full llama.cpp history is large and none of it is
needed to build a pinned commit. llama.cpp's own `.gitmodules` is empty at the
pinned tag — everything it needs is vendored in-tree under `vendor/` — so there
is nothing to recurse into and `--recurse-submodules` is not used.

### Which commit, and why that one

The submodule is pinned to the released tag **`b10150`** (commit `dee2a846`,
2026-07-27). The reasoning is repeated in `.gitmodules` next to the pin, where
someone bumping it will actually read it:

* **A tag, not `master`.** The C API is unversioned and renames without
  deprecation. Three renames this integration had to be written against, all of
  which break code copied from a tutorial: `llama_kv_self_*` became the
  `llama_memory_*` family, `llama_load_model_from_file` became
  `llama_model_load_from_file`, and `use_mmap` left the params struct in favour
  of `llama_model_params.load_mode` (a `llama_load_mode` enum that also covers
  mlock and direct I/O).
* **Not the newest tag.** Upstream cuts roughly ten `bNNNN` tags a day straight
  off `master`, so the newest one is a commit that is hours old and has been
  exercised by nobody. `b10150` was several days settled when it was pinned —
  long enough for a build break or an ARM CPU backend regression to have been
  reported upstream — and recent enough to carry the current memory and sampler
  APIs and the `common/chat.h` Jinja templating this project depends on.

Bump it with `scripts/update-llamacpp.sh`, which moves the pin. Never with
`git -C third_party/llama.cpp pull`, which leaves the superproject pointing at
a commit nobody reviewed.

!!! danger "Re-read `llama.h` when you bump"
    The JNI layer is written against the header at this exact commit. A bump is
    not a version number change; it is an API review. Start with the functions
    listed above plus the sampler chain, `llama_batch`, `llama_decode`'s return
    codes and `common_chat_templates_apply`.

Upstream is MIT licensed (`Copyright (c) 2023-2024 The ggml authors`) and the
attribution ships in the app's licence screen. See
[the model catalogue page](../models/catalog.md) for the equivalent obligation
around model weights, which is a separate and looser thing.

## How Gradle invokes CMake

The build is driven by AGP's `externalNativeBuild`, configured centrally in
`build-logic/convention/src/main/kotlin/AndroidNativeConventionPlugin.kt` and
applied only to `:core-llm`. No other module has any knowledge of the NDK.

`core-llm/src/main/cpp/CMakeLists.txt` is the entry point. It is a thin file: it
`add_subdirectory()`s the submodule and then defines the single JNI shared
library that wraps it. Everything about *how* llama.cpp is configured is passed
from Gradle as `-D` arguments rather than being written into the CMakeLists,
because that keeps the flag set in one reviewable place next to the comment
explaining it, and keeps `CMakeLists.txt` from drifting out of sync with the
convention plugin.

The toolchain versions come from `gradle/libs.versions.toml` — NDK
`29.0.14206865`, CMake `3.31.0` — resolved through the version catalogue so
there is exactly one place to bump them.

```kotlin
arguments += listOf(
    "-DANDROID_STL=c++_shared",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DBUILD_SHARED_LIBS=ON",
    "-DGGML_BACKEND_DL=ON",
    "-DGGML_CPU_ALL_VARIANTS=ON",
    "-DGGML_NATIVE=OFF",
    "-DGGML_OPENMP=OFF",
    "-DGGML_LLAMAFILE=OFF",
    "-DGGML_CPU_KLEIDIAI=ON",
    "-DLLAMA_BUILD_COMMON=ON",
    "-DLLAMA_BUILD_TESTS=OFF",
    "-DLLAMA_BUILD_EXAMPLES=OFF",
    "-DLLAMA_BUILD_TOOLS=OFF",
    "-DLLAMA_BUILD_SERVER=OFF",
)
cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
```

## The flags, and why

### `BUILD_SHARED_LIBS=ON`

Required, not preferred. `GGML_BACKEND_DL` (below) works by producing each ggml
backend as its own shared object that is `dlopen`ed at runtime. A static build
has nothing to `dlopen`. Turning this off silently collapses the multi-variant
CPU dispatch back to a single baseline build.

The cost is more `.so` files in the APK. That is acceptable; the alternative is
either a baseline-only build that ignores modern ARM instructions, or a build
that crashes on any device lacking them.

### `GGML_BACKEND_DL=ON` and `GGML_CPU_ALL_VARIANTS=ON`

Together these are the whole reason a single APK can run well on both a 2019
mid-range phone and a current flagship.

`GGML_CPU_ALL_VARIANTS=ON` compiles the ggml CPU backend several times over, once
per ARM feature tier (baseline ARMv8-A, plus variants using dotprod, i8mm, fp16
arithmetic, and so on), emitting `libggml-cpu-<variant>.so` for each.
`GGML_BACKEND_DL=ON` makes ggml load these dynamically at startup, probe the
CPU, and pick the best variant the hardware actually supports.

Without this you must choose at build time between compiling for the lowest
common denominator (leaving a large amount of throughput on the table on any
recent SoC) and compiling for i8mm (SIGILL on anything older). Neither is
acceptable when the distribution channel is a single universal APK on GitHub
Releases.

This has a packaging consequence, already handled in
`internal/KotlinAndroid.kt`: `jniLibs.useLegacyPackaging = true`. ggml's loader
enumerates candidate backends by scanning a directory, so the `.so` files must
be extracted to `applicationInfo.nativeLibraryDir` at install time rather than
left compressed inside the APK and loaded in place. This does **not** affect
16 KB page compliance — see below.

The selection policy on our side lives in `:core-ml`, not in `:core-llm`; see
[backends](backends.md).

### `GGML_NATIVE=OFF`

`GGML_NATIVE=ON` means `-march=native`: compile for the machine doing the
compiling. In a cross-compile this is meaningless at best and actively wrong at
worst — the build host is an x86-64 CI runner or a developer laptop, and the
target is an unknown arm64 phone. It must be off. Feature selection is the job
of `GGML_CPU_ALL_VARIANTS` plus runtime dispatch, which knows the answer at the
only time the answer is knowable.

### `GGML_OPENMP=OFF`

This one is a deliberate trade, and the most likely to be questioned.

llama.cpp can parallelise either with OpenMP or with its own internal threadpool.
On desktop Linux, OpenMP is usually the better choice. On Android it is a
liability: the NDK's `libomp` has to be packaged into the APK and loaded
correctly, and getting that wrong produces `UnsatisfiedLinkError` at load time or
— worse — a working debug build and a crashing release build once R8 and the
packaging rules differ. Mismatches between the `libomp.so` the NDK links against
and the one that ends up in `nativeLibraryDir` are a well-known and repeatedly
rediscovered source of crashes in Android llama.cpp integrations.

llama.cpp's own threadpool is not a fallback; it is a fully supported path, and
it gives us something OpenMP does not: direct control over thread count and
affinity from the Kotlin side, which matters on big.LITTLE where spawning one
thread per `availableProcessors()` is the wrong answer. See
[tuning](tuning.md).

So: one fewer shared library, one fewer class of packaging bug, and better
control. The threading discussion is on the tuning page rather than here.

### `GGML_LLAMAFILE=OFF`

The llamafile sgemm kernels are tuned for x86-64 server-class cores. On arm64
the relevant work is done by ggml's own repacking path and by KleidiAI. Enabling
it adds compile time and binary size for no benefit on our only shipping ABI.

### `GGML_CPU_KLEIDIAI=ON`

KleidiAI is ARM's micro-kernel library for matmul on ARM CPUs, integrated into
ggml as an optional CPU path. It is on because it is the right thing to have
available.

!!! warning "KleidiAI does not accelerate every quantisation"
    KleidiAI's ggml integration covers `Q4_0`, `Q8_0`, `F16` and `F32` only. The
    most commonly recommended quant, `Q4_K_M`, is **not** KleidiAI-accelerated.
    This is important enough that it is encoded in the domain model —
    `Quantization.kleidiAiAccelerated` in `:core-model` — so the UI cannot
    accidentally imply otherwise. Read
    [quantization](quantization.md) before assuming this flag makes your
    chosen model faster.

### `LLAMA_BUILD_COMMON=ON`

`common/` is described upstream as example-support code, so the instinct is to
turn it off along with the examples. That instinct is wrong for a chat client.

`llama_chat_apply_template()` in the core library implements a small hard-coded
set of well-known chat formats. It is **not** a Jinja engine. Real GGUF files
carry a `tokenizer.chat_template` string that is genuine Jinja2 — with
conditionals, loops, `tojson`, custom tool-call blocks and, increasingly,
reasoning-content handling. Rendering those correctly requires the templating
support that lives in `common/chat.h` (and the vendored minja engine behind it).

If we skipped it we would have to reimplement Jinja in Kotlin. That is a large,
security-relevant, permanently-out-of-date piece of work, and getting it subtly
wrong does not throw — it produces a prompt with the wrong special tokens, which
degrades output quality in a way that looks like the model being bad. So
`LLAMA_BUILD_COMMON=ON`, and the JNI layer exposes template rendering rather
than reconstructing prompts by string concatenation.

`LLAMA_BUILD_TESTS`, `LLAMA_BUILD_EXAMPLES`, `LLAMA_BUILD_TOOLS` and
`LLAMA_BUILD_SERVER` stay off: they build binaries we never package, and
`LLAMA_BUILD_SERVER` in particular drags in an HTTP stack that would duplicate
what `:server` already does in Kotlin.

### The two things `CMakeLists.txt` decides on its own

Everything above is passed in from Gradle. Two things cannot be, because they
are reactions to what the submodule does:

**KleidiAI is forced off for any ABI that is not `arm64-v8a`.** Upstream's
`if (GGML_CPU_KLEIDIAI)` block in `ggml/src/ggml-cpu/CMakeLists.txt` is not
guarded by architecture: on x86_64 it still fetches KleidiAI and appends NEON
pack kernels to the source list, which then fail to compile. Debug builds
produce x86_64 so instrumentation tests can run on an emulator, so the override
is what keeps that ABI buildable. Nothing is lost — there are no KleidiAI
kernels for x86.

**The common library's target name is probed, not hard-coded.** It was `common`
for years and is `llama-common` at the pinned tag. The CMakeLists does:

```cmake
if(TARGET llama-common)
    set(LLAMA_COMMON_TARGET llama-common)
elseif(TARGET common)
    set(LLAMA_COMMON_TARGET common)
else()
    message(FATAL_ERROR "...")
endif()
```

so the next rename is one legible error at configure time rather than a wall of
undefined references to `common_chat_templates_apply` at link time.

### `LLAMA_CURL` — never pass it

!!! danger "Do not add `-DLLAMA_CURL=OFF`"
    It was removed upstream. Passing it today produces a CMake
    "manually-specified variables were not used by this project" warning and
    nothing else, so it looks harmless — but it is cargo cult, and it implies
    that native code might fetch models over the network. It does not. All model
    acquisition is Kotlin-side in `:core-download`; see
    [downloading](../models/downloading.md). If you find this flag in a diff,
    delete it rather than flipping it.

### `ANDROID_STL=c++_shared`, `-fexceptions`, `-frtti`

One shared libc++ across every `.so` we ship, rather than a static copy per
library — multiple static libc++ instances in one process is undefined behaviour
around exceptions and RTTI. `common/` uses both exceptions and RTTI, so both are
enabled explicitly rather than relying on the NDK default.

## The three `-Pollama.nativeSource` modes

The switch is read in `internal/ProjectExtensions.kt` and defaults to `none`
(also set explicitly in `gradle.properties`).

| Mode | What happens | Needs NDK | Needs submodule |
| --- | --- | --- | --- |
| `none` *(default)* | No native code. `BuildConfig.NATIVE_ENABLED=false`, `StubLlamaEngine` is bound, the app is a pure remote Ollama client. | No | No |
| `build` | CMake configures and compiles llama.cpp from the submodule with the flags above. | Yes | Yes |
| `prebuilt` | No CMake. `.so` files are consumed from `core-llm/prebuilt/<abi>/` as a `jniLibs` source directory. | No | No |

```bash
./gradlew :app:assembleDebug                                  # none
./gradlew :app:assembleDebug -Pollama.nativeSource=prebuilt
./gradlew :app:assembleRelease -Pollama.nativeSource=build
```

`none` being the default is the load-bearing decision. It is what lets a fresh
clone build on a machine that has never installed the NDK, which is what keeps
lint, unit-test and static-analysis CI jobs fast and green. The whole module
graph is arranged to support it: only `:core-llm` depends on the native engine,
everything else depends on `:core-llm-api`, and `checkModuleGraph` fails the
build if that is violated.

`prebuilt` exists so pull-request jobs never pay the llama.cpp compile cost. The
`.so` files are produced by a separate, deliberately gated workflow and dropped
into `core-llm/prebuilt/<abi>/`.

`-Pollama.requireNative=true` turns a silent degradation into a hard failure:

```
-Pollama.requireNative=true but -Pollama.nativeSource=none.
```

Use it in any job whose entire purpose is to prove the native path works.
Without it, a misconfigured CI matrix produces a green build that quietly tested
nothing.

The mode is read from the Gradle property and never from `file(...).exists()`.
That is deliberate: deriving it from the filesystem would make the configuration
cache go stale the instant someone initialised the submodule, and configuration
caching is on for this project.

## 16 KB page size

Android 15 introduced devices with a 16 KB kernel page size, and from Android 16
new and updated apps targeting API 35+ must support it. A shared library whose
ELF `LOAD` segments are aligned to 4 KB will not load on such a device.

NDK r28 and later emit 16 KB-aligned segments by default, and we are on NDK 29.
We pass the flags explicitly anyway, because "it is the default" is not a
property we control and a future toolchain change should not be able to silently
break loading:

```cmake
target_link_options(<target> PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
```

These belong on every native target we produce — the JNI wrapper, `libllama.so`,
`libggml*.so` and each `libggml-cpu-*.so` variant. `core-llm/src/main/cpp/CMakeLists.txt`
sets them with `add_link_options()` at directory scope **before**
`add_subdirectory()`, which every target created afterwards inherits, including
the submodule's. Putting them on our own target with `target_link_options()`
would harden one of the roughly ten shared objects that reach the APK, which is
the same as not doing it.

`-Wl,-z,relro` and `-Wl,-z,now` are set in the same call, for the same reason.

Compile-time hardening is the opposite: it goes on **our target only**, via
`target_compile_options()`. Specifically, `-D_FORTIFY_SOURCE=2` must not be
pushed at directory scope. The NDK already defines it, so a second definition
reaches every one of llama.cpp's translation units as `-Wmacro-redefined` —
harmless until someone sets `LLAMA_FATAL_WARNINGS`, at which point a flag that
looks like hardening is a build break we did not cause. On our own translation
unit it is written `-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2`; the undefine is what
keeps it quiet.

!!! note "Legacy packaging does not undo this"
    `jniLibs.useLegacyPackaging = true` is set so ggml can enumerate backend
    variants in `nativeLibraryDir`. Alignment compliance is a property of ELF
    segment alignment inside the `.so`, not of how the `.so` is stored in the
    APK zip. Zip page-alignment (`zipalign -P 16`) only matters when libraries
    are loaded uncompressed directly from the APK, which is precisely the mode
    we are not using.

### Verifying

Check the `Align` column of every `LOAD` program header. `0x4000` is 16384 and
is what you want; `0x1000` is 4 KB and will fail on a 16 KB device.

```bash
NDK=$ANDROID_HOME/ndk/29.0.14206865
READELF=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf

for so in core-llm/build/intermediates/merged_native_libs/release/out/lib/arm64-v8a/*.so; do
  printf '%s: ' "$(basename "$so")"
  "$READELF" --program-headers "$so" \
    | awk '$1 == "LOAD" { print $NF }' \
    | sort -u | tr '\n' ' '
  echo
done
```

Every line must show `0x4000` and nothing else. On Windows, run the same command
through the Bash shell shipped with Git and substitute
`prebuilt/windows-x86_64/bin/llvm-readelf.exe`.

`scripts/verify-16kb-alignment.sh` wraps exactly this and finds the NDK's
`llvm-readelf` for you:

```bash
scripts/verify-16kb-alignment.sh \
  core-llm/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a
```

It has been run against a real `-Pollama.nativeSource=build` debug build: 14
arm64-v8a libraries and 20 x86_64 libraries, every `LOAD` segment at `0x4000`,
zero misaligned. `llvm-readelf -d` on the same libraries shows `BIND_NOW` and
`FLAGS_1: NOW`, and a `GNU_RELRO` program header, on our library and on
llama.cpp's alike — which is the evidence that the directory-scope link options
really do reach the submodule's targets.

Re-run it whenever llama.cpp is bumped or the NDK changes. It has not been wired
into a Gradle task; there is no such task today.

### What the build actually produces

From one `-Pollama.nativeSource=build` debug build of `:core-llm` (both ABIs,
since debug adds x86_64 for the emulator):

| | arm64-v8a | x86_64 |
| --- | --- | --- |
| ggml CPU feature variants | 7 (`android_armv8.0_1`, `android_armv8.2_1`, `android_armv8.2_2`, `android_armv8.6_1`, `android_armv9.0_1`, `android_armv9.2_1`, `android_armv9.2_2`) | 14 (`x64` … `sapphirerapids`) |
| other `.so` | `libollamamobile_llm`, `libllama`, `libllama-common`, `libggml`, `libggml-base`, `libkleidiai`, `libc++_shared` | the same minus `libkleidiai` |
| total | 14 | 20 |

The arm64 tier names come from `ggml_add_cpu_backend_variant` in
`ggml/src/CMakeLists.txt`, which has a dedicated Android list — the Linux ARM
list is a different set of tags. `libggml-cpu-android_armv8.0_1.so` is the
baseline with no optional extensions, and it is the one the safe-mode fallback
loads by name; see [backends](backends.md).

## Related

* [Backends and acceleration on Android](backends.md) — what the variant
  dispatch actually chooses between, and what NNAPI/LiteRT cannot do.
* [Quantisation](quantization.md) — which formats the KleidiAI flag helps.
* [Memory](memory.md) — deciding whether a model will load at all.
