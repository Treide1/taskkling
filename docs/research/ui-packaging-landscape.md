# UI packaging landscape for v0.6.0 (`taskkling ui`)

Research ticket: t-xtrw. Date: 2026-07-10. Status: facts only — the packaging
decision itself is a later human ticket.

Goal: ship the Compose Desktop UI (JVM module `:ui`) so end users never manage a
JVM or `JAVA_HOME`. Four options compared. Hard constraint carried from ADR-001:
no paid Apple Developer ID signing/notarization for our own artifacts.

Grounding: README (Install/Updating/Development), ADR-001, ADR-003,
`ui/build.gradle.kts` (already applies the Compose plugin with
`nativeDistributions` targeting Msi/Dmg/Deb; `createDistributable` is wired).

---

## Option (a): Compose `createDistributable` / jpackage app-image, bundled runtime

One self-contained app image per platform: a native launcher + your jars + a
jlink-trimmed JRE inside one directory tree (`.app` on macOS). The Compose
Gradle plugin drives jlink + jpackage; ProGuard minification is available via
the `packageRelease*` / `createReleaseDistributable` tasks
([Compose native distributions docs](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)).
Note: the plugin does NOT auto-detect required JDK modules — they must be
listed via `modules(...)` (there is a `suggestModules` helper task).

