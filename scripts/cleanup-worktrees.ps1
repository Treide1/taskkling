<#
.SYNOPSIS
  Sweep taskkling worktrees and branches back to a main-only state.

.DESCRIPTION
  Removes every git worktree except the main checkout, deletes fully-merged
  local branches, and sweeps orphaned worktree directories that t3code failed
  to remove (its removeWorktree is bugged on Windows).

  Safety gates, none of which -Force overrides unless noted:
    SELF      never touches the worktree this process runs from
    IN-USE    rename-probe: a directory that cannot be renamed has an open
              handle somewhere inside (live agent, JVM daemon, editor) - skipped
    UNMERGED  branch has commits `git cherry main` does not see on main - kept
              unless -Force (raw `log main..branch` lies after rebases; cherry
              compares patch-ids)
    DIRTY     worktree has uncommitted changes - kept unless -Force

  Default is a dry run that prints the plan. Nothing is deleted without
  -Execute, and -Execute prompts for confirmation unless -Yes is given.

.PARAMETER Execute
  Actually delete. Without it the script only prints the plan.

.PARAMETER Force
  Also delete UNMERGED branches and DIRTY worktrees. SELF and IN-USE are
  never overridden.

.PARAMETER Yes
  Skip the interactive confirmation (required when run non-interactively
  with -Execute).

.EXAMPLE
  .\scripts\cleanup-worktrees.ps1              # plan only
  .\scripts\cleanup-worktrees.ps1 -Execute     # prompt, then clean
#>
[CmdletBinding()]
param(
  [switch]$Execute,
  [switch]$Force,
  [switch]$Yes
)

# 'Continue', not 'Stop': under Stop, PS 5.1 turns any stderr line from a
# native git call with a 2>$null redirect into a terminating NativeCommandError.
# Failure handling is explicit via $LASTEXITCODE and -ErrorAction Stop instead.
$ErrorActionPreference = 'Continue'

# --- Resolve the main repo root from wherever this script copy lives -------
$commonDir = git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir
if ($LASTEXITCODE -ne 0) { throw 'Not inside a git repository.' }
$RepoRoot = (Split-Path -Parent $commonDir) -replace '/', '\'

