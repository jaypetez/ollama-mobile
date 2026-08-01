<#
.SYNOPSIS
    Read-only readiness check for an OllamaMobile development machine.

.DESCRIPTION
    Prints a pass/fail table for everything the build needs and, for each
    missing item, the exact command that installs it. It never installs,
    downloads, edits or deletes anything itself: the point is that a developer
    can run it on a machine they do not fully trust the state of and get an
    honest answer.

    The only unavoidable side effect is -SkipGradle's opposite: invoking
    gradlew.bat makes the Gradle wrapper download its distribution into
    GRADLE_USER_HOME if it is not already cached. Pass -SkipGradle to avoid
    even that.

    Language choice: PowerShell. Every item inspected here is a Windows-side
    concern (JAVA_HOME, ANDROID_HOME, local.properties, the SDK layout under
    %LOCALAPPDATA%), and this is the script a developer runs before anything
    else works, so it must not depend on Git Bash being on PATH. CI is Linux
    and does not run this script -- CI asserts the same facts by failing the
    build.

.PARAMETER SkipGradle
    Skip the './gradlew --version' probe. Use on a slow link or when you only
    want the SDK inventory.

.PARAMETER SdkRoot
    Override the Android SDK location. Normally resolved from ANDROID_HOME,
    then ANDROID_SDK_ROOT, then sdk.dir in local.properties.

.EXAMPLE
    .\scripts\setup-dev-env.ps1

.EXAMPLE
    .\scripts\setup-dev-env.ps1 -SkipGradle

.OUTPUTS
    Exit code 0 when every required check passes, 1 otherwise. WARN rows do
    not fail the run; they mark things that are optional today (the docs site
    tooling, the llama.cpp submodule) but will matter later.

.NOTES
    Project: OllamaMobile   https://github.com/jaypetez/ollama-mobile
    Expected versions are read from gradle/libs.versions.toml so this script
    cannot drift from the build.
#>
[CmdletBinding()]
param(
    [switch]$SkipGradle,
    [string]$SdkRoot
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Catalog = Join-Path $RepoRoot 'gradle\libs.versions.toml'

# --- infrastructure --------------------------------------------------------

$Results = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet('OK', 'FAIL', 'WARN')][string]$Status,
        [string]$Detail = '',
        [string]$Fix = ''
    )
    $Results.Add([pscustomobject]@{
            Name   = $Name
            Status = $Status
            Detail = $Detail
            Fix    = $Fix
        }) | Out-Null
}

<#
Runs an external program without letting a non-zero exit code or stderr output
turn into a terminating error. $ErrorActionPreference = 'Stop' plus '2>&1' on a
native command is a known Windows PowerShell 5.1 trap (NativeCommandError), so
the preference is relaxed for the duration of the call only.
#>
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $pushed = $false
    try {
        if ($WorkingDirectory) { Push-Location -LiteralPath $WorkingDirectory; $pushed = $true }
        # ToString() before Out-String: stderr lines arrive as ErrorRecords
        # under '2>&1' and would otherwise be rendered with the full
        # "CategoryInfo / FullyQualifiedErrorId" decoration. java -version and
        # git both write perfectly ordinary output to stderr.
        $output = & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
    } catch {
        return [pscustomobject]@{ ExitCode = -1; Output = $_.Exception.Message }
    } finally {
        if ($pushed) { Pop-Location }
        $ErrorActionPreference = $previous
    }
}

function Get-CatalogVersion {
    param([Parameter(Mandatory = $true)][string]$Alias)
    if (-not (Test-Path -LiteralPath $Catalog)) {
        throw "Version catalogue not found at $Catalog. Are you running this from inside the repository?"
    }
    $pattern = '^\s*' + [regex]::Escape($Alias) + '\s*=\s*"([^"]+)"'
    $match = Select-String -LiteralPath $Catalog -Pattern $pattern | Select-Object -First 1
    if (-not $match) {
        throw "Version alias '$Alias' is missing from $Catalog."
    }
    return $match.Matches[0].Groups[1].Value
}