**Size (community data, empty Compose template, Apple Silicon,
[dev.to size comparison](https://dev.to/coltonidle/compose-for-desktop-app-size-comparison-3d8h)):**

| Build | Compressed (dmg/zip) | On disk (.app) |
|---|---|---|
| default `packageDmg` | 64.9 MB | 124.1 MB |
| release (ProGuard minify) | 51.9 MB | 109.9 MB |
| release + obfuscate | 48.0 MB | 105.8 MB |

Windows (msi/zip) and Linux (deb/tar) land in the same ballpark (the runtime
image dominates). Realistic estimate per target: **45-65 MB compressed,
105-125 MB on disk**, times four targets = ~200-260 MB of release assets.

**CI / release pipeline:** jpackage has **no cross-compilation** — "to build a
.dmg you have to run packageDmg on macOS"
([Compose docs](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)),
confirmed by the [jpackage man page](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)
and [JEP 392](https://openjdk.org/jeps/392). Required matrix: `ubuntu-latest`
(linux-x64), `windows-latest` (win-x64), `macos-latest` (arm64), and for
macos-x64 the **`macos-15-intel`** label — `macos-13` was retired 2025-12
([GitHub changelog](https://github.blog/changelog/2025-09-19-github-actions-macos-13-runner-image-is-closing-down/)),
and `macos-15-intel` is the last Intel image, available **until Aug 2027**
([runner-images #13045](https://github.com/actions/runner-images/issues/13045)).
This mirrors the existing 4-leg native CLI matrix (ADR-003), so no new runner
OSes — but each leg adds a full Gradle + jlink + jpackage (+ ProGuard) build,
roughly 5-10 min per leg on hosted runners.

**macOS Gatekeeper (unsigned):** jpackage **ad-hoc signs** the .app by default
(pseudo-identity `-`) even without `--mac-sign`
([JDK-8332110 review thread](https://www.mail-archive.com/core-libs-dev@openjdk.org/msg32209.html)),
which satisfies the arm64 kernel's signature requirement — same situation as
our Kotlin/Native CLI binary (ADR-001). But ad-hoc is not Developer ID, so:

- *Browser-downloaded* (dmg or zip via Finder): quarantine attribute is set and
  propagates into the extracted .app; Gatekeeper blocks. On **macOS 15 Sequoia
  the Control-click -> Open override was removed**; the user must go to System
  Settings > Privacy & Security > "Open Anyway" + admin password
  ([Apple developer news](https://developer.apple.com/news/?id=saqachfa),
  [Daring Fireball](https://daringfireball.net/linked/2024/08/07/mac-os-15-sequoia-gatekeeper)).
  This is *worse* friction than when ADR-001 was written.
- *CLI-downloaded* (curl + `/usr/bin/tar` driven by `taskkling ui`): no file in
  the bundle carries `com.apple.quarantine`, and Gatekeeper only assesses
  quarantined items — it keys off the xattr on the bundle root
  ([nixhacker Gatekeeper internals](https://nixhacker.com/security-protection-in-macos-1/),
  [Red Canary](https://redcanary.com/threat-detection-report/techniques/gatekeeper-bypass/)).
  **The ADR-001 curl trick does extend to .app bundles / JVM app images on
  Sequoia-era macOS**, with two provisos: (1) ship a tar.gz/zip that the CLI
  extracts itself — if the *user* double-clicks the archive, Archive Utility
  propagates quarantine from the archive into every extracted file; (2) do not
  ship the .dmg as the CLI-fetched artifact (dmg mounting is Finder-mediated).
  No paid signing needed on the CLI path.

**Windows SmartScreen:** unsigned launcher .exe inside the app image. SmartScreen
triggers only on files carrying Mark-of-the-Web (the `Zone.Identifier` ADS),
which browsers apply and `curl.exe` / WinHTTP-based fetchers do not
([textslashplain MOTW](https://textslashplain.com/2016/04/04/downloads-and-the-mark-of-the-web/),
[Red Canary](https://redcanary.com/threat-detection-report/techniques/mark-of-the-web-bypass/)).
CLI-fetched zip -> no MOTW -> no SmartScreen prompt. A browser-downloaded
unsigned MSI/exe shows the "unknown publisher" interstitial.

**Update mechanics:** the artifact is a **directory tree** (hundreds of files),
not a single file. The CLI's existing rename-swap dance does not transfer
1:1; the natural scheme is: download archive -> extract to
`cache/ui/<version>-new` -> atomic directory rename swap (the running UI must
not be live during swap; on Windows a running app image locks its own files).
The Compose plugin has no built-in updater; the docs point at the third-party
[Conveyor](https://www.hydraulic.software) tool for delta updates.

**JVM invisibility:** total. Native launcher binary, runtime embedded,
`JAVA_HOME` never consulted, no `java` on the user's PATH. Failure surface is
the app itself, not JVM provisioning.

---

## Option (b): Uberjar + jlink-minimized runtime, fetched and managed by the CLI

Ship two kinds of assets on GitHub Releases: a per-platform Compose uberjar
(skiko native lib differs per target) and a per-platform jlink runtime image.
`taskkling ui` downloads both into a cache dir, verifies against SHA256SUMS,
and execs `<cache>/runtime/bin/java -jar <cache>/ui.jar`.

**Size:** a jlink image with `java.base,java.desktop,java.logging,jdk.unsupported`
(Compose's typical set) is ~60-80 MB on disk; `--compress`/`--strip-debug`
brings the image itself down markedly (worked examples: 36 MB -> 23 MB image;
"under 50 MB" from a 200+ MB JDK)
([jlink man page](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jlink.html),
[Adoptium jlink guide](https://adoptium.net/news/2021/10/jlink-to-produce-own-runtime),
[Baeldung](https://www.baeldung.com/jlink)). Archived for download:
**~30-40 MB runtime + ~50-60 MB uberjar per target** (uberjar estimate derived
from the option-(a) app data: ~124 MB app minus ~65-80 MB runtime = app code +
Compose/skiko jars). On disk: ~120-140 MB total per user.

**CI / release pipeline:** cheapest full-control option. **jlink CAN
cross-link**: point `--module-path` at the target platform's jmods and a Linux
runner emits runtime images for all four targets
([Jake Wharton, cross-compiling minimal JREs](https://jakewharton.com/using-jlink-to-cross-compile-minimal-jres/)).
Uberjars are plain Gradle builds (per-target skiko classifier), also
cross-buildable. In principle a **single ubuntu runner** can produce all eight
assets; no Intel mac runner needed, no jpackage.

**macOS Gatekeeper:** there is no .app bundle at all — just a `java` launcher
inside the runtime tree, exec'd by the CLI. CLI-fetched -> no quarantine -> no
Gatekeeper assessment (same mechanism as the CLI binary today, ADR-001). The
JDK-derived launcher binaries retain/receive ad-hoc signatures, satisfying the
arm64 kernel. Browser download is not an intended path for these assets (the
CLI fetches them), which sidesteps the Sequoia friction entirely. No Dock
integration/app identity, though: the UI shows up as a generic `java` process
unless extra work is done.

**Windows SmartScreen:** CLI fetch -> no MOTW -> silent. Nothing for the user
to double-click, so effectively zero SmartScreen exposure.

**Update mechanics:** best fit with existing machinery. The uberjar is a
**single file** -> the CLI's existing download/verify/rename-swap logic applies
almost verbatim (a jar is not even locked while the UI is closed). The runtime
is a directory tree but changes rarely (only on JDK bumps) and can be staged as
`runtime-<javaVersion>/` side-by-side, no swap needed.

**JVM invisibility:** high. `JAVA_HOME` irrelevant (CLI invokes the cached
runtime by absolute path). The JVM exists but is an implementation detail in a
cache dir. New error surfaces owned by the CLI: download failures, checksum
mismatch, corrupted cache, first-run latency (~80-100 MB fetch).

---

## Option (c): CLI auto-provisions a Temurin 21 JRE (Adoptium API) + shipped uberjar

Like (b), but instead of our own jlink image the CLI fetches a stock Temurin 21
JRE from the Adoptium API on first `taskkling ui`:
`https://api.adoptium.net/v3/binary/latest/21/ga/{os}/{arch}/jre/hotspot/normal/eclipse`
([Adoptium API cookbook](https://github.com/adoptium/api.adoptium.net/blob/main/docs/cookbook.adoc)).
The API also serves checksums and release metadata; 404 for unavailable combos.

**Size (measured via the assets API, JRE 21.0.11+10):**

| Target | Download | On disk (approx) |
|---|---|---|
| windows x64 (zip) | 46.8 MB | ~110 MB |
| linux x64 (tar.gz) | 49.7 MB | ~130 MB |
| macos x64 (tar.gz) | 40.2 MB | ~110 MB |
| macos aarch64 (tar.gz) | 45.9 MB | ~115 MB |

Plus the shipped uberjar (~50-60 MB per target, as in (b)). Full JRE, so
~30-50 MB more on disk per user than (b)'s trimmed image.

**CI / release pipeline:** the lightest of all: release assets are just the
per-target uberjars from a single runner; **no jlink, no jpackage, no runner
matrix growth**. Trade-off: a **runtime dependency on a third-party service**
(Adoptium CDN) at user install time — if api.adoptium.net is down, first
`taskkling ui` fails. Mitigable by also mirroring the JRE archives as GitHub
Release assets (which converges toward option (b) with a fatter runtime).

**macOS Gatekeeper:** double cover. (1) CLI fetch -> no quarantine, as above.
(2) Temurin macOS binaries are normally **signed and notarized by the Eclipse
Foundation's own Developer ID** — there have been occasional lapses
(e.g. [adoptium-support #1181](https://github.com/adoptium/adoptium-support/issues/1181)
for one 17.x aarch64 build), but the steady state is signed. So even a
quarantined JRE would pass. The uberjar itself is not executable and is not
assessed by Gatekeeper.

**Windows SmartScreen:** Temurin Windows installers/binaries are signed by
Eclipse; the CLI fetch carries no MOTW anyway. Zero expected exposure.

**Update mechanics:** identical single-file story to (b) for the jar
(rename-swap reuse). JRE cache keyed by `feature_version`; the Adoptium API's
`/v3/binary/latest/21/ga/...` URL means a re-fetch naturally picks up the
newest 21.x patch — the CLI decides when to refresh.

**JVM invisibility:** good but the weakest of a/b/c: full stock JRE in a cache
dir, generic `java` process, first-run network dependency on a third party.
`JAVA_HOME` still irrelevant (absolute-path invocation).

---

## Option (d): GraalVM native-image of Compose Desktop — feasibility check

**Verdict: not viable for v0.6.0. Kill.** Evidence:

- Compose Desktop windowing sits on **AWT**, and GraalVM native-image AWT/Swing
  support is **experimental and Linux-only**; Windows and macOS are explicitly
  not covered ([oracle/graal #6686 "GraalVM Swing still does not work"](https://github.com/oracle/graal/issues/6686),
  [JDK-8254024](https://bugs.openjdk.org/browse/JDK-8254024)). On macOS,
  class-init failures in `sun.lwawt.macosx.LWCToolkit` / font manager are
  long-standing ([oracle/graal #2644](https://github.com/oracle/graal/issues/2644),
  [#664](https://github.com/oracle/graal/issues/664)). Two of our four targets
  are macOS — disqualifying on its own.
- Compose's renderer **Skiko** (Skia JNI) fails native-image resource/library
  resolution ("Cannot find libskiko-linux-x64.so.sha256")
  ([kotlinlang slack](https://slack-chats.kotlinlang.org/t/26738162/i-m-trying-to-use-graalvm-with-compose-for-desktop-i-m-using)).
- The only working demo, [esp-er/compose-graal-hello](https://github.com/esp-er/compose-graal-hello),
  **replaces AWT with the JWM window library** — i.e. not stock Compose
  Desktop, and not a supported JetBrains configuration.
- Compose Desktop does not appear on GraalVM's
  [ready-for-native-image list](https://www.graalvm.org/native-image/libraries-and-frameworks/);
  JetBrains ships no native-image config for it. BellSoft's Liberica NIK
  extends AWT coverage ([bell-sw guide](https://bell-sw.com/blog/how-to-turn-awt-applications-into-native-images/))
  but does not claim Compose/Skiko support.

Would-be upside (single ~50-80 MB static binary, CLI-identical distribution)
is real but unreachable today without abandoning stock Compose.

---

## Comparison table

| Axis | (a) app-image | (b) uberjar + jlink | (c) uberjar + Temurin JRE | (d) native-image |
|---|---|---|---|---|
| Download per target | 45-65 MB | ~80-100 MB (30-40 rt + 50-60 jar) | ~90-110 MB (40-50 JRE + 50-60 jar) | n/a (killed) |
| On disk per user | 105-125 MB | ~120-140 MB | ~160-190 MB | n/a |
| Release matrix | 4 runners (incl. `macos-15-intel`, sunset Aug 2027); jpackage cannot cross-compile | 1 runner possible (jlink cross-links) | 1 runner; JRE fetched at user time | n/a |
| Gatekeeper, CLI-fetched | passes (no quarantine; ad-hoc-signed .app) | passes (no quarantine, no .app) | passes (+ Temurin is Eclipse-signed/notarized) | n/a |
| Gatekeeper, browser-dl | blocked; Sequoia needs System Settings + admin pw | not an intended path | Temurin signed either way; jar inert | n/a |
| SmartScreen | none via CLI fetch; warns on browser-dl of unsigned msi/exe | none (CLI fetch, no MOTW) | none (CLI fetch + Eclipse-signed) | n/a |
| Update unit | directory tree (needs staged-dir swap) | jar = single-file rename-swap; runtime dir rarely changes | same as (b); JRE refresh via `latest` URL | n/a |
| JVM invisibility | total (native launcher) | high (cached rt, CLI-owned errors) | good (stock JRE, third-party fetch at first run) | n/a |
| Runtime 3rd-party dependency | none | none (all on GitHub Releases) | Adoptium API/CDN at first run | n/a |

## Signals for the decision (facts only)

- The **ADR-001 curl trick holds for .app bundles and JVM app images** on
  Sequoia-era macOS: Gatekeeper assesses only quarantined items (xattr on the
  bundle root), and curl/tar set none. Provisos: the CLI must do the archive
  extraction itself, and the CLI-fetched asset must be tar.gz/zip, not dmg.
- Sequoia **removed the Control-click override** — browser-download friction
  for unsigned artifacts is now materially worse than when ADR-001 was
  written. All options therefore lean on the CLI-fetch path.
- jpackage's default **ad-hoc signature** covers the arm64 kernel requirement
  for option (a), same as the Kotlin/Native linker does for the CLI today.
- Only option (a) needs the release matrix to grow real per-OS packaging work;
  options (b)/(c) fit the existing "single runner + GitHub Release assets +
  SHA256SUMS + rename-swap" machinery the CLI already has. Option (a)'s
  macos-x64 leg rides `macos-15-intel`, which GitHub retires **Aug 2027**.
- Option (c) is the only one with a **runtime dependency on a third party**
  (Adoptium) at first launch; it is also the only one whose runtime is
  Developer-ID-signed and notarized by someone else for free.
- Option (d) is dead on arrival for the macOS targets; any revisit should
  watch GraalVM AWT support for macOS/Windows and JetBrains/Skiko
  native-image metadata.
