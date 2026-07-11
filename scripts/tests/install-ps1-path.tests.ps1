# Shell-level regression harness for install.ps1's Windows USER-PATH registry logic (t-dywy).
#
# Runs the ACTUAL install.ps1 end-to-end (fake download -> checksum -> install -> PATH write) as a
# child process, for each scenario below, against:
#   - a disposable HKCU:\Software\taskkling-test-<guid> scratch subkey, via the
#     TASKKLING_TEST_ENV_SUBKEY test seam install.ps1 grew for exactly this purpose. The real
#     HKCU\Environment key is NEVER opened, read, or written by this harness.
#   - a local file:// "release" directory instead of GitHub, via TASKKLING_BASE_URL, so the run is
#     fully offline.
#
# Bug classes pinned down here (see install.ps1's PATH-write comment + git history for context):
#   - t-3vt8: REG_EXPAND_SZ must never be downgraded to REG_SZ on append.
#   - t-3vt8: %VAR% entries must survive the append unexpanded.
#   - t-359h: pre-existing ';;'/trailing-';' cosmetics in the raw value survive the append
#     VERBATIM; a fresh/empty PATH gains no leading ';'.
#   - idempotence: an entry already present (case-insensitively) is never appended twice.
#   - t-kyt1: -NoPath skips the registry write entirely -- not just the value, the key too.
#
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\install-ps1-path.tests.ps1
# Exits non-zero (and prints a FAIL: line per failure) if any assertion fails.

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
$installScript = Join-Path $repoRoot 'install.ps1'
if (-not (Test-Path -LiteralPath $installScript)) { throw "install.ps1 not found at $installScript" }

$script:pass = 0
$script:fail = 0
$script:failures = New-Object System.Collections.Generic.List[string]

function Assert-Equal {
    param($Actual, $Expected, [string]$Label)
    # -ceq: registry PATH text is case-preserving and every byte (incl. ';;' cosmetics) is
    # load-bearing here, so comparisons must be exact, not PowerShell's default case-insensitive.
    if ($null -ne $Actual -and $Actual -ceq $Expected) {
        $script:pass++
    } else {
        $script:fail++
        $script:failures.Add("FAIL: $Label`n  expected: [$Expected]`n  actual:   [$Actual]")
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Label)
    if ($Condition) { $script:pass++ } else { $script:fail++; $script:failures.Add("FAIL: $Label") }
}

# --- Build a fake "release" served over file:// --------------------------------------------------

$fakeRelease = Join-Path $env:TEMP ('taskkling-fakerelease-' + [Guid]::NewGuid().ToString('N'))
$dlDir = Join-Path $fakeRelease 'latest\download'
New-Item -ItemType Directory -Path $dlDir -Force | Out-Null

$fakeExePath = Join-Path $dlDir 'taskkling-windows-x64.exe'
# A real (tiny) PE so install.ps1's `& $dest --version` succeeds instead of throwing on an invalid
# executable format -- the script has no `|| true`-style guard around that call the way install.sh does.
$fakeSrc = 'class P { static int Main(string[] a) { System.Console.WriteLine("fake-taskkling 0.0.0-test"); return 0; } }'
Add-Type -TypeDefinition $fakeSrc -OutputType ConsoleApplication -OutputAssembly $fakeExePath -Language CSharp

$fakeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $fakeExePath).Hash.ToLower()
Set-Content -LiteralPath (Join-Path $dlDir 'SHA256SUMS') -Value "$fakeHash  taskkling-windows-x64.exe" -Encoding ascii -NoNewline

$fakeBaseUrl = 'file:///' + ($fakeRelease -replace '\\', '/')

# --- Scratch registry + install invocation helpers ------------------------------------------------

function New-ScratchEnvSubKeyName {
    'Software\taskkling-test-' + [Guid]::NewGuid().ToString('N')
}

function New-ScratchEnvSubKey {
    param([string]$Name)
    New-Item -Path "HKCU:\$Name" -Force | Out-Null
}

function Remove-ScratchEnvSubKey {
    param([string]$Name)
    Remove-Item -Path "HKCU:\$Name" -Recurse -Force -ErrorAction SilentlyContinue
}

function Set-ScratchPathValue {
    param([string]$Name, [string]$Value, [Microsoft.Win32.RegistryValueKind]$Kind)
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($Name, $true)
    try { $key.SetValue('Path', $Value, $Kind) } finally { $key.Dispose() }
}

