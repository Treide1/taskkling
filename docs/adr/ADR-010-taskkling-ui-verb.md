# ADR-010: The `taskkling ui` verb — acquisition, pinning, launch, and error surface

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-10
**Follow-up ADRs:** —

---

## Context

ADR-009 fixed the UI's distribution shape: per-target Compose uberjars plus
self-hosted jlink-trimmed Temurin-21 runtime images, published as GitHub
Release assets, fetched and executed by the CLI. What remained undecided was
the verb itself — when `taskkling ui` acquires those assets, how CLI and UI
versions relate, where the artifacts live on disk, what happens to the
terminal on launch, which flags exist, and how failures are surfaced.

Constraints in force:

- **Touch-surface tightening:** a human touches exactly one artifact, the
  CLI. The install scripts must not grow a second artifact class.
- **The verb owns its error surface** (scope decision, ticket t-1gv9): no
  separate doctor/diagnostics verb exists in v0.6.0; corrupt or missing
  artifacts, failed or blocked downloads, half-extracted bundles, and
  headless sessions must all fail actionably inside `taskkling ui`.
- **Phantom-workspace rule (ADR-001):** nothing may create a `.taskkling/`
  under `$HOME`. The CLI already has a user-level cache home (ADR-005
  `UserPaths`): `%LOCALAPPDATA%\taskkling\cache` on Windows, XDG cache dirs
  (or platform defaults) on macOS/Linux.
- **Existing machinery:** download → SHA256SUMS-verify → rename-swap
  (ADR-002); `uninstall --purge` already deletes the config and cache homes
  (ADR-004).

---

## Decision

1. **Acquisition — lazy fetch on first launch.** `taskkling ui` downloads
   its platform's uberjar and jlink runtime from the CLI's own release tag on
   first use, verifies them against `SHA256SUMS`, caches them, and launches.
   Progress is printed while fetching. The install scripts are unchanged and
   never touch UI assets.

2. **Version pinning — the UI is pinned to the CLI's version by
   construction.** The verb always fetches and runs the UI jar from the
   release tag matching the CLI's own version. There is no compatibility
   negotiation, no contract-version handshake, and no mismatch state. After
   `taskkling update`, the next `taskkling ui` fetches the matching jar.

3. **Cache layout — version-keyed with auto-prune,** under the existing
   ADR-005 cache home: `cache/ui/app/<version>/` for the uberjar,
   `cache/ui/runtime/<jdk-major>/` for the runtime image (which changes only
   on JDK bumps and so survives most CLI updates). After a successful launch
   of version X, other `app/<*>` directories and any `runtime/<*>` no current
   version needs are deleted. `uninstall --purge` removes the whole cache
   home, so UI removal requires no new code.

4. **Launch semantics — detach and return.** The verb spawns
   `<cache>/ui/runtime/<jdk-major>/bin/java -jar <cache>/ui/app/<version>/…`
   as a detached process by absolute path (`JAVA_HOME` never consulted),
   prints a one-line confirmation, and returns the prompt. The CLI resolves
   the workspace exactly as other verbs do (cwd discovery, `--root`
   override) and passes the resolved root to the UI as an argument — the UI
   does not re-run discovery. UI stdout/stderr are redirected to a log file
   in the cache for post-mortem.

5. **Flags — `--fetch-only`, nothing else.** `taskkling ui --fetch-only`
   downloads and verifies without launching (prefetch before going offline;
   works headless, e.g. provisioning over SSH). Pinning makes
   `--check`/`--update`/`--version` on the verb meaningless, so they do not
   exist. `taskkling update` remains CLI-only and never prefetches UI assets.

6. **Failure posture — self-heal once, then explain.** Fetches are atomic:
   download to a temp path, verify, rename into place — a half-extracted
   state can never become "current". If launch finds the cache missing or
   corrupt, the verb silently re-fetches once; if that also fails, it emits
   one actionable error naming the cause (offline, checksum mismatch, GitHub
   unreachable) and the log path. Headless sessions (no DISPLAY/Wayland on
   Linux, SSH without X forwarding) are refused before spawning java, with a
   message naming `--fetch-only` as the operation that does work headless.

---

## Rationale

Lazy fetch is the only acquisition mode that keeps the install scripts at
exactly one artifact class while making CLI-only users pay nothing for the
UI's ~80–100 MB; a progress line communicates the one-time cost as well as a
mandatory `--install` step would, without the extra error state.

Pinning to the CLI's tag deletes an entire problem class. The alternative —
independently versioned UI plus contract negotiation — buys a compatibility
matrix and a family of mismatch errors in exchange for UI-only releases
nobody has asked for. With pinning, the export-contract version (ADR-008)
never needs cross-version enforcement: both sides of every conversation ship
from the same tag.

Version-keyed caching means a running UI's jar is never swapped underneath
it and a failed fetch can never damage the known-good copy; pruning on
successful launch keeps disk at ~one UI footprint without a user-facing
cleanup verb. Keying the runtime by JDK major keeps the 80 MB piece stable
across app-only updates and lets runtimes coexist across a JDK bump (the
open point ADR-009 flagged).

Detaching matches desktop-app ergonomics; passing the CLI-resolved root
keeps workspace discovery in exactly one implementation. Refusing headless
before spawn, atomic fetches, and the single re-fetch retry are what "the
verb owns its error surface" concretely means — the user never performs
cache surgery and never reads an AWT stack trace.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Fetch UI assets during install script | Every installer pays ~100 MB whether or not they use the UI; install scripts grow a second artifact class against the touch-surface invariant. |
| Explicit `taskkling ui --install` gate | Adds a mandatory step and an error state that lazy-fetch-with-progress already communicates. |
| Independent UI versioning + contract negotiation | Compatibility matrix, negotiation logic, and a class of mismatch errors, for UI-only release flexibility with no current demand. |
| Unversioned cache, rename-swap in place | A running UI's jar can be swapped underneath it; a failed swap leaves no known-good copy. |
| Keep all cached versions forever | ~40 MB growth per release with no reclaim path short of manual deletion. |
| Hold the terminal (foreground child) | Occupies a terminal for the whole UI session — wrong ergonomics for a desktop app. |
| UI re-runs workspace discovery itself | Duplicates discovery across languages; can disagree with the CLI's resolution, e.g. under `--root`. |
| `taskkling update` prefetches UI assets | update grows a 40 MB side effect that CLI-only users who once tried the UI keep paying. |
| No self-heal on corrupt cache | Pushes manual cache surgery onto the user — the doctor-verb-shaped hole this design consciously ruled out. |

---

## Consequences

**Positive:** install and update flows are untouched; CLI-only users never
download UI bytes; version mismatch between CLI and UI is structurally
impossible; UI removal and cache location need no new decisions (ADR-004,
ADR-005 cover them); the whole runtime-trouble surface lives in one verb.

**Negative / open:** first `taskkling ui` needs network and ~80–100 MB —
offline first use fails (mitigated by `--fetch-only` as an explicit prefetch
step). A CLI downgrade re-downloads its UI version (prior versions are
pruned). The headless check must be reliable across Linux/X11/Wayland and
SSH permutations, or it blocks legitimate launches. Log-file redirection is
the only diagnostic channel for UI crashes. Supersede this ADR if UI-only
release cadence ever becomes a real need (pinning is the load-bearing
assumption) or if the UI outgrows single-workspace launch arguments.
