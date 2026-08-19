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
    [switch]$VerifyOnly,
    [switch]$VerifyApp
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

# The protected fork package id (the ONLY YouTube-capable app that must stay enabled).
$ForkId = 'io.gh.yossim.tizentube.cobalt'

# Apps that can show YouTube content on Android TV boxes, checked explicitly.
$YouTubeApps = @(
    'com.google.android.youtube.tv',
    'com.google.android.apps.youtube.tv',
    'com.google.android.youtube',
    'com.google.android.youtube.tvunplugged',
    'com.google.android.youtube.tvmusic',
    'com.google.android.apps.youtube.tvkids',
    'com.google.android.apps.youtube.kids',
    'com.google.android.apps.youtube.music',
    'com.teamsmart.videomanager.tv',
    'com.teamsmart.videomanager.v2',
    'com.teamsmart.videomanager.atv',
    'com.liskovsoft.smarttubetv.beta',
    'com.liskovsoft.smarttubetv',
    'org.schabi.newpipe',
    'com.github.yokolet.ytdroid',
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
# Future-proof: also lock any installed app whose id mentions youtube/smarttube/
# newpipe/tizentube, EXCEPT the protected fork itself. This keeps catching new
# or renamed YouTube-capable apps without changing the OS or the launcher.
foreach ($line in (Invoke-Adb @('shell', 'pm', 'list', 'packages'))) {
    if ($line -notmatch '^package:') { continue }
    $id = $line -replace '^package:', ''
    if ($id -eq $ForkId) { continue }
if ($id -match 'youtube|smarttube|newpipe|tizentube') {
    if ($YouTubeApps -notcontains $id) { $YouTubeApps += $id }
}
}

# Cold-start the fork and confirm the guard actually ran, from the box's own
# log. The app can hit a net::ERR_INTERNET_DISCONNECTED platform-error dialog
# on a cold start even when the network is fine (observed in the field): we
# dismiss it with an OK press and retry so the very first launch just works.
function Invoke-StartupGuardCheck {
    Write-Host '=== Cold-start guard check ==='
    Invoke-Adb @('logcat', '-c') | Out-Null
    Invoke-Adb @('shell', 'am', 'force-stop', $ForkId) | Out-Null
    Start-Sleep -Seconds 3
    Invoke-Adb @('shell', 'am', 'start', '-n', "$ForkId/dev.cobalt.app.MainActivity") | Out-Null
    $confirmed = $false
    for ($i = 0; $i -lt 6; $i++) {
        Start-Sleep -Seconds 20
        $lines = Invoke-Adb @('logcat', '-d') | Where-Object { $_ -match 'allowed-only|ERR_INTERNET_DISCONNECTED|filter active|boot ready|signed out' }
        if ($lines -match '\[allowed-only\] filter active') {
            $confirmed = $true
            Write-Host 'GUARD OK: [allowed-only] filter active'
            $lines | Where-Object { $_ -match 'allowed-only' } | Select-Object -Last 8 | ForEach-Object { Write-Host ("  " + $_) }
            break
        }
        if ($lines -match 'ERR_INTERNET_DISCONNECTED') {
            Write-Host ("Startup hit a network error (try " + ($i + 1) + "); pressing OK to retry...")
            Invoke-Adb @('shell', 'input', 'keyevent', '23') | Out-Null
        }
    }
    if (-not $confirmed) { Write-Host 'WARNING: guard was not confirmed during cold start; check the app and network.' }
    return $confirmed
}

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

if ($VerifyApp) {
    Write-Host '=== Verify app mode: re-assert lock-down, then cold-start guard check ==='
    foreach ($pkg in $YouTubeApps) {
        if (PackageExists $pkg) { Invoke-Adb @('shell', 'pm', 'disable-user', '--user', '0', $pkg) | Out-Null }
    }
    Invoke-StartupGuardCheck | Out-Null
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

Write-Host '=== Launching the fork and confirming the guard ==='
Invoke-StartupGuardCheck | Out-Null

Write-Host ''
Write-Host 'Done. Sign in on the TV with the Google account whose Likes/subscriptions define the allowed catalog.'
Write-Host 'Then open the YouTube app on your phone, tap the cast/remote icon, and pair with this TV.'
