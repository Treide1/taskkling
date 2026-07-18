# Build variants: making a dev build impossible to mistake for a release

Research ticket: t-8pvm (v0.6.5 packaging-strategy milestone). Date: 2026-07-18.
Status: research + approach presentation only — the recommendation below is
**pending human agreement**; nothing here is implemented, and no ADR exists yet.

Depends on the packaging research (`docs/research/ui-cli-bundling.md`, t-op1m),
whose recommendation — also pending agreement — is **B′**: a full bundle
(CLI exe + UI uberjar + jlink runtime) as the default human install, plus the
existing CLI-only asset for agents/CI. This document's recommendation is framed
against B′ but is deliberately **strategy-independent** (§7 spells out what
changes under plain B or the status quo — answer: almost nothing, and under the
status quo it matters *more*).

---

## 1. The problem, precisely

A dev-built `taskkling.exe` prints `taskkling 0.6.4` — byte-identical to what
the released 0.6.4 binary prints. The version constant is generated from the
`version` property in `gradle.properties` (line 9, currently `0.6.4`) by the
`generateVersionFile` task in `core/build.gradle.kts:12-30`, becomes
`Taskkling.VERSION` (`core/src/commonMain/kotlin/io/taskkling/core/Core.kt:16`),
and from there flows to every identity surface the tool has:

| Surface | Where |
|---|---|
| `--version` payload line | `cli/src/commonMain/kotlin/io/taskkling/cli/Main.kt:827-834` |
| `doctor`'s `version` row | `core/.../DoctorRun.kt:154` (via `DoctorEffects.version`, :62-70) |
| local-bin `.version` stamp | `core/.../LocalBin.kt:160` |
| UI skew toast (t-eeze) | `ui/.../AppStore.kt:156-167` compares the probed token against the UI's own generated `BUILD_VERSION` (`ui/build.gradle.kts:22-40`) |
| `update` / notifier comparisons | `core/.../Update.kt:172-176`, `UpdateNotifier.kt:99`, `UpdateRun.kt:126` |
| version-keyed UI fetch (ADR-010) | `taskkling ui` fetches assets from the release tagged `v<VERSION>` |

Because `main` sits at an already-released version between bumps, **every one
of these surfaces lies for every from-source build**: `installLocalBinDev`
(which installs the *debug* mingw binary, `cli/build.gradle.kts:96-104`) stamps
`.version` with a release number; `doctor` — the verb that exists so agents can
orient (t-8der) — asserts a release identity for a binary that predates or
postdates it; and the t-eeze skew probe, being a string-equality check,
**stays silent in exactly the incident case** (dev 0.6.4 UI vs released 0.6.4
binary, or vice versa: both say `0.6.4`, no toast). The `:ui:run` incident of
2026-07-15 — fresh UI silently rendering a v0.6.2 binary's export — is this
class of failure; the dev-loop instance is fixed (`ui/build.gradle.kts:126-179`
now builds and pins the host CLI), but the identity lie that made it invisible
is still in every from-source binary.

So "debug vs release" here is **not** about optimizer flags. What needs
surfacing is *provenance*: "this binary came out of the release pipeline at a
tag" vs "somebody built this from a working tree".

---

## 2. Build side: what the variant is and how it gets into the binary

### 2.1 A constant cannot vary per Kotlin/Native build type — and shouldn't

`linkDebugExecutableMingwX64` and `linkReleaseExecutableMingwX64` are two
*links* of the **same compilation** — one shared klib per target, differing
only in linker/optimizer flags. There is no per-`NativeBuildType` source set,
so a generated constant physically cannot say "I was linkDebug'd" without
doubling every compilation. This kills the superficially obvious design
("derive the marker from debug/release binaries, which already exist") — and
that's fine, because it was the wrong axis anyway: a local
`linkReleaseExecutableMingwX64` (which anyone can run; release.yml runs exactly
that at `.github/workflows/release.yml:74`) is still not *the release*. The
distinction that stops the lie is **who built it**, not how hard the optimizer
tried.

### 2.2 Recommended mechanism: provenance flag at the Gradle boundary

