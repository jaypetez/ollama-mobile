<#
.SYNOPSIS
    Builds, installs and launches OllamaMobile on an Android emulator.

.DESCRIPTION
    Takes a checkout from source to the app on screen in one command. Every
    step between `assembleDebug` and a running app is here, including the ones
    that are easy to get wrong:

        * resolving the SDK when ANDROID_HOME is not set,
        * waiting for sys.boot_completed rather than for `adb wait-for-device`,
          which returns long before there is a UI to launch into,
        * granting POST_NOTIFICATIONS, without which every foreground-service
          notification the app posts is silently invisible on API 33+.

    Installs the debug variant, and only the debug variant. Release builds are
    arm64-v8a only (Abis.release in build-logic), so an installRelease against
    an x86_64 emulator fails with INSTALL_FAILED_NO_MATCHING_ABIS. That is why
    there is no -Release switch: it could not work.

    By default this builds with -Pollama.nativeSource=none, the project
    default, which binds StubLlamaEngine. The UI, the remote Ollama client and
    the embedded server are all fully exercised by such a build; only local
    inference is absent, and it reports Engine.NotAvailable rather than
    pretending. Pass -Native for real on-device llama.cpp.

    Language choice: PowerShell. It reads sdk.dir out of local.properties,
    drives emulator.exe and adb.exe, and has to work before Git Bash can be
    assumed to be on PATH. Every path it touches is a Windows-side concern. CI
    never runs it -- there is no emulator in the blocking workflow.

.PARAMETER Avd
    Name of the AVD to boot. Defaults to the only one `emulator -list-avds`
    reports; if there are several, the script lists them and stops rather than
    guessing. Ignored when a device is already attached and -Fresh is absent.

.PARAMETER Serial
    adb serial of an already-running device or emulator to target. Skips
    booting entirely. Required when more than one device is attached.

.PARAMETER Native
    Build real llama.cpp: -Pollama.nativeSource=build -Pollama.requireNative=true.
    Preflights the NDK, CMake and the third_party/llama.cpp submodule first.

    Slow the first time, and slower than it needs to be: debug carries both
    arm64-v8a and x86_64 (Abis.debug), there is no per-ABI Gradle property, so
    an arm64 slice the emulator will never load gets compiled too.

.PARAMETER SkipBuild
    Do not run Gradle. Boots and launches whatever is already installed.

.PARAMETER Fresh
    Boot a new emulator even if one is already attached.

.PARAMETER WipeData
    Cold boot with -wipe-data, so the app starts against empty storage. This is
    how to test onboarding, first-run and the Room schema from version 1.
    Implies -Fresh.

.PARAMETER ForwardServerPort
    adb forward tcp:11434 -> tcp:11434, so the app's own Ollama-compatible
    server can be reached from the host once it has been switched on in-app:

        curl http://127.0.0.1:11434/api/tags

.PARAMETER Logcat
    Stream the app's logcat after launch, filtered to its pid. Ctrl-C to stop;
    the app keeps running.

.PARAMETER CreateAvd
    Create an AVD when none exists, from the first installed x86_64 system
    image. Without this the script prints the avdmanager command and stops.

.PARAMETER SdkRoot
    Android SDK root. Defaults to ANDROID_HOME, then ANDROID_SDK_ROOT, then
    sdk.dir from local.properties.

.EXAMPLE
    .\scripts\run-emulator.ps1

.EXAMPLE
    .\scripts\run-emulator.ps1 -WipeData -Logcat

.EXAMPLE
    .\scripts\run-emulator.ps1 -Native -ForwardServerPort

.EXAMPLE
    .\scripts\run-emulator.ps1 -SkipBuild

.NOTES
    Exit status, following scripts/bench.sh:
      0  the app is installed and running
      2  environment problem -- no SDK, no adb, no AVD, boot timed out
      *  whatever Gradle returned

    TALKING TO AN OLLAMA SERVER ON THIS MACHINE.
    The emulator reaches the Windows host at 10.0.2.2. Start Ollama with
    OLLAMA_HOST=0.0.0.0:11434 (it binds 127.0.0.1 by default and will not
    answer the emulator), then add 10.0.2.2:11434 under Servers in the app.
    LanOnlyGuard classifies 10/8 as private and the network security config
    permits cleartext, so nothing has to be relaxed for this to work.

    Project: OllamaMobile   https://github.com/jaypetez/ollama-mobile
