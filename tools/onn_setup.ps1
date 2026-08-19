#!/usr/bin/env pwsh
# One-shot Onn 4K setup for the TizenTube Liked+Subscriptions fork.
#
# What this does (all reversible, none of it changes the OS or the launcher):
#   1. Installs the fixed release APK (persistent-key signed, dex-valid).
#   2. Disables every other YouTube-capable app on the box so this fork is
#      the ONLY way to watch YouTube there (no stock YouTube, no browsers).
#   3. Launches the fork.
#
# Phone remote control still works: the phone YouTube app pairs with this
# app normally, and the filter only blocks disallowed playback - it never
# touches pairing endpoints.

param(
    [string]$Serial = '192.168.1.172:5555',
    [string]$ApkPath = '',
    [string]$Adb = 'C:\Users\YossiMH\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [switch]$Restore,
    [switch]$VerifyOnly
)

# Pick the app build that matches the box: ARM64 for modern boxes like the Onn
# 4K Pro, 32-bit ARM for older devices. The release ships both.
function Get-MatchingApk {
    param([string]$DeviceAbi)
    if ($DeviceAbi -like 'arm64*') {
        return (Join-Path $PSScriptRoot '..\.temp\release-build\dist\TizenTube-Liked-Subs-arm64.apk')
    }
    return (Join-Path $PSScriptRoot '..\.temp\release-build\dist\TizenTube-Liked-Subs-arm.apk')
}

$ErrorActionPreference = 'Stop'

function Invoke-Adb([string[]]$Argv) {
    & $Adb -s $Serial @Argv 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Argv -join ' ')" }
}

function PackageExists([string]$pkg) {
    $lines = Invoke-Adb @('shell', 'pm', 'list', 'packages', $pkg)
    return ($lines | Where-Object { $_ -match ('package:' + [regex]::Escape($pkg) + '\s*$') }).Count -gt 0
}

# Apps that can show YouTube content on Android TV boxes.
$YouTubeApps = @(
    'com.google.android.youtube.tv',
    'com.google.android.apps.youtube.tv',
    'com.google.android.youtube',
    'com.google.android.youtube.tvunplugged',
    'com.google.android.youtube.tvmusic',
    'com.teamsmart.videomanager.tv',
    'com.teamsmart.videomanager.v2',
    'com.liskovsoft.smarttubetv.beta',
    'io.gh.reisxd.tizentube.cobalt',
    'com.android.chrome',
    'com.chrome.beta',
    'org.chromium.chrome',
    'com.opera.browser',
    'com.opera.tv.browser',
    'com.microsoft.emmx',
    'org.mozilla.firefox',
    'com.duckduckgo.mobile.android',
    'com.sec.android.app.sbrowser',
    'com.brave.browser',
    'com.puffin.free',
    'com.phlox.tvwebbrowser',
    'com.internet.tvwebbrowser'
)

if ($Restore) {
    foreach ($pkg in $YouTubeApps) {
        if (PackageExists $pkg) {
            Invoke-Adb @('shell', 'pm', 'enable', '--user', '0', $pkg) | Out-Null
            Write-Host "restored: $pkg"
        }
    }
    Write-Host 'All apps restored.'
    exit 0
}

if ($VerifyOnly) {
    Write-Host '=== Lock-down verify + re-assert ==='
    foreach ($pkg in $YouTubeApps) {
        if (PackageExists $pkg) {
            Invoke-Adb @('shell', 'pm', 'disable-user', '--user', '0', $pkg) | Out-Null
            Write-Host ("locked: $pkg")
        } else {
            Write-Host ("skip (not installed): $pkg")
        }
    }
    Write-Host '--- enabled YouTube-capable apps (must be ONLY io.gh.yossim.tizentube.cobalt) ---'
    Invoke-Adb @('shell', 'pm', 'list', 'packages', '-e') | Where-Object { $_ -match 'youtube|tizentube|smarttube|browser' }
    Write-Host '--- disabled ---'
    Invoke-Adb @('shell', 'pm', 'list', 'packages', '-d') | Where-Object { $_ -match 'youtube|tizentube|smarttube|browser' }
    Write-Host 'Done. If anything leaks into the enabled list, re-run this mode after the TV finishes Google account re-verification.'
    exit 0
}

if (-not $ApkPath) {
    $DeviceAbi = (& $Adb -s $Serial shell getprop ro.product.cpu.abi 2>$null | Select-Object -First 1).Trim()
    $ApkPath = Get-MatchingApk -DeviceAbi $DeviceAbi
    Write-Host "Device ABI: $DeviceAbi -> $(Split-Path $ApkPath -Leaf)"
}
if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }

# Refuse to install anything but a verified guarded allowed-only build. This
# stops a stale or unpatched copy of the app (e.g. one whose native slot still
# points at the upstream user script) from silently becoming the only YouTube
# experience on the box.
if ($ApkPath) {
    Write-Host '=== Verifying this is a guarded allowed-only build ==='
    python (Join-Path $PSScriptRoot 'verify_allowed_only_apk.py') $ApkPath
    if ($LASTEXITCODE -ne 0) { throw 'Refusing to install: build is not a verified guarded allowed-only release.' }
}

Write-Host '=== Installing fixed release APK ==='
Invoke-Adb @('install', '-r', '-t', $ApkPath) | Write-Host

Write-Host '=== Disabling other YouTube-capable apps ==='
$disabled = 0
foreach ($pkg in $YouTubeApps) {
    if (PackageExists $pkg) {
        Invoke-Adb @('shell', 'pm', 'disable-user', '--user', '0', $pkg) | Out-Null
        Write-Host "disabled: $pkg"
        $disabled++
    } else {
        Write-Host "skip (not installed): $pkg"
    }
}
if ($disabled -eq 0) { Write-Host '(no other YouTube-capable apps found - this box is already clean)' }

Write-Host '=== Launching the fork ==='
Invoke-Adb @('shell', 'am', 'start', '-n', 'io.gh.yossim.tizentube.cobalt/dev.cobalt.app.MainActivity') | Write-Host

Write-Host ''
Write-Host 'Done. Sign in on the TV with the Google account whose Likes/subscriptions define the allowed catalog.'
Write-Host 'Then open the YouTube app on your phone, tap the cast/remote icon, and pair with this TV.'
