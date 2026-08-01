<#
.SYNOPSIS
    Generates a throwaway signing keystore for local development and writes the
    matching keystore.properties.

.DESCRIPTION
    app/build.gradle.kts makes release signing opt-in: if keystore.properties
    (or the OLLAMA_KEYSTORE_* environment variables) are absent it falls back to
    the debug key and warns. That fallback is fine for assembleDebug but it
    makes assembleRelease/bundleRelease produce something you cannot install
    over a previous build. This script creates a *local, disposable* key so
    those tasks behave like the real thing.

    THE KEY THIS PRODUCES MUST NEVER SIGN A PUBLISHED ARTEFACT. OllamaMobile
    ships through GitHub Releases only, and the release key lives in the
    repository's Actions secrets, not on a developer laptop.

    Language choice: PowerShell. keytool comes from the same JDK that
    JAVA_HOME points at, the output has to land at Windows paths, and this is a
    developer-laptop-only operation -- CI signs with secrets and never runs
    this.

.PARAMETER KeystorePath
    Where to write the keystore. Default: <repo>\dev-debug.jks. Anything
    matching *.jks / *.keystore is already gitignored.

.PARAMETER Alias
    Key alias. Default: ollamamobile-dev.

.PARAMETER StorePassword
    Store and key password. Default: android, the conventional debug password.
    There is no Read-Host prompt anywhere in this repository's scripts; pass
    -StorePassword if you want something else. Note that this password ends up
    in plaintext in keystore.properties either way, which is precisely why the
    key must be worthless.

.PARAMETER ValidityDays
    Certificate validity. Default 10000 days (~27 years), matching the
    Android debug certificate convention.

.PARAMETER DName
    X.500 distinguished name for the self-signed certificate.

.PARAMETER Force
    Overwrite an existing keystore and keystore.properties. Without it the
    script refuses and exits non-zero.

.EXAMPLE
    .\scripts\gen-dev-keystore.ps1

.EXAMPLE
    .\scripts\gen-dev-keystore.ps1 -Force -Alias my-dev-key

.NOTES
    Project: OllamaMobile   https://github.com/jaypetez/ollama-mobile
#>
[CmdletBinding()]
param(
    [string]$KeystorePath,
    [string]$Alias = 'ollamamobile-dev',
    [string]$StorePassword = 'android',
    [int]$ValidityDays = 10000,
    [string]$DName = 'CN=OllamaMobile Dev, OU=Development, O=OllamaMobile, L=Unspecified, C=US',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $KeystorePath) { $KeystorePath = Join-Path $RepoRoot 'dev-debug.jks' }
$PropertiesPath = Join-Path $RepoRoot 'keystore.properties'

function Write-Banner {
    Write-Host ''
    Write-Host '  ------------------------------------------------------------------' -ForegroundColor Yellow
    Write-Host '  THIS IS A THROWAWAY DEVELOPMENT KEY.' -ForegroundColor Yellow
    Write-Host '  It is NOT the publishing key. Never sign a GitHub Release, or any' -ForegroundColor Yellow
    Write-Host '  artefact you hand to another person, with it. Its password is'      -ForegroundColor Yellow
    Write-Host '  stored in plaintext next to it on purpose: it protects nothing.'    -ForegroundColor Yellow
    Write-Host '  ------------------------------------------------------------------' -ForegroundColor Yellow
    Write-Host ''
}

