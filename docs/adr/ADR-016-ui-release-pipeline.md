# ADR-016: UI release pipeline — dedicated build-ui job, pinned jmods, smoke checks

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-11
**Follow-up ADRs:** —

---

## Context

ADR-009 fixed the UI packaging shape (per-target uberjars + self-hosted
Temurin-21 jlink runtime images, buildable from one CI runner) and ADR-011
fixed the asset list a release must carry (13 assets; `taskkling-ui-<target>.jar`,
`taskkling-ui-runtime-jdk<major>-<target>.tar.gz|.zip`, one `SHA256SUMS`
covering exactly the 12 payload assets). Both consciously deferred the
CI/release-pipeline mechanics: which job builds the 8 UI assets, how
per-target Skiko uberjars come out of Gradle on a single machine, where the
target-platform jmods for cross-jlinking come from, how the jlink module set
is determined and kept from drifting, and what PR CI exercises.

Facts in force: `release.yml` today is `test` (JVM, ubuntu) → `build`
(4-leg native matrix) → `release` (fan-in; `sha256sum taskkling-*`; explicit
publish list). The Compose plugin's `packageUberJarForCurrentOS` can only
bake in the host's Skiko native. jlink cross-links against target-platform
jmods, which Adoptium publishes per target (`temurin21-binaries` releases).
jdeps derives a module list from static references only; reflective use
(e.g. Skiko's `sun.misc.Unsafe` → `jdk.unsupported`) must be added by hand.

---

## Decision

1. **Topology: a dedicated `build-ui` job** on `ubuntu-latest`, `needs: test`,
   running in parallel with the native `build` matrix; it builds and uploads
   all 8 UI assets, and `release` becomes `needs: [build, build-ui]`.

2. **Four explicit per-target uberjar tasks in `:ui`** (`linux-x64`,
   `macos-x64`, `macos-arm64`, `windows-x64`), each assembling the shared
   application classpath with that target's own `skiko-awt-runtime-<os>-<arch>`
   artifact, manifest-equivalent to today's `packageUberJarForCurrentOS`
   output. One Gradle invocation on the ubuntu leg yields all four jars; the
   tasks run on any dev machine.

3. **Pinned Temurin jmods with checked-in checksums.** The build-ui job
   downloads the four target-platform Temurin 21 jmod archives from the
   `temurin21-binaries` release pinned in the repo (exact `21.0.x+y` build +
   four per-target SHA256s), verifies them, then cross-jlinks the four runtime
   images. Bumping the pin is a deliberate one-commit act. (This is a CI-time
   fetch; ADR-009's self-hosting rule governs the user's launch path, not
   build inputs.)

4. **Fixed curated jlink module list**, derived once with
   `jdeps --multi-release 21 --print-module-deps --ignore-missing-deps`
   against the built jar, reviewed, and hardcoded next to the jlink invocation
   together with the derivation command and the hand-added reflective modules.
   RELEASING.md gets a reminder of when and how to re-derive.

5. **Smoke checks in build-ui** (all deterministic, seconds each):
   (a) execute the *linux* runtime image's `bin/java -version`; structural
   presence of `bin/java`/`bin/java.exe` for the three non-host images;
   (b) run jdeps against the built jar and assert its module set is a subset
   of the curated list — the automatic tripwire that makes the fixed list
   maintenance-free; (c) audit each jar's contents and assert it carries
   exactly its own target's Skiko native and none of the others. No
   xvfb/headless launch attempt in v0.6.0.

6. **Release fan-in: count assertion + globs.** Before checksumming, assert
   `dist/` holds exactly the 12 expected payload files; then keep
   `sha256sum taskkling-*` and publish via glob. The single numeric invariant
   enforces ADR-011's "SHA256SUMS covers exactly the full asset set".

7. **PR CI builds the four uberjars and runs checks (b) and (c)** in the
   existing JVM job after `:ui:test`; **jlink stays release-only** (its inputs
   are pinned constants; re-verifying them per PR costs ~300 MB of downloads
   for no information).

8. **Cost accepted:** ~8–12 ubuntu minutes per release, in parallel with the
   ~10–15 min macOS long pole (release wall-clock roughly flat); ~1–2 min
   added to PR CI.

---

## Rationale

**Dedicated job.** Mirrors the existing shape (test gates everything, release
fans in), keeps UI-asset failures legible as their own red job, and leaves the
native legs' timing untouched. Bolting onto the Linux matrix leg would
serialize UI work behind the native link and make matrix rows asymmetric.

**Explicit per-target tasks.** The only way to honor ADR-009's single-runner
decision — `packageUberJarForCurrentOS` on a 4-runner matrix would silently
reopen it and resurrect the sunsetting Intel-mac runner dependency.

**Pinning.** Reproducibility (same tag → same runtime bytes) and supply-chain
sanity (a shifted or compromised upstream archive fails loudly instead of
shipping). "Latest GA at build time" would make shipped bytes a function of
when the tag was pushed, with nothing to verify against. The maintenance cost
is real but small: Temurin CPU updates arrive quarterly and each release
re-ships runtimes anyway.

**Curated list + tripwire.** A fixed list is deterministic and reviewed, but
on its own it relies on a human remembering to re-derive after dependency
bumps — and the failure mode (successful build, `NoClassDefFoundError` at
*user* launch) is the worst kind. The jdeps-subset assertion turns that
forgotten re-derive into a red release job; with it, the list needs no
discipline at all. Feeding jdeps output to jlink directly would ship an
unreviewed, silently growing module set and still miss reflective deps.

**Skiko audit.** The classic cross-jar packaging bug — wrong or all natives
baked into a jar — is invisible to any launch test on one OS; a content
audit catches it on every target for free.

**Count assertion over explicit lists.** Two hand-maintained 13-entry lists
(checksum + publish) can drift from each other and from reality — which is
precisely how under-coverage happens. One expected-count constant is the
minimal invariant that still fails loudly when an upload half-succeeds.

**PR-time uberjars.** The checks guard against exactly the changes PRs
introduce (dependency bumps, packaging edits); leaving them release-only means
discovering breakage while cutting a release. The expensive part of the jar
tasks — compiling `:ui` — is already paid by `:ui:test`.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Build UI assets on the existing Linux matrix leg | Serializes ~10 min of UI work behind the native link; asymmetric matrix rows; "taskkling-linux-x64 failed" becomes ambiguous. |
| `packageUberJarForCurrentOS` on a 4-runner matrix | Reopens ADR-009's single-runner decision; depends on the retiring Intel-mac image. |
| Fetch "latest GA 21" jmods from the Adoptium API at build time | Non-reproducible releases; upstream re-spins silently change shipped bytes; nothing to verify against. |
| jdeps-in-CI feeding jlink directly | Unreviewed, can silently grow the runtime, and still misses reflective deps — doesn't even remove the need for the smoke check. |
| Full headless launch check (xvfb) | The only check that catches reflective gaps, but flaky by construction (Skiko under Xvfb, aliveness heuristics); revisit only if a reflective-module gap actually bites. |
| Explicit 13-entry checksum + publish lists | Two lists that can drift is how incomplete releases ship; the count assertion is the smaller, stronger invariant. |
| Full jlink parity in PR CI | ~300 MB of downloads and 2–4 min per PR to re-verify pinned constants. |
| Everything release-tag-only (no PR coverage) | Module drift and packaging breakage surface at the worst possible moment — while cutting a release. |

---

## Consequences

**Positive:** all 8 UI assets from one ubuntu leg with release wall-clock
roughly flat; releases are byte-reproducible per tag; the three cheap smoke
checks convert the design's known drift risks (module list, wrong natives,
incomplete uploads) into red CI instead of shipped bugs; PRs catch UI
packaging breakage for ~1–2 min of CI.

**Negative / open:** the Temurin pin must be bumped by hand for CPU updates
(quarterly cadence, one commit); a JDK major bump touches the pin, the module
list, asset names (ADR-011), and cache keys (ADR-010) in one coordinated
change; the count assertion (12) and the expected-asset vocabulary must move
in lockstep with any future asset-list change; reflective-module gaps remain
theoretically possible until an xvfb-style launch check exists. Supersede
this ADR if the asset set changes shape, if PR build times degrade enough to
demote the PR-time uberjar checks, or if a reflective gap forces the launch
check in.
