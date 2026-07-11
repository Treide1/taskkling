<p align="center">
  <img src="docs/assets/logo.svg" width="112" alt="the taskkling — two done deps converge into a ready task"/>
</p>

# taskkling

A git-native, **markdown-file-per-task DAG task manager** for solo operators and
human + agent teams. Work is modelled as tasks connected by `depends` edges;
the source of truth is **one markdown file per task** (YAML frontmatter + freeform body)
in a git repository. No database, daemon, or server.

It ships two artifacts:

- a **Kotlin/Native CLI** (`taskkling`) — the single read **and** write path; fast cold
  start, no JVM needed. This is what humans and agents drive.
- a **Compose Desktop UI** — a *pure CLI client*: it renders the CLI's JSON `export` and
  performs every mutation by invoking the CLI, so it can never become a second writer.

> Design north star: **"file I/O with editable metadata."** Everything the CLI does, a
> human could do by hand-editing markdown — the CLI just makes it safe, concurrent, and
> queryable. See **[PRD.md](PRD.md)** for the full design.

## Why a DAG?

Flat checklists force a binary, sequential shape onto work that isn't. A DAG models
dependencies (`can't start B until A is done`), deadlines (`due`) and defer-until dates
(`defer`) directly, and turns *"what can I work on right now?"* into a computed query —
the **ready set**: a task is `ready` when it is `open`, all its `depends` are `done`, and
it is not deferred.

## Install

> These install scripts are available from the **v0.2.0 release onward**. The release
> pipeline is wired; the release itself is cut by a human when the milestone is complete.

**macOS / Linux**

```sh
curl -fsSL https://github.com/Treide1/taskkling/releases/latest/download/install.sh | sh
```

**Windows (PowerShell)**

```powershell
irm https://github.com/Treide1/taskkling/releases/latest/download/install.ps1 | iex
```

Both scripts fetch the `taskkling` binary for your platform and put it on `PATH`:
`~/.local/bin` on Unix, `%LOCALAPPDATA%\Programs\taskkling` on Windows. Restart your
shell (or open a new terminal) so the new location is picked up.

### Manual install

Download the binary for your platform from the [Releases page](../../releases) and place
it somewhere on your `PATH`. Verify it against the accompanying `SHA256SUMS` file:

```sh
# Unix
sha256sum -c SHA256SUMS

# macOS (shasum ships by default)
shasum -a 256 -c SHA256SUMS
```

On **Windows (PowerShell)**, compare the binary's hash against its `SHA256SUMS` line
(`-eq` is case-insensitive, so the upper-case `Get-FileHash` digest still matches):

```powershell
$want = (((Get-Content SHA256SUMS | Select-String 'taskkling-windows-x64.exe') -split '\s+')[0])
$got  = (Get-FileHash -Algorithm SHA256 .\taskkling-windows-x64.exe).Hash
if ($got -eq $want) { 'OK' } else { 'MISMATCH' }
```

**macOS quarantine caveat:** a binary downloaded via a browser carries the quarantine
attribute and will be blocked by Gatekeeper on first run. Clear it with:

```sh
xattr -dr com.apple.quarantine ./taskkling
```

This is not needed when using the `curl | sh` path above — `curl` does not set the
quarantine attribute.

### First run

In any repo, scaffold the taskkling workspace:

```sh
taskkling init          # creates .taskkling/ + tasks/, idempotent
```

