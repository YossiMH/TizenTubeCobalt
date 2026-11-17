# Sign exactly two pinned, already-built APKs with the existing local key.
# No compilation, installation, UI, service changes, or private-key export.
# Replace every <PLACEHOLDER> and supply signing passwords only in the local environment.
# Never commit the filled script, exchange nonce, passwords, keystore, or signed APKs.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$WorkRoot = '<EXACT_WINDOWS_STAGING_DIRECTORY>'
$TransferOrigin = '<AUTHORIZED_EXCHANGE_ORIGIN>'
$ExchangeNonce = '<ONE_USE_64_HEX_EXCHANGE_NONCE>'
$ExpectedCertificate = '<INSTALLED_CERTIFICATE_SHA256>'
$Artifacts = @(
    @{ Name = 'morning'; Download = '/morning-unsigned.apk'; Upload = '/signed/morning.apk';
       Sha256 = '<MORNING_UNSIGNED_APK_SHA256>' },
    @{ Name = 'guard'; Download = '/guard-unsigned.apk'; Upload = '/signed/guard.apk';
       Sha256 = '<GUARD_UNSIGNED_APK_SHA256>' }
)

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Quote-ProcessArgument([string] $Value) {
    # Windows argv quoting for a direct process launch; no command shell.
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

function Invoke-BoundedProcess([string] $Executable, [string[]] $Arguments, [int] $Seconds) {
    $Info = New-Object System.Diagnostics.ProcessStartInfo
    $Info.FileName = $Executable
    $Info.Arguments = (($Arguments | ForEach-Object { Quote-ProcessArgument $_ }) -join ' ')
    $Info.WorkingDirectory = $WorkRoot
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $Info
    try {
        if (-not $Process.Start()) { throw 'Could not start the selected signing utility.' }
        try { $Process.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal } catch {}
        $OutputTask = $Process.StandardOutput.ReadToEndAsync()
        $ErrorTask = $Process.StandardError.ReadToEndAsync()
        if (-not $Process.WaitForExit($Seconds * 1000)) {
            # Terminate only this exact child created by this function.
            try { $Process.Kill() } catch {}
            [void] $Process.WaitForExit(5000)
            throw ('Signing utility exceeded its {0}-second limit.' -f $Seconds)
        }
        $Output = $OutputTask.GetAwaiter().GetResult()
        $Errors = $ErrorTask.GetAwaiter().GetResult()
        if ($Process.ExitCode -ne 0) {
            $Detail = ($Output + "`n" + $Errors).Trim()
            if ($Detail.Length -gt 4000) { $Detail = $Detail.Substring(0, 4000) }
            throw ('Signing utility exit {0}: {1}' -f $Process.ExitCode, $Detail)
        }
        return ($Output + "`n" + $Errors)
    } finally {
        $Process.Dispose()
    }
}

function Read-BoundedResponse($Response, [int] $MaximumBytes, $CancellationToken) {
    $Length = $Response.Content.Headers.ContentLength
    if ($null -ne $Length -and $Length -gt $MaximumBytes) { throw 'HTTP content exceeds the exact transfer size limit.' }
    $Stream = $Response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $Memory = New-Object System.IO.MemoryStream
    try {
        $Buffer = New-Object byte[] 8192
        while ($true) {
            $Read = $Stream.ReadAsync($Buffer, 0, $Buffer.Length, $CancellationToken).GetAwaiter().GetResult()
            if ($Read -eq 0) { break }
            if ($Memory.Length + $Read -gt $MaximumBytes) { throw 'HTTP body exceeds the exact transfer size limit.' }
            $Memory.Write($Buffer, 0, $Read)
        }
        return ,$Memory.ToArray()
    } finally {
        $Memory.Dispose()
        $Stream.Dispose()
    }
}

function Receive-PinnedApk([string] $RelativePath, [string] $Destination, [string] $ExpectedHash) {
    $Cancellation = New-Object System.Threading.CancellationTokenSource
    $Cancellation.CancelAfter(20000)
    $Response = $null
    try {
        $Response = $Http.GetAsync(($TransferOrigin + $RelativePath),
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $Cancellation.Token).GetAwaiter().GetResult()
        if ([int] $Response.StatusCode -ne 200) { throw ('APK download HTTP {0}.' -f [int] $Response.StatusCode) }
        [byte[]] $Bytes = Read-BoundedResponse $Response 524288 $Cancellation.Token
        $File = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $File.Write($Bytes, 0, $Bytes.Length) } finally { $File.Dispose() }
        if ((Get-Sha256 $Destination) -ne $ExpectedHash) { throw ('Unsigned APK hash mismatch: ' + $RelativePath) }
    } finally {
        if ($null -ne $Response) { $Response.Dispose() }
        $Cancellation.Dispose()
    }
}

