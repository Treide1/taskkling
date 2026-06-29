# taskkling

A git-native, markdown-file-per-task **DAG task manager** for solo + human-agent teams.
Kotlin Multiplatform: a Kotlin/Native CLI (`:cli`, the single read+write path) and a Compose
Desktop UI (`:ui`, a pure CLI client). See **`PRD.md`** for the full design and **`docs/`** /
`spike/index.html` for rationale and the read-only graph view.

## This repo dogfoods itself
taskkling's own development tasks are tracked **in taskkling**, in a workspace inside this repo
(`.taskkling/` + `tasks/`). `tasks/*.md` is committed; `.taskkling/` (lock, tmp, config) is
git-ignored, so after a fresh clone run `taskkling init` once (idempotent — it won't touch
existing `tasks/`).

### Read the current task structure from the CLI — don't guess from docs
Use the **built binary directly** — the binary name is `taskkling`, the only name (no short alias). On the
Windows dev host the debug binary is:

```
cli\build\bin\mingwX64\debugExecutable\taskkling.exe
```

(Build it first if missing — see below. On other hosts the path is `linuxX64`/`macosArm64`.)

```
taskkling.exe list            # whole backlog, ls -la style (id · title · thread · status · attrs)
taskkling.exe list --ready    # what's actionable right now (nothing blocking/deferred)
taskkling.exe list -t m2      # one thread
taskkling.exe export          # full JSON (stored + computed) — what the UI/spike consume
taskkling.exe get <id>        # all fields of one node;  show <id> prints the raw .md
```

**Structure:** tasks are grouped by `thread` (`m1`, `polish`, `m2`, `m3`, `m4`); milestones are
**gate tasks** that `depends` on their constituent tasks (no `type`/phase field by design); the
root gate is `taskkling v0.1`. Readiness is computed, never stored: a task is `ready` when it's
`open`, all `depends` are `done`, and it isn't deferred.

### Keep the graph current as you work
When you finish a dev task: `taskkling.exe done <id>`. Add new work with
`taskkling.exe add "<title>" -t <thread> [-d <dep-id>]` (capture the printed id to wire
dependencies). Commit `tasks/` changes alongside the code they describe.

## Build
`java`/`gradle` are **not on PATH**; set `JAVA_HOME` to a JDK 21 (Temurin) first.

```
gradlew.bat :cli:linkDebugExecutableMingwX64   # the native CLI used above
gradlew.bat :contract:jvmTest :core:jvmTest    # fast JVM checks
```

CI (`.github/workflows/ci.yml`) builds the JVM target plus a native matrix (linux/macOS/Windows)
on every push — the Windows dev host can't cross-compile linux/macOS, so **CI is the
cross-platform verifier**.

## Status
**M0–M4 complete; v0.1 (gate `t-qc79`) closed — the whole backlog is `done`.** The full CLI
surface ships (reads; lifecycle; `link/unlink`; `set`; body `write/append/read`; `delete`→trash +
cascade-prune; `restore`; `cleanup`; `doctor`/`export --ics` stubs; leading global `--root/--quiet`),
plus the Compose Desktop UI (DAG render + detail panel + CLI-subprocess mutations), `:core` unit/
golden/concurrency tests, README, and the tag-triggered release workflow (no tag cut yet). Version is
`0.1.0`. Post-v0.1 work lives in PRD §19 (doctor fix catalogue, `export --ics` internals, datetime
granularity, …). Always check `list --ready` for the live picture rather than trusting this paragraph.