Just exploring? `taskkling init --demo-mode` (short `-dm`) seeds a small demo backlog
instead of an empty one — every state on display, safe to mutate freely, kept entirely
under `.taskkling/` (so git never sees it; delete `.taskkling/` and it's gone). Ideal
for a scratch dir, a first look at `taskkling ui`, or agent worktrees that need working
data without touching a real store (ADR-017).

## Invocation

There are three ways to drive `taskkling` after install:

**1. Global PATH (recommended for personal machines)**
Run `taskkling …` from anywhere inside a project tree. The CLI walks up the directory
tree until it finds a `.taskkling/` directory, so you don't need to be at the root.

**2. Per-project wrapper scripts**
A tracked `./taskkling` (Unix) and `./taskkling.cmd` (Windows) at the repo root exec
the project-pinned binary in `.taskkling/bin`. Contributors can run `./taskkling …`
without a global install — useful in team repos where you can't assume everyone has
`taskkling` on their `PATH`.

**3. `taskkling init --local-bin`**
Scaffolds the workspace AND copies the running binary into `.taskkling/bin`, then drops
the wrapper scripts (`./taskkling` + `./taskkling.cmd`) in the repo root. Run this once
after a fresh install to set up the per-project wrapper in one step.

## Usage

```sh
taskkling add "Draft the proposal" -t docs        # create a task, prints its id (-b - = body from stdin)
taskkling add "Ship it" -t docs -d t-a1z9         # depends on t-a1z9 (-d repeats; or -d a,b)
taskkling list                                    # whole backlog, ls -la style
taskkling list --ready                            # what's actionable right now
taskkling export                                  # full JSON (stored + computed)

taskkling done <id>                               # lifecycle: done / drop / reopen / wait
taskkling set <id> --due 2026-07-31 --priority high
taskkling write <id> "body text"                  # body I/O: write / append / read (- = stdin)
taskkling link <id> --depends <dep>               # edges: link / unlink (cycle-checked)
taskkling delete <id>                             # -> trash, prunes dependents; restore <id> undoes
taskkling cleanup                                 # sweep closed tasks -> archive/
```

Conventions: human-readable output by default, `--json` where applicable (`export` is
always JSON); any mutation accepts `--export-on-success` for a transactional read-after-write.
Global flags: `--root <path>`, `--quiet`, `--no-color`. Exit codes: `0` ok · `2` usage ·
`3` validation · `4` lock timeout. Full command surface in [PRD.md §10](PRD.md).

## Updating

`taskkling update` replaces the running binary in place with the latest GitHub
release: it detects your platform, downloads the matching asset, verifies it
against `SHA256SUMS`, swaps it in, and prints `old -> new`.

```sh
taskkling update                  # update to the latest release
taskkling update --check          # is a newer release out? report only, never installs
taskkling update --version v0.3.0 # install a specific release tag (pin / roll back)
```

`update` acts on **whichever binary you invoked** — it resolves its own path.
Run the global `taskkling` and it updates the global install; run a project's
pinned copy (`./taskkling update`) and it updates that `.taskkling/bin` binary
and re-stamps its `.version`. A global update deliberately does **not** cascade
into a project's pinned local-bin copy — update that one separately, or re-run
`init --local-bin` from a newer binary. On Windows the running `.exe` is locked,
so `update` swaps it via a rename and clears the leftover on the next run; on
Unix the file is replaced atomically.

Re-running the install script (`curl … | sh` / `irm … | iex`) is always a valid
alternative upgrade — both installers overwrite in place after the same checksum
check.

### "Newer version available" check

taskkling gives you a passive heads-up when a newer release is out. On
`taskkling --version` this check is **on by default**, but only in an
**interactive terminal** — pipes, scripts, CI, and any `--json`/machine-readable
output stay fully offline (no network call, no cache write). The explicit
`taskkling update --check` always runs, terminal or not. It only notifies; it
never installs.

To turn the passive check **off**, set `update_check = false` in a `config.toml`:

- **User-level** config (honoured by the global binary anywhere):
  - Linux — `~/.config/taskkling/config.toml` (or `$XDG_CONFIG_HOME/taskkling/`)
  - macOS — `~/Library/Application Support/taskkling/config.toml`
  - Windows — `%LOCALAPPDATA%\taskkling\config.toml`
- A workspace's `.taskkling/config.toml` overrides the user-level value.

Both installers run `taskkling config init` for you, which writes that
user-level file (write-if-absent, never clobbering your edits) pre-populated
with the `update_check` toggle and prints its path — run it yourself anytime to
create or locate the file.

When it runs, the check hits GitHub Releases at most once every ~24 h, fails
silently, and the `vX.Y.Z available` line appears on **only two** surfaces:
`taskkling --version` (interactive only) and the explicit `taskkling update
--check`. It never appears in `list`, `get`, `export`, or any `--json` output.

## Uninstalling

`taskkling uninstall` is the inverse of install: it removes the binary and the
`PATH` entry the installer added — and, by design, **nothing you authored**.
Your workspace — `.taskkling/` (config, caches) and your tasks directory — is
never touched unless you explicitly pass `--purge`.

```sh
taskkling uninstall           # interactive: shows what it will remove, then asks
taskkling uninstall -y        # non-interactive, safe scope only (binary + PATH)
taskkling uninstall --purge   # ALSO delete .taskkling/ AND the tasks dir — irreversible
```

It is **interactive by default**: it prints exactly what it will remove — the
binary path, the `PATH` entry, and (with `--purge`) how many tasks that would
destroy — then waits for confirmation. `-y` skips the prompt but runs only the
**safe scope** (binary + `PATH`); authored data is deleted **only** via
`--purge`. So `--purge -y` is the single non-interactive form that erases a task
graph, and that destruction is spelled out on the command line.

Like `update`, it is **tier-aware**: it removes whichever binary you invoked, or
use `--global` / `--local` to target a tier explicitly. Removing a per-project
copy also deletes that workspace's pinned `.version` stamp and the `./taskkling`
wrapper scripts. On Windows the `PATH` entry is removed immediately (the tool is
gone the moment the command returns) while the locked `.exe` clears on your next
reboot; on Unix the binary is removed right away.

## Development

Any JDK findable on `PATH` or `JAVA_HOME` boots Gradle; the build then
auto-provisions the JDK 21 it actually compiles with (foojay toolchain
resolver) — no manual Temurin install, no `JAVA_HOME` ritual.

```sh
./gradlew :cli:linkDebugExecutableMingwX64    # native CLI (host target: Mingw/Linux/Macos)
./gradlew :cli:linkReleaseExecutableMingwX64  # optimized release binary
./gradlew :contract:jvmTest :core:jvmTest     # fast JVM unit/golden tests
./gradlew :ui:run                             # launch the Compose Desktop UI from source
./gradlew :ui:createDistributable             # assemble the packaged app image
```

> **Using the UI day-to-day?** Since v0.6.0 you don't build it: `taskkling ui`
> fetches and launches the desktop UI matching your installed CLI (first run
> downloads ~80–100 MB, cached from then on). `:ui:run` remains the from-source
> path for working ON the UI.

The debug CLI lands at `cli/build/bin/<target>/debugExecutable/taskkling[.exe]`
(`<target>` = `mingwX64` on Windows, `linuxX64` / `macosArm64` elsewhere).

### Modules

| Module | Targets | Responsibility |
|---|---|---|
| `:contract` | native + JVM | `@Serializable` DTOs for the `export` JSON contract (CLI ↔ UI) |
| `:core` | native + JVM | domain model, frontmatter I/O, graph + ready-set + validation, lock/atomic-write |
| `:cli` | native | the `taskkling` binary; thin command layer over `:core` |
| `:ui` | JVM (Compose Desktop) | desktop app; renders `export`, mutates via the CLI |

The UI links **only `:contract`** (the DTOs), never `:core` — so it is *physically
incapable* of writing task files, structurally guaranteeing the single-write-path design.

### This repo dogfoods itself

taskkling's own development backlog is tracked **in taskkling**. A fresh clone does
**not** include `tasks/` or `CLAUDE.md` — both are git-ignored, local-only dev state.
After cloning, run `taskkling init`; the dogfooded dev backlog lives under
`.taskkling/tasks` (also git-ignored). CI (`.github/workflows/ci.yml`) builds JVM plus
a native matrix (linux/macOS/Windows) on every push and PR.

## License

See repository for license details.