function Get-ScratchPathRaw {
    param([string]$Name)
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($Name, $false)
    try {
        if ($null -eq $key) { return $null }
        [string]$key.GetValue('Path', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    } finally { if ($key) { $key.Dispose() } }
}

function Get-ScratchPathKind {
    param([string]$Name)
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($Name, $false)
    try { $key.GetValueKind('Path') } finally { $key.Dispose() }
}

function Invoke-Install {
    param([string]$InstallDir, [string]$EnvSubKeyName, [switch]$NoPath)
    $env:TASKKLING_BASE_URL = $fakeBaseUrl
    $env:TASKKLING_VERSION = 'latest'
    $env:TASKKLING_TEST_ENV_SUBKEY = $EnvSubKeyName
    try {
        $psArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $installScript, '-InstallDir', $InstallDir)
        if ($NoPath) { $psArgs += '-NoPath' }
        $output = & powershell.exe @psArgs 2>&1 | Out-String
        [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
    } finally {
        Remove-Item Env:\TASKKLING_BASE_URL, Env:\TASKKLING_VERSION, Env:\TASKKLING_TEST_ENV_SUBKEY -ErrorAction SilentlyContinue
    }
}

function New-ScratchInstallDir {
    Join-Path $env:TEMP ('taskkling-test-install-' + [Guid]::NewGuid().ToString('N'))
}

# --- Test cases -------------------------------------------------------------------------------

function Test-Case {
    param([string]$Name, [scriptblock]$Body)
    $envSubKey = New-ScratchEnvSubKeyName
    try {
        & $Body $envSubKey
    } catch {
        $script:fail++
        $script:failures.Add("FAIL: $Name (threw: $($_.Exception.Message))")
    } finally {
        Remove-ScratchEnvSubKey $envSubKey
    }
}

# 1. REG_EXPAND_SZ kind survives the append when the appended value has no '%' of its own.
Test-Case 'RegExpandSzKindSurvivesAppend_NoPercent' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey 'C:\Windows' ([Microsoft.Win32.RegistryValueKind]::ExpandString)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'RegExpandSzKindSurvivesAppend_NoPercent: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "C:\Windows;$installDir" 'RegExpandSzKindSurvivesAppend_NoPercent: raw value'
    Assert-Equal (Get-ScratchPathKind $envSubKey) ([Microsoft.Win32.RegistryValueKind]::ExpandString) 'RegExpandSzKindSurvivesAppend_NoPercent: value kind stays ExpandString'
}

# 2. Plain REG_SZ is NOT upgraded to REG_EXPAND_SZ when the append introduces no '%'.
Test-Case 'RegSzKindStaysRegSzWhenNoPercentIntroduced' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey 'C:\Windows' ([Microsoft.Win32.RegistryValueKind]::String)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'RegSzKindStaysRegSzWhenNoPercentIntroduced: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "C:\Windows;$installDir" 'RegSzKindStaysRegSzWhenNoPercentIntroduced: raw value'
    Assert-Equal (Get-ScratchPathKind $envSubKey) ([Microsoft.Win32.RegistryValueKind]::String) 'RegSzKindStaysRegSzWhenNoPercentIntroduced: value kind stays String'
}

# 3. A %VAR% entry survives the append UNEXPANDED (raw read, not the real user profile path).
Test-Case 'PercentVarEntrySurvivesUnexpanded' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey '%USERPROFILE%\bin' ([Microsoft.Win32.RegistryValueKind]::ExpandString)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'PercentVarEntrySurvivesUnexpanded: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "%USERPROFILE%\bin;$installDir" 'PercentVarEntrySurvivesUnexpanded: raw value keeps literal %USERPROFILE%'
    Assert-Equal (Get-ScratchPathKind $envSubKey) ([Microsoft.Win32.RegistryValueKind]::ExpandString) 'PercentVarEntrySurvivesUnexpanded: value kind stays ExpandString'
}

# 4. A '%' introduced by the EXISTING value forces ExpandString even if it was mis-stored as REG_SZ.
Test-Case 'PercentInExistingRegSzForcesExpandStringOnAppend' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey '%USERPROFILE%\bin' ([Microsoft.Win32.RegistryValueKind]::String)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'PercentInExistingRegSzForcesExpandStringOnAppend: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "%USERPROFILE%\bin;$installDir" 'PercentInExistingRegSzForcesExpandStringOnAppend: raw value'
    Assert-Equal (Get-ScratchPathKind $envSubKey) ([Microsoft.Win32.RegistryValueKind]::ExpandString) 'PercentInExistingRegSzForcesExpandStringOnAppend: value kind becomes ExpandString'
}

# 5. t-359h: a trailing ';' survives verbatim (append is raw concatenation, not re-normalized).
Test-Case 'TrailingSemicolonSurvivesVerbatim' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey 'C:\Windows;' ([Microsoft.Win32.RegistryValueKind]::String)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'TrailingSemicolonSurvivesVerbatim: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "C:\Windows;;$installDir" 'TrailingSemicolonSurvivesVerbatim: raw value preserves the original trailing ; verbatim'
}

# 6. t-359h: leading ';' and an internal ';;' both survive verbatim.
Test-Case 'LeadingAndDoubledEmptySegmentsSurviveVerbatim' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey ';C:\Windows;;C:\Windows\system32' ([Microsoft.Win32.RegistryValueKind]::String)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'LeadingAndDoubledEmptySegmentsSurviveVerbatim: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) ";C:\Windows;;C:\Windows\system32;$installDir" 'LeadingAndDoubledEmptySegmentsSurviveVerbatim: raw value'
}

