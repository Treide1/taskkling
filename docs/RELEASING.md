# Releasing

How a `vX.Y.Z` release is cut and verified. The pipeline is wire-only
(`.github/workflows/release.yml`): pushing a version tag runs the test suites, builds the
release assets, checksums them, and publishes them with both install scripts to the
project's public GitHub Releases. Tagging is human-owned — no tag is cut by automation.

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

For `BASE = https://github.com/Treide1/taskkling/releases/latest/download`:

1. **HEAD 200 on all 15 files** — the 12 payload assets (see inventory above) plus
   `SHA256SUMS`, `install.sh`, `install.ps1`. A 200 on `latest/download/…` also proves
   `latest` advanced to the new tag.
2. **`SHA256SUMS` is well-formed** — exactly twelve entries, one per payload asset.
3. **The install path works end-to-end** — fetch and run one install script from the
   published URL on a scratch `HOME` and confirm `taskkling --version` reports the new version.
4. **The UI path works end-to-end** — on a host with a display, run `taskkling ui` with the
   fresh binary: first launch fetches jar + runtime with progress, verifies, opens the
   window, and returns the prompt; `taskkling ui --fetch-only` on a second machine (or
   after clearing the cache home) exercises the headless prefetch path.

   > **Windows hazard:** overriding `HOME`/env vars does **not** isolate `install.ps1`'s
   > PATH step — it writes the **real** `HKCU\Environment\Path` regardless (and that rewrite
   > carries the t-359h empty-segment normalization). Running it naively pollutes the real
   > user registry. Snapshot `HKCU\Environment\Path` before and byte-exact-restore it after,
   > or verify the artifact directly (download + extract + `--version`) without invoking the
   > PATH registration. See `dx` task for an `install.ps1` `-NoPath`/isolation flag that
   > would make this safe by construction.

## v0.6.0 release-notes stub (one-time; paste atop the generated notes)

> **The desktop UI now ships with the CLI.** Run `taskkling ui` — the first launch
> fetches the UI and its trimmed Java runtime (~80–100 MB, verified against
> `SHA256SUMS`, cached under your user cache dir) and every later launch is instant.
> No `JAVA_HOME`, no Temurin install, no Gradle. `taskkling ui --fetch-only`
> prefetches without launching (works headless, e.g. before going offline).
> If you previously ran the UI from source with `./gradlew :ui:run`: that still
> works and stays the contributor path — the clone-and-build ritual is just no
> longer needed to *use* the UI, and the build now auto-provisions its JDK too.