Definition: a build is **release** iff the invocation says so; everything else
is **dev**. Concretely:

- Default: dev. Every `gradlew` invocation on any machine, any task
  (`installLocalBinDev`, `linkRelease*`, `:ui:run`, uberjars) produces
  dev-marked artifacts.
- release.yml passes an explicit property (e.g. `-Ptaskkling.release=true`) on
  its build steps. The workflow already guards that the pushed `vX.Y.Z` tag
  matches `gradle.properties` `version=` (per the comment at
  gradle.properties:4-8), so "release" and "correct version number" are
  asserted by the same pipeline — the flag cannot mark a wrong version as
  released without the existing guard failing first.
- Both `generateVersionFile` tasks (`core/build.gradle.kts:12`,
  `ui/build.gradle.kts:22`) consume the same derived value — computed **once,
  in the root build** so :core and :ui can never disagree within one
  invocation (they already register the value as `inputs.property`, so
  toggling the flag correctly invalidates the generated file; same pattern,
  new input).

No BuildConfig plugin needed: the repo already has exactly this generator,
twice, single-sourced. This is one new input to it, not new machinery.

### 2.3 Where the marker lives: inside the version token itself

Two candidate encodings:

**(a) Separate constant** — `BUILD_VARIANT = "dev" | "release"` next to
`BUILD_VERSION`, version string unchanged. Safe for `SemVer.parse`, but every
surface that shows *only the version* keeps lying (`--version` scraped by
scripts, the `.version` stamp, and critically the t-eeze equality probe, which
compares version strings and would still call dev-0.6.4 == release-0.6.4).
Each surface then needs individual plumbing to also fetch and show the
variant, and any surface anyone forgets keeps the lie. Rejected as the
primary encoding (fine as an *additional* constant for structured use).

**(b) Suffix in the version token** — dev builds report
`0.6.5-dev.<short-sha>` (release builds report plain `0.6.5`, byte-identical
to today). **Recommended.** The version token is already threaded through
every identity surface in §1's table, so one change at the generator makes it
impossible for *any* of them to lie, including ones added later:

- `--version` → `taskkling 0.6.5-dev.7d267cd`. The UI's parse regex
  `^taskkling\s+(\S+)$` (`ui/.../CliClient.kt:177`) accepts any single token —
  the suffix rides through unchanged.
- t-eeze toast: dev-vs-release now *differs as a string* and fires — the probe
  gains exactly the detection it was blind to, with zero code change.
  (Same-invocation dev UI + dev CLI carry the same suffix and stay silent,
  because §2.2 computes the value once for both generators. A dev UI built in
  a *different* invocation/commit than the dev CLI it talks to will toast —
  that is a true statement, not a false positive.)
- `doctor`'s version row, the `.version` local-bin stamp, the update-refusal
  message (§2.4): all inherit the truth for free.
- **The predates-main fetch trap dies as a side effect** (status quo / any
  configuration where the lazy fetch survives): `taskkling ui` fetches assets
  keyed to `v<VERSION>` — a dev binary would look for tag `v0.6.5-dev.7d267cd`,
  which does not exist, and fail *loudly* instead of silently fetching the
  released UI that predates its own code. The failure message should name the
  cause ("dev build has no published UI — use `:ui:run`"), but even unmodified
  it converts a silent lie into a visible error. This is the same lie the
  t-op1m doc's §1 documents; the suffix kills it at the source.