#>
[CmdletBinding()]
param(
    [string]$Avd,
    [string]$Serial,
    [switch]$Native,
    [switch]$SkipBuild,
    [switch]$Fresh,
    [switch]$WipeData,
    [switch]$ForwardServerPort,
    [switch]$Logcat,
    [switch]$CreateAvd,
    [string]$SdkRoot
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Catalog = Join-Path $RepoRoot 'gradle\libs.versions.toml'

# The debug build type carries applicationIdSuffix = ".debug"
# (AndroidApplicationConventionPlugin), so the *installed package* is suffixed
# while the activity class is not -- a suffix moves the applicationId, never the
# manifest namespace. Getting this wrong is how `am start` returns "Error type 3:
# Activity class does not exist" straight after a successful install.
$Namespace = 'io.github.jaypetez.ollamamobile'
$ApplicationId = "$Namespace.debug"
$FallbackActivity = "$ApplicationId/$Namespace.MainActivity"
$ServerPort = 11434
$BootTimeoutSeconds = 240
$DeviceTimeoutSeconds = 90

# --- helpers ---------------------------------------------------------------

function Fail {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ''
    Write-Host "ERROR: $Message" -ForegroundColor Red
    Write-Host ''
    exit 2
}

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "==> $Message" -ForegroundColor Cyan
}

<#
Runs a native executable and returns its exit code and combined output.

Copied in spirit from setup-ndk.ps1: under Windows PowerShell 5.1 a native
program's stderr arrives as ErrorRecords when redirected, which both decorates
the text and sets $? to false on an exit code of 0. Continue plus ToString()
is what makes the output usable.
#>
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String
        # Out-String returns $null for zero objects, and several callers Trim()
        # this. `pidof` on a process that is not running is exactly that case,
        # which is the moment the script most needs to stay alive to report it.
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = [string]$output }
    } catch {
        return [pscustomobject]@{ ExitCode = -1; Output = $_.Exception.Message }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-CatalogVersion {
    param([Parameter(Mandatory = $true)][string]$Alias)
    if (-not (Test-Path -LiteralPath $Catalog)) {
        Fail "Version catalogue not found at $Catalog. Run this from inside the repository."
    }
    $pattern = '^\s*' + [regex]::Escape($Alias) + '\s*=\s*"([^"]+)"'
    $match = Select-String -LiteralPath $Catalog -Pattern $pattern | Select-Object -First 1
    if (-not $match) { Fail "Version alias '$Alias' is missing from $Catalog." }
    return $match.Matches[0].Groups[1].Value
}

function Resolve-SdkRoot {
    if ($SdkRoot) { return $SdkRoot.TrimEnd('\') }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME.TrimEnd('\') }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT.TrimEnd('\') }

    $file = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $file) {
        $match = Select-String -LiteralPath $file -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
        if ($match) {
            # A Java properties file escapes the drive colon and every
            # separator: sdk.dir=C\:\\Users\\me\\AppData\\Local\\Android\\Sdk.
            $raw = $match.Matches[0].Groups[1].Value.Trim()
            return (($raw -replace '\\:', ':') -replace '\\\\', '\').TrimEnd('\')
        }
    }

    Fail @"
Cannot locate the Android SDK.
Set ANDROID_HOME, or add sdk.dir to local.properties, or pass -SdkRoot.
    .\scripts\setup-dev-env.ps1     reports what is missing and how to install it
"@
}

<#
Serials of everything adb considers usable. `adb devices` lists offline and
unauthorized entries too, and installing to one of those fails in a way that
reads like a build error, so they are filtered out here.
#>
function Get-AttachedSerials {
    param([Parameter(Mandatory = $true)][string]$Adb)
    $result = Invoke-Native -FilePath $Adb -Arguments @('devices')
    if ($result.ExitCode -ne 0) { Fail "adb devices failed: $($result.Output)" }

    $serials = @()
    foreach ($line in ($result.Output -split "`r?`n")) {
        if ($line -match '^(\S+)\s+device$') { $serials += $Matches[1] }
    }
    return , $serials
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    return Invoke-Native -FilePath $Adb -Arguments (@('-s', $DeviceSerial) + $Arguments)
}

function Get-Prop {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $result = Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Arguments @('shell', 'getprop', $Name)
    if ($result.ExitCode -ne 0) { return '' }
    return $result.Output.Trim()
}

<#
Waits for a serial that was not attached before the emulator was started.

Comparing against a snapshot rather than taking "the first serial adb reports"
means an emulator already running for something else is never hijacked.
#>
function Wait-ForNewSerial {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Before
    )
    $deadline = (Get-Date).AddSeconds($DeviceTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($serial in (Get-AttachedSerials -Adb $Adb)) {
            if ($Before -notcontains $serial) { return $serial }
        }
        Start-Sleep -Seconds 2
    }
    Fail "No new device appeared within $DeviceTimeoutSeconds seconds. Is the emulator window open? Check for a PANIC message in it."
}

<#
Blocks until Android is genuinely up.

`adb wait-for-device` only waits for adbd, which is running minutes before the
launcher exists; an `am start` issued then fails with "Error type 3". Both
signals are checked because sys.boot_completed can flip to 1 while the boot
animation is still on screen.
#>
function Wait-ForBoot {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial
    )
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $booted = Get-Prop -Adb $Adb -DeviceSerial $DeviceSerial -Name 'sys.boot_completed'
        $bootanim = Get-Prop -Adb $Adb -DeviceSerial $DeviceSerial -Name 'init.svc.bootanim'
        if ($booted -eq '1' -and $bootanim -ne 'running') { return }
        Start-Sleep -Seconds 3
    }
    Fail "$DeviceSerial did not finish booting within $BootTimeoutSeconds seconds. A cold boot of a fresh AVD can genuinely take longer -- rerun with -SkipBuild once the emulator is up."
}

