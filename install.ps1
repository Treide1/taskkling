# taskkling installer for Windows (PowerShell).
#
# Quick install:
#   irm https://github.com/Treide1/taskkling/releases/latest/download/install.ps1 | iex
#
# Pin a version (defaults to the latest release):
#   $env:TASKKLING_VERSION = 'v0.1.0'; irm https://github.com/Treide1/taskkling/releases/latest/download/install.ps1 | iex
$ErrorActionPreference = 'Stop'

$repo = 'Treide1/taskkling'
$version = if ($env:TASKKLING_VERSION) { $env:TASKKLING_VERSION } else { 'latest' }
$baseUrl = if ($env:TASKKLING_BASE_URL) { $env:TASKKLING_BASE_URL } else { "https://github.com/$repo/releases" }

# Windows x64 only — that is the single Windows binary that gets published.
$arch = $env:PROCESSOR_ARCHITECTURE
if ($arch -ne 'AMD64') {
    throw "Unsupported architecture '$arch'. taskkling for Windows is published for x64 (AMD64) only."
}

$asset = 'taskkling-windows-x64.exe'

if ($version -eq 'latest') {
    $dl = "$baseUrl/latest/download"
} else {
    # Accept both '0.1.0' and 'v0.1.0'; release tags are 'vX.Y.Z'.
    $tag = if ($version.StartsWith('v')) { $version } else { "v$version" }
    $dl = "$baseUrl/download/$tag"
}

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('taskkling-' + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
try {
    $binPath = Join-Path $tmp $asset
    $sumsPath = Join-Path $tmp 'SHA256SUMS'

    Write-Host "Downloading $asset ($version) ..."
    Invoke-WebRequest -Uri "$dl/$asset" -OutFile $binPath -UseBasicParsing
    Invoke-WebRequest -Uri "$dl/SHA256SUMS" -OutFile $sumsPath -UseBasicParsing

    # Verify the binary's SHA-256 against the matching line in SHA256SUMS.
    $expected = $null
    foreach ($line in Get-Content -LiteralPath $sumsPath) {
        $parts = $line -split '\s+', 2
        if ($parts.Count -eq 2) {
            $name = $parts[1].TrimStart('*').Trim()
            if ($name -eq $asset) { $expected = $parts[0].Trim().ToLower(); break }
        }
    }
    if (-not $expected) { throw "No checksum entry for $asset in SHA256SUMS." }

    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $binPath).Hash.ToLower()
    if ($actual -ne $expected) {
        throw "Checksum mismatch for $asset.`n  expected: $expected`n  actual:   $actual"
    }
    Write-Host "Checksum OK ($actual)"

    # Install to %LOCALAPPDATA%\Programs\taskkling\taskkling.exe.
    $installDir = Join-Path $env:LOCALAPPDATA 'Programs\taskkling'
    New-Item -ItemType Directory -Path $installDir -Force | Out-Null
    $dest = Join-Path $installDir 'taskkling.exe'
    Move-Item -LiteralPath $binPath -Destination $dest -Force

    Write-Host ''
    Write-Host "Installed taskkling to $dest"
    & $dest --version

    # Add the install dir to the USER PATH if it is not already present.
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($null -eq $userPath) { $userPath = '' }
    $entries = $userPath.Split(';') | Where-Object { $_ -ne '' }
    if ($entries -notcontains $installDir) {
        $trimmed = $userPath.TrimEnd(';')
        $newPath = if ($trimmed -eq '') { $installDir } else { "$trimmed;$installDir" }
        [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        Write-Host ''
        Write-Host "Added $installDir to your user PATH. Restart your shell for it to take effect."
    }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
