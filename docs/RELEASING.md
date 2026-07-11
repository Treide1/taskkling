# Releasing

How a `vX.Y.Z` release is cut and verified. The pipeline is wire-only
(`.github/workflows/release.yml`): pushing a version tag runs the test suites, builds the
release assets, checksums them, and publishes them with both install scripts to the
project's public GitHub Releases. Tagging is human-owned — no tag is cut by automation.
An **explicit user instruction to an agent to cut a release counts as the human cut**; that
one sentence is the single authority rule — gate-task bodies and agent memory notes restate
it, they do not add to it. The executable procedure lives in `.claude/skills/cut-release`
(invoked as `/cut-release`); this document is the rationale.

## Asset inventory (since v0.6.0, ADR-011/016)

A release carries **13 assets** — 12 payload files plus one `SHA256SUMS` covering exactly
those 12 (no separate UI sums file):

| Assets | Built by |
|---|---|
| 4 CLI binaries `taskkling-<target>[.exe]` | `build` job (4-leg native matrix) |
| 4 UI uberjars `taskkling-ui-<target>.jar` | `build-ui` job (one ubuntu leg; Gradle `packageUberJar<Target>` tasks in `ui/build.gradle.kts`) |
| 4 UI runtimes `taskkling-ui-runtime-jdk21-<target>.tar.gz` (`.zip` for windows) | `build-ui` job (jlink cross-linked from pinned Temurin jmods) |

`<target>` ∈ linux-x64, macos-x64, macos-arm64, windows-x64. The `release` fan-in asserts
`dist/` holds **exactly 12** payload files before checksumming — a half-succeeded upload
fails there, so any change to the asset set must update that count in the same commit.

## Temurin pin bump (quarterly)