function Get-AvdNames {
    param([Parameter(Mandatory = $true)][string]$Emulator)
    $result = Invoke-Native -FilePath $Emulator -Arguments @('-list-avds')
    if ($result.ExitCode -ne 0) { Fail "emulator -list-avds failed: $($result.Output)" }

    # The emulator prefixes diagnostics to the same stream ("INFO | ...") and
    # an AVD name can never contain whitespace, so that is the discriminator.
    $names = @()
    foreach ($line in ($result.Output -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -and $trimmed -notmatch '\s') { $names += $trimmed }
    }
    return , $names
}

# --- preflight -------------------------------------------------------------

$sdk = Resolve-SdkRoot
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$gradlew = Join-Path $RepoRoot 'gradlew.bat'

Write-Host ''
Write-Host 'OllamaMobile emulator run' -ForegroundColor White
Write-Host "  SDK root : $sdk"
if ($SkipBuild) {
    # Nothing is being built, so claiming an engine here would be a guess --
    # and a wrong one for anyone who ran -Native and then relaunched.
    Write-Host '  Engine   : whatever is already installed (-SkipBuild)'
} elseif ($Native) {
    Write-Host '  Engine   : native llama.cpp (-Pollama.nativeSource=build)'
} else {
    Write-Host '  Engine   : stub (default; local inference reports Engine.NotAvailable)'
}
Write-Host ''

if (-not (Test-Path -LiteralPath $adb)) {
    Fail @"
adb not found at $adb
Install it with:  sdkmanager "platform-tools"
Or run .\scripts\setup-dev-env.ps1 to see everything that is missing.
"@
}
if (-not (Test-Path -LiteralPath $emulator)) {
    Fail @"
emulator not found at $emulator
Install it with:  sdkmanager "emulator"
"@
}
if (-not (Test-Path -LiteralPath $gradlew)) {
    Fail "gradlew.bat not found at $gradlew. Run this from inside the repository."
}

if ($Native) {
    $ndkVersion = Get-CatalogVersion 'ndk'
    $cmakeVersion = Get-CatalogVersion 'cmake'
    $submodule = Join-Path $RepoRoot 'third_party\llama.cpp'

    if (-not (Test-Path -LiteralPath (Join-Path $sdk "ndk\$ndkVersion"))) {
        Fail "NDK $ndkVersion is not installed. Run .\scripts\setup-ndk.ps1"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $sdk "cmake\$cmakeVersion"))) {
        Fail "CMake $cmakeVersion is not installed. Run .\scripts\setup-ndk.ps1"
    }
    $submodulePopulated = $false
    if (Test-Path -LiteralPath $submodule) {
        $submodulePopulated = @(Get-ChildItem -LiteralPath $submodule -Force).Count -gt 0
    }
    if (-not $submodulePopulated) {
        Fail @"
third_party/llama.cpp is empty. Initialise the submodule first:
    git submodule update --init --depth 1 third_party/llama.cpp
"@
    }
    Write-Host "  NDK $ndkVersion and CMake $cmakeVersion present, submodule populated." -ForegroundColor DarkGray
    Write-Host ''
}