function Get-SdkDirFromLocalProperties {
    $file = Join-Path $RepoRoot 'local.properties'
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    $match = Select-String -LiteralPath $file -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if (-not $match) { return $null }
    # java.util.Properties escaping: '\:' -> ':' and '\\' -> '\'.
    $raw = $match.Matches[0].Groups[1].Value.Trim()
    return ($raw -replace '\\:', ':') -replace '\\\\', '\'
}

function Test-SdkPackage {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$SdkPackage,
        [string]$Sdk
    )
    if (-not $Sdk) {
        Add-Result -Name $Name -Status 'FAIL' -Detail 'no SDK root' `
            -Fix "Resolve the Android SDK root first (see the 'Android SDK root' row)."
        return
    }
    $path = Join-Path $Sdk $RelativePath
    if (Test-Path -LiteralPath $path) {
        Add-Result -Name $Name -Status 'OK' -Detail $path
    } else {
        Add-Result -Name $Name -Status 'FAIL' -Detail "missing: $path" `
            -Fix "sdkmanager `"$SdkPackage`"    (PowerShell: use single quotes -> sdkmanager '$SdkPackage')"
    }
}

# --- expected versions -----------------------------------------------------

$NdkVersion = Get-CatalogVersion 'ndk'
$CmakeVersion = Get-CatalogVersion 'cmake'
$CompileSdk = Get-CatalogVersion 'compileSdk'
$CompileSdkMinor = Get-CatalogVersion 'compileSdkMinor'
$PlatformPackage = "platforms;android-$CompileSdk.$CompileSdkMinor"
$PlatformDir = "platforms\android-$CompileSdk.$CompileSdkMinor"
$JavaToolchain = Get-CatalogVersion 'javaToolchain'
# build-tools is not in the catalogue (AGP picks its own default); this is the
# version the project standardised on and the one CI installs.
$BuildToolsVersion = '36.0.0'
$CmdlineToolsRev = '22.0'

# --- JDK -------------------------------------------------------------------

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    Add-Result -Name 'JAVA_HOME' -Status 'FAIL' -Detail 'not set' `
        -Fix "Install a JDK $JavaToolchain (Temurin or Microsoft Build of OpenJDK) and set JAVA_HOME to its root, e.g. setx JAVA_HOME `"C:\Program Files\Microsoft\jdk-21`""
    Add-Result -Name "JDK $JavaToolchain" -Status 'FAIL' -Detail 'cannot check without JAVA_HOME' `
        -Fix 'See the JAVA_HOME row.'
} else {
    $javaHome = $javaHome.Trim().Trim('"').TrimEnd('\')
    $javaExe = Join-Path $javaHome 'bin\java.exe'
    $javacExe = Join-Path $javaHome 'bin\javac.exe'

    if (-not (Test-Path -LiteralPath $javaExe)) {
        Add-Result -Name 'JAVA_HOME' -Status 'FAIL' -Detail "$javaHome (no bin\java.exe)" `
            -Fix 'JAVA_HOME must point at the JDK root, not at bin\ and not at a JRE.'
    } elseif (-not (Test-Path -LiteralPath $javacExe)) {
        Add-Result -Name 'JAVA_HOME' -Status 'FAIL' -Detail "$javaHome (JRE, not a JDK)" `
            -Fix "Gradle and AGP need a full JDK. Install a JDK $JavaToolchain and repoint JAVA_HOME."
    } else {
        Add-Result -Name 'JAVA_HOME' -Status 'OK' -Detail $javaHome
    }

    if (Test-Path -LiteralPath $javaExe) {
        $probe = Invoke-Native -FilePath $javaExe -Arguments @('-version')
        $versionMatch = [regex]::Match($probe.Output, 'version "([0-9][0-9A-Za-z._+-]*)"')
        if (-not $versionMatch.Success) {
            Add-Result -Name "JDK $JavaToolchain" -Status 'FAIL' -Detail 'could not parse java -version' `
                -Fix "Run `"$javaExe`" -version by hand and check the installation."
        } else {
            $reported = $versionMatch.Groups[1].Value
            # "21.0.10", "21", "1.8.0_402" -- only the first component matters,
            # and no JDK this project supports uses the 1.x scheme.
            $major = [int]($reported -split '\.')[0]
            if ($major -eq [int]$JavaToolchain) {
                Add-Result -Name "JDK $JavaToolchain" -Status 'OK' -Detail "java $reported"
            } else {
                Add-Result -Name "JDK $JavaToolchain" -Status 'FAIL' -Detail "java $reported (need $JavaToolchain)" `
                    -Fix "The Gradle toolchain is JDK $JavaToolchain. Install it and repoint JAVA_HOME, or set org.gradle.java.installations.paths in gradle.properties."
            }
        }
    }
}

# --- Android SDK root ------------------------------------------------------

$sdk = $null
$sdkOrigin = ''
if ($SdkRoot) {
    $sdk = $SdkRoot.TrimEnd('\')
    $sdkOrigin = '-SdkRoot'
} elseif ($env:ANDROID_HOME) {
    $sdk = $env:ANDROID_HOME.TrimEnd('\')
    $sdkOrigin = 'ANDROID_HOME'
} elseif ($env:ANDROID_SDK_ROOT) {
    $sdk = $env:ANDROID_SDK_ROOT.TrimEnd('\')
    $sdkOrigin = 'ANDROID_SDK_ROOT'
} else {
    $fromProps = Get-SdkDirFromLocalProperties
    if ($fromProps) {
        $sdk = $fromProps.TrimEnd('\')
        $sdkOrigin = 'local.properties sdk.dir'
    }
}

if (-not $sdk) {
    Add-Result -Name 'Android SDK root' -Status 'FAIL' -Detail 'not found' `
        -Fix 'Set ANDROID_HOME, or put sdk.dir=<path> in local.properties (Android Studio writes it for you).'
} elseif (-not (Test-Path -LiteralPath $sdk)) {
    Add-Result -Name 'Android SDK root' -Status 'FAIL' -Detail "$sdk (from $sdkOrigin) does not exist" `
        -Fix 'Point ANDROID_HOME / sdk.dir at a real SDK directory.'
    $sdk = $null
} else {
    Add-Result -Name 'Android SDK root' -Status 'OK' -Detail "$sdk (from $sdkOrigin)"
    if (-not $env:ANDROID_HOME) {
        Add-Result -Name 'ANDROID_HOME' -Status 'WARN' -Detail 'not set; resolved another way' `
            -Fix "The NDK and adb helper scripts read ANDROID_HOME: setx ANDROID_HOME `"$sdk`""
    } else {
        Add-Result -Name 'ANDROID_HOME' -Status 'OK' -Detail $env:ANDROID_HOME
    }
}

# --- local.properties ------------------------------------------------------

$localProps = Join-Path $RepoRoot 'local.properties'
if (-not (Test-Path -LiteralPath $localProps)) {
    Add-Result -Name 'local.properties' -Status 'WARN' -Detail 'absent' `
        -Fix "Optional when ANDROID_HOME is set. Otherwise create it with: sdk.dir=<escaped path>"
} else {
    $hasNdkDir = Select-String -LiteralPath $localProps -Pattern '^\s*ndk\.dir\s*=' -Quiet
    if ($hasNdkDir) {
        Add-Result -Name 'local.properties' -Status 'FAIL' -Detail 'sets ndk.dir' `
            -Fix "Remove ndk.dir. ndkVersion in AndroidNativeConventionPlugin is authoritative and keeps every machine on NDK $NdkVersion."
    } else {
        Add-Result -Name 'local.properties' -Status 'OK' -Detail 'present, no ndk.dir override'
    }
}

# --- SDK packages ----------------------------------------------------------

if ($sdk) {
    $sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path -LiteralPath $sdkManager) {
        $rev = '?'
        $revFile = Join-Path $sdk 'cmdline-tools\latest\source.properties'
        if (Test-Path -LiteralPath $revFile) {
            $revMatch = Select-String -LiteralPath $revFile -Pattern '^Pkg\.Revision\s*=\s*(.+)$' | Select-Object -First 1
            if ($revMatch) { $rev = $revMatch.Matches[0].Groups[1].Value.Trim() }
        }
        Add-Result -Name 'cmdline-tools' -Status 'OK' -Detail "rev $rev (expected $CmdlineToolsRev)"
    } else {
        Add-Result -Name 'cmdline-tools' -Status 'FAIL' -Detail "missing: $sdkManager" `
            -Fix 'Chicken and egg: without cmdline-tools there is no sdkmanager. Run .\scripts\setup-ndk.ps1, which downloads and unpacks it first.'
    }
} else {
    Add-Result -Name 'cmdline-tools' -Status 'FAIL' -Detail 'no SDK root' -Fix "See the 'Android SDK root' row."
}

Test-SdkPackage -Sdk $sdk -Name $PlatformPackage -RelativePath $PlatformDir -SdkPackage $PlatformPackage
Test-SdkPackage -Sdk $sdk -Name "build-tools;$BuildToolsVersion" -RelativePath "build-tools\$BuildToolsVersion" -SdkPackage "build-tools;$BuildToolsVersion"
Test-SdkPackage -Sdk $sdk -Name 'platform-tools' -RelativePath 'platform-tools\adb.exe' -SdkPackage 'platform-tools'
Test-SdkPackage -Sdk $sdk -Name "ndk;$NdkVersion" -RelativePath "ndk\$NdkVersion" -SdkPackage "ndk;$NdkVersion"
Test-SdkPackage -Sdk $sdk -Name "cmake;$CmakeVersion" -RelativePath "cmake\$CmakeVersion" -SdkPackage "cmake;$CmakeVersion"

# The NDK and CMake are only needed for -Pollama.nativeSource=build. Downgrade
# their failure to a warning so a remote-client-only contributor is not told
# their machine is broken.
foreach ($optional in @("ndk;$NdkVersion", "cmake;$CmakeVersion")) {
    $row = $Results | Where-Object { $_.Name -eq $optional -and $_.Status -eq 'FAIL' } | Select-Object -First 1
    if ($row) {
        $row.Status = 'WARN'
        $row.Fix = ".\scripts\setup-ndk.ps1   (only needed for -Pollama.nativeSource=build; assembleDebug works without it)"
    }
}

# --- Python / mkdocs -------------------------------------------------------

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python3 -ErrorAction SilentlyContinue }
$docsRequirements = Join-Path $RepoRoot 'docs\requirements.txt'

if (-not $python) {
    Add-Result -Name 'python' -Status 'WARN' -Detail 'not on PATH' `
        -Fix 'Only needed to build the docs site: install Python 3.11+ from python.org or winget install Python.Python.3.12'
} else {
    $pyProbe = Invoke-Native -FilePath $python.Source -Arguments @('--version')
    Add-Result -Name 'python' -Status 'OK' -Detail ($pyProbe.Output.Trim())

    if (-not (Test-Path -LiteralPath $docsRequirements)) {
        Add-Result -Name 'mkdocs' -Status 'WARN' -Detail 'docs/requirements.txt does not exist yet' `
            -Fix 'The documentation site lands in a later stage. Nothing to install today.'
    } else {
        $mkdocsProbe = Invoke-Native -FilePath $python.Source -Arguments @('-m', 'mkdocs', '--version')
        if ($mkdocsProbe.ExitCode -eq 0) {
            Add-Result -Name 'mkdocs' -Status 'OK' -Detail ($mkdocsProbe.Output.Trim())
        } else {
            Add-Result -Name 'mkdocs' -Status 'WARN' -Detail 'not importable' `
                -Fix "python -m pip install -r docs\requirements.txt"
        }
    }
}

# --- git submodules --------------------------------------------------------

$gitmodules = Join-Path $RepoRoot '.gitmodules'
if (-not (Test-Path -LiteralPath $gitmodules)) {
    Add-Result -Name 'llama.cpp submodule' -Status 'WARN' -Detail 'no .gitmodules yet' `
        -Fix 'third_party/llama.cpp lands in a later stage. Until then only -Pollama.nativeSource=none|prebuilt work.'
} else {
    $status = Invoke-Native -FilePath 'git' -Arguments @('submodule', 'status', '--recursive') -WorkingDirectory $RepoRoot
    if ($status.ExitCode -ne 0) {
        Add-Result -Name 'llama.cpp submodule' -Status 'FAIL' -Detail 'git submodule status failed' `
            -Fix 'Run: git submodule status --recursive   and read the error.'
    } else {
        $lines = $status.Output -split "`r?`n" | Where-Object { $_.Trim() -ne '' }
        $uninitialised = $lines | Where-Object { $_.StartsWith('-') }
        $dirty = $lines | Where-Object { $_.StartsWith('+') }
        if ($uninitialised) {
            Add-Result -Name 'llama.cpp submodule' -Status 'WARN' -Detail 'not initialised' `
                -Fix 'git submodule update --init --depth 1 third_party/llama.cpp'
        } elseif ($dirty) {
            Add-Result -Name 'llama.cpp submodule' -Status 'WARN' -Detail 'checked out at a different commit than recorded' `
                -Fix 'git submodule update --recursive   (or commit the bump deliberately via scripts/update-llamacpp.sh)'
        } else {
            Add-Result -Name 'llama.cpp submodule' -Status 'OK' -Detail 'in sync'
        }
    }
}

# --- Gradle wrapper --------------------------------------------------------

if ($SkipGradle) {
    Add-Result -Name 'gradlew' -Status 'WARN' -Detail 'skipped (-SkipGradle)' -Fix 'Re-run without -SkipGradle to verify.'
} else {
    $wrapper = Join-Path $RepoRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) {
        Add-Result -Name 'gradlew' -Status 'FAIL' -Detail 'gradlew.bat missing' -Fix 'Corrupt checkout. Re-clone the repository.'
    } else {
        Write-Host 'Probing the Gradle wrapper (first run may download the distribution)...' -ForegroundColor DarkGray
        $gradleProbe = Invoke-Native -FilePath $wrapper -Arguments @('--version') -WorkingDirectory $RepoRoot
        if ($gradleProbe.ExitCode -eq 0) {
            $gradleVersion = [regex]::Match($gradleProbe.Output, 'Gradle\s+([\d.]+)')
            $detail = 'runs'
            if ($gradleVersion.Success) { $detail = "Gradle $($gradleVersion.Groups[1].Value)" }
            Add-Result -Name 'gradlew' -Status 'OK' -Detail $detail
        } else {
            $firstError = ($gradleProbe.Output -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -First 3) -join ' | '
            Add-Result -Name 'gradlew' -Status 'FAIL' -Detail $firstError `
                -Fix 'Usually a JDK problem: check the JAVA_HOME rows above, then run .\gradlew.bat --version by hand.'
        }
    }
}

# --- render ----------------------------------------------------------------

$nameWidth = ($Results | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
if ($nameWidth -lt 20) { $nameWidth = 20 }

Write-Host ''
Write-Host ("OllamaMobile developer environment  --  {0}" -f $RepoRoot)
Write-Host ('-' * ($nameWidth + 60))

foreach ($result in $Results) {
    $colour = 'Green'
    if ($result.Status -eq 'FAIL') { $colour = 'Red' }
    if ($result.Status -eq 'WARN') { $colour = 'Yellow' }

    Write-Host ('  {0}  ' -f $result.Name.PadRight($nameWidth)) -NoNewline
    Write-Host $result.Status.PadRight(5) -ForegroundColor $colour -NoNewline
    Write-Host ('  {0}' -f $result.Detail)
}

$failures = @($Results | Where-Object { $_.Status -eq 'FAIL' })
$warnings = @($Results | Where-Object { $_.Status -eq 'WARN' -and $_.Fix })

Write-Host ('-' * ($nameWidth + 60))

if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host 'Blocking problems:' -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host ("  {0}" -f $failure.Name) -ForegroundColor Red
        Write-Host ("      {0}" -f $failure.Fix)
    }
}

if ($warnings.Count -gt 0) {
    Write-Host ''
    Write-Host 'Not blocking today:' -ForegroundColor Yellow
    foreach ($warning in $warnings) {
        Write-Host ("  {0}" -f $warning.Name) -ForegroundColor Yellow
        Write-Host ("      {0}" -f $warning.Fix)
    }
}

Write-Host ''
if ($failures.Count -eq 0) {
    Write-Host 'Ready. Try: .\gradlew.bat assembleDebug' -ForegroundColor Green
    exit 0
}

Write-Host ("{0} blocking problem(s). Nothing was modified; fix the items above and re-run." -f $failures.Count) -ForegroundColor Red
exit 1
