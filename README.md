<p align="center">
  <img src="docs/assets/logo.svg" width="112" alt="the taskkling — two done deps converge into a ready task"/>
</p>

# taskkling

taskkling is a task manager for people whose work doesn't fit a flat checklist.
Every task is a plain markdown file in your git repository, tasks can depend on
each other, and one command answers the question you actually have:
*"what can I work on right now?"*

There's no database, no server, no account. Your tasks are files you own —
readable in any editor, versioned with your code, greppable, and still yours if
you stop using the tool tomorrow.

## Why taskkling?

Real work has shape: B can't start until A is done, C is parked until next
week, D has a deadline. A checklist flattens all of that into one anxious list.

taskkling models the dependencies directly and *computes* your to-do list from
them. A task is **ready** when it's open, nothing it depends on is unfinished,
and it isn't deferred — so `taskkling list --ready` always shows exactly what's
actionable, and nothing that isn't.

And because every task is just a markdown file with a small metadata header,
you're never locked in. Everything the tool does, you could do by hand-editing
files — taskkling just makes it fast, safe, and queryable.

## Get started

**macOS / Linux**

```sh
curl -fsSL https://github.com/Treide1/taskkling/releases/latest/download/install.sh | sh
```

**Windows (PowerShell)**

```powershell
irm https://github.com/Treide1/taskkling/releases/latest/download/install.ps1 | iex
```

The script fetches the right binary for your platform, puts it on your `PATH`
(`~/.local/bin` on Unix, `%LOCALAPPDATA%\Programs\taskkling` on Windows), and
verifies it against the published checksums. Open a new terminal afterwards so
the `PATH` change is picked up.