if ($WipeData) { $Fresh = $true }

# --- choose or boot a device -----------------------------------------------

$attached = Get-AttachedSerials -Adb $adb

if ($Serial) {
    if ($attached -notcontains $Serial) {
        Fail "No usable device with serial '$Serial'. Attached: $(if ($attached.Count) { $attached -join ', ' } else { 'none' })"
    }
    $target = $Serial
    Write-Step "Using attached device $target"
} elseif ($attached.Count -ge 1 -and -not $Fresh) {
    if ($attached.Count -gt 1) {
        Fail @"
$($attached.Count) devices are attached and -Serial was not given:
    $($attached -join "`n    ")
Pick one with -Serial <id>, or pass -Fresh to boot another emulator.
"@
    }
    $target = $attached[0]
    Write-Step "Reusing attached device $target (pass -Fresh to boot a new emulator)"
} else {
    $avdNames = Get-AvdNames -Emulator $emulator

    if ($avdNames.Count -eq 0) {
        $image = ''
        $imageRoot = Join-Path $sdk 'system-images'
        if (Test-Path -LiteralPath $imageRoot) {
            $candidate = Get-ChildItem -LiteralPath $imageRoot -Recurse -Depth 2 -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -eq 'x86_64' } | Select-Object -First 1
            if ($candidate) {
                # <sdk>/system-images/android-36/google_apis_playstore/x86_64
                #   -> system-images;android-36;google_apis_playstore;x86_64
                $relative = $candidate.FullName.Substring($imageRoot.Length).Trim('\')
                $image = 'system-images;' + ($relative -replace '\\', ';')
            }
        }
        if (-not $image) {
            Fail @"
No AVD exists and no x86_64 system image is installed. Install one, then rerun
with -CreateAvd:
    sdkmanager "system-images;android-36;google_apis_playstore;x86_64"
"@
        }

        $avdManager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
        $newAvdName = 'OllamaMobile_x86_64'
        if (-not $CreateAvd) {
            Fail @"
No AVD exists. Create one with:
    & "$avdManager" create avd --name $newAvdName --package "$image" --device pixel_7
Or rerun this script with -CreateAvd to do exactly that.
"@
        }
        if (-not (Test-Path -LiteralPath $avdManager)) {
            Fail @"
avdmanager not found at $avdManager
Install it with:  sdkmanager "cmdline-tools;latest"
"@
        }

        Write-Step "Creating AVD $newAvdName from $image"
        $create = Invoke-Native -FilePath $avdManager -Arguments @(
            'create', 'avd', '--name', $newAvdName, '--package', $image, '--device', 'pixel_7', '--force'
        )
        if ($create.ExitCode -ne 0) { Fail "avdmanager create avd failed:`n$($create.Output)" }
        $avdNames = @($newAvdName)
    }

    if ($Avd) {
        if ($avdNames -notcontains $Avd) {
            Fail @"
No AVD named '$Avd'. Available:
    $($avdNames -join "`n    ")
"@
        }
        $chosen = $Avd
    } elseif ($avdNames.Count -eq 1) {
        $chosen = $avdNames[0]
    } else {
        Fail @"
$($avdNames.Count) AVDs exist and -Avd was not given:
    $($avdNames -join "`n    ")
Pick one with -Avd <name>.
"@
    }

    $emulatorArgs = @('-avd', $chosen, '-netdelay', 'none', '-netspeed', 'full')
    if ($WipeData) { $emulatorArgs += '-wipe-data' }

    Write-Step "Booting $chosen$(if ($WipeData) { ' (wiping data)' })"
    # WorkingDirectory matters: emulator.exe resolves its own libraries and
    # system path relative to where it was launched from, and started from the
    # repository root it dies with "PANIC: Broken AVD system path".
    Start-Process -FilePath $emulator -ArgumentList $emulatorArgs -WorkingDirectory (Split-Path -Parent $emulator) | Out-Null

    $target = Wait-ForNewSerial -Adb $adb -Before $attached
    Write-Host "    device $target appeared; waiting for Android to finish booting" -ForegroundColor DarkGray
}

