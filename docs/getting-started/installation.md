# Installation

There are two ways to get OllamaMobile onto a device: install a release APK, or
build one yourself. Both are covered here. Building is not an expert path — the
default build needs no NDK and no submodules.

!!! warning "Status"
    **No release has been published**, so there is nothing to install at 0.1.0,
    and a build you make yourself starts to a placeholder screen: the app has no
    UI, no remote client and no local engine yet. See
    [Verification status](../verification-status.md). The procedure below is
    accurate for the release process as it is configured; it just has not had a
    release to act on.

## Install a release APK

Releases are published on GitHub and nowhere else.

1. Open <https://github.com/jaypetez/ollama-mobile/releases> and pick the latest
   release.
2. Download `ollama-mobile-<version>-arm64-v8a.apk`. Release builds ship
   **arm64-v8a only**; a 32-bit-only phone cannot run it.
3. Verify the download against the `SHA256SUMS` file attached to the release
   before installing:

    ```bash
    sha256sum -c SHA256SUMS --ignore-missing
    ```

4. Allow installation from your browser or file manager when Android prompts,
   then install.

!!! warning "There is no Google Play or F-Droid listing"
    Anything claiming to be OllamaMobile on an app store is not this project.
    Only artefacts attached to a GitHub release under `jaypetez/ollama-mobile`
    are genuine. Signing keys and the exact verification procedure are described
    in [Release process](../release-process.md).

Android will not auto-update a sideloaded app. Watch the repository's releases,
or check for a newer version periodically.

## Build from source

### Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 | The Gradle toolchain is 21; bytecode target is 17. |
| Android SDK Platform | `platforms;android-37.0` | Note the `.0` — `compileSdk` is 37 with minor 0. |
| Android SDK Build-Tools | 36.0.0 | |
| Android SDK cmdline-tools | 22.0 | |
| Gradle | — | Do not install it. Use the wrapper (9.6.1). |
| NDK | 29.0.14206865 | **Only** for `-Pollama.nativeSource=build`. |
| CMake (SDK) | 3.31.0 | **Only** for `-Pollama.nativeSource=build`. |

Install the SDK packages with `sdkmanager`:

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
```

Point the build at your SDK by setting `ANDROID_HOME`, or by creating
`local.properties` at the repository root with `sdk.dir=/path/to/Android/sdk`.

### Clone and build

```bash
git clone https://github.com/jaypetez/ollama-mobile.git
cd ollama-mobile
./gradlew assembleDebug          # gradlew.bat on Windows
```

The APK lands in `app/build/outputs/apk/debug/`. Debug builds carry both
`arm64-v8a` and `x86_64`; the second ABI exists purely so instrumentation tests
can run on an emulator on hosted CI runners, and it is never in a release build.

!!! success "This works with no NDK installed"
    `assembleDebug` with default properties compiles no native code at all, and
    that is verified on the development machine — see
    [Verification status](../verification-status.md). What you get is an APK
    that installs and starts; it does not yet do anything, because the client,
    the engine and the UI are not written.

### If you are going to commit

Enable the version-controlled git hooks once per clone, before your first
commit:

```bash
git config core.hooksPath scripts/hooks
```

This is what makes `scripts/hooks/pre-commit` run, and that hook is the gate
that stops a multi-gigabyte GGUF, a `.so`, an APK or a signing keystore entering
the object database — damage a revert cannot undo, because the blob stays
reachable in history for every future clone. It also runs `spotlessCheck` when
Kotlin or Gradle scripts are staged. Full detail, and the rest of `scripts/`, is
in [Contributing](../contributing.md).

### Building with native inference

```bash
git submodule update --init --depth 1 third_party/llama.cpp
./gradlew assembleDebug -Pollama.nativeSource=build -Pollama.requireNative=true
```

!!! note "Not available yet"
    `third_party/llama.cpp` is not in the repository at 0.1.0. The build fails
    with an explicit message pointing at the `git submodule` command above, and
    that command will not yet find anything to initialise. Native inference
    lands in a later stage; the switch and the plumbing are already in place so
    that nothing else has to change when it does.

Once prebuilt shared objects exist under `core-llm/prebuilt/<abi>/`, the cheap
path is:

```bash
./gradlew assembleDebug -Pollama.nativeSource=prebuilt
```

which skips CMake entirely. See [Native build](../local-inference/native-build.md).

### Release builds

```bash
./gradlew assembleRelease        # APK
./gradlew bundleRelease          # AAB
```

Release signing is opt-in. Without a `keystore.properties` at the repository
root or the `OLLAMA_KEYSTORE_*` environment variables, the build falls back to
the debug key and prints a loud warning. Such an artefact must never be
published; see [Release process](../release-process.md).

## Verify your checkout builds cleanly

These are the same gates CI runs. All of them exist today and pass on the
development machine.

```bash
./gradlew spotlessCheck      # formatting (blocking)
./gradlew lintDebug          # Android Lint (blocking)
./gradlew test               # unit tests, all modules (blocking)
./gradlew checkModuleGraph   # layering rules (blocking)
./gradlew assembleDebug      # (blocking)
./gradlew detekt             # advisory only, never fails the build
```

`./gradlew spotlessApply` fixes formatting violations in place. Do not add
Gradle tasks to this list from memory — if a task is not named above, it does
not exist in this project yet.

## Next

[First run](first-run.md) walks through connecting to a server and starting a
conversation. If the build failed, [Troubleshooting](../troubleshooting.md)
covers the failures that actually happen.