function Get-ApkPayloadHashes([string] $Path) {
    $Archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    $Result = [System.Collections.Generic.Dictionary[string,string]]::new([System.StringComparer]::Ordinal)
    try {
        foreach ($Entry in $Archive.Entries) {
            if ($Entry.FullName.StartsWith('META-INF/', [System.StringComparison]::Ordinal)) { continue }
            if ($Entry.FullName.EndsWith('/')) { continue }
            $Stream = $Entry.Open()
            $Hasher = [System.Security.Cryptography.SHA256]::Create()
            try {
                $Hash = [System.BitConverter]::ToString($Hasher.ComputeHash($Stream)).Replace('-', '').ToLowerInvariant()
                $Result.Add($Entry.FullName, $Hash)
            } finally { $Hasher.Dispose(); $Stream.Dispose() }
        }
        return ,$Result
    } finally { $Archive.Dispose() }
}

function Save-Receipt {
    $Receipt.updated_at_utc = [DateTime]::UtcNow.ToString('o')
    $Text = $Receipt | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($ReceiptTemp, $Text, [System.Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $ReceiptPath) {
        [System.IO.File]::Replace($ReceiptTemp, $ReceiptPath, [NullString]::Value)
    } else { [System.IO.File]::Move($ReceiptTemp, $ReceiptPath) }
}

function Send-SignedApk([string] $RelativePath, [string] $Path, [string] $ExpectedHash) {
    $Cancellation = New-Object System.Threading.CancellationTokenSource
    $Cancellation.CancelAfter(20000)
    $Request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,
        ($TransferOrigin + $RelativePath))
    $Response = $null
    $Stream = [System.IO.File]::OpenRead($Path)
    try {
        $Request.Content = [System.Net.Http.StreamContent]::new($Stream)
        $Request.Content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/vnd.android.package-archive')
        $Request.Content.Headers.ContentLength = $Stream.Length
        [void] $Request.Headers.TryAddWithoutValidation('X-Content-SHA256', $ExpectedHash)
        $Response = $Http.SendAsync($Request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            $Cancellation.Token).GetAwaiter().GetResult()
        if (-not $Response.IsSuccessStatusCode) { throw ('Signed upload HTTP {0}.' -f [int] $Response.StatusCode) }
        [byte[]] $Body = Read-BoundedResponse $Response 16384 $Cancellation.Token
        $Ack = [System.Text.Encoding]::UTF8.GetString($Body) | ConvertFrom-Json
        if ($Ack.ok -ne $true -or $Ack.sha256 -ne $ExpectedHash -or [long] $Ack.bytes -ne $Stream.Length) {
            throw 'Upload response does not confirm the exact signed APK; inspect the remote receipt before any new action.'
        }
        return @{ http_status = [int] $Response.StatusCode; sha256 = $Ack.sha256; bytes = [long] $Ack.bytes }
    } finally {
        if ($null -ne $Response) { $Response.Dispose() }
        $Request.Dispose()
        $Stream.Dispose()
        $Cancellation.Dispose()
    }
}