# Every path lands here, including the reuse-an-attached-device one: a device
# can be listed by adb while it is still on the boot animation.
Wait-ForBoot -Adb $adb -DeviceSerial $target

$abi = Get-Prop -Adb $adb -DeviceSerial $target -Name 'ro.product.cpu.abi'
$sdkLevel = Get-Prop -Adb $adb -DeviceSerial $target -Name 'ro.build.version.sdk'
Write-Host "    $target is up: API $sdkLevel, $abi" -ForegroundColor DarkGray
if ($abi -and $abi -ne 'x86_64') {
    Write-Warning "Device ABI is $abi, not x86_64. The debug APK carries arm64-v8a as well, so this should still install -- but an arm64 image under emulation is slow."
}

# --- build and install -----------------------------------------------------

if ($SkipBuild) {
    Write-Step 'Skipping the build (-SkipBuild)'

    $installed = Invoke-Adb -Adb $adb -DeviceSerial $target -Arguments @('shell', 'pm', 'path', $ApplicationId)
    if ($installed.ExitCode -ne 0 -or -not ($installed.Output -match 'package:')) {
        Fail "$ApplicationId is not installed on $target, and -SkipBuild means nothing will install it. Rerun without -SkipBuild."
    }
} else {
    $gradleArgs = @(':app:installDebug')
    if ($Native) {
        $gradleArgs += '-Pollama.nativeSource=build'
        $gradleArgs += '-Pollama.requireNative=true'
    }

    Write-Step "gradlew $($gradleArgs -join ' ')"
    if ($Native) {
        Write-Host '    First native build compiles llama.cpp for arm64-v8a and x86_64. Expect this to take a while.' -ForegroundColor DarkGray
    }

    # installDebug picks its target from ANDROID_SERIAL. Without it AGP fails
    # outright when more than one device is attached.
    $previousSerial = $env:ANDROID_SERIAL
    $env:ANDROID_SERIAL = $target
    try {
        # Output is deliberately not captured: a native build is long enough
        # that watching it matters more than tidy output.
        & $gradlew @gradleArgs
        $gradleExit = $LASTEXITCODE
    } finally {
        $env:ANDROID_SERIAL = $previousSerial
    }

    if ($gradleExit -ne 0) {
        Write-Host ''
        Write-Host "ERROR: Gradle exited with $gradleExit. The app was not installed." -ForegroundColor Red
        Write-Host ''
        exit $gradleExit
    }
}

# --- permissions, launch, extras -------------------------------------------

# API 33+ makes this a runtime permission, and the inference, server and
# download foreground services all post notifications. Ungranted, those
# services look like they never started. Failure is not fatal: on an older
# image the permission is install-time and pm grant refuses it.
$grant = Invoke-Adb -Adb $adb -DeviceSerial $target -Arguments @(
    'shell', 'pm', 'grant', $ApplicationId, 'android.permission.POST_NOTIFICATIONS'
)
if ($grant.ExitCode -eq 0) {
    Write-Step 'Granted POST_NOTIFICATIONS'
} else {
    Write-Host "    POST_NOTIFICATIONS not granted ($($grant.Output.Trim())); foreground-service notifications may not appear." -ForegroundColor DarkGray
}

