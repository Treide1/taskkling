# ADR-009: UI packaging — per-target uberjar + self-hosted jlink runtime

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-10
**Follow-up ADRs:** ADR-011, ADR-016

---

## Context

taskkling ships two runnable pieces: a Kotlin/Native CLI (a compiled binary,
no JVM) and a Compose Desktop UI (a JVM application). Until now the UI has not
been distributed at all — using it means cloning the repo, installing Temurin
21, setting `JAVA_HOME`, and running Gradle. The goal is that a user installs
exactly one artifact (the CLI, per ADR-001's install scheme) and reaches the
desktop UI through a new `taskkling ui` verb with the JVM entirely invisible:
no `JAVA_HOME`, no Temurin install, no Gradle.

Constraints in force:

- **No paid signing/notarization** for our own artifacts (ADR-001). On macOS
  15 Sequoia this constraint got sharper: the Control-click → Open override
  for unsigned browser-downloaded apps was removed, so any design that expects
  users to download UI artifacts in a browser inherits real Gatekeeper
  friction. Conversely, the curl-fetch path ADR-001 relies on (no
  `com.apple.quarantine` xattr → Gatekeeper never assesses) extends to JVM
  runtime trees and app bundles, provided the CLI itself extracts the archive.
- **Touch-surface tightening:** a human touches exactly one artifact, the CLI.
  Release assets may multiply as machine-consumed plumbing, but nothing new
  may demand human handling.
- **Existing machinery:** releases are GitHub Release assets verified against
  `SHA256SUMS`; the CLI already owns a download → verify → rename-swap update
  flow for single-file binaries (ADR-002). CI builds the four release targets
  win-x64 / linux-x64 / macos-x64 / macos-arm64 (ADR-003).

Four packaging technologies were evaluated (research ticket t-xtrw):
(a) jpackage/Compose `createDistributable` app image with a bundled runtime,
(b) per-target uberjar plus our own jlink-trimmed runtime image, fetched and
managed by the CLI, (c) per-target uberjar plus a stock Temurin JRE fetched
from the Adoptium API at first launch, and (d) GraalVM native-image. The
question: which one does v0.6.0 ship?

---

## Decision

Ship option (b): per-target Compose uberjars plus our own jlink-trimmed
runtime images, all self-hosted as GitHub Release assets; `taskkling ui`
downloads its platform's pair, verifies against `SHA256SUMS`, caches them, and
execs `<cache>/runtime/bin/java -jar <cache>/ui.jar` by absolute path.

Specifics decided with it:

- Runtime images are cross-linked from **Temurin 21 jmods** (the same major
  version the project builds against) — jlink can produce all four targets'
  images on a single CI runner.
- **Per-target uberjars** (four assets), each carrying only its own platform's
  Skiko native library — not one universal jar hauling all four.
- **No ProGuard minification** of the uberjar in v0.6.0.
- The UI runs as a plain `java` process — **no .app bundle or launcher stub**.
  Accepted, with only the free JVM-flag mitigations (`-Xdock:name` /
  `-Xdock:icon` on macOS; Compose already sets window title/icon elsewhere).

---

## Rationale

Option (b) is the only candidate that is simultaneously **fully self-hosted**
(every byte a user runs comes from our GitHub Releases — no third-party
service in the launch path) and **buildable from one CI runner** (jlink
cross-links against target-platform jmods; jpackage explicitly cannot
cross-compile). It also fits the machinery we already trust:

- **Updates reuse ADR-002 almost verbatim.** The uberjar is a single file, so
  the existing download/verify/rename-swap flow applies as-is. The runtime is
  a directory tree but changes only on JDK bumps, so it stages side-by-side
  keyed by JDK version — no in-place swap ever needed.
- **Zero Gatekeeper/SmartScreen exposure.** Assets are CLI-fetched (no
  quarantine xattr, no Mark-of-the-Web), the CLI does its own extraction, and
  there is no .app bundle for Gatekeeper to assess in the first place. The
  no-paid-signing constraint carries a third artifact class without new
  exceptions.
- **The JVM stays an implementation detail** in a cache directory, invoked by
  absolute path; `JAVA_HOME` is never consulted. Failure surfaces (download,
  checksum, corrupt cache, first-run latency of ~80–100 MB) are all owned by
  the `taskkling ui` verb, which is where this effort wants errors to live.

The sub-decisions follow from the same forces. Temurin 21 keeps the shipped
runtime on the exact major version development and CI already exercise —
a newer runtime-only LTS would add a skew with no benefit to Compose.
Per-target jars keep downloads ~30–50 MB smaller than a universal jar; the CLI
already knows its os/arch, so per-target selection costs nothing, and "fewer
asset kinds" helps no human under the touch-surface invariant (assets are
plumbing). ProGuard's ~10–15 MB saving is not worth the reflection-breakage
risk in Compose/Skiko plus hand-maintained keep rules, and — unlike the
app-image route, where the Compose plugin wires it in — we would be
integrating it manually; it can be added later without changing the
distribution shape this ADR fixes. Re-buying OS-level app identity (launcher
stubs, .app scaffolding) would re-import exactly the per-platform packaging
cost that made option (a) lose.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| (a) jpackage / Compose app image, bundled runtime | Total JVM invisibility and real Dock/taskbar identity, but jpackage cannot cross-compile: a 4-runner release matrix including `macos-15-intel` (GitHub's last Intel image, retired Aug 2027), each leg adding 5–10 min of Gradle+jlink+jpackage. Update unit is a directory tree of hundreds of files — the rename-swap flow doesn't transfer. Browser-downloaded unsigned bundles hit the hardened Sequoia Gatekeeper wall. |
| (c) uberjar + Temurin JRE auto-provisioned from the Adoptium API | Lightest CI of all, and the runtime is Eclipse-signed/notarized for free — but first launch depends on a third-party CDN (api.adoptium.net down ⇒ `taskkling ui` fails), and mitigating that by mirroring the JRE converges on (b) with a fatter (~160–190 MB on-disk) runtime. |
| (d) GraalVM native-image | Not viable: native-image AWT support is experimental and Linux-only; Compose's Skiko fails resource resolution; the only working demo replaces AWT with JWM (not stock Compose). Two of our four targets are macOS. Revisit only if GraalVM ships macOS/Windows AWT support and JetBrains ships native-image metadata for Compose/Skiko. |
| Universal (all-platform) uberjar | Every user downloads every platform's Skia natives (~30–50 MB dead weight) to save one asset kind that no human ever touches. |
| ProGuard-minified uberjar | ~10–15 MB saving vs. reflection-breakage risk and manual keep-rule upkeep; revisitable later without redeciding this ADR. |

---

## Consequences

**Positive:** one CI runner can build all eight UI assets (4 jars + 4 runtime
images); no new runner OSes, no jpackage, no dependency on the sunsetting
Intel-mac image. Updates ride the existing single-file swap machinery. No
signing spend, no Gatekeeper/SmartScreen prompts on the supported (CLI-fetch)
path. The human-touched surface stays exactly one artifact.

**Negative / open:** ~80–100 MB first-run download and ~120–140 MB on disk per
user, all fetched by the CLI — download/checksum/cache-corruption errors become
the `taskkling ui` verb's to own and explain. The UI has generic OS process
identity (no real Dock/taskbar app identity beyond `-Xdock:*` flags); if that
ever matters, it is a conscious follow-up effort. Browser-downloading UI
assets is unsupported by design. A JDK major bump means shipping four new
runtime images and cache-keying logic must handle coexisting runtimes.
Supersede this ADR if GraalVM native-image becomes viable for stock Compose
Desktop on macOS/Windows, or if OS-level app identity becomes a requirement.
