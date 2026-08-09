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
    [string]$ApkPath = (Join-Path $PSScriptRoot '..\.temp\release-build\dist\TizenTube-Liked-Subs-arm.apk'),
    [string]$Adb = 'C:\Users\YossiMH\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [switch]$Restore
)

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

if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }

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