# Ask the device which component the LAUNCHER intent filter actually resolves
# to, rather than trusting a name compiled into this script: a rename in the
# manifest then cannot silently break the launch. --brief prints the component
# on its own line; the constructed name is only a fallback.
$launchComponent = $FallbackActivity
$resolved = Invoke-Adb -Adb $adb -DeviceSerial $target -Arguments @(
    'shell', 'cmd', 'package', 'resolve-activity', '--brief', $ApplicationId
)
if ($resolved.ExitCode -eq 0) {
    foreach ($line in ($resolved.Output -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$') { $launchComponent = $trimmed }
    }
}

Write-Step "Launching $launchComponent"
$start = Invoke-Adb -Adb $adb -DeviceSerial $target -Arguments @('shell', 'am', 'start', '-n', $launchComponent)
if ($start.ExitCode -ne 0 -or $start.Output -match 'Error type|does not exist|Exception') {
    Fail "am start failed:`n$($start.Output)"
}

$forwardedHostPort = 0
if ($ForwardServerPort) {
    # Host port 11434 is usually already taken -- by Ollama, on the very machine
    # someone testing this app is most likely running it. That is the normal
    # case rather than an error, so walk up to the first host port that binds.
    # Only the host side moves; the device side is always the server's own port.
    $forward = $null
    foreach ($hostPort in $ServerPort..($ServerPort + 5)) {
        $forward = Invoke-Adb -Adb $adb -DeviceSerial $target -Arguments @(
            'forward', "tcp:$hostPort", "tcp:$ServerPort"
        )
        if ($forward.ExitCode -eq 0) {
            $forwardedHostPort = $hostPort
            break
        }
    }
    if ($forwardedHostPort -eq 0) {
        Write-Warning "adb forward could not bind a host port in $ServerPort..$($ServerPort + 5): $($forward.Output.Trim())"
    } elseif ($forwardedHostPort -ne $ServerPort) {
        Write-Step "Forwarded host 127.0.0.1:$forwardedHostPort -> device $ServerPort (host $ServerPort is in use, most likely by Ollama itself)"
    } else {
        Write-Step "Forwarded host 127.0.0.1:$ServerPort -> device $ServerPort"
    }
}

# `am start` returns once the intent is dispatched, which is before zygote has
# forked the process -- reliably so on a cold start, and more so with the
# native build, where the first frame waits on dlopen of libllama. Asking once
# reports a perfectly healthy launch as a crash, so poll briefly.
$appPid = ''
$pidDeadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $pidDeadline) {
    $pidResult = Invoke-Adb -Adb $adb -DeviceSerial $target -Arguments @('shell', 'pidof', '-s', $ApplicationId)
    $appPid = $pidResult.Output.Trim()
    if ($appPid) { break }
    Start-Sleep -Milliseconds 500
}

Write-Host ''
if ($appPid) {
    Write-Host "OllamaMobile is running on $target (pid $appPid)." -ForegroundColor Green
} else {
    Write-Warning "am start reported success but $ApplicationId has no process. Check logcat for a crash on startup."
}

Write-Host ''
Write-Host 'Next:' -ForegroundColor White
Write-Host '  Chat against Ollama on this machine (the fastest route to a working chat):'
Write-Host '    1. Restart Ollama with OLLAMA_HOST=0.0.0.0:11434 -- it binds 127.0.0.1'
Write-Host '       by default and will not answer the emulator.'
Write-Host '    2. In the app: Servers -> Add server -> 10.0.2.2:11434'
if (-not $Native -and -not $SkipBuild) {
    Write-Host ''
    Write-Host '  Local models will report Engine.NotAvailable in this build. That is'
    Write-Host '    correct -- it is StubLlamaEngine. Rerun with -Native for real inference.'
}
Write-Host ''
if ($forwardedHostPort -ne 0) {
    Write-Host "  Reach the app's own server: switch it on in-app, then"
    Write-Host "    curl http://127.0.0.1:$forwardedHostPort/api/tags"
} else {
    Write-Host "  Reach the app's own server from here: rerun with -ForwardServerPort,"
    Write-Host "    switch the server on in-app, then curl the port it reports."
}
Write-Host ''
Write-Host '  Logs       : ' -NoNewline
if ($appPid) {
    Write-Host "& `"$adb`" -s $target logcat --pid=$appPid" -ForegroundColor DarkGray
} else {
    # No pid to filter on, so fall back to the crash buffer -- which is what
    # anyone reading logs at this point actually needs.
    Write-Host "& `"$adb`" -s $target logcat -b crash -d" -ForegroundColor DarkGray
}
# `exec-out screencap -p > file` is the usual incantation and it does NOT work
# here: PowerShell's redirection operator is text-mode, so it prepends a BOM and
# replaces every invalid UTF-8 byte -- including the PNG magic -- leaving an
# unopenable file. Capture on the device and pull the bytes instead.
Write-Host '  Screenshot : ' -NoNewline
Write-Host "& `"$adb`" -s $target shell screencap -p /sdcard/shot.png; & `"$adb`" -s $target pull /sdcard/shot.png" -ForegroundColor DarkGray
Write-Host '  Reinstall  : ' -NoNewline
Write-Host ".\scripts\run-emulator.ps1 -SkipBuild" -ForegroundColor DarkGray
Write-Host ''

if ($Logcat) {
    if (-not $appPid) { Fail 'Cannot stream logcat: the app has no process.' }
    Write-Step "Streaming logcat for pid $appPid (Ctrl-C stops the stream, not the app)"
    Write-Host ''
    & $adb -s $target logcat --pid=$appPid
}

exit 0