Suffix content: `-dev.<short-sha>` with an optional `.dirty` marker for an
unclean tree. The sha answers the question that always follows "it's a dev
build" — *which* dev build — and is what the 2026-07-15 debugging session
actually needed. Cost: the generator shells out to `git rev-parse --short HEAD`
at configuration/execution time; worktrees are fine (`.git` file
indirection is git's own job); a non-git source tree (tarball) falls back to
`-dev` alone. `inputs.property` on the sha means a new commit regenerates the
file — that is *correct* (the constant should track HEAD), and the cost is one
tiny recompile per commit, which the dev loop already pays for any :core edit.

### 2.4 Interactions the suffix forces us to handle (all small, all listed)

1. **`SemVer.parse` is strict** (`core/.../Update.kt:163-168`): `"5-dev"`
   fails `toIntOrNull` → `IllegalArgumentException`. Callers today:
   - `--version` notifier path: wrapped in a swallow-everything catch
     (`cli/.../Main.kt:530-545`) — degrades silently, no crash, but silently
     is not a fix.
   - `update` / `update --check` (`UpdateRun.kt:126`): would surface as
     `unexpected error … exit 1`.
   Resolution options: (i) teach `SemVer.parse` to strip a `-…` suffix
   (ordering by base version); or (ii) make `update` on a dev build **refuse
   by design**: "this is a dev build (`0.6.5-dev.7d267cd`); `update` would
   replace your own build with a release — rebuild instead, or reinstall from
   a release". (ii) is recommended: a dev binary self-replacing with an older
   release tarball is itself a variant-confusion hazard, and refusal is the
   only answer consistent with this ticket's premise. (i) is still needed for
   the *notifier* comparison so it degrades deliberately rather than by
   swallowed exception. Human call in §8.
2. **Subprocess golden tests** (`cli/src/commonTest`, CliSubprocessTest et al.)
   run against the *debug* binary (`cli/build.gradle.kts:77-90`) and would see
   suffixed output — any assertion pinning `taskkling X.Y.Z` verbatim updates
   to the dev shape. That is a feature: the tests then pin the dev format.
3. **`qa-capture-header.ps1` / QA scripts** that read the window title get a
   ` · dev` suffix in dev runs (§4). Grep-style checks (`TITLE=[taskkling · ws]`)
   need the suffix-tolerant form.
4. **Release artifacts are byte-identical to today** — the flag only changes
   non-release builds. No installer, SHA256SUMS, tag-guard, or fetch-path
   changes for released bytes.

---

## 3. The agent surface (CLI)

### 3.1 The shape that is wrong: a banner

A per-invocation stderr banner is unmissable for one day. Agents make dozens
of `taskkling` calls per session (`list`, `get`, mutation verbs whose entire
output is a task id — `cli/.../Main.kt:151-177`); a constant line becomes
part of the expected noise floor, gets pattern-filtered, and *trains* the
agent to skip exactly the class of line that matters. It also pollutes stdout
discipline: the CLI's contract keeps stdout scrapeable and pushes diagnostics
to stderr (`eprintln`, Main.kt:88-91) — a banner on stderr survives
mechanically but dies attentionally. Rejected.

### 3.2 The shape that is stateful: "once per session/workspace"

"Print it on the first invocation, then be quiet" needs a definition of
*first*. The CLI has no session concept; per-workspace would mean a marker
file write on otherwise read-only verbs (`list`, `export`), a lock
acquisition, and a race between concurrent agents — and the one agent that
didn't make the first call never sees the line at all. It is *missable
exactly once* rather than unmissable. Rejected.

### 3.3 Recommended shape: the identity surfaces cannot lie, and failures name themselves

Three pieces, none of which add a recurring line:

1. **The version token carries the variant** (§2.3). Every surface where the
   agent *asks who the tool is* — `--version`, `doctor` — answers truthfully
   with no new output line. This satisfies "unmissable when it matters"
   by making the *asking* moments truthful rather than adding new telling
   moments: an agent that suspects skew runs `doctor`, and `doctor` is the
   documented orientation verb (its whole rendering is asserted wording,
   `DoctorRun.kt:33-36`).

2. **`doctor` gains one `variant` row** stating provenance in words, next to
   the version row it already prints (`doctorResolutionLines`,
   `DoctorRun.kt:145-158`):

   ```
     version       0.6.5-dev.7d267cd
     variant       dev build (not a published release; built from 7d267cd)
   ```

   Release builds print nothing extra — per the ticket's own principle, the
   marker earns its place only when it tells you something you'd otherwise
   get wrong, and `doctor` saying "release (the normal case)" on every
   healthy install is exactly the noise-training §3.1 rejects. (Consistent
   with doctor's existing style: the absent `chain picks` row,
   `DoctorRun.kt:150-153`.)

3. **Failure paths append the full identity.** The moment variant confusion
   *matters* to an agent is when something behaves wrongly — and the
   agent-visible symptom is an error. The `TkCommand` catch-all
   (`cli/.../Main.kt:132-142`) renders `TkError`s and unexpected exceptions;
   appending the suffixed version to the **unexpected-error** line only
   (`taskkling: unexpected error: … [taskkling 0.6.5-dev.7d267cd]`) puts the
   identity in front of the agent precisely when it is debugging, at zero
   cost the rest of the time. Expected errors (usage, unknown id) stay terse —
   they are the agent's normal working vocabulary, not anomalies.

Explicitly considered and not recommended:

- **Exit codes**: overloading the exit-code contract (`ExitCode`, PRD §10.1)
  to signal variant punishes scripts for using a dev build and conveys one bit
  at the price of breaking automation. Rejected.
- **Env marker** (`TASKKLING_VARIANT` set by wrappers): environment does not
  travel with the binary; the marker must be *in* the artifact. Rejected.
- **Structured carrier in `export`** (a `cliVersion`/`cliVariant` field on
  `ExportDto`, `contract/.../Dto.kt`): genuinely attractive — export is the
  one payload both the UI and JSON-consuming agents parse, and a structured
  field is present-but-not-noisy. Not part of the minimal recommendation
  because the UI already probes `--version` (t-eeze) and agents have `doctor`;
  but it is the natural *second step* if the human wants the UI to stop
  spending a subprocess call on the probe, and it is a contract change, so it
  is listed as an open question (§8.5) rather than smuggled in.

### 3.4 How agents actually meet the binary today (grounding)

Agents run the repo-root `./taskkling` wrapper or the local-bin pin
(`init --local-bin`, `LocalBin.kt`) — i.e. **the dev binary is the normal
agent case in this repo**, installed by `installLocalBinDev` from
`linkDebugExecutableMingwX64`. Under B′ the agent/CI audience installs the
CLI-only *release* asset. The suffix makes these two worlds distinguishable
by any `--version` call, which is precisely what today's plain `0.6.4` denies.

---

## 4. The human surface (UI header)

### 4.1 Where it goes

The header title block (`ui/.../Main.kt:480-503`) renders
`taskkling · <workspace-name>`: the wordmark (`WORDMARK`, Main.kt:80), a faint
`·` separator, and the name bounded by `WORKSPACE_NAME_MAX_WIDTH` (160.dp,
Main.kt:91) with its own ellipsis budget. The block sits deliberately OUTSIDE
`HeaderLadder` (t-2on2) — it never shrinks — so every dp added here is a fixed
tax on the ladder (Main.kt:83-90).

Recommended shape: **a separate trailing entry, dev builds only**:

```
taskkling · my-repo · dev
```

- A third `Text("dev")` after the existing bounded name `Text`, preceded by
  the same faint `·` separator — *outside* the name's `widthIn(max=…)` so
  truncation can never eat the marker (the name ellipsizes, `dev` does not).
  Fixed cost: ~30dp, only in dev builds; release builds render exactly today's
  header, zero tax — meeting the ticket's "a release build should say nothing
  at all".
- Styling: `Tk.faint`, same 14.sp as the name — muted and reserved, per the
  header's existing look and the standing no-accent rule. It is ambient
  status, not an alert; the *alerting* channel for actual mismatch is the
  t-eeze toast, which the suffix upgrade makes strictly better (§2.3).
- Not a suffix glued onto the name (`my-repo-dev`): that reads as part of the
  directory name — the exact confusion this exists to prevent — and would
  spend the name's ellipsis budget.

### 4.2 The window title too

`windowTitle()` (Main.kt:176-177) should carry the same marker
(`taskkling · my-repo · dev`): the title bar and taskbar are what a human
alt-tabs by — the place a stray dev window gets mistaken for the real one —
and the existing `WindowTitleTest` (`ui/src/test/.../WindowTitleTest.kt`)
pins the format cheaply. The title is unbounded by design (the WM truncates),
so no width concern.

### 4.3 Whose variant does the header show?

Two variants exist per window: the UI's own (`BUILD_VERSION`/variant in :ui's
generated file) and the resolved binary's (probed via `--version`,
`AppStore.kt:156-167`). Recommended: **the UI renders its own variant**
(compile-time constant — available at first frame, no probe, no race,
testable without a client), and the *cross*-question ("is my binary the same
flavor/commit?") stays the toast's job, which the suffixed token now answers
in full. Deriving the header marker from the binary's probe would make the
header ambiguous during the probe window and duplicate the toast's role.
Under §2.2's single-invocation generation, "dev UI" ⇒ built from source ⇒ the
paired CLI in every sanctioned dev path (`:ui:run` pinning,
ui/build.gradle.kts:126-179) is dev too; unsanctioned pairings are exactly
what the toast exists for.

---

## 5. Build-side summary (the recommendation in one table)

| Piece | Recommendation |
|---|---|
| Variant meaning | Provenance: release = built by release.yml with the explicit flag at a tag-guarded version; everything else = dev |
| Mechanism | One new input (`-Ptaskkling.release`) + git short-sha, derived once in the root build, consumed by both existing `generateVersionFile` tasks |
| Encoding | In the version token: dev = `X.Y.Z-dev.<sha>[.dirty]`, release = `X.Y.Z` (unchanged bytes) |
| `gradle.properties` | Unchanged — `version=` stays the plain base version |
| K/N debug vs release link | Orthogonal; NOT the variant axis (single shared compilation makes it impossible anyway, §2.1) |
| `SemVer`/update | `update` refuses on dev builds (preferred) + suffix-tolerant parse for the notifier (§2.4.1) |

## 6. Cross-checks against the ticket's failure story

- *"A dev build masquerading as a release version number"*: structurally
  impossible — the release token can only come out of the tag-guarded
  pipeline invocation; every other build self-identifies in the token that
  all surfaces already display.
- *The `:ui:run` incident class*: the dev-loop pairing fix (t-hn1g) prevents
  it; the suffix makes any residual instance *visible three ways* — toast
  (now fires on dev-vs-release), header marker, and `doctor`.
- *Version-keyed-fetch / predates-main trap*: a suffixed dev version cannot
  resolve a release tag, so the silent wrong-UI fetch becomes a loud error
  (§2.3, last bullet) — or is moot under B′ with fetch retired.

## 7. If the human picks something other than B′

- **Plain B** (bundle only): identical recommendation. Bundles are assembled
  from release-pipeline artifacts and are release-marked; the dev world is
  unchanged.
- **Status quo A** (independent halves + lazy fetch): the recommendation
  matters *more* — detection is the only defense, and the suffix upgrades
  both detectors (toast + fetch failure) for free. The `taskkling ui`
  dev-build error message (§2.3) becomes required work rather than nice-to-have,
  since dev CLIs can no longer fetch any UI.
- The only genuinely strategy-*dependent* piece is that error message's
  wording ("use `:ui:run`" vs "install the bundle").

## 8. Open questions for the human

1. **Suffix shape**: `-dev.<short-sha>`? Include a `.dirty` marker for
   unclean trees? Or plain `-dev` (no sha — cheaper, but loses the "which dev
   build" answer and stops the per-commit regeneration)?
2. **`update` on a dev build**: refuse (recommended, §2.4.1), warn-and-proceed
   comparing base versions, or proceed silently?
3. **Header marker source**: the UI's own compile-time variant (recommended,
   §4.3), or derived from the binary's probed token?
4. **Window title**: carry ` · dev` too (recommended, §4.2), or header only?
5. **Contract**: add `cliVersion`/`cliVariant` to `ExportDto` as a structured
   carrier (and let the UI drop the `--version` subprocess probe), or keep the
   contract unchanged and rely on probe + doctor? (Contract changes ripple to
   every JSON consumer; ADR-territory either way.)
6. **Strictness**: is `-Ptaskkling.release` usable by a human building a
   release locally (escape hatch), or pipeline-only by convention? (No
   technical enforcement is possible either way — the flag is honesty
   machinery, not security.)
7. **`doctor` on release builds**: stay silent about variant (recommended,
   §3.3.2), or always print `variant release` for symmetry?
