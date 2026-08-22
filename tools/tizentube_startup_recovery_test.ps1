#requires -Version 7
param(
    [string]$Serial = '192.168.1.172:5555',
    [string]$Adb = 'C:\Users\YossiMH\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$setupScript = Join-Path $repoRoot 'tools/onn_setup.ps1'
$output = & $setupScript -Serial $Serial -Adb $Adb -VerifyApp
if ($LASTEXITCODE -ne 0) { throw 'startup recovery setup exited nonzero' }

function Get-RequiredMarker {
    param([string]$Name)
    $line = $output | Where-Object { $_ -match ('^' + [regex]::Escape($Name) + '=(.*)$') } | Select-Object -Last 1
    if (-not $line) { throw "missing recovery evidence marker: $Name" }
    return $Matches[1]
}

$state = Get-RequiredMarker 'STARTUP_RECOVERY_STATE'
$attempt = [int](Get-RequiredMarker 'STARTUP_RECOVERY_ATTEMPT')
$recoveredPid = Get-RequiredMarker 'STARTUP_RECOVERY_PID'
$filterActive = Get-RequiredMarker 'STARTUP_RECOVERY_FILTER_ACTIVE'
$bootReady = Get-RequiredMarker 'STARTUP_RECOVERY_BOOT_READY'
$enabledPackages = (Get-RequiredMarker 'LOCKDOWN_ENABLED_PACKAGES') -split ',' | Where-Object { $_ }
$evidencePath = Get-RequiredMarker 'STARTUP_RECOVERY_EVIDENCE'

if ($state -notin @('pending','retrying-offline','confirmed','failed')) {
    throw "recovery returned non-lifecycle status: $state"
}
if ($state -ne 'confirmed') { throw "startup recovery did not confirm health: $state" }
if ($attempt -lt 1 -or $attempt -gt 3) { throw "retry attempt outside bounded budget: $attempt" }
if (-not $recoveredPid) { throw 'recovery did not identify the live process' }
if ($filterActive -ne '1') { throw '[allowed-only] filter did not activate' }
if ($bootReady -ne '1') { throw 'allowed-only boot-ready/fail-closed marker was absent' }
if (@($enabledPackages) -ne @('io.gh.yossim.tizentube.cobalt')) {
    throw ('unexpected enabled YouTube-capable packages: ' + ($enabledPackages -join ','))
}
if (-not (Test-Path $evidencePath)) { throw "evidence bundle missing: $evidencePath" }
$evidence = Get-Content -Raw $evidencePath
foreach ($required in @('[allowed-only] filter active', '[allowed-only] boot ready')) {
    if ($evidence -notmatch [regex]::Escape($required)) {
        throw "required runtime marker missing from evidence: $required"
    }
}

Write-Host 'All TizenTube startup recovery tests passed.'
