# ADR-002: Update and Upgrade Strategy

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-06-30
**Follow-up ADRs:** 005, 006, 007

---

## Context

This is a **design-only** decision; implementation is deferred to ≥ v0.3.

ADR-001 made taskkling trivially *installable* but said nothing about *updating*, which leaves a
gap: a one-command install with no upgrade story is a dead-end — someone who installs v0.2.0 has
no sanctioned way to reach v0.3.0 short of re-deriving the install command. This decision closes
the **install → use → update** loop.

Three facts shape it:

1. **Two install tiers exist** (from ADR-001 plus the human-invocation design): a **global**
   binary (`~/.local/bin/taskkling` or `%LOCALAPPDATA%\Programs\taskkling\taskkling.exe`) and a
   **per-project local-bin** copy (`.taskkling/bin/`, pinned by `.taskkling/bin/.version`, created
   by `init --local-bin`). They update by different mechanisms, and the per-project copy is
   deliberately pinned so a project carries the version it was set up with.
2. **The binary knows its own version** — `Taskkling.VERSION`, single-sourced at compile time from
   the Gradle `version` property and printed by `--version`.
3. **PRD §15 states "No auto-update, no telemetry, no network calls."** This decision revisits the
   *auto-update* clause narrowly (the opt-in check below); the default "no network calls" posture
   is preserved.

A fourth fact is an OS constraint, not a choice, and it drives the `update`-verb mechanics: a
running executable **cannot be overwritten on Windows** (the image file is locked while the process
runs), whereas **Unix lets you unlink a running binary** (the inode survives until the last handle
closes).

---

## Decision

Close the loop in three parts:

1. **The v0.2 upgrade path is to re-run the installer** (documentation, not new code). Both
   installers already overwrite in place after a SHA-256 check, so re-running them *is* the
   upgrade — the global tier via the same `curl|sh` / `irm|iex` command (pin with
   `TASKKLING_VERSION`), the per-project tier via re-running `init --local-bin` from a newer
   binary.
2. **A future `taskkling update` verb (v0.3+)** detects the host triple, resolves the latest (or
   `--version vX.Y.Z`) release asset, verifies its SHA-256 against `SHA256SUMS`, then self-replaces
   the running binary (resolved via `currentExecutablePath()`) and prints old → new. Self-replace
   splits by OS: **Unix** writes the new binary to a temp file in the same directory and
   `rename()`s it atomically over the live path; **Windows** uses **rename-then-swap** (live
   `taskkling.exe` → `.old`, move the new binary in, best-effort delete `.old` on the next run).
3. **An opt-in `update_check` config flag** (`config.toml`, default `false`) — a best-effort,
   cached (~24 h), silent-on-failure notifier that prints `vX.Y.Z available — run 'taskkling
   update'`. It checks; it never installs.

---

## Rationale

The asset-resolution and SHA-256 verification both the documented re-run path and the future verb
rely on **already exist** in `install.sh` / `install.ps1`, so the verb is mostly a port into the
binary rather than new trust machinery. Resolving via `currentExecutablePath()` means `update`
naturally acts on whichever tier invoked it and can re-stamp the local-bin `.version`. The OS split
is forced, not chosen: Windows file-locking rules out an in-place overwrite, so rename-then-swap is
the only safe path; Unix's unlink-while-running semantics make an atomic rename sufficient. The
update *check* is gated behind explicit consent and made cached/best-effort precisely so the
default posture stays "no network calls" — it reconciles with PRD §15 as the single documented,
user-enabled exception, and it is a notifier, not an auto-updater.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Ship the `taskkling update` verb in v0.2 | The re-run-the-installer path already works and needs no code; the verb is deferred to v0.3 to keep v0.2 scoped to install. |
| Overwrite the running binary in place on Windows | Impossible — a running `.exe` image is locked; rename-then-swap is required. |
| A silent/automatic update check or auto-install | Violates PRD §15's privacy posture; the check is opt-in, cached, and never installs. |
| Package-manager-driven upgrades (brew/scoop/winget) | Package managers are deferred per ADR-001; when added, the manager owns upgrades and `update` should defer to it. |

---

## Consequences

**Positive:**

- v0.2 is no longer a dead-end install — the upgrade path (re-run the installer) is documented and
  needs no new code.
- `taskkling update` is well-scoped for v0.3, reusing the installers' resolve + verify logic.
- The two tiers update independently; a global update never silently changes a repo's pinned
  `.taskkling/bin/` copy.

**Negative / open:**

- Windows self-replace needs the rename-then-swap dance plus deferred `.old` cleanup, so the
  `update` code path diverges by OS (an `expect`/`actual` split, like `ExePath` / `markExecutable`).
- If the opt-in check ships, it introduces the project's first network call, and PRD §15's
  "no auto-update / no network calls" wording must be amended to record the exception.
- Per-project pinned copies move version only on an explicit `init --local-bin` (or future
  `update`) run inside the project — intended reproducibility, but a surprise if a user expects a
  global update to cascade.