The runtime images are jlinked from a **pinned** Temurin build: the `build-ui` job's `env`
block in `release.yml` holds `TEMURIN_TAG` + four per-target archive SHA256s (from the
`temurin21-binaries` release's `.sha256.txt` files). Adoptium ships CPU updates roughly
quarterly; bumping the pin is a deliberate **one-commit act** (new tag + four new sums).
A stale pin only means shipping a slightly older JDK build — the pinned bytes stay
reproducible either way.

**A JDK MAJOR bump is a coordinated change**, all in one commit: the pin, the curated
module list (below), the runtime asset names (`jdk<major>`, ADR-011), the UI cache keys
(ADR-010, `uiRuntimeCacheKey` in `core`), and every workflow's `setup-java` `java-version`
plus the Gradle toolchain declarations — they move in lockstep.

## Curated jlink module list

The module list lives ONCE in `ui/build.gradle.kts` (`curatedUiRuntimeModules`), derived via

```sh
jdeps --multi-release 21 --print-module-deps --ignore-missing-deps ui/build/uberjars/taskkling-ui-linux-x64.jar
```

plus any hand-added reflective modules jdeps cannot see. Re-derive after `:ui` dependency
bumps (Compose/Skiko) or when code starts using a new JDK area. Forgetting is safe by
construction: PR CI's `:ui:checkUiJarModules` asserts the jdeps output stays a **subset**
of the curated list and goes red the moment it doesn't — review the additions, update the
list, done. (What the tripwire cannot catch: NEW reflective-only deps; if a shipped UI ever
dies with `NoClassDefFoundError` at launch, that's the signature.)

## Pre-cut

Run before tagging; each catches state the pipeline's own guard cannot:

1. **Feature branches are merged** — `git log main..<branch>` is empty for every branch.
   A non-empty range strands commits, so the release silently omits them.
2. **`main` matches the remote** — clean tree, not behind `origin/main` (ahead is expected;
   those are the commits shipping).
3. **`version=` in `gradle.properties` equals the tag's `X.Y.Z`** — the publish job asserts
   this and fails the run on a mismatch, after the tag name is already spent.

Then bump `gradle.properties`, commit, push `main`, and push the tag `vX.Y.Z`. The tag runs
`test → build → publish`; a failing suite blocks the publish, so a tag can never ship an
untested commit.

## Post-publish

Confirm the release actually landed and is downloadable — fetch the published URLs
directly, not any local build output.

**`release.yml`'s `smoke-anonymous` job (`needs: release`) automates the core of this
already — check it green in the Actions tab first.** It exists because v0.2.0 once
"succeeded" while the then-private repo 404'd every anonymous download: authenticated
`gh` saw a perfect release, users got nothing. So the job uses plain, unauthenticated
`curl` (never `gh`, no token — `permissions: {}` and an empty `env` on the job make that
structural, not just convention) to fetch `install.sh`, `install.ps1`, and the linux
binary from `releases/latest/download/` — retrying for up to ~2 minutes to absorb
`latest` propagation lag — verifies the binary against the published `SHA256SUMS`, runs
it with `--version`, and asserts the output matches the tag that triggered the release.

What it does **not** cover — still do these by hand:

For `BASE = https://github.com/Treide1/taskkling/releases/latest/download`:

1. **HEAD 200 on the other 11 payload assets** — the job only fetches
   `install.sh`/`install.ps1`/`taskkling-linux-x64`/`SHA256SUMS`; the remaining 3 CLI
   binaries and all 8 UI assets (see inventory above) aren't touched.
2. **`SHA256SUMS` has exactly twelve entries** — the job only checks the one line it
   needs (the linux binary's); it doesn't assert the full count or verify every asset.
3. **`install.sh`/`install.ps1` actually run end-to-end** — the job only proves they
   *download* (HTTP success); it never executes either. Run one from the published URL
   on a scratch `HOME` and confirm `taskkling --version` reports the new version.
   On Windows, run `install.ps1 -NoPath -InstallDir <scratch dir>` — `-NoPath` skips the
   `HKCU\Environment\Path` write entirely and `-InstallDir` keeps the binary out of the real
   `%LOCALAPPDATA%`, so the check touches nothing outside `<scratch dir>` (see the script's
   header comment for the full flag list, including the `iex`-pipe invocation form).

   > **Windows note:** don't run `install.ps1` without `-NoPath` for verification — plain
   > `HOME`/env overrides do **not** isolate its PATH step; it writes the real
   > `HKCU\Environment\Path` regardless. Always pass `-NoPath` (and `-InstallDir`) as above.
4. **The UI path works end-to-end** — on a host with a display, run `taskkling ui` with the
   fresh binary: first launch fetches jar + runtime with progress, verifies, opens the
   window, and returns the prompt; `taskkling ui --fetch-only` on a second machine (or
   after clearing the cache home) exercises the headless prefetch path.

## Milestone head convention (interim until v0.7.0 first-class milestones)

Every release milestone in the dogfood backlog is headed by the same three tasks — copy
this shape verbatim instead of imitating the nearest older milestone (hand-imitated heads
are how v0.6.1/v0.6.2 drifted apart and accrued dangling edges):

- **bump** — `bump version to X.Y.Z`; depends on **the previous milestone's gate only**
  (not the impl tasks — the gate already aggregates them).
- **gate** — `taskkling vX.Y.Z (<name> gate)`; depends on the previous gate, the bump,
  and **every impl task in the milestone**. The body carries the milestone spec/DoD and
  any start conditions.
- **cut** — `cut vX.Y.Z release (human)`; depends on the gate only.

Shared thread label on all of them (e.g. `v0.6.1-stabilization`). Hard rule: **every id a
head task references must exist in the active store** — ids that live only on an unmerged
branch make the head un-mutable (referential integrity rejects any new edge on the node,
which is what froze the v0.6.2 head). Create head tasks only from ids visible in
`taskkling list`.

This section retires when v0.7.0's first-class `milestone` attribute + derived gate lands
(t-xgzw / t-6pfw).

## v0.6.0 release-notes stub (one-time; paste atop the generated notes)

> **The desktop UI now ships with the CLI.** Run `taskkling ui` — the first launch
> fetches the UI and its trimmed Java runtime (~80–100 MB, verified against
> `SHA256SUMS`, cached under your user cache dir) and every later launch is instant.
> No `JAVA_HOME`, no Temurin install, no Gradle. `taskkling ui --fetch-only`
> prefetches without launching (works headless, e.g. before going offline).
> If you previously ran the UI from source with `./gradlew :ui:run`: that still
> works and stays the contributor path — the clone-and-build ritual is just no
> longer needed to *use* the UI, and the build now auto-provisions its JDK too.
