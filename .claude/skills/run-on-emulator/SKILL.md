---
name: run-on-emulator
description: "Build, install and launch the OllamaMobile app on an Android emulator so it can be tested by hand. Use whenever asked to run, start, launch or open the app, to try a change on a device or emulator, to reproduce something in the UI, or to take a screenshot of a screen."
allowed-tools:
  - Bash
  - PowerShell
  - Read
  - Glob
  - Grep
---

# Run OllamaMobile on the emulator

One command takes a checkout to the app on screen:

```powershell
.\scripts\run-emulator.ps1
```

It resolves the SDK, boots an AVD if none is attached, waits for the boot to
genuinely finish, runs `:app:installDebug`, grants `POST_NOTIFICATIONS`, and
starts `MainActivity`. Read `scripts/run-emulator.ps1` for the detail — its
comment-based help is the reference, this page is the map.

## Flags

| Flag | Use it when |
| --- | --- |
| `-Native` | You need real on-device inference. Builds llama.cpp: `-Pollama.nativeSource=build -Pollama.requireNative=true`. |
| `-SkipBuild` | The emulator is already up and the APK is current — just relaunch. Seconds instead of a Gradle run. |
| `-WipeData` | Testing onboarding, first run, or an empty database. Cold-boots with wiped storage; implies `-Fresh`. |
| `-Fresh` | Boot a second emulator instead of reusing the attached one. |
| `-Logcat` | Stream the app's logcat after launch, filtered to its pid. |
| `-ForwardServerPort` | You want to curl the app's *own* Ollama-compatible server from Windows. |
| `-Avd <name>` | More than one AVD exists. Without it the script lists them and stops rather than guessing. |
| `-Serial <id>` | More than one device is attached. |
| `-CreateAvd` | No AVD exists yet and you want the script to make one. |

Exit status follows `scripts/bench.sh`: `0` running, `2` environment problem,
otherwise Gradle's own code.

## What a default build can and cannot do

The default is `-Pollama.nativeSource=none`, which binds `StubLlamaEngine`. That
build exercises the whole app **except** local inference:

- **Works**: the entire UI, remote Ollama and OpenAI-compatible clients, subnet
  discovery, the embedded server, downloads, Room persistence, settings.
- **Does not work**: running a model on the phone. `StubLlamaEngine.generate`
  emits `InferenceEvent.Failed` carrying `AppError.Engine.NotAvailable`.

**A local chat that reports `Engine.NotAvailable` in a default build is correct
behaviour, not a bug.** The stub is deliberately loud rather than a silent no-op
— see the KDoc on `core-llm/.../internal/StubLlamaEngine.kt`. Use `-Native` when
the thing under test is inference itself.

## Getting a working chat in two minutes

The emulator reaches the Windows host at `10.0.2.2`, and the app permits it
without any config change:

1. Restart Ollama on Windows with `OLLAMA_HOST=0.0.0.0:11434`. It binds
   `127.0.0.1` by default and will not answer the emulator.
2. In the app: **Servers → Add server → `10.0.2.2:11434`**.
3. Pick a model the server has, and chat.

This works because `LanOnlyGuard.classifyV4`
(`core-common/src/main/kotlin/.../net/LanOnlyGuard.kt`) classifies `10/8` as
`PRIVATE`, and `app/src/main/res/xml/network_security_config.xml` permits
cleartext at the platform layer. Nothing has to be relaxed, and nothing about
the network policy should be edited to make this work — if it fails, the cause
is elsewhere.

## Hitting the app's own server from Windows

```powershell
.\scripts\run-emulator.ps1 -ForwardServerPort
# switch the server on in-app, then curl the port the script reports:
curl http://127.0.0.1:11434/api/tags
```

Host port 11434 is usually already held by Ollama on the same machine, so the
script walks up to the first free host port and tells you which one it bound.
Only the host side moves; the device side is always 11434.

`docs/server/endpoints.md` lists what it serves.

## Traps

- **There is no `-Release` option, on purpose.** `Abis.release = setOf(ARM64)`
  in `build-logic/.../internal/ProjectExtensions.kt`, so a release APK has no
  `x86_64` slice and dies with `INSTALL_FAILED_NO_MATCHING_ABIS` on a normal
  emulator. Only debug carries both ABIs.
- **`POST_NOTIFICATIONS` is a runtime permission on API 33+.** Ungranted, every
  foreground-service notification (inference, server, downloads) is invisible
  and the services look like they never started. The script grants it; if you
  install by hand, grant it by hand.
- **`-Native` compiles llama.cpp twice.** Debug carries `arm64-v8a` and
  `x86_64` and there is no per-ABI Gradle property, so the first build pays for
  an arm64 slice the emulator never loads. Expect it to be slow once.
- **The installed package is suffixed, the activity class is not.**
  `applicationIdSuffix = ".debug"` means `pm grant`, `pidof` and `pm path` want
  `io.github.jaypetez.ollamamobile.debug`, while `am start` wants
  `…ollamamobile.debug/io.github.jaypetez.ollamamobile.MainActivity`. Mixing
  them up gives `Error type 3: Activity class does not exist` immediately after
  a successful install. The script asks the device via
  `cmd package resolve-activity --brief` rather than trusting a compiled-in name.
- **`adb wait-for-device` is not enough** — it returns while the boot animation
  is still running, and an `am start` then fails with `Error type 3`. The script
  polls `sys.boot_completed` and `init.svc.bootanim` instead.

## Logs and screenshots

The installed package is **`io.github.jaypetez.ollamamobile.debug`** — the debug
build type sets `applicationIdSuffix = ".debug"`. The activity class keeps the
unsuffixed namespace, because a suffix moves the applicationId and never the
manifest namespace:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat --pid=(& $adb shell pidof -s io.github.jaypetez.ollamamobile.debug)

# Screenshot: capture on the device and pull it.
& $adb shell screencap -p /sdcard/shot.png
& $adb pull /sdcard/shot.png
```

**Do not use `adb exec-out screencap -p > shot.png` from PowerShell.** The `>`
operator is text-mode: it prepends a BOM and replaces every byte that is not
valid UTF-8, including the PNG magic, and the file will not open. It is fine
from Git Bash.

Crash capture also writes to app storage the user can read — see
`core-common`'s crash capture and the in-app API inspector, which are the
supported local diagnostics. There is no telemetry and none may be added.

## When it fails

| Symptom | Cause |
| --- | --- |
| `Cannot locate the Android SDK` | Neither `ANDROID_HOME` nor `ANDROID_SDK_ROOT` is set and `local.properties` has no `sdk.dir`. Run `.\scripts\setup-dev-env.ps1`. |
| `No AVD exists` | Rerun with `-CreateAvd`, or use the printed `avdmanager` command. |
| Boot timed out | A cold boot of a fresh AVD can exceed the timeout. Leave the emulator running and rerun with `-SkipBuild`. |
| `NDK <version> is not installed` (with `-Native`) | `.\scripts\setup-ndk.ps1`. |
| `third_party/llama.cpp is empty` (with `-Native`) | `git submodule update --init --depth 1 third_party/llama.cpp`. |
| Gradle fails, app not installed | A real build failure. The script exits with Gradle's own code and installs nothing. |
