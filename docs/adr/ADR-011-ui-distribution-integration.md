# ADR-011: UI distribution integration — release assets, cache pruning, uninstall scope

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-10
**Follow-up ADRs:** —

---

## Context

ADR-009 fixed the UI's packaging shape (per-target uberjars + self-hosted
jlink runtimes as GitHub Release assets) and ADR-010 fixed the `taskkling ui`
verb (lazy fetch pinned to the CLI's tag, version-keyed cache with auto-prune,
`--purge` removes the cache home). Three integration points with the existing
distribution machinery remained open:

1. **Release assets:** the exact per-platform asset list, naming scheme, and
   `SHA256SUMS` coverage. Today a release carries four CLI binaries
   (`taskkling-linux-x64`, `taskkling-macos-x64`, `taskkling-macos-arm64`,
   `taskkling-windows-x64.exe`) plus one `SHA256SUMS`.
2. **Windows locked files during pruning:** ADR-010's prune (after a
   successful launch of version X, delete other `app/<*>` and orphaned
   `runtime/<*>` cache dirs) collides with Windows file locking when an older
   UI process is still running — its jar and its runtime's `java.exe` cannot
   be deleted. The same OS asymmetry forced ADR-002's rename-swap for updates
   and ADR-004's delete-on-reboot for self-removal.
3. **Non-purge uninstall scope** (ADR-004 follow-up): ADR-004's safe scope
   removes only the binary and the installer's `PATH` entry; the cache home —
   which now grows to ~120–140 MB once the UI has been fetched — was
   reclaimable only via `--purge`, the flag whose purpose is authored-data
   destruction.

CI/release-pipeline mechanics (how the assets get built) are a separate
decision and are consciously not made here.

---

## Decision

1. **Asset list and naming.** A release carries 13 assets: the four existing
   CLI binaries, four uberjars named `taskkling-ui-<target>.jar`, four runtime
   archives named `taskkling-ui-runtime-jdk<major>-<target>.tar.gz` (`.zip`
   for `windows-x64`), and one `SHA256SUMS`. `<target>` reuses the CLI's
   exact target vocabulary (`linux-x64`, `macos-x64`, `macos-arm64`,
   `windows-x64`). The JDK major (currently `jdk21`) is part of the runtime
   filename. No filename carries the release version — the tag does. The
   single `SHA256SUMS` file covers all 12 payload assets; there is no separate
   UI sums file.

2. **Prune is best-effort with skip-and-retry-later.** Pruning attempts a
   recursive delete of each stale cache dir; if any file in a dir is locked
   (an older UI still running from it), that dir is abandoned — partial
   remains and all — with at most a debug line in the log, never a user-facing
   error. The next successful launch re-runs the prune, so stale dirs
   self-collect once the old process exits. No delete-on-reboot scheduling,
   no PID tracking, no rename-to-`.old`.

3. **Global uninstall's safe scope deletes the whole cache home,** including
   all UI artifacts. Config home and workspace data remain `--purge`-only,
   unchanged from ADR-004. The scope is tier-aware: uninstalling a
   per-project `.taskkling/bin` copy never touches the user-level cache home
   (a global install may remain and still use it). If files in the cache are
   locked during uninstall (a UI is running), deletion is best-effort and
   anything left behind is reported with its path in the uninstall summary.

---

## Rationale

**Naming.** Reusing the CLI's target vocabulary means the verb derives asset
names from the os/arch detection it already has, with zero mapping tables.
The JDK major sits in the runtime filename because the cache is keyed by it
(ADR-010) and it changes independently of the release cadence — the name is
the key. One `SHA256SUMS` file keeps verification at one extra fetch, the
same file the CLI update flow already downloads; a second sums file would be
a second thing to fetch, sign nothing extra, and desynchronize.

**Pruning.** Delete-on-reboot and helper-process machinery earned their place
in ADR-002/ADR-004 because a *running self* has no later opportunity to act.
Stale cache garbage is the opposite case: every future launch is a retry
opportunity, so the cheapest correct behavior is to skip what's locked and
let the next launch sweep it. Half-deleted stale dirs are harmless by
construction — launch only consults the current version's keys, and
ADR-010's atomic fetch (temp → verify → rename) means presence of the
*current* key is the only "exists" that is ever trusted. The accepted cost:
a user who leaves an old UI running keeps ~120 MB of stale cache until they
next relaunch.

**Uninstall.** ADR-004's principle is "remove what the tool put there, never
what the user authored." The cache home is entirely machine-replaceable tool
bytes — UI jars, runtime images, update-check state; nothing authored lives
there. Leaving ~140 MB of unreachable cache behind the safe scope is exactly
the install residue the verb exists to clean, and gating its reclamation
behind `--purge` conflated "large" with "irreplaceable." The tier guard
follows the same logic as ADR-004's tier-awareness: a local uninstall must
not degrade a surviving global install. Reporting locked leftovers (rather
than scheduling reboot deletion) is enough because, unlike the binary, a
lingering cache file has no PATH presence — it is inert bytes the summary
tells the user how to remove.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Version in UI asset filenames (`taskkling-ui-0.6.0-…`) | The release tag already carries the version; the CLI assets set the precedent, and pinning (ADR-010) means the verb always fetches from its own tag anyway. |
| Separate `SHA256SUMS-ui` file | Second fetch, second failure mode, no verification gain. |
| Universal runtime name without JDK major | The cache is keyed by JDK major (ADR-010); omitting it from the asset name forces a lookup table or re-inspection of archive contents. |
| Delete-on-reboot for locked stale cache dirs | Registry/OS machinery for garbage that self-collects on the next launch; justified only when no later run exists (ADR-004 uninstall). |
| PID-tracking to prune only provably-dead versions | Bookkeeping plus race conditions to avoid a delete that fails safely anyway. |
| Keep safe-scope uninstall at binary + PATH only | Strands ~140 MB of re-downloadable bytes behind a flag whose meaning is authored-data destruction. |
| Local-tier uninstall also clears the cache home | Breaks a surviving global install's UI cache; violates ADR-004's tier isolation. |

---

## Consequences

**Positive:** the verb computes every asset URL from facts it already knows
(tag, target, JDK major); verification stays one-file; pruning needs no new
OS machinery on any platform; `uninstall` (global, safe scope) now leaves a
machine truly clean of everything the tool wrote outside the workspace, while
authored data keeps its `--purge`-only protection.

**Negative / open:** a long-running old UI process delays cache reclamation
indefinitely (until its next relaunch); uninstall-while-UI-running leaves
reported-but-present leftovers with no automatic cleanup; a JDK major bump
changes runtime asset names, and the release pipeline (decided separately)
must keep `SHA256SUMS` covering exactly the full asset set. Supersede this
ADR if release assets ever need independent versioning from the tag, or if
lingering locked cache dirs prove a real problem in practice.
