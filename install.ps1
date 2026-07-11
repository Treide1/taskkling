# taskkling installer for Windows (PowerShell).
#
# Quick install:
#   irm https://github.com/Treide1/taskkling/releases/latest/download/install.ps1 | iex
#
# Pin a version (defaults to the latest release):
#   $env:TASKKLING_VERSION = 'v0.1.0'; irm https://github.com/Treide1/taskkling/releases/latest/download/install.ps1 | iex
#
# Flags. A bare `irm | iex` pipes script *text* into iex, which cannot receive named
# arguments, so flags only reach the script via the scriptblock-invocation pattern or by
# downloading the script first:
#   iex "& { $(irm https://github.com/Treide1/taskkling/releases/latest/download/install.ps1) } -NoPath"
#   # or:
#   Invoke-WebRequest .../install.ps1 -OutFile install.ps1; .\install.ps1 -NoPath
#
#   -NoPath                Install the binary and print --version, but skip the HKCU
#                           user-PATH registry write entirely. Use this to verify a release
#                           in isolation without touching the real user PATH — see
#                           docs/RELEASING.md post-publish check 3.
#   -InstallDir <path>     Override where the binary is placed (defaults to
#                           %LOCALAPPDATA%\Programs\taskkling, or $env:TASKKLING_INSTALL_DIR
#                           if set). Combine with -NoPath for a fully isolated, disposable
#                           install that touches nothing outside <path>.
param(
    [switch]$NoPath,
    [string]$InstallDir = $(if ($env:TASKKLING_INSTALL_DIR) { $env:TASKKLING_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\taskkling' })
)
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

    # Install to $InstallDir\taskkling.exe (see -InstallDir above for overrides).
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    $dest = Join-Path $InstallDir 'taskkling.exe'
    Move-Item -LiteralPath $binPath -Destination $dest -Force

    Write-Host ''
    Write-Host "Installed taskkling to $dest"
    & $dest --version

    # Materialize the user-level config.toml (ADR-006) so the on-by-default
    # update_check notifier's OFF switch is discoverable right after install.
    # Best-effort: a config-write hiccup must never fail the install.
    try { & $dest config init | Out-Null } catch { }

    if ($NoPath) {
        Write-Host ''
        Write-Host '-NoPath set: skipping the user PATH registry write.'
    } else {
        # Add the install dir to the USER PATH if it is not already present.
        #
        # Edit the registry directly instead of [Environment]::*EnvironmentVariable:
        # that API reads the *expanded* PATH and always writes REG_SZ, which downgrades
        # REG_EXPAND_SZ -> REG_SZ and freezes every %VAR% entry (e.g.
        # %USERPROFILE%\AppData\Local\Microsoft\WindowsApps) into a literal path. Read
        # raw (DoNotExpandEnvironmentNames) and write back as ExpandString. Same approach
        # as the bun and cargo-dist installers.
        # Test seam (t-dywy): the shell-level regression harness (scripts/tests/install-ps1-path.tests.ps1)
        # points this at a disposable HKCU scratch subkey instead of the real user Environment key, so it
        # can exercise the append/idempotence/REG_EXPAND_SZ logic without ever touching HKCU\Environment.
        # Defaults to 'Environment' — unset (and therefore a no-op) on every real install.
        $envSubKeyName = if ($env:TASKKLING_TEST_ENV_SUBKEY) { $env:TASKKLING_TEST_ENV_SUBKEY } else { 'Environment' }
        $envKey = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($envSubKeyName, $true)
        try {
            $rawPath = [string]$envKey.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
            # Match on the filtered split (a `;;`/trailing-`;` is never a real entry), but rebuild
            # from the RAW value so a pre-existing empty segment survives verbatim — ADR-004
            # minimal-touch: the add owns only its own entry and must not silently normalize the
            # user's PATH cosmetics away on the first rewrite (t-359h). '' -> just $InstallDir.
            $entries = @($rawPath.Split(';') | Where-Object { $_ -ne '' })
            if ($entries -notcontains $InstallDir) {
                $newPath = if ($rawPath -eq '') { $InstallDir } else { "$rawPath;$InstallDir" }
                # Keep PATH expandable when it carries %VAR% refs; else preserve the
                # existing kind; else default to ExpandString for a fresh value.
                $kind = if ($newPath.Contains('%')) {
                    [Microsoft.Win32.RegistryValueKind]::ExpandString
                } elseif ($null -ne $envKey.GetValue('Path')) {
                    $envKey.GetValueKind('Path')
                } else {
                    [Microsoft.Win32.RegistryValueKind]::ExpandString
                }
                $envKey.SetValue('Path', $newPath, $kind)
                Write-Host ''
                Write-Host "Added $InstallDir to your user PATH. Restart your shell for it to take effect."

                # Nudge running processes so new shells see it without a reboot:
                # round-tripping a dummy USER var fires WM_SETTINGCHANGE with no P/Invoke.
                [Environment]::SetEnvironmentVariable('TASKKLING_PATH_SYNC', '1', 'User')
                [Environment]::SetEnvironmentVariable('TASKKLING_PATH_SYNC', [NullString]::Value, 'User')
            }
        } finally {
            $envKey.Dispose()
        }
    }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
