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

`gradlew :ui:run` launched as a background task exits 0 with no output and **no window** —
useless for verification. Instead build the uberjar and launch it detached
(`Start-Process` on Windows) so the window actually appears and can be driven/captured.