function Resolve-Keytool {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME.Trim().Trim('"').TrimEnd('\') 'bin\keytool.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $onPath = Get-Command keytool -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    throw @"
keytool not found.
It ships with the JDK. Set JAVA_HOME to a JDK 21 root and re-run:
    .\scripts\setup-dev-env.ps1
"@
}

function Assert-Gitignored {
    param([Parameter(Mandatory = $true)][string]$Path)

    # A path outside the working tree cannot be committed, so there is nothing
    # to assert. Normalise first: 'C:\git\ollama-mobile\..\elsewhere\k.jks'
    # would otherwise look like it is inside.
    $full = [System.IO.Path]::GetFullPath($Path)
    $rootPrefix = $RepoRoot.TrimEnd('\') + '\'
    if (-not $full.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        Write-Host "  $full is outside the repository; git cannot see it." -ForegroundColor DarkGray
        return
    }

    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) {
        Write-Warning "git is not on PATH, so I cannot confirm that $Path is ignored. Check .gitignore yourself before committing."
        return
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Push-Location -LiteralPath $RepoRoot
        & git check-ignore --quiet -- $Path 2>&1 | Out-Null
        $ignored = ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
        $ErrorActionPreference = $previous
    }

    if (-not $ignored) {
        throw @"
$Path is NOT ignored by git.

Refusing to create a signing key that could be committed. Add a rule to
.gitignore first -- the repository already ignores *.jks, *.keystore and
keystore.properties, so a path outside those patterns is the likely cause.
Either move the keystore or extend .gitignore.
"@
    }
}

Write-Banner

$keytool = Resolve-Keytool

if ((Test-Path -LiteralPath $KeystorePath) -and -not $Force) {
    Write-Host "A keystore already exists at:" -ForegroundColor Red
    Write-Host "    $KeystorePath"
    Write-Host ''
    Write-Host 'Refusing to overwrite it. Overwriting would invalidate every debug build' -ForegroundColor Red
    Write-Host 'already installed on your devices and emulators (signature mismatch on'  -ForegroundColor Red
    Write-Host 'update). Pass -Force if that is what you want, or -KeystorePath to write' -ForegroundColor Red
    Write-Host 'somewhere else.'                                                          -ForegroundColor Red
    exit 1
}

if ((Test-Path -LiteralPath $PropertiesPath) -and -not $Force) {
    Write-Host "keystore.properties already exists at:" -ForegroundColor Red
    Write-Host "    $PropertiesPath"
    Write-Host ''
    Write-Host 'Refusing to overwrite it; it may point at a real key. Pass -Force to replace it.' -ForegroundColor Red
    exit 1
}

# Fail before creating anything, not after.
Assert-Gitignored -Path $KeystorePath
Assert-Gitignored -Path $PropertiesPath

if (Test-Path -LiteralPath $KeystorePath) {
    Write-Host "Removing existing keystore (-Force): $KeystorePath" -ForegroundColor Yellow
    Remove-Item -LiteralPath $KeystorePath -Force
}

$keystoreDirectory = Split-Path -Parent $KeystorePath
if ($keystoreDirectory -and -not (Test-Path -LiteralPath $keystoreDirectory)) {
    New-Item -ItemType Directory -Path $keystoreDirectory -Force | Out-Null
}

# PKCS12 is the JDK default and the only format keytool will create without
# warning; in PKCS12 the key password must equal the store password, so both
# are set to the same value.
$keytoolArguments = @(
    '-genkeypair'
    '-storetype', 'PKCS12'
    '-keystore', $KeystorePath
    '-alias', $Alias
    '-keyalg', 'RSA'
    '-keysize', '4096'
    '-sigalg', 'SHA256withRSA'
    '-validity', "$ValidityDays"
    '-dname', $DName
    '-storepass', $StorePassword
    '-keypass', $StorePassword
)

Write-Host "Generating $KeystorePath (alias '$Alias', $ValidityDays days)" -ForegroundColor Cyan

$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    # keytool writes its progress banner to stderr. With '2>&1' those lines
    # arrive as ErrorRecords, and Out-String would render them with the full
    # "CategoryInfo / FullyQualifiedErrorId" decoration. ToString() first.
    $keytoolOutput = & $keytool @keytoolArguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String
    $keytoolExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}

if ($keytoolExit -ne 0 -or -not (Test-Path -LiteralPath $KeystorePath)) {
    throw @"
keytool failed (exit $keytoolExit):

$keytoolOutput
"@
}
if ($keytoolOutput.Trim()) { Write-Host $keytoolOutput.Trim() -ForegroundColor DarkGray }

# java.util.Properties treats backslash as an escape character, so a Windows
# path must use forward slashes (java.io.File accepts them on Windows) or be
# double-escaped. Forward slashes are the readable option. The path is absolute
# because app/build.gradle.kts resolves storeFile with the :app module's
# file(...), not the root project's.
$storeFileValue = $KeystorePath -replace '\\', '/'

$propertiesContent = @"
# Generated by scripts/gen-dev-keystore.ps1 on $(Get-Date -Format 'yyyy-MM-dd').
#
# THROWAWAY DEVELOPMENT KEY -- NOT FOR PUBLISHING.
# Read by app/build.gradle.kts. This file is gitignored and must stay that way.
# The real release key lives in the repository's GitHub Actions secrets as
# OLLAMA_KEYSTORE_PATH / OLLAMA_KEYSTORE_PASSWORD / OLLAMA_KEY_ALIAS /
# OLLAMA_KEY_PASSWORD and never touches a developer machine.
storeFile=$storeFileValue
storePassword=$StorePassword
keyAlias=$Alias
keyPassword=$StorePassword
"@

Set-Content -LiteralPath $PropertiesPath -Value $propertiesContent -Encoding ASCII
Write-Host "Wrote $PropertiesPath" -ForegroundColor Cyan

# Show the fingerprint: it is what you compare against when an install fails
# with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
$ErrorActionPreference = 'Continue'
$listOutput = & $keytool -list -v -keystore $KeystorePath -storepass $StorePassword -alias $Alias 2>&1 |
    ForEach-Object { $_.ToString() } | Out-String
$ErrorActionPreference = 'Stop'
$fingerprint = ($listOutput -split "`r?`n" | Where-Object { $_ -match 'SHA256:' } | Select-Object -First 1)
if ($fingerprint) { Write-Host ("Certificate {0}" -f $fingerprint.Trim()) -ForegroundColor DarkGray }

Write-Banner
Write-Host 'Now: .\gradlew.bat assembleRelease' -ForegroundColor Green
Write-Host 'To go back to the AGP debug-key fallback, delete keystore.properties.'
exit 0