# Validate all fixed inputs before creating a claim or issuing any HTTP request.
foreach ($Artifact in $Artifacts) {
    if ($Artifact.Sha256 -notmatch '^[0-9a-f]{64}$') { throw ('Missing final APK pin: ' + $Artifact.Name) }
}
if ($ExchangeNonce -notmatch '^[0-9a-f]{64}$') { throw 'Missing exact one-use exchange authorization nonce.' }
if ([System.IO.Path]::GetFullPath($WorkRoot) -ne '<EXACT_WINDOWS_STAGING_DIRECTORY>') { throw 'Unexpected signing directory.' }
$Keystore = '<EXISTING_LOCAL_KEYSTORE>'
$SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) { throw 'Existing debug signing keystore is unavailable.' }
$Versions = @(Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory | ForEach-Object {
    try { [pscustomobject] @{ Directory = $_.FullName; Version = [version] $_.Name } } catch {}
})
$LatestTools = $Versions | Sort-Object Version -Descending | Select-Object -First 1
if ($null -eq $LatestTools) { throw 'No stable Android build-tools directory is available.' }
$ApkSignerJar = Join-Path $LatestTools.Directory 'lib\apksigner.jar'
$Zipalign = Join-Path $LatestTools.Directory 'zipalign.exe'
foreach ($Tool in @($ApkSignerJar, $Zipalign)) {
    if (-not (Test-Path -LiteralPath $Tool -PathType Leaf)) { throw ('Required signing tool is missing: ' + $Tool) }
}
$JavaCommand = Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1
$Java = $JavaCommand.Source
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not (Test-Path -LiteralPath $WorkRoot)) { [void] (New-Item -ItemType Directory -Path $WorkRoot) }
$WorkItem = Get-Item -LiteralPath $WorkRoot
if (-not $WorkItem.PSIsContainer -or ($WorkItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
    throw 'Signing directory must be the exact ordinary directory.'
}
$ReceiptPath = Join-Path $WorkRoot 'signing-receipt.json'
$ReceiptTemp = Join-Path $WorkRoot 'signing-receipt.tmp'
$ClaimPath = Join-Path $WorkRoot 'signing.once'
if (Test-Path -LiteralPath $ClaimPath) {
    throw 'This signing operation was already claimed. Read signing-receipt.json and the Onn upload receipts; do not replay it.'
}
$Claim = [System.IO.File]::Open($ClaimPath, [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
$Claim.Dispose()
$Receipt = [ordered] @{
    schema = 1; phase = 'claimed'; status = 'running'; automatic_retry = $false;
    started_at_utc = [DateTime]::UtcNow.ToString('o'); updated_at_utc = '';
    expected_certificate_sha256 = $ExpectedCertificate;
    expected_unsigned_sha256 = @{ morning = $Artifacts[0].Sha256; guard = $Artifacts[1].Sha256 };
    artifacts = @(); uploads = @(); error = $null
}
$Handler = New-Object System.Net.Http.HttpClientHandler
$Handler.AllowAutoRedirect = $false
$Handler.UseProxy = $false
$Http = [System.Net.Http.HttpClient]::new($Handler)
$Http.Timeout = [TimeSpan]::FromSeconds(20)
$Http.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $ExchangeNonce)
try {
    Save-Receipt
    $CertificatePath = Join-Path $WorkRoot 'signing-certificate.der'
    if (Test-Path -LiteralPath $CertificatePath) { throw 'Public certificate output already exists; inspect prior work.' }
    [void] (Invoke-BoundedProcess $Java @('-Xms16m', '-Xmx128m', 'sun.security.tools.keytool.Main',
        '-exportcert', '-keystore', $Keystore, '-alias', '<EXISTING_KEY_ALIAS>', '-storepass:env', 'APK_SIGN_STOREPASS',
        '-file', $CertificatePath) 15)
    if ((Get-Sha256 $CertificatePath) -ne $ExpectedCertificate) { throw 'Existing keystore certificate does not match both installed apps.' }

    foreach ($Artifact in $Artifacts) {
        $Name = $Artifact.Name
        $Unsigned = Join-Path $WorkRoot ($Name + '-unsigned.apk')
        $Aligned = Join-Path $WorkRoot ($Name + '-aligned.apk')
        $Signed = Join-Path $WorkRoot ($Name + '-signed.apk')
        foreach ($Output in @($Unsigned, $Aligned, $Signed)) {
            if (Test-Path -LiteralPath $Output) { throw ('Output already exists; inspect prior work: ' + $Output) }
        }
        $Receipt.phase = 'download_' + $Name; Save-Receipt
        Receive-PinnedApk $Artifact.Download $Unsigned $Artifact.Sha256
        $Receipt.phase = 'sign_' + $Name; Save-Receipt
        [void] (Invoke-BoundedProcess $Zipalign @('4', $Unsigned, $Aligned) 10)
        [void] (Invoke-BoundedProcess $Java @('-Xms16m', '-Xmx128m', '-jar', $ApkSignerJar,
            'sign', '--ks', $Keystore, '--ks-key-alias', '<EXISTING_KEY_ALIAS>', '--ks-pass', 'env:APK_SIGN_STOREPASS',
            '--key-pass', 'env:APK_SIGN_KEYPASS', '--v4-signing-enabled', 'false', '--out', $Signed, $Aligned) 25)
        $Verification = Invoke-BoundedProcess $Java @('-Xms16m', '-Xmx128m', '-jar', $ApkSignerJar,
            'verify', '--verbose', '--print-certs', $Signed) 15
        $Certificates = [regex]::Matches($Verification, '(?im)^(?:Signer #\d+|V3\.0 Signer:) certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$')
        if ($Certificates.Count -ne 1 -or $Certificates[0].Groups[1].Value.ToLowerInvariant() -ne $ExpectedCertificate) {
            throw ('Signed APK certificate mismatch: ' + $Name)
        }
        [void] (Invoke-BoundedProcess $Zipalign @('-c', '4', $Signed) 10)
        $Before = Get-ApkPayloadHashes $Unsigned
        $After = Get-ApkPayloadHashes $Signed
        if ($Before.Count -ne $After.Count) { throw ('Signing changed payload inventory: ' + $Name) }
        foreach ($Entry in $Before.GetEnumerator()) {
            if (-not $After.ContainsKey($Entry.Key) -or $After[$Entry.Key] -ne $Entry.Value) {
                throw ('Signing changed APK payload: ' + $Name + '/' + $Entry.Key)
            }
        }
        $Receipt.artifacts += @{
            name = $Name; unsigned_sha256 = $Artifact.Sha256; signed_sha256 = (Get-Sha256 $Signed);
            signed_bytes = (Get-Item -LiteralPath $Signed).Length; certificate_sha256 = $ExpectedCertificate;
            unchanged_payloads = $Before.Count; signed_path = $Signed
        }
        $Receipt.phase = 'verified_' + $Name; Save-Receipt
    }

    # Both artifacts are signed and verified before the first one-use upload.
    for ($Index = 0; $Index -lt $Artifacts.Count; $Index++) {
        $Artifact = $Artifacts[$Index]
        $Verified = $Receipt.artifacts[$Index]
        $Receipt.phase = 'upload_' + $Artifact.Name + '_pending'; Save-Receipt
        $Ack = Send-SignedApk $Artifact.Upload $Verified.signed_path $Verified.signed_sha256
        $Receipt.uploads += @{ name = $Artifact.Name; state = 'confirmed'; acknowledgement = $Ack }
        $Receipt.phase = 'upload_' + $Artifact.Name + '_confirmed'; Save-Receipt
    }
    $Receipt.phase = 'complete'; $Receipt.status = 'complete'; Save-Receipt
    $Receipt | ConvertTo-Json -Depth 8 -Compress
} catch {
    $Receipt.status = 'failed'; $Receipt.error = $_.Exception.Message
    Save-Receipt
    $Receipt | ConvertTo-Json -Depth 8 -Compress
    throw
} finally {
    $Http.Dispose()
    $Handler.Dispose()
}