Prefer a manual install? Grab a binary from the
[Releases page](../../releases) and check it against the `SHA256SUMS` file
published alongside it. On macOS, a browser-downloaded binary needs
`xattr -dr com.apple.quarantine ./taskkling` before its first run (the
`curl | sh` path doesn't).

Then, in any project:

```sh
taskkling init                                    # scaffold .taskkling/ + tasks/ (idempotent)

taskkling add "Draft the proposal" -t docs        # prints the new task's id, e.g. t-a1z9
taskkling add "Ship it" -d t-a1z9                 # "Ship it" depends on the draft
taskkling list --ready                            # → only "Draft the proposal" is actionable
taskkling done t-a1z9                             # finish it...
taskkling list --ready                            # → now "Ship it" is ready
```

You can run `taskkling` from anywhere inside the project — it walks up the
directory tree to find the workspace. For team repos where not everyone has a
global install, `taskkling init --local-bin` pins a copy of the binary into the
project and drops `./taskkling` / `./taskkling.cmd` wrapper scripts at the root,
so contributors can use it straight from a clone.

## Everyday commands

```sh
taskkling list                          # the whole backlog, ls -la style
taskkling list --ready                  # what's actionable right now
taskkling done <id>                     # lifecycle: done / drop / reopen / wait
taskkling set <id> --due 2026-07-31 --priority high
taskkling write <id> "body text"        # body I/O: write / append / read (- = stdin)
taskkling link <id> --depends <dep>     # add/remove edges (cycles are rejected)
taskkling delete <id>                   # -> trash; restore <id> undoes it
taskkling cleanup                       # sweep closed tasks into archive/
```

Output is human-readable by default; most commands take `--json`, and
`taskkling export` dumps the entire graph as JSON. The full command surface is
specified in [PRD.md](PRD.md).

## The desktop app

Alongside the CLI there's a desktop UI (Compose Desktop) that renders your task
graph and performs every change by driving the CLI — so the two can never
disagree about your data. Since v0.6.0 it ships with the CLI: `taskkling ui`
fetches the UI and its trimmed Java runtime on first launch (~80–100 MB,
checksum-verified, cached from then on) and starts it detached — no Java
install, no Gradle. `taskkling ui --fetch-only` prefetches without launching;
running from a clone with `./gradlew :ui:run` remains the contributor path.

## Staying current, and leaving cleanly

```sh
taskkling update              # self-update to the latest release (checksum-verified)
taskkling update --check      # just tell me if something newer exists
taskkling uninstall           # remove the binary + PATH entry — never your tasks
```

`taskkling --version` also mentions (in interactive terminals only, at most once
a day) when a newer release is out; set `update_check = false` in your
`config.toml` (`taskkling config init` creates it and prints its path) to turn
that off. Nothing you authored is ever deleted unless you explicitly run
`uninstall --purge`. The finer points — per-project vs. global binaries, how the
locked `.exe` is swapped on Windows — are recorded in [docs/adr/](docs/adr/).

## Project status

taskkling is pre-1.0 and under active development; the current release is
**v0.6.0**. Binaries ship for Linux x64, macOS (Intel and Apple Silicon), and
Windows x64 on every tagged release — since v0.6.0 each release also carries
the desktop UI, fetched on demand by `taskkling ui` — and CI builds and tests
all native targets on every push. The project manages its own backlog with taskkling.

## Building from source

Any JDK findable on `PATH` or `JAVA_HOME` boots Gradle; the build then
auto-provisions the JDK 21 it actually compiles with (foojay toolchain
resolver) — no manual Temurin install, no `JAVA_HOME` ritual.

```sh
./gradlew :cli:linkDebugExecutableMingwX64    # native CLI (host target: Mingw/Linux/Macos)
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

## License

[MIT](LICENSE).

---

## For agents

Machine-facing summary for coding agents and automation working in or against a
taskkling workspace.

**Data model.** One markdown file per task (YAML frontmatter + freeform body)
under the workspace's tasks directory; edges are `depends` lists in
frontmatter. `ready` is computed: `open` ∧ all `depends` `done` ∧ not deferred.
Source of truth is the files; git is the history/sync layer. Design north star:
"file I/O with editable metadata" — hand-editing files is legal, but the CLI is
the safe, lock-aware path.

**Single write path.** The Kotlin/Native CLI (`taskkling`) is the only
programmatic reader/writer. The desktop UI is a pure CLI client: it links only
the `:contract` DTOs (never `:core`), renders `taskkling export` JSON, and
mutates exclusively by invoking the CLI — structurally incapable of becoming a
second writer. Do the same: drive the CLI, don't write task files directly.

**Machine conventions.**

- `taskkling export` is always JSON (stored + computed fields); other commands
  take `--json` where applicable.
- Any mutation accepts `--export-on-success` for a transactional
  read-after-write.
- Global flags: `--root <path>`, `--quiet`, `--no-color`.
- Exit codes: `0` ok · `2` usage · `3` validation · `4` lock timeout.
- Writes are lock-guarded and atomic; concurrent writers contend on the lock
  rather than corrupting files.
- The passive update check never fires in pipes, scripts, CI, or `--json`
  output — machine surfaces are fully offline.

**Repo layout.**

| Module | Targets | Responsibility |
|---|---|---|
| `:contract` | native + JVM | `@Serializable` DTOs for the `export` JSON contract (CLI ↔ UI) |
| `:core` | native + JVM | domain model, frontmatter I/O, graph + ready-set + validation, lock/atomic-write |
| `:cli` | native | the `taskkling` binary; thin command layer over `:core` |
| `:ui` | JVM (Compose Desktop) | desktop app; renders `export`, mutates via the CLI |

**Docs to read first:** [PRD.md](PRD.md) (full spec, command surface in §10),
[docs/DESIGN.md](docs/DESIGN.md), [docs/DOMAIN_LANGUAGE.md](docs/DOMAIN_LANGUAGE.md),
[docs/adr/](docs/adr/) (immutable decision records), [docs/RELEASING.md](docs/RELEASING.md).

**Dev-state note.** A fresh clone contains no `tasks/` or `CLAUDE.md` — the
project's own dogfooded backlog lives in git-ignored local state
(`.taskkling/tasks`). Run `taskkling init` after cloning. `version=` in
`gradle.properties` is the canonical tool version; release tags are cut by a
human, never automation.
