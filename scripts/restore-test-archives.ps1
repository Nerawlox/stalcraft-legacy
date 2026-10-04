param([ValidateSet('Auto', 'Client', 'Server')][string]$Role = 'Auto')
$ErrorActionPreference = 'Stop'
$kitDownloadRoot = [IO.Path]::GetFullPath($PSScriptRoot)

function Local-File([string]$Name) {
    if ($Name -notmatch '^[A-Za-z0-9._-]+$' -or [IO.Path]::GetFileName($Name) -ne $Name) {
        throw "Invalid local file name in manifest: $Name"
    }
    $p = Join-Path $kitDownloadRoot $Name
    if ((Test-Path -LiteralPath $p) -and ((Get-Item -LiteralPath $p).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "File links are not accepted: $Name"
    }
    return $p
}

function File-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $hash.Dispose()
    }
}

$manifest = Get-Content -LiteralPath (Local-File 'archive-parts.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.schema -ne 1) { throw 'Unsupported archive manifest' }
$selected = 0
foreach ($archive in $manifest.archives) {
    if ($Role -ne 'Auto' -and $archive.role -ne $Role) { continue }
    $parts = @($archive.parts)
    if ($parts.Count -lt 1 -or $parts.Count -gt 64) { throw 'Invalid part count' }
    $present = @($parts | Where-Object { Test-Path -LiteralPath (Local-File $_.name) })
    if ($Role -eq 'Auto' -and $present.Count -eq 0) { continue }
    $selected++
    if ($archive.name -notmatch '\.zip$' -or $archive.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Invalid archive identity' }
    $destination = Local-File $archive.name
    [long]$total = 0
    foreach ($part in $parts) {
        $partPath = Local-File $part.name
        if ($part.name -eq $archive.name -or $part.bytes -le 0 -or !(Test-Path -LiteralPath $partPath -PathType Leaf)) {
            throw "Missing or invalid part: $($part.name)"
        }
        if ((Get-Item -LiteralPath $partPath).Length -ne [long]$part.bytes) { throw "Wrong part size: $($part.name)" }
        $total += [long]$part.bytes
    }
    if ($total -ne [long]$archive.bytes) { throw 'Manifest byte count mismatch' }
    if (Test-Path -LiteralPath $destination) {
        if ((File-Sha256 $destination) -ne $archive.sha256) {
            throw "Existing archive differs; preserved without overwriting: $($archive.name)"
        }
        Write-Host "Already verified: $($archive.name)"
        continue
    }
    $partial = Local-File ($archive.name + '.partial')
    if (Test-Path -LiteralPath $partial) { throw "Previous partial output preserved: $partial" }
    Write-Host "Restoring $($archive.name) ..."
    $output = [IO.File]::Open($partial, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $digest = [Security.Cryptography.SHA256]::Create()
    $buffer = New-Object byte[] (1024 * 1024)
    try {
        foreach ($part in $parts) {
            Write-Host "  $($part.name)"
            $inputStream = [IO.File]::OpenRead((Local-File $part.name))
            try {
                while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $output.Write($buffer, 0, $read)
                    [void]$digest.TransformBlock($buffer, 0, $read, $null, 0)
                }
            } finally { $inputStream.Dispose() }
        }
        [void]$digest.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        $actual = [BitConverter]::ToString($digest.Hash).Replace('-', '').ToLowerInvariant()
        if ($output.Length -ne [long]$archive.bytes -or $actual -ne $archive.sha256) {
            throw 'Archive checksum mismatch; original parts and partial output preserved'
        }
    } finally {
        $output.Dispose()
        $digest.Dispose()
    }
    Move-Item -LiteralPath $partial -Destination $destination
    Write-Host "SHA-256 PASS: $($archive.name)"
}
if ($selected -eq 0) { throw 'No archive parts found. Download every part of Client or Server beside this script and archive-parts.json.' }
Write-Host 'Extract the verified ZIP with Windows Explorer, then read START-HERE-RU.txt.'