# 7. A MISSING Path value (fresh scratch key, never had one) gets no leading ';'.
Test-Case 'FreshMissingPathGetsNoLeadingSemicolon' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'FreshMissingPathGetsNoLeadingSemicolon: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "$installDir" 'FreshMissingPathGetsNoLeadingSemicolon: raw value is the install dir alone'
    Assert-Equal (Get-ScratchPathKind $envSubKey) ([Microsoft.Win32.RegistryValueKind]::ExpandString) 'FreshMissingPathGetsNoLeadingSemicolon: kind defaults to ExpandString'
}

# 8. An EMPTY-STRING Path value (present but "") also gets no leading ';', and keeps its prior kind.
Test-Case 'EmptyStringPathGetsNoLeadingSemicolon' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey '' ([Microsoft.Win32.RegistryValueKind]::String)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'EmptyStringPathGetsNoLeadingSemicolon: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "$installDir" 'EmptyStringPathGetsNoLeadingSemicolon: raw value is the install dir alone'
    Assert-Equal (Get-ScratchPathKind $envSubKey) ([Microsoft.Win32.RegistryValueKind]::String) 'EmptyStringPathGetsNoLeadingSemicolon: kind preserved (was present, just empty)'
}

# 9. Idempotence: an exact-match entry is not appended twice.
Test-Case 'IdempotentExactMatchNotAppendedTwice' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    $installDir = New-ScratchInstallDir
    Set-ScratchPathValue $envSubKey "C:\Windows;$installDir" ([Microsoft.Win32.RegistryValueKind]::String)
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'IdempotentExactMatchNotAppendedTwice: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) "C:\Windows;$installDir" 'IdempotentExactMatchNotAppendedTwice: raw value unchanged (no duplicate)'
}

# 10. Idempotence is case-insensitive (Windows PATH entries are).
Test-Case 'IdempotentCaseInsensitiveMatchNotAppendedTwice' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    $installDir = New-ScratchInstallDir
    $upper = "C:\Windows;$($installDir.ToUpperInvariant())"
    Set-ScratchPathValue $envSubKey $upper ([Microsoft.Win32.RegistryValueKind]::String)
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'IdempotentCaseInsensitiveMatchNotAppendedTwice: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) $upper 'IdempotentCaseInsensitiveMatchNotAppendedTwice: raw value unchanged despite case difference'
}

# 11. Idempotence when the entry sits in the middle of a multi-entry PATH.
Test-Case 'AlreadyPresentEntryAmongMultipleNotDuplicated' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    $installDir = New-ScratchInstallDir
    $existing = "C:\Windows;$installDir;C:\Users\me\.cargo\bin"
    Set-ScratchPathValue $envSubKey $existing ([Microsoft.Win32.RegistryValueKind]::String)
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey
    Assert-True ($result.ExitCode -eq 0) 'AlreadyPresentEntryAmongMultipleNotDuplicated: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) $existing 'AlreadyPresentEntryAmongMultipleNotDuplicated: raw value unchanged'
}

# 12. -NoPath: the registry VALUE is left completely untouched.
Test-Case 'NoPathSkipsRegistryWriteEntirely_ValueUntouched' {
    param($envSubKey)
    New-ScratchEnvSubKey $envSubKey
    Set-ScratchPathValue $envSubKey 'C:\Windows' ([Microsoft.Win32.RegistryValueKind]::String)
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $envSubKey -NoPath
    Assert-True ($result.ExitCode -eq 0) 'NoPathSkipsRegistryWriteEntirely_ValueUntouched: install.ps1 exits 0'
    Assert-Equal (Get-ScratchPathRaw $envSubKey) 'C:\Windows' 'NoPathSkipsRegistryWriteEntirely_ValueUntouched: raw value unchanged'
    Assert-Equal (Get-ScratchPathKind $envSubKey) ([Microsoft.Win32.RegistryValueKind]::String) 'NoPathSkipsRegistryWriteEntirely_ValueUntouched: kind unchanged'
}

# 13. -NoPath: when the scratch key never existed, install.ps1 never even opens/creates it.
$noKeyEnvSubKey = New-ScratchEnvSubKeyName
try {
    $installDir = New-ScratchInstallDir
    $result = Invoke-Install -InstallDir $installDir -EnvSubKeyName $noKeyEnvSubKey -NoPath
    Assert-True ($result.ExitCode -eq 0) 'NoPathSkipsRegistryWriteEntirely_KeyNeverCreated: install.ps1 exits 0'
    Assert-True (-not (Test-Path "HKCU:\$noKeyEnvSubKey")) 'NoPathSkipsRegistryWriteEntirely_KeyNeverCreated: scratch key was never created'
} catch {
    $script:fail++
    $script:failures.Add("FAIL: NoPathSkipsRegistryWriteEntirely_KeyNeverCreated (threw: $($_.Exception.Message))")
} finally {
    Remove-ScratchEnvSubKey $noKeyEnvSubKey
}

# --- Cleanup + summary --------------------------------------------------------------------------

Remove-Item -LiteralPath $fakeRelease -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
if ($script:fail -gt 0) {
    foreach ($f in $script:failures) { Write-Host $f -ForegroundColor Red }
}
Write-Host "install-ps1-path.tests.ps1: $($script:pass) passed, $($script:fail) failed"
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
