# DEV (t-aq99): build + launch the drag-to-link UI dev against THIS checkout's
# .taskkling workspace. Refuses to run against the real dogfood store - the dev
# mutates depends edges, so it must only ever see a demo workspace.
#
# Usage:  .\dev-run.ps1 [-Variant a|b] [-Handles selected-hover|hover] [-SkipBuild]
#   -Variant   overrides the branch default (a = two handles + live validity,
#              b = one handle + drop-side direction + re-drag toggle)
#   -Handles   handle reveal rule (default selected-hover = the task's leaning)
#   -SkipBuild launch the last-built jar without rebuilding
param(
    [ValidateSet("a", "b")] [string]$Variant,
    [ValidateSet("selected-hover", "hover")] [string]$Handles,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# Guard: only run against a demo workspace (the dogfood store contains t-aq99 itself).
$store = Join-Path $root ".taskkling\tasks"
if (-not (Test-Path $store)) {
    Write-Error "no .taskkling workspace at $root - run from a checkout with a demo store"
}
if (Get-ChildItem $store -Filter "t-aq99*" -ErrorAction SilentlyContinue) {
    Write-Error "this looks like the REAL dogfood store - the dev only runs against demo data"
}

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21.0.8"
}
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Error "no JDK found - set JAVA_HOME to a JDK 21"
}

# CLI binary for the UI: explicit env wins; else the main checkout's pin (the UI's
# own discovery can't do the git-common-dir walk the CLI wrapper does).
if (-not $env:TASKKLING_BINARY) {
    $common = git -C $root rev-parse --git-common-dir 2>$null
    if ($common -and $common -ne ".git") {
        $pin = Join-Path (Split-Path $common) ".taskkling\bin\taskkling.exe"
        if (Test-Path $pin) { $env:TASKKLING_BINARY = $pin }
    }
}

if (-not $SkipBuild) {
    & "$root\gradlew.bat" :ui:packageUberJarWindowsX64 --console=plain
    if ($LASTEXITCODE -ne 0) { Write-Error "uberjar build failed" }
}

if ($Variant) { $env:TASKKLING_LINK_DEV = $Variant } else { Remove-Item Env:TASKKLING_LINK_DEV -ErrorAction SilentlyContinue }
if ($Handles) { $env:TASKKLING_LINK_HANDLES = $Handles } else { Remove-Item Env:TASKKLING_LINK_HANDLES -ErrorAction SilentlyContinue }

$jar = Join-Path $root "ui\build\uberjars\taskkling-ui-windows-x64.jar"
Write-Host "launching dev (variant=$(if ($Variant) { $Variant } else { 'branch default' }), binary=$env:TASKKLING_BINARY)"
Start-Process -FilePath "$env:JAVA_HOME\bin\javaw.exe" -ArgumentList "-jar", "`"$jar`"" -WorkingDirectory $root
