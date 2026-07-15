# Dev quick-facts

Build/test facts that are cheap to forget and expensive to re-derive.

## Per-module test tasks

| Module | Test task | Notes |
|---|---|---|
| `:core` | `:core:jvmTest` | fast, primary TDD loop |
| `:contract` | `:contract:jvmTest` | fast |
| `:ui` | `:ui:test` | plus `:ui:checkUiPackaging` for packaging invariants |
| `:cli` | `:cli:mingwX64Test` (Windows host) | **native-only — there is no `:cli:jvmTest`**; every run pays a ~1 min mingw link, so iterate in `:core:jvmTest` where possible |

On non-Windows hosts the `:cli` test task is the host target's (`linuxX64Test`, `macosArm64Test`, …).

## What to run before pushing

`./gradlew build` is broken on clean main (metadata compile) — don't use it as a gate. The
local gate is CI's own JVM job:

```
gradlew.bat :contract:jvmTest :core:jvmTest :ui:test :ui:checkUiPackaging
```

CI (`.github/workflows/ci.yml`) is the cross-platform verifier and runs three jobs:

| Job | What it runs |
|---|---|
| `jvm` | the task list above (ubuntu) |
| `native` | `:cli:<target>Test` across linux / macOS×2 / Windows — links the binary *and* runs the black-box golden tests |
| `install-scripts` | the `install.ps1` / `install.sh` regression harnesses (windows + ubuntu) |

It triggers on **every PR, and on pushes to `main` only** — a pushed feature branch runs
nothing until a PR exists. macOS targets are CI-only, but a Windows host can catch linux
compile errors first with `gradlew.bat :core:compileKotlinLinuxX64` (~30s cold).

## JVM

`java`/`gradle` are not assumed on PATH. Set `JAVA_HOME` to a JDK 21 (Temurin) before any
`gradlew` invocation; find installed JDKs via `gradlew.bat -q javaToolchains` (dev hosts
usually have several under `~/.jdks/` — pick a 21). The same JDK runs the UI uberjar
directly, and it must be the 21: launching the uberjar on a 17 fails with a `LinkageError`.

## Tracker CLI — actual commands

Every taskkling verb takes `--root <dir>` (workspace-discovery override) — from a
worktree or any other cwd, `taskkling --root C:/git/taskkling <verb> …` works without
`cd`-prefixing each call.

Reads:

```
.\taskkling.cmd list                       # whole backlog (id · title · thread · status · attrs)
.\taskkling.cmd list --ready               # actionable now (open, deps done, not deferred)
.\taskkling.cmd list -t <thread>           # one thread
.\taskkling.cmd get <id>                   # raw .md verbatim;  --body = body only;  -i = parsed fields
.\taskkling.cmd export                     # full JSON (stored + computed) — what the UI consumes
```

Writes:

```
.\taskkling.cmd add "<title>" -t <thread> [-d <dep>…] -b -   # -b - reads body from stdin
.\taskkling.cmd write <id> -    # replace body from stdin
.\taskkling.cmd append <id> -   # append body from stdin
.\taskkling.cmd link <id> -d <dep>
.\taskkling.cmd done <id>
```

`-d` is repeatable and/or comma-separated (`-d a -d b` == `-d a,b`); capture the printed id
to wire deps.

### Mutation verbs that trap agents

- `get`, not `show` — `show` exits 127 with "Too many arguments!".
- `link <id> -d <dep>` — **not** `set --depends`, which does not exist. Argument order is
  `<task> -d <its-dependency>`; reversed is silent.
- `write`/`append <id> -` read stdin — pipe it. Passing a heredoc as a positional arg is a
  different (wrong) thing.
- `restore` is the **un-archive** verb, not an undo for `done` — it pulls a task from
  trash/archive back to the active set and reopens it on the way (status→`open`, closed
  stamp cleared). On a task that is still active it refuses with exit 2 "already active";
  to undo a close there, use `reopen`.
- `get` cannot see archived tasks — it exits 2 "unknown id", and unlike `list`/`export` it
  has no `--archived`. After a `cleanup`, reach an archived task via `list --archived`.

## Running the UI for QA

`gradlew :ui:run` launched as a background task exits 0 with no output and **no window**. So for
anything that needs a *visible* window (driving it, screenshots), build the uberjar and launch it
detached (`Start-Process` on Windows) so the window actually appears and can be driven/captured.

For **env-dependent QA `:ui:run` is fine**, and is the cheaper path. It forwards `TASKKLING_BINARY`
and `TASKKLING_SMOKE` from your shell to the app JVM (pinned explicitly in `ui/build.gradle.kts`),
and `TASKKLING_SMOKE=1` takes a headless smoke path that prints the resolved binary and exits
without opening a window — so it needs no display and steals no focus:

```
TASKKLING_SMOKE=1 TASKKLING_BINARY=/path/to/taskkling gradlew :ui:run --args=<workspace-dir>
# smoke ok: binary=/path/to/taskkling nodes=… layers=… edges=…
```

`--args=<workspace-dir>` is optional (it defaults to the cwd, then walks up); point it at a
throwaway `init`-ed workspace to keep QA off the dogfood store.

**Read the `binary=` line — never infer which binary the UI picked from behaviour.** If
`TASKKLING_BINARY` names a path that isn't executable (typo, relative path, stale build),
`CliDiscovery` silently falls back to an up-tree `.taskkling/bin/taskkling[.exe]`, and a UI
pointed at the wrong binary looks exactly like a broken feature.

Absent that override, `:ui:run` builds the host CLI too and points the UI at it, so the two halves
can't skew. That wiring exists because the UI renders nothing of its own: it shells out to the CLI,
so a fresh UI on a stale binary shows the OLD tool's data. Exporting `TASKKLING_BINARY` yourself is
therefore also how you point a from-source UI at an old binary to exercise the skew toast.

Nothing rebuilds that binary for you outside `:ui:run`. The uberjar path above, the
`./taskkling[.cmd]` wrappers, and `taskkling ui` all use whatever binary is already on disk
(`.taskkling/bin`, `binary_path`, `PATH`) — run `gradlew :cli:installLocalBinDev` to refresh
the repo's own pin after touching `:core`/`:cli`. If a UI change seems to do nothing, check
`taskkling --version` against `gradle.properties` before debugging the UI.
