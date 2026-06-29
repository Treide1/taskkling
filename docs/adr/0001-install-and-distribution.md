# ADR 0001 — Install and Distribution Strategy

## Status

Accepted — 2026-06-30

---

## Context

PRD §15 establishes that the CLI ships as per-platform Kotlin/Native binaries on GitHub Releases
and that install equals "drop on PATH." v0.2 operationalises that sentence: it makes the CLI
trivially installable on a clean machine with a single command and ensures `taskkling` is
invokable from any directory afterwards.

The Compose Desktop UI (PRD §13) is explicitly **deferred** — v0.2 is CLI-only.

Two constraints shaped the decisions below:

1. **No paid signing infrastructure.** A Developer ID certificate and notarization require an
   Apple Developer account and a CI secrets pipeline that is disproportionate for an early release.
2. **`~/.taskkling` must never be a tool home.** `Workspace.discover` walks up from the cwd
   looking for a `.taskkling/` directory and treats the first hit as the workspace root (PRD §9).
   Installing the binary into `~/.taskkling/bin/` would create a `.taskkling/` directory directly
   under `$HOME`, turning the user's home directory into a phantom workspace that shadows every
   real project beneath it.

---

## Decision

### Delivery channels

Ship two install scripts and raw binaries on every GitHub Release:

- **`install.sh`** — POSIX `curl -fsSL … | sh` (or `wget … | sh`) installer for Linux and macOS.
- **`install.ps1`** — Windows `irm … | iex` installer for PowerShell.
- **Per-platform binaries** and a **`SHA256SUMS`** file attached to the release as downloadable
  assets for users who prefer manual install or want to verify integrity.

Package managers (Homebrew, Scoop, winget) are deferred — they require maintained tap/bucket/manifest
infrastructure; out of scope for v0.2.

### Targets

Ship all four `:cli` targets:

| Target | Gradle name |
|---|---|
| `linux-x64` | `linuxX64` |
| `macos-arm64` | `macosArm64` |
| `macos-x64` | `macosX64` |
| `windows-x64` | `mingwX64` |

Today CI and `release.yml` build three targets; adding `macos-x64` is a release-pipeline task
tracked separately.

### Install location

| Platform | Directory |
|---|---|
| Unix (Linux + macOS) | `~/.local/bin` |
| Windows | `%LOCALAPPDATA%\Programs\taskkling` |

The install script adds the directory to `PATH` for the current shell profile and prints a note
if the directory was already on `PATH`. The binary is placed directly in the install directory
(`~/.local/bin/taskkling`, `%LOCALAPPDATA%\Programs\taskkling\taskkling.exe`).

**`~/.taskkling` is never used as a tool home** (see Context above).

### macOS Gatekeeper

macOS Gatekeeper quarantines executables that carry the `com.apple.quarantine` extended attribute.
`curl` and `wget` **do not set this attribute** — only LaunchServices-mediated downloads (browsers,
Finder, etc.) do. A `curl | sh` install therefore runs without a Gatekeeper prompt on a stock
macOS system, with no signing required from the project side.

The `macos-arm64` binary is already **ad-hoc-signed** by the Kotlin/Native linker, which satisfies
Apple silicon's requirement that executables carry a valid code signature before the kernel will
exec them. This is not a deliberate build step; it is verified post-build with `codesign -dv` and
is **not** a notarization or Developer ID signature.

For users who download the binary manually via a browser (which does set the quarantine attribute),
document the manual removal: `xattr -dr com.apple.quarantine <path>`.

No paid Developer ID certificate and no notarization for v0.2.

### Invocation (cross-reference)

After install, human invocation is handled by a sibling design: global PATH lookup, a
per-project `./taskkling` / `taskkling.cmd` wrapper, `taskkling init --local-bin`, and a resolver
chain of `TASKKLING_BINARY → up-tree .taskkling/bin → config binary_path → PATH`. Not designed
here.

### Updates (cross-reference)

The supported upgrade path (re-run the installer) and a future `taskkling update` verb are
specified in a separate design ADR. Out of scope here.

---

## Consequences

- **No runtime dependencies.** The install script fetches a single static binary; nothing else is
  required on the target machine.
- **macOS-x64 build gap.** Until `release.yml` is updated, macOS Intel users are not served. This
  is a known gap, tracked as a release-pipeline task.
- **Package-manager absence.** Discovery via `brew install` / `scoop install` / `winget install`
  is not available in v0.2. Users must use the install script or download the binary manually.
- **Browser-download friction on macOS.** Users who download via a browser must run
  `xattr -dr com.apple.quarantine` or right-click → Open on first launch. The `curl | sh` path
  has no such friction.
- **Home-directory workspace safety.** The install location choice (`~/.local/bin`,
  `%LOCALAPPDATA%\Programs\taskkling`) guarantees no `.taskkling/` directory lands under `$HOME`,
  preserving workspace discovery correctness for all projects.
