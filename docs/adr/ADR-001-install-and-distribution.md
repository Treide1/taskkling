# ADR-001: Install and Distribution Strategy

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-06-30
**Follow-up ADRs:** 003

---

## Context

PRD §15 establishes that the CLI ships as per-platform Kotlin/Native binaries on GitHub Releases
and that install equals "drop on PATH." v0.2 operationalises that sentence: it makes the CLI
trivially installable on a clean machine with a single command and ensures `taskkling` is
invokable from any directory afterwards. The Compose Desktop UI (PRD §13) is explicitly
**deferred** — v0.2 is CLI-only.

Two constraints made the install design non-trivial:

1. **No paid signing infrastructure.** A Developer ID certificate and notarization require an
   Apple Developer account and a CI secrets pipeline that is disproportionate for an early release.
2. **`~/.taskkling` must never be a tool home.** `Workspace.discover` walks up from the cwd
   looking for a `.taskkling/` directory and treats the first hit as the workspace root (PRD §9).
   Installing the binary into `~/.taskkling/bin/` would create a `.taskkling/` directory directly
   under `$HOME`, turning the user's home directory into a phantom workspace that shadows every
   real project beneath it.

---

## Decision

Ship two install scripts plus raw binaries on every GitHub Release, install into a PATH directory
that is **not** `~/.taskkling`, and rely on the `curl | sh` quarantine-bypass on macOS rather than
paid signing:

- **Delivery:** `install.sh` (POSIX `curl … | sh` / `wget … | sh`, Linux + macOS), `install.ps1`
  (Windows `irm … | iex`), and per-platform binaries + a `SHA256SUMS` file attached as assets for
  manual install / integrity verification.
- **Targets:** ship all four `:cli` targets — `linux-x64` (`linuxX64`), `macos-arm64`
  (`macosArm64`), `macos-x64` (`macosX64`), `windows-x64` (`mingwX64`). Today CI and `release.yml`
  build three targets; adding `macos-x64` is a release-pipeline task tracked separately.
- **Install location:** Unix → `~/.local/bin`; Windows → `%LOCALAPPDATA%\Programs\taskkling`. The
  script adds the directory to the shell profile's PATH and notes if it was already present.
  `~/.taskkling` is never used as a tool home.
- **macOS:** rely on the `curl | sh` quarantine-bypass (curl/wget do not set
  `com.apple.quarantine`); the `macos-arm64` binary is already ad-hoc-signed by the Kotlin/Native
  linker (verified with `codesign -dv`, not a deliberate signing step). No Developer ID
  certificate and no notarization. For manual/browser downloads, document
  `xattr -dr com.apple.quarantine <path>`.

---

## Rationale

The core insight is that a single static binary needs no runtime — the only friction is *getting
it onto PATH safely*. A `curl | sh` script does exactly that and, on macOS, sidesteps Gatekeeper
for free: the `com.apple.quarantine` attribute is applied by LaunchServices-mediated GUI
downloaders (browsers, Finder), not by curl or wget, so the piped-install path runs without a
Gatekeeper prompt and requires no project-side signing. The install directories
(`~/.local/bin`, `%LOCALAPPDATA%\Programs\taskkling`) are chosen specifically because they never
create a `.taskkling/` directory under `$HOME`, preserving workspace-discovery correctness for
every project beneath it. Ad-hoc signing on Apple silicon is a linker side-effect that satisfies
the kernel's signature requirement without a paid Developer ID.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Package managers (Homebrew / Scoop / winget) | Require maintained tap/bucket/manifest infrastructure; out of scope for v0.2. |
| Install into `~/.taskkling/bin/` | Creates a `.taskkling/` under `$HOME` that `Workspace.discover` treats as a phantom workspace root, shadowing every project. |
| Developer ID certificate + notarization | Needs a paid Apple account and a CI secrets pipeline; disproportionate for an early release, and the `curl` path avoids the Gatekeeper prompt anyway. |

---

## Consequences

**Positive:**

- No runtime dependencies — the install script fetches a single static binary; nothing else is
  required on the target machine.
- The `curl | sh` path has no Gatekeeper friction on a stock macOS system.
- The install-location choice guarantees no `.taskkling/` directory lands under `$HOME`,
  preserving workspace discovery for all projects.

**Negative / open:**

- **macOS-x64 build gap.** Until `release.yml` is updated, macOS Intel users are not served. This
  is a known gap, tracked as a release-pipeline task.
- **Package-manager absence.** No `brew/scoop/winget install` in v0.2; users use the install
  script or download the binary manually.
- **Browser-download friction on macOS.** Users who download via a browser must run
  `xattr -dr com.apple.quarantine` or right-click → Open on first launch.
- Human invocation after install (global PATH, per-project `./taskkling` / `taskkling.cmd`
  wrappers, `init --local-bin`, the resolver chain) is a sibling design, not specified here. The
  update/upgrade path is specified in a separate design ADR.
