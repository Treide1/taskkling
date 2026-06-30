# taskkling

A git-native, **markdown-file-per-task DAG task manager** for solo operators and
human + agent teams. Work is modelled as nodes (tasks) connected by `depends` edges;
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
taskkling add "Draft the proposal" -t docs        # create a node, prints its id
taskkling add "Ship it" -t docs -d t-a1z9         # ... that depends on t-a1z9
taskkling list                                    # whole backlog, ls -la style
taskkling list --ready                            # what's actionable right now
taskkling export                                  # full JSON (stored + computed)

taskkling done <id>                               # lifecycle: done / drop / reopen / wait
taskkling set <id> --due 2026-07-31 --priority high
taskkling write <id> "body text"                  # body I/O: write / append / read (- = stdin)
taskkling link <id> --depends <dep>               # edges: link / unlink (cycle-checked)
taskkling delete <id>                             # -> trash, prunes dependents; restore <id> undoes
taskkling cleanup                                 # sweep closed nodes -> archive/
```

Conventions: human-readable output by default, `--json` where applicable (`export` is
always JSON); any mutation accepts `--export-on-success` for a transactional read-after-write.
Global flags: `--root <path>`, `--quiet`, `--no-color`. Exit codes: `0` ok · `2` usage ·
`3` validation · `4` lock timeout. Full command surface in [PRD.md §10](PRD.md).

## Development

`java`/`gradle` are not assumed on `PATH`. Set `JAVA_HOME` to a **JDK 21 (Temurin)** first:

```sh
export JAVA_HOME=/path/to/temurin-21          # Windows: setx / $env:JAVA_HOME

./gradlew :cli:linkDebugExecutableMingwX64    # native CLI (host target: Mingw/Linux/Macos)
./gradlew :cli:linkReleaseExecutableMingwX64  # optimized release binary
./gradlew :contract:jvmTest :core:jvmTest     # fast JVM unit/golden tests
./gradlew :ui:run                             # launch the Compose Desktop UI
./gradlew :ui:createDistributable             # assemble the packaged app image
```

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
