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

Download the binary for your platform from [GitHub Releases](../../releases) and drop it
on your `PATH` (install = one file; vendorable per-repo). Then, in any repo:

```sh
taskkling init          # scaffold .taskkling/ + tasks/ (idempotent)
```

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

## Build from source

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

## Modules

| Module | Targets | Responsibility |
|---|---|---|
| `:contract` | native + JVM | `@Serializable` DTOs for the `export` JSON contract (CLI ↔ UI) |
| `:core` | native + JVM | domain model, frontmatter I/O, graph + ready-set + validation, lock/atomic-write |
| `:cli` | native | the `taskkling` binary; thin command layer over `:core` |
| `:ui` | JVM (Compose Desktop) | desktop app; renders `export`, mutates via the CLI |

The UI links **only `:contract`** (the DTOs), never `:core` — so it is *physically
incapable* of writing task files, structurally guaranteeing the single-write-path design.

## This repo dogfoods itself

taskkling's own development backlog is tracked **in taskkling**, in a workspace inside this
repo (`tasks/*.md` is committed; `.taskkling/` is git-ignored — run `taskkling init` once
after a fresh clone). CI (`.github/workflows/ci.yml`) builds the JVM target plus a native
matrix (linux/macOS/Windows) on every push to `main` and on every PR.

## License

See repository for license details.
