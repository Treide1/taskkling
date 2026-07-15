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

## JVM

`java`/`gradle` are not assumed on PATH. Set `JAVA_HOME` to a JDK 21 (Temurin) before any
`gradlew` invocation; find installed JDKs via `gradlew.bat -q javaToolchains` (dev hosts
typically have one under `~/.jdks/`). The same JDK runs the UI uberjar directly.

## Driving the tracker from outside the repo

Every taskkling verb takes `--root <dir>` (workspace-discovery override) — from a
worktree or any other cwd, `taskkling --root C:/git/taskkling <verb> …` works without
`cd`-prefixing each call.

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
