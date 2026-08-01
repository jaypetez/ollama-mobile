<#
.SYNOPSIS
    Installs the native toolchain (NDK + CMake) needed by
    -Pollama.nativeSource=build.

.DESCRIPTION
    Solves the bootstrap problem first: sdkmanager itself ships inside the
    "cmdline-tools" SDK package, so on a machine that has never had Android
    Studio there is no sdkmanager to install cmdline-tools with. When
    $ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat is absent this script
    downloads the command line tools zip straight from Google's repository and
    unpacks it there, then uses it to install:

        ndk;<version from gradle/libs.versions.toml>
        cmake;<version from gradle/libs.versions.toml>

    Idempotent: an already-installed package is left alone unless -Force is
    given, in which case its directory is removed and the package reinstalled.

    Language choice: PowerShell. It runs before Git Bash can be assumed to
    exist, it has to write into %LOCALAPPDATA% style paths, and it needs
    Expand-Archive and Invoke-WebRequest without asking the developer to
    install curl or unzip. Linux CI never runs this: the GitHub runners already
    ship an SDK and use the sdkmanager on PATH.

.PARAMETER Force
    Reinstall the NDK and CMake even if they are already present. Also
    re-bootstraps cmdline-tools\latest.

.PARAMETER SdkRoot
    Android SDK root. Defaults to ANDROID_HOME, then ANDROID_SDK_ROOT, then
    sdk.dir from local.properties.

.PARAMETER CmdlineToolsUrl
    Explicit URL of the command line tools zip, bypassing repository
    discovery. See the NOTES section: the filename is not stable.

.PARAMETER SkipLicenses
    Do not pre-accept the Android SDK licences. sdkmanager will then refuse to
    install anything it considers unlicensed, so this is only useful for a dry
    inspection.

.EXAMPLE
    .\scripts\setup-ndk.ps1

.EXAMPLE
    .\scripts\setup-ndk.ps1 -Force

.EXAMPLE
    .\scripts\setup-ndk.ps1 -CmdlineToolsUrl https://dl.google.com/android/repository/commandlinetools-win-XXXXXXXX_latest.zip

.NOTES
    THE COMMAND LINE TOOLS FILENAME CHANGES.
    The zip is published as commandlinetools-win-<buildnumber>_latest.zip and
    the build number changes with every revision (rev 22.0 is what this project
    expects). There is no stable "latest" alias, which is why this script
    discovers the URL instead of hardcoding it. To look it up by hand:

      1. https://developer.android.com/studio#command-line-tools-only lists the
         current Windows/macOS/Linux zips.
      2. Or read Google's package manifest directly:
         https://dl.google.com/android/repository/repository2-3.xml
         and find <remotePackage path="cmdline-tools;latest">; its
         archives/archive/complete/url is relative to
         https://dl.google.com/android/repository/ .

    Then pass it with -CmdlineToolsUrl.

    Project: OllamaMobile   https://github.com/jaypetez/ollama-mobile
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [string]$SdkRoot,
    [string]$CmdlineToolsUrl,
    [switch]$SkipLicenses
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Catalog = Join-Path $RepoRoot 'gradle\libs.versions.toml'
$RepositoryBase = 'https://dl.google.com/android/repository/'

