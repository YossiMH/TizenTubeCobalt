param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\\Android\\Sdk",
    [string]$Keystore = "$env:USERPROFILE\\.android\\debug.keystore",
    [string]$KeyAlias = "androiddebugkey",
    [string]$KeystorePassword = "android",
    [string]$KeyPassword = "android",
    [string]$OutputPath = "$env:TEMP\\MorningSesameWakeHelper.apk"
)

$ErrorActionPreference = "Stop"

function Get-NewestVersionedDirectory([string]$Path, [scriptblock]$VersionSelector) {
    $dirs = Get-ChildItem -LiteralPath $Path -Directory
    if (-not $dirs) { throw "No versioned directories under $Path" }
    return $dirs | Sort-Object { & $VersionSelector $_ } -Descending | Select-Object -First 1
}

if (-not (Test-Path -LiteralPath $SdkRoot)) { throw "Android SDK not found: $SdkRoot" }
if (-not (Test-Path -LiteralPath $Keystore)) { throw "Signing keystore not found: $Keystore" }

$buildTools = Get-NewestVersionedDirectory (Join-Path $SdkRoot "build-tools") { param($d) [version]$d.Name }
$platform = Get-NewestVersionedDirectory (Join-Path $SdkRoot "platforms") { param($d) [int]($d.Name -replace '^android-', '') }
$androidJar = Join-Path $platform.FullName "android.jar"

$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$d8 = Join-Path $buildTools.FullName "d8.bat"
$zipalign = Join-Path $buildTools.FullName "zipalign.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $androidJar)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required build tool not found: $tool" }
}

$src = Join-Path $PSScriptRoot "morning_sesame_wake_helper"
$manifest = Join-Path $src "AndroidManifest.xml"
$javaSources = Get-ChildItem -LiteralPath $src -Filter "*.java" | Sort-Object Name
if (-not $javaSources) { throw "No Java sources found under $src" }

$work = Join-Path $env:TEMP ("morning-sesame-build-" + [guid]::NewGuid().ToString("N"))
$classesDir = Join-Path $work "classes"
$dexDir = Join-Path $work "dex"
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir | Out-Null

try {
    & javac -source 8 -target 8 -classpath $androidJar -d $classesDir @($javaSources.FullName)
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    $classFiles = Get-ChildItem -LiteralPath $classesDir -Recurse -Filter "*.class"
    & $d8 --lib $androidJar --output $dexDir @($classFiles.FullName)
    if ($LASTEXITCODE -ne 0) { throw "d8 failed with exit code $LASTEXITCODE" }

    $unsigned = Join-Path $work "unsigned.apk"
    & $aapt2 link -o $unsigned -I $androidJar --manifest $manifest --min-sdk-version 26 --target-sdk-version 35
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed with exit code $LASTEXITCODE" }

    Push-Location $dexDir
    try {
        & jar uf $unsigned classes.dex
        if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    $aligned = Join-Path $work "aligned.apk"
    & $zipalign -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
    $signArgs = @(
        "sign",
        "--ks", $Keystore,
        "--ks-key-alias", $KeyAlias,
        "--ks-pass", "pass:$KeystorePassword",
        "--key-pass", "pass:$KeyPassword",
        "--out", $OutputPath,
        $aligned
    )
    & $apksigner @signArgs
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed with exit code $LASTEXITCODE" }

    & $apksigner verify --print-certs $OutputPath
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed with exit code $LASTEXITCODE" }

    Write-Host "Built $OutputPath"
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
