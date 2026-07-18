# Bundling the UI with the CLI — packaging strategies against version skew

Research ticket: t-op1m (v0.6.5 packaging-strategy milestone). Date: 2026-07-18.
Status: research + empirical validation only — the decision is the human's; the
resulting ADR (expected to supersede/amend ADR-010, and amend ADR-009/011/016)
is written **after** agreement, not here.

Builds on `docs/research/ui-packaging-landscape.md` (t-xtrw, 2026-07-10) — the
per-technology facts there (jlink cross-linking, Gatekeeper/SmartScreen
behavior, jpackage's no-cross-compile constraint) still hold and are not
re-derived. This document is about a different axis: **who guarantees that the
CLI and the UI a user runs together came from the same build.**

---

## 1. The problem: the architecture permits skew

The UI is a pure CLI client (PRD §6.3/§13): every pixel it renders is the
output of a `taskkling` subprocess. Two independently-resolved halves means
two independent version channels, and **each direction can skew on its own**:

- **CLI → UI:** `taskkling ui` fetches the UI jar pinned to the CLI's own
  version from a GitHub release (ADR-010 decision 2). Structurally safe for
  installed releases — but `main` sits at an already-released version between
  bumps, so a from-source CLI on main silently fetches and launches a UI that
  **predates main**. Version-keyed caching cannot tell "main" from "the
  release".
- **UI → CLI:** the UI re-resolves its *own* CLI via `TASKKLING_BINARY` →
  up-tree `.taskkling/bin` → workspace `config.toml` `binary_path` → `PATH`
  (`CliDiscovery` in `ui/.../CliClient.kt`). Nothing ties that resolution to
  the CLI that launched it: a correctly version-pinned UI can still end up
  talking to a *different, older* binary (exactly the t-eeze dogfood finding —
  a pinned 0.6.2 local-bin under a 0.6.3 UI).

The 2026-07-15 incident that motivated this ticket was the dev-loop variant:
`gradlew :ui:run` rebuilt the UI but not the native CLI, so a 0.6.3 UI
rendered a v0.6.2 binary's export and looked like a broken feature — a full
debugging session lost. Every mitigation to date is **detection after the
fact** (the t-eeze `--version` toast), not prevention.

A third, related failure surface: **first-start installation itself**. The
lazy fetch (jar + runtime download, archive extraction) is a network + archive
+ filesystem gauntlet that runs on the user's box at the worst moment — first
launch. t-fst5 (fixed in 7d267cd on this branch) was exactly that: Git's GNU
`tar` shadowing System32 bsdtar broke runtime extraction outright on a dev
Windows box. A packaging strategy with no first-start step deletes that
surface entirely.

### Already solved, out of scope for the strategy choice

- **Dev-loop (`:ui:run`) skew is fixed on main** (t-tlk0 follow-up + t-hn1g,
  commits bf7b4ae/524ab77): `:ui:run` now builds the host CLI as part of the
  run and pins the `TASKKLING_BINARY`/`TASKKLING_SMOKE` hand-off, so the dev
  loop always pairs the UI with a same-commit binary. (PR #27 was an earlier
  shape of this, closed unmerged in favor of the landed variant.) The
  installed-product path is the open front.
- **Detection exists** (t-eeze, 4c10731): `CliClient.version()` probes
  `--version` at UI launch and toasts on any mismatch with the UI's generated
  `BUILD_VERSION`. See §6 for its recommended fate.

---

## 2. Candidate strategies

### A. Status quo: independent halves + pinned fetch + detection (baseline)

What ships today (ADR-009/010/011/016). Skew between an installed CLI and its
fetched UI is prevented *by pinning* — but only on the happy path. Skew
remains possible via: mixed installs (pinned local-bin + global UI), dev
CLIs on main (fetch predates main), and hand-assembled setups. First-start
fetch/extract failure surface stays (t-fst5 class). Cost: zero new work.

### B. Full bundle: one archive per target = CLI + UI jar + jlink runtime  ⟵ validated (§3)

One release asset per target, e.g. `taskkling-bundle-windows-x64.zip`,
containing:

```
taskkling.exe          (native CLI, unchanged bytes)
taskkling-ui.jar       (the per-target uberjar, unchanged bytes)
runtime/               (the jlink image, unchanged bytes — extracted, not archived)
```

Install/update lands the whole tree; `taskkling ui` launches
`<own dir>/runtime/bin/java -jar <own dir>/taskkling-ui.jar <ws-root>` and
passes **itself** as `TASKKLING_BINARY` — closing *both* skew directions by
construction. No first-start fetch, no first-start extract (extraction is the
installer's normal job, before first run). All three payload byte-streams are
exactly the assets the pipeline already builds; bundling is an assembly step
at the release fan-in, not a new build.

- **Skew:** structurally impossible within an install. The three pieces
  cannot version-drift because they travel and swap as one unit.
- **"main predates main":** solved for installed products (nothing is
  fetched). A from-source CLI simply *has no sibling UI* — `taskkling ui`
  should then say "run from a bundle install, or use `:ui:run`" (or fall back
  to fetch; open question, §7).
- **Cost:** every install downloads ~63–85 MB (§3) instead of 5–15 MB,
  whether or not the user ever opens the UI. This inverts ADR-010's core
  rationale ("CLI-only users pay nothing for the UI").
- **Update unit becomes a directory tree** — the concern that sank option (a)
  in the t-xtrw research. Mitigations in §5.

### B′. Two flavors: CLI-only asset *and* the full bundle

Keep `taskkling-<target>[.exe]` for CLI-only users (agents, CI, servers —
the headless audience is real: the export/list path is the agent interface)
and add the four bundles for humans who want the UI. Installer picks via a
flag (`install.ps1 -WithUi` / `TASKKLING_FLAVOR=bundle`); `taskkling update`
updates whichever flavor is installed. Skew prevention identical to B within
a bundle install; CLI-only installs have no UI at all, hence no skew. Cost:
two install flavors to document and test, asset count grows, and a CLI-only
user who later runs `taskkling ui` needs a story (fetch fallback, or "reinstall
with -WithUi").

### C. Half bundle: CLI + UI jar bundled, runtime still cached

Bundle the two things that actually skew (CLI + jar, ~38–55 MB) and keep the
runtime (the big, *stable*, jdk-major-keyed piece) on the existing lazy
fetch. CLI↔UI skew becomes impossible; download grows only by the jar.
But the first-start fetch/extract surface **survives** (t-fst5 was the
*runtime* extraction), first launch still needs network, and the design now
has three artifacts across two acquisition mechanisms — more moving parts
than either A or B, for half the benefit. Listed for completeness; weak.

### D. Embed the UI jar inside the CLI binary

A single file again — but +30–40 MB inside a Kotlin/Native binary, the jar
must be written out to disk before `java` can run it (re-introducing an
extract-on-first-run step), and the runtime is still separate. All of C's
weaknesses with extra exotic build machinery. Killed.

### E. jpackage app-image with the CLI inside

Ship the Compose app image (t-xtrw option (a)) and put the CLI binary inside
it. Re-imports everything that made (a) lose — 4-runner release matrix,
`macos-15-intel` sunset (Aug 2027), directory-tree updates, Sequoia
Gatekeeper friction on browser downloads — and *inverts* the product's shape:
taskkling is a CLI with an optional UI, not a desktop app with an embedded
CLI. Killed.

### F. Harden detection: escalate the t-eeze toast to a refusal

Keep the split, but make the UI (or the CLI at spawn time) *refuse* on
version mismatch instead of toasting. Cheap, catches every skew pair —
but it converts skew from "confusing behavior" into "hard outage" without
removing its causes, and the refusal needs an escape hatch for legitimate
dev setups, which re-opens the hole. Worth having only as a *backstop*
inside strategy A, not as a strategy.

---

## 3. Empirical probe: strategy B on this Windows box

Goal (per ticket): prove on a real machine that a single bundled artifact
delivers CLI + UI + runtime with **no first-start fetch and no first-start
extraction** — install/update, launch, window up, logs clean, cache untouched.

Setup: worktree at v0.6.4 + 7d267cd (`t3code/feat/v0-6-5`), Windows 11,
Temurin 21.0.8+9 from `~/.jdks` — **the exact Temurin build release.yml pins**
(`TEMURIN_TAG: jdk-21.0.8+9`), so the local jlink output matches shipped
runtime bytes modulo host-vs-cross linking.

### 3.1 Assembling the bundle (all release-pipeline-equivalent steps)

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21.0.8"
.\gradlew.bat :ui:packageUberJarWindowsX64 :cli:linkReleaseExecutableMingwX64   # 1m24s warm-ish

$jdk = "$env:USERPROFILE\.jdks\temurin-21.0.8"
jlink --module-path "$jdk\jmods" `
  --add-modules "java.base,java.desktop,java.instrument,jdk.unsupported" `   # = curatedUiRuntimeModules
  --strip-debug --no-header-files --no-man-pages --compress zip-6 `          # = release.yml flags
  --output bundle\runtime

# bundle\ = taskkling.exe + taskkling-ui.jar + runtime\
Compress-Archive bundle\* bundle.zip
```

### 3.2 Sizes (measured, windows-x64)

| Piece | Size |
|---|---|
| `taskkling.exe` (linkReleaseExecutableMingwX64) | 6.6 MB |
| `taskkling-ui.jar` (packageUberJarWindowsX64) | 31.1 MB |
| `runtime/` (jlink, 144 files) | 46.1 MB |
| **Bundle on disk** | **83.8 MB** |
| **Bundle as one zip (Optimal)** | **62.9 MB** |

Estimated bundle *downloads* for the other targets, from the published
v0.6.4 asset sizes (CLI + jar + runtime archive, summed — a single archive
compresses slightly better, cf. 62.9 vs the 68.6 windows sum):

| Target | CLI | UI jar | runtime archive | Σ download |
|---|---|---|---|---|
| windows-x64 | 6.6 | 31.1 | 31.0 | ~68.6 → **62.9 measured as one zip** |
| linux-x64 | 13.8 | 32.8 | 34.3 | ~81 MB |
| macos-x64 | 5.6 | 38.5 | 31.6 | ~76 MB |
| macos-arm64 | 5.4 | 38.5 | 30.5 | ~74 MB |

Today's CLI-only install for comparison: 5.4–13.8 MB. On disk, a bundle
(~84 MB) is *at parity* with today's UI user, who stores the exe **plus** a
~77 MB cache (jar + extracted runtime) anyway — bundling moves those bytes
from the cache home into the install dir; it does not add them.

### 3.3 Launch with no fetch and no extract — leg 1 (assembled bundle)

```powershell
bundle\taskkling.exe --version                      # → taskkling 0.6.4
bundle\taskkling.exe init --demo-mode               # temp workspace under $env:TEMP (ADR-017)
# snapshot: 147 files under %LOCALAPPDATA%\taskkling\cache (path|size|mtime each)

powershell -File scripts\qa-capture-header.ps1 -Workspace $ws -Out window.png `
  -Jar bundle\taskkling-ui.jar -Cli bundle\taskkling.exe -Java bundle\runtime\bin\java.exe
# → TITLE=[taskkling · ws]  SIZE=1632x877  SAVED=window.png
```

Observed: the captured window (PrintWindow, background, no input injection)
shows the full demo DAG rendered — 12 seeded tasks, status chips, edges,
detail panel, workspace name in the header. The title growing past the bare
wordmark *is* the export landing, i.e. the UI completed a real
`taskkling export` round-trip through the **bundled** CLI. No version-skew
toast (both halves 0.6.4 from the same build). Cache diff after: **0 entries**.

### 3.4 Install-simulation — leg 2 (extract zip → launch, observed sockets)

```powershell
Expand-Archive bundle.zip installed\                # the installer's job: 2.4 s
installed\taskkling.exe --version                   # → taskkling 0.6.4
$env:TASKKLING_BINARY = "installed\taskkling.exe"   # what a bundle-aware `ui` verb would set
Start-Process installed\runtime\bin\java.exe -Args "-jar installed\taskkling-ui.jar $ws" `
  -RedirectStandardOutput ui2.log -RedirectStandardError ui2.err.log
```

Observed, all measured:

- Window ready (title = `taskkling · ws`) **2.5 s** after spawn.
- `Get-NetTCPConnection -OwningProcess <ui-pid>` at ready time: **0 connections**.
- `ui2.log` / `ui2.err.log`: **0 bytes each** — clean launch.
- `%LOCALAPPDATA%\taskkling\cache` before/after inventory diff
  (147 files, path+size+mtime): **0 changes**. Nothing was fetched, nothing
  was extracted, the existing lazy-fetch cache was never consulted.

**Conclusion: validated.** A single ~63 MB zip delivers CLI + UI + runtime on
this box; extraction happens once at install time (2.4 s, installer-owned);
launch is local-only and cache-independent. Every payload byte is an asset
the release pipeline already produces — the only new artifact is the archive
around them. (Probe scaffolding lived entirely under `$env:TEMP`; nothing
landed in product code.)

---

## 4. Trade-off table

| Axis | A status quo | B full bundle | B′ two flavors | C half bundle | F refuse-on-skew |
|---|---|---|---|---|---|
| CLI↔UI skew (installed) | possible (mixed installs, dev CLIs) | **impossible by construction** | impossible within bundle; n/a for CLI-only | impossible | possible, but fatal-loud |
| First-start fetch/extract (t-fst5 class) | yes (~65 MB + archive extract) | **none** | none (bundle) / n/a (CLI-only) | runtime only | yes |
| "main predates release" fetch trap | yes | gone (nothing fetched); from-source CLI needs a defined answer (§7) | same as B | still there for runtime | yes |
| Install download | 5–14 MB | 63–81 MB | 5–14 or 63–81 | ~38–55 MB | 5–14 MB |
| On-disk per UI user | exe + ~77 MB cache | ~84 MB, one dir | same as B | similar to A | same as A |
| Agent/CI/headless install cost | minimal | **+~57–67 MB dead weight** | minimal (CLI flavor) | +jar dead weight | minimal |
| Update unit | single file (exe) + cache re-fetch | **directory tree** (§5) | file or tree per flavor | file + jar | single file |
| Release pipeline delta | none | +4 assembly-zips at fan-in; asset count 12→16 (or →8 if fetch path retired) | +4 assets, keep 12 | ±0 | none |
| Offline-capable first UI launch | no (needs `--fetch-only` prep) | **yes** | yes (bundle flavor) | no | no |
| New failure surfaces | — | tree-swap on update; locked files while UI runs | flavor drift/confusion | three artifacts, two mechanisms | false-positive refusals in dev |

---

## 5. The update story under bundling

Today `taskkling update` rename-swaps one file (ADR-002) and the next
`taskkling ui` lazily re-fetches the matching jar. Under B, update must
deliver the whole tree. Two workable shapes:

1. **Staged tree swap:** download bundle archive → extract to
   `<install>/.new-<ver>/` → swap. The exe can replace itself with the
   existing rename dance (a running exe can be renamed on Windows); jar and
   runtime are only locked **while the UI is running** — the same situation
   ADR-011 already handles for cache pruning with best-effort +
   retry-next-launch semantics. Same solution applies: swap what's swappable,
   leave a staged tree, complete on next launch.
2. **Version-keyed subtrees:** keep the exe at the top and place UI pieces in
   `ui/<version>/` inside the install dir — literally today's cache layout
   relocated, with the *update* (not the first launch) populating it. Sidesteps
   locked-file swaps entirely (old tree pruned later, ADR-011-style), at the
   cost of transiently holding two UI versions.

Either way `install.ps1`/`install.sh` change from "download one file, verify,
place" to "download one archive, verify, extract, place" — the installer
scripts already run `tar`/`Expand-Archive`-class operations in their
regression harnesses, and the extraction moves from the user's first launch
(unattended, self-heal-once) to the installer (attended, loud on failure) —
a strictly better place for the t-fst5 class of failure to surface.

macOS note: the bundle must be installed via the `install.sh` curl path (no
`com.apple.quarantine` xattr → no Gatekeeper assessment), which is already
the only supported install path — the t-xtrw research's provisos (CLI/script
does the extraction; tar.gz not dmg) carry over unchanged.

## 6. Recommendation on t-eeze (the `--version` skew probe)

**Keep it.** It shipped, it works, and under every strategy it stays earning:

- Under A/C/F it is the primary (or only) guard.
- Under B/B′ bundling makes skew impossible *within a bundle install* — but
  not across the world bundling doesn't govern: from-source CLIs, pinned
  `.taskkling/bin` local installs overriding the UI's discovery chain
  (t-eeze's original repro!), `TASKKLING_BINARY` overrides, and any
  hand-assembled layout. The probe is one subprocess `--version` call at UI
  launch and a toast — its cost is indistinguishable from zero, and in a
  bundled world a firing toast means "your environment overrode the bundle
  pairing", which is precisely the surprising situation worth a visible hint.

Retire it only if a future decision removes every non-bundled way to pair a
UI with a CLI (unlikely — `:ui:run` and local-bin pinning are permanent dev
fixtures). One follow-up worth considering with B: since intra-bundle skew
becomes impossible, an *intra-bundle* mismatch (should the toast ever fire on
a plain bundle install) indicates a corrupted/mixed install and could
justifiably escalate from toast to a louder warning — that is a refinement,
not a retirement.

## 7. Open questions for the human

1. **B or B′?** Does the CLI-only 5–14 MB artifact remain a first-class
   install flavor (B′ — protects the agent/CI audience from ~60 MB of dead
   weight), or does everyone get the bundle (B — one flavor, simplest story)?
   The headless-agent use case suggests B′.
2. **Fate of the lazy-fetch path (ADR-010).** With bundles: retire it
   entirely (bundle-or-`:ui:run`, `taskkling ui` errors helpfully without a
   sibling jar), or keep it as fallback for CLI-only installs that invoke
   `taskkling ui`? Keeping it preserves today's UX for B′'s CLI flavor but
   keeps the fetch/extract surface and the "main predates release" trap alive
   in that path.
3. **Update mechanics:** staged tree swap vs. version-keyed subtrees (§5).
4. **Asset set arithmetic:** 12→16 assets (bundles added) or →8 (jar+runtime
   assets retired once nothing fetches them); the release fan-in count
   assertion and `SHA256SUMS` coverage move in lockstep (ADR-016 decision 6).
5. **From-source `taskkling ui`:** refuse-with-hint (pointing at `:ui:run`)
   or fetch-fallback? Refusing is the only answer that actually kills the
   predates-main trap.

**Recommended strategy: B′** — bundle as the default human install, CLI-only
asset retained for agents/CI, lazy fetch retired, `taskkling ui` in a bundle
launching sibling jar + runtime and passing itself as `TASKKLING_BINARY`,
t-eeze toast kept as the cross-world backstop. Empirically validated on this
box (§3); expected ADR: supersedes ADR-010's acquisition decisions (1, 3, 6),
amends ADR-009 (distribution shape), ADR-011 (asset list), ADR-016 (fan-in
assembly + count) — to be written only after human agreement.