# Windows PowerShell 5.1 still defaults to SSL3/TLS1.0 on some hosts and
# dl.google.com will simply reset the connection.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-CatalogVersion {
    param([Parameter(Mandatory = $true)][string]$Alias)
    if (-not (Test-Path -LiteralPath $Catalog)) {
        throw "Version catalogue not found at $Catalog. Run this from inside the repository."
    }
    $pattern = '^\s*' + [regex]::Escape($Alias) + '\s*=\s*"([^"]+)"'
    $match = Select-String -LiteralPath $Catalog -Pattern $pattern | Select-Object -First 1
    if (-not $match) { throw "Version alias '$Alias' is missing from $Catalog." }
    return $match.Matches[0].Groups[1].Value
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$StdIn
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # ToString() before Out-String: stderr arrives as ErrorRecords under
        # '2>&1' and would otherwise be rendered with the full PowerShell error
        # decoration. sdkmanager writes its progress to stderr.
        if ($PSBoundParameters.ContainsKey('StdIn')) {
            $output = $StdIn | & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String
        } else {
            $output = & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String
        }
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
    } catch {
        return [pscustomobject]@{ ExitCode = -1; Output = $_.Exception.Message }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Resolve-SdkRoot {
    if ($SdkRoot) { return $SdkRoot.TrimEnd('\') }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME.TrimEnd('\') }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT.TrimEnd('\') }

    $file = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $file) {
        $match = Select-String -LiteralPath $file -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
        if ($match) {
            $raw = $match.Matches[0].Groups[1].Value.Trim()
            return (($raw -replace '\\:', ':') -replace '\\\\', '\').TrimEnd('\')
        }
    }

    throw @"
Cannot locate the Android SDK.
Set ANDROID_HOME, or add sdk.dir to local.properties, or pass -SdkRoot.
A brand new machine can point ANDROID_HOME at an empty directory; this script
will populate it:
    setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"
    (then open a new shell)
"@
}

<#
Discovers the current Windows command line tools zip from Google's package
manifest. The manifest name has changed over the years (repository2-1.xml ->
2-2 -> 2-3), so several are tried; whichever parses first wins.
#>
function Find-CmdlineToolsUrl {
    $manifests = @('repository2-3.xml', 'repository2-2.xml', 'repository2-1.xml')
    foreach ($manifest in $manifests) {
        $manifestUrl = $RepositoryBase + $manifest
        try {
            Write-Host "  reading $manifestUrl"
            $response = Invoke-WebRequest -Uri $manifestUrl -UseBasicParsing -TimeoutSec 60
            [xml]$xml = $response.Content
        } catch {
            Write-Host "  (unavailable: $($_.Exception.Message))"
            continue
        }

        $packages = @($xml.DocumentElement.ChildNodes | Where-Object {
                $_.LocalName -eq 'remotePackage' -and $_.path -eq 'cmdline-tools;latest'
            })
        foreach ($package in $packages) {
            foreach ($archive in @($package.archives.archive)) {
                # host-os appears as a child element in the current schema and
                # as an attribute in older ones; the PowerShell XML adapter
                # exposes both the same way.
                $hostOs = $archive.'host-os'
                if ($hostOs -isnot [string]) { $hostOs = [string]$hostOs }
                if ($hostOs -ne 'windows') { continue }
                $relative = [string]$archive.complete.url
                if ($relative -match '^commandlinetools-win-.*\.zip$') {
                    return $RepositoryBase + $relative
                }
            }
        }
    }

    throw @"
Could not discover the command line tools download URL from Google's package
manifest. Look it up manually and pass it explicitly:

    https://developer.android.com/studio#command-line-tools-only

    .\scripts\setup-ndk.ps1 -CmdlineToolsUrl https://dl.google.com/android/repository/commandlinetools-win-<build>_latest.zip
"@
}

function Install-CmdlineTools {
    param([Parameter(Mandatory = $true)][string]$Sdk)

    $target = Join-Path $Sdk 'cmdline-tools\latest'
    $sdkManager = Join-Path $target 'bin\sdkmanager.bat'

    if ((Test-Path -LiteralPath $sdkManager) -and -not $Force) {
        Write-Host "cmdline-tools: already present at $target" -ForegroundColor Green
        return $sdkManager
    }

    $url = $CmdlineToolsUrl
    if (-not $url) {
        Write-Host 'cmdline-tools: discovering the current download URL' -ForegroundColor Cyan
        $url = Find-CmdlineToolsUrl
    }
    Write-Host "cmdline-tools: downloading $url" -ForegroundColor Cyan

    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("ollama-cmdline-tools-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    $zip = Join-Path $work 'cmdline-tools.zip'

    try {
        # $ProgressPreference slows Invoke-WebRequest to a crawl on large files
        # in PS 5.1; turning the progress bar off is a real speed fix.
        $previousProgress = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        try {
            Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing -TimeoutSec 600
        } finally {
            $ProgressPreference = $previousProgress
        }

        $size = (Get-Item -LiteralPath $zip).Length
        if ($size -lt 1MB) {
            throw "Downloaded $size bytes from $url; that is not the command line tools zip. Check the URL."
        }
        Write-Host ("cmdline-tools: downloaded {0:N1} MB" -f ($size / 1MB))

        $extracted = Join-Path $work 'extracted'
        Expand-Archive -LiteralPath $zip -DestinationPath $extracted -Force

        # The zip contains a top level cmdline-tools/ directory; the SDK wants
        # its *contents* under cmdline-tools/latest/.
        $inner = Join-Path $extracted 'cmdline-tools'
        if (-not (Test-Path -LiteralPath (Join-Path $inner 'bin\sdkmanager.bat'))) {
            throw "Unexpected zip layout: $inner\bin\sdkmanager.bat not found after extraction of $url"
        }

        if (Test-Path -LiteralPath $target) {
            Write-Host "cmdline-tools: removing existing $target (-Force)" -ForegroundColor Yellow
            Remove-Item -LiteralPath $target -Recurse -Force
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Move-Item -LiteralPath $inner -Destination $target

        if (-not (Test-Path -LiteralPath $sdkManager)) {
            throw "Install finished but $sdkManager is missing."
        }
        Write-Host "cmdline-tools: installed to $target" -ForegroundColor Green
        return $sdkManager
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

<#
Installs SDK packages. Package coordinates contain a semicolon, which cmd.exe
happily treats as an argument separator when it reaches sdkmanager.bat, so the
coordinates go through --package_file instead of the command line. That is
sdkmanager's own supported mechanism and it removes the quoting question
entirely.
#>
function Install-SdkPackages {
    param(
        [Parameter(Mandatory = $true)][string]$SdkManager,
        [Parameter(Mandatory = $true)][string]$Sdk,
        [Parameter(Mandatory = $true)][string[]]$Packages
    )

    $listFile = Join-Path ([System.IO.Path]::GetTempPath()) ("ollama-sdk-packages-" + [guid]::NewGuid().ToString('N') + '.txt')
    Set-Content -LiteralPath $listFile -Value $Packages -Encoding ASCII

    try {
        Write-Host ("sdkmanager: installing {0}" -f ($Packages -join ', ')) -ForegroundColor Cyan
        $result = Invoke-Native -FilePath $SdkManager `
            -Arguments @("--sdk_root=$Sdk", '--install', "--package_file=$listFile") `
            -StdIn ("y`n" * 50)
        Write-Host $result.Output
        if ($result.ExitCode -ne 0) {
            throw @"
sdkmanager exited with $($result.ExitCode).

Common causes:
  * Licences not accepted. Run:
        "$SdkManager" --sdk_root="$Sdk" --licenses
  * JAVA_HOME not set or pointing at a JRE. sdkmanager needs a JDK.
  * A package coordinate no longer exists upstream. List what is available:
        "$SdkManager" --sdk_root="$Sdk" --list | findstr /i ndk
"@
        }
    } finally {
        Remove-Item -LiteralPath $listFile -Force -ErrorAction SilentlyContinue
    }
}

# --- main ------------------------------------------------------------------

$ndkVersion = Get-CatalogVersion 'ndk'
$cmakeVersion = Get-CatalogVersion 'cmake'
$sdk = Resolve-SdkRoot

Write-Host ''
Write-Host 'OllamaMobile native toolchain setup' -ForegroundColor White
Write-Host "  SDK root : $sdk"
Write-Host "  NDK      : $ndkVersion"
Write-Host "  CMake    : $cmakeVersion"
Write-Host ''

if (-not (Test-Path -LiteralPath $sdk)) {
    Write-Host "Creating $sdk" -ForegroundColor Yellow
    New-Item -ItemType Directory -Path $sdk -Force | Out-Null
}

if (-not $env:JAVA_HOME) {
    Write-Warning 'JAVA_HOME is not set. sdkmanager runs on the JVM and will probably fail. Run .\scripts\setup-dev-env.ps1 first.'
}

$sdkManager = Install-CmdlineTools -Sdk $sdk

if (-not $SkipLicenses) {
    Write-Host 'sdkmanager: accepting Android SDK licences (the same prompts as --licenses)' -ForegroundColor Cyan
    $licenceResult = Invoke-Native -FilePath $sdkManager -Arguments @("--sdk_root=$sdk", '--licenses') -StdIn ("y`n" * 100)
    if ($licenceResult.ExitCode -ne 0) {
        Write-Warning "sdkmanager --licenses exited with $($licenceResult.ExitCode). Continuing; the install below will fail loudly if a licence is genuinely missing."
    }
}

$wanted = @(
    [pscustomobject]@{ Coordinate = "ndk;$ndkVersion"; Directory = (Join-Path $sdk "ndk\$ndkVersion") }
    [pscustomobject]@{ Coordinate = "cmake;$cmakeVersion"; Directory = (Join-Path $sdk "cmake\$cmakeVersion") }
)

$toInstall = @()
foreach ($item in $wanted) {
    $present = Test-Path -LiteralPath (Join-Path $item.Directory 'source.properties')
    if ($present -and -not $Force) {
        Write-Host "$($item.Coordinate): already installed at $($item.Directory)" -ForegroundColor Green
        continue
    }
    if ($present -and $Force) {
        Write-Host "$($item.Coordinate): removing $($item.Directory) (-Force)" -ForegroundColor Yellow
        Remove-Item -LiteralPath $item.Directory -Recurse -Force
    }
    $toInstall += $item.Coordinate
}

if ($toInstall.Count -gt 0) {
    Install-SdkPackages -SdkManager $sdkManager -Sdk $sdk -Packages $toInstall
}

# Verify rather than trust the exit code: sdkmanager has been known to report
# success for a package it silently skipped.
$missing = @()
foreach ($item in $wanted) {
    if (-not (Test-Path -LiteralPath (Join-Path $item.Directory 'source.properties'))) {
        $missing += "$($item.Coordinate) -> expected $($item.Directory)"
    }
}
if ($missing.Count -gt 0) {
    throw @"
Installation did not produce the expected directories:
  $($missing -join "`n  ")

List what the SDK actually has:
  "$sdkManager" --sdk_root="$sdk" --list_installed
"@
}

Write-Host ''
Write-Host 'Native toolchain ready.' -ForegroundColor Green
Write-Host 'Next:'
Write-Host '  .\scripts\setup-dev-env.ps1                 verify the whole environment'
Write-Host '  git submodule update --init --depth 1 third_party/llama.cpp'
Write-Host '                                              (only once that submodule exists)'
Write-Host '  ./scripts/build-native.sh --abi arm64-v8a   build llama.cpp'
exit 0