function Get-NormPath([string]$p) {
  return ($p -replace '/', '\').TrimEnd('\').ToLowerInvariant()
}

function Test-PathInside([string]$child, [string]$parent) {
  $c = Get-NormPath $child
  $p = Get-NormPath $parent
  if ($c -eq $p) { return $true }
  return $c.StartsWith($p + '\')
}

# Rename-probe: on Windows, renaming a directory fails if any process holds a
# handle to anything inside it. Rename there and back = cheap liveness check.
function Test-DirUnlocked([string]$Path) {
  $leaf = Split-Path -Leaf $Path
  $probeLeaf = $leaf + '.cleanup-probe'
  $probePath = Join-Path (Split-Path -Parent $Path) $probeLeaf
  try {
    Rename-Item -LiteralPath $Path -NewName $probeLeaf -ErrorAction Stop
  } catch {
    return $false
  }
  try {
    Rename-Item -LiteralPath $probePath -NewName $leaf -ErrorAction Stop
  } catch {
    # Extremely unlikely; do not leave the probe name behind silently.
    Write-Warning "Probe rename-back failed for $Path - directory is now at $probePath"
    throw
  }
  return $true
}

# All commits on <branch> whose patch already exists on main show as '-' in
# git cherry; any '+' line is a genuinely unmerged commit.
function Get-UnmergedCommits([string]$branch) {
  $out = git -C $RepoRoot cherry main $branch 2>$null
  if ($LASTEXITCODE -ne 0) { return @('(git cherry failed - treating as unmerged)') }
  $plus = @($out | Where-Object { $_ -match '^\+' })
  return $plus
}

# --- Gather state -----------------------------------------------------------
$selfDirs = @($PWD.Path, $PSScriptRoot)

# Parse `git worktree list --porcelain` into objects.
$worktrees = @()
$cur = $null
$porcelain = git -C $RepoRoot worktree list --porcelain
foreach ($line in ($porcelain + '')) {
  if ($line -match '^worktree (.+)$') {
    $cur = [pscustomobject]@{ Path = $Matches[1] -replace '/', '\'; Branch = $null; Locked = $false }
  } elseif ($line -match '^branch refs/heads/(.+)$') {
    $cur.Branch = $Matches[1]
  } elseif ($line -match '^locked') {
    $cur.Locked = $true
  } elseif ($line -eq '') {
    if ($null -ne $cur) { $worktrees += $cur; $cur = $null }
  }
}
if ($null -ne $cur) { $worktrees += $cur }

$mainWt = $worktrees[0]   # first entry is always the main checkout
$extraWts = @($worktrees | Select-Object -Skip 1)

$branches = @(git -C $RepoRoot for-each-ref --format='%(refname:short)' refs/heads/) |
  Where-Object { $_ -ne 'main' }

# Orphan directories: on disk under a known worktree root, but not registered.
$registered = @($worktrees | ForEach-Object { Get-NormPath $_.Path })
$repoLeaf = Split-Path -Leaf $RepoRoot
$orphanRoots = @(
  (Join-Path $RepoRoot '.claude\worktrees'),
  (Join-Path $env:USERPROFILE (".t3\worktrees\" + $repoLeaf))
)
$orphans = @()
foreach ($root in $orphanRoots) {
  if (-not (Test-Path $root)) { continue }
  foreach ($d in (Get-ChildItem -LiteralPath $root -Directory)) {
    if ($registered -notcontains (Get-NormPath $d.FullName)) { $orphans += $d.FullName }
  }
}

# --- Build the plan ---------------------------------------------------------
$plan = @()

foreach ($wt in $extraWts) {
  $verdict = 'DELETE'
  $reason = ''
  $isSelf = $false
  foreach ($s in $selfDirs) {
    if (Test-PathInside $s $wt.Path) { $isSelf = $true }
  }
  if ($isSelf) {
    $verdict = 'SELF'; $reason = 'this process runs from here'
  } else {
    $dirty = @(git -C $wt.Path status --porcelain 2>$null)
    if ($LASTEXITCODE -eq 0 -and $dirty.Count -gt 0) {
      if ($Force) { $reason = 'dirty (forced)' }
      else { $verdict = 'DIRTY'; $reason = "$($dirty.Count) uncommitted change(s)" }
    }
    if ($verdict -eq 'DELETE' -and $null -ne $wt.Branch) {
      $unmerged = Get-UnmergedCommits $wt.Branch
      if ($unmerged.Count -gt 0) {
        if ($Force) { $reason = "$($unmerged.Count) unmerged commit(s) (forced)" }
        else { $verdict = 'UNMERGED'; $reason = "$($unmerged.Count) commit(s) not on main" }
      }
    }
    if ($verdict -eq 'DELETE') {
      if (-not (Test-DirUnlocked $wt.Path)) {
        $verdict = 'IN-USE'; $reason = 'open handle inside - agent still running?'
      }
    }
  }
  $plan += [pscustomobject]@{
    Kind = 'worktree'; Target = $wt.Path; Branch = $wt.Branch
    Verdict = $verdict; Reason = $reason
  }
}

# Branches not covered above (not checked out anywhere, or checked out in a
# worktree we are keeping - those we keep too).
$keptWtBranches = @($plan | Where-Object { $_.Verdict -ne 'DELETE' -and $null -ne $_.Branch } |
  ForEach-Object { $_.Branch })
$wtBranches = @($extraWts | Where-Object { $null -ne $_.Branch } | ForEach-Object { $_.Branch })
foreach ($b in $branches) {
  if ($keptWtBranches -contains $b) {
    $plan += [pscustomobject]@{ Kind = 'branch'; Target = $b; Branch = $b
      Verdict = 'KEEP'; Reason = 'checked out in a kept worktree' }
    continue
  }
  $unmerged = Get-UnmergedCommits $b
  if ($unmerged.Count -gt 0 -and -not $Force) {
    $plan += [pscustomobject]@{ Kind = 'branch'; Target = $b; Branch = $b
      Verdict = 'UNMERGED'; Reason = "$($unmerged.Count) commit(s) not on main" }
  } else {
    $reason = 'fully merged (git cherry)'
    if ($unmerged.Count -gt 0) { $reason = "$($unmerged.Count) unmerged commit(s) (forced)" }
    $plan += [pscustomobject]@{ Kind = 'branch'; Target = $b; Branch = $b
      Verdict = 'DELETE'; Reason = $reason }
  }
}

foreach ($o in $orphans) {
  $isSelf = $false
  foreach ($s in $selfDirs) {
    if (Test-PathInside $s $o) { $isSelf = $true }
  }
  if ($isSelf) {
    $plan += [pscustomobject]@{ Kind = 'orphan-dir'; Target = $o; Branch = $null
      Verdict = 'SELF'; Reason = 'this process runs from here' }
  } elseif (Test-DirUnlocked $o) {
    $plan += [pscustomobject]@{ Kind = 'orphan-dir'; Target = $o; Branch = $null
      Verdict = 'DELETE'; Reason = 'on disk but not a registered worktree' }
  } else {
    $plan += [pscustomobject]@{ Kind = 'orphan-dir'; Target = $o; Branch = $null
      Verdict = 'IN-USE'; Reason = 'open handle inside - agent still running?' }
  }
}

# --- Show the plan ----------------------------------------------------------
Write-Host ''
Write-Host "Main repo: $RepoRoot (kept, branch $($mainWt.Branch))"
Write-Host ''
$plan | Format-Table Kind, Verdict, Target, Branch, Reason -AutoSize | Out-String -Width 300 | Write-Host

$toDelete = @($plan | Where-Object { $_.Verdict -eq 'DELETE' })
$skipped = @($plan | Where-Object { $_.Verdict -in @('UNMERGED', 'DIRTY', 'IN-USE', 'SELF') })

foreach ($row in ($plan | Where-Object { $_.Verdict -eq 'UNMERGED' -and $_.Kind -eq 'branch' })) {
  Write-Host "Unmerged commits on $($row.Target):"
  git -C $RepoRoot log main..$($row.Target) --oneline | ForEach-Object { Write-Host "  $_" }
}

$t3Root = Get-NormPath (Join-Path $env:USERPROFILE '.t3')
$inUseT3 = @($plan | Where-Object { $_.Verdict -eq 'IN-USE' -and (Get-NormPath $_.Target).StartsWith($t3Root) })
if ($inUseT3.Count -gt 0) {
  Write-Host "HINT: $($inUseT3.Count) IN-USE item(s) live under ~\.t3 - the T3 Code app holds"
  Write-Host 'file-watcher handles on all its worktree dirs while it is open. Quit T3 Code'
  Write-Host '(check no sessions are mid-run first) and re-run to sweep them.'
  Write-Host ''
}

if ($toDelete.Count -eq 0) {
  Write-Host 'Nothing to delete.'
  exit 0
}

if (-not $Execute) {
  Write-Host "DRY RUN - $($toDelete.Count) item(s) would be deleted, $($skipped.Count) skipped."
  Write-Host 'Re-run with -Execute to delete.'
  exit 0
}

if (-not $Yes) {
  if (-not [Environment]::UserInteractive -or $null -eq $Host.UI.RawUI) {
    throw 'Non-interactive session: pass -Yes to confirm.'
  }
  Write-Host "About to delete $($toDelete.Count) item(s) listed as DELETE above."
  Write-Host 'REMINDER: make sure no agents, t3code sessions, or editors are'
  Write-Host 'running in any of these worktrees. The rename-probe catches open'
  Write-Host 'handles, but an idle session that touches files later does not hold one.'
  $answer = Read-Host 'Proceed? [y/N]'
  if ($answer -notmatch '^[yY]') {
    Write-Host 'Aborted.'
    exit 1
  }
}

# --- Delete: worktrees first, then branches, then orphan dirs ---------------
$failures = 0

foreach ($row in ($toDelete | Where-Object { $_.Kind -eq 'worktree' })) {
  Write-Host "Removing worktree $($row.Target)"
  git -C $RepoRoot worktree remove --force --force $row.Target
  if ($LASTEXITCODE -ne 0 -or (Test-Path -LiteralPath $row.Target)) {
    # t3code-style fallback: brute-delete the directory, then let git prune
    # the stale registration.
    Write-Host '  git worktree remove failed - falling back to Remove-Item + prune'
    try {
      Remove-Item -LiteralPath $row.Target -Recurse -Force -Confirm:$false -ErrorAction Stop
    } catch {
      Write-Warning "  Could not delete $($row.Target): $($_.Exception.Message)"
      $failures++
      continue
    }
  }
}
git -C $RepoRoot worktree prune

foreach ($row in ($toDelete | Where-Object { $_.Kind -eq 'branch' })) {
  Write-Host "Deleting branch $($row.Target)"
  git -C $RepoRoot branch -D $row.Target
  if ($LASTEXITCODE -ne 0) { $failures++ }
}
# Branches that only existed to pin a now-removed worktree.
foreach ($b in $wtBranches) {
  if ($keptWtBranches -contains $b) { continue }
  $stillThere = git -C $RepoRoot for-each-ref --format='x' "refs/heads/$b"
  if ($stillThere -eq 'x') {
    Write-Host "Deleting branch $b (was pinned to a removed worktree)"
    git -C $RepoRoot branch -D $b
    if ($LASTEXITCODE -ne 0) { $failures++ }
  }
}

foreach ($row in ($toDelete | Where-Object { $_.Kind -eq 'orphan-dir' })) {
  Write-Host "Deleting orphan dir $($row.Target)"
  try {
    Remove-Item -LiteralPath $row.Target -Recurse -Force -Confirm:$false -ErrorAction Stop
  } catch {
    Write-Warning "  Could not delete $($row.Target): $($_.Exception.Message)"
    $failures++
  }
}

# --- Report -----------------------------------------------------------------
Write-Host ''
Write-Host '=== Cleanup done ==='
Write-Host "Deleted: $($toDelete.Count - $failures) of $($toDelete.Count) planned item(s); failures: $failures"
if ($skipped.Count -gt 0) {
  Write-Host 'Kept (re-run after resolving):'
  $skipped | ForEach-Object { Write-Host "  [$($_.Verdict)] $($_.Target) - $($_.Reason)" }
}
git -C $RepoRoot worktree list
if ($failures -gt 0) { exit 1 }
