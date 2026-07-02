# ADR-006: Update-Check On by Default, TTY-Gated Notifier

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-01
**Follow-up ADRs:** —

---

## Context

ADR-002 introduced the opt-in `update_check` notifier and ADR-005 settled its surface (the
`--version` line and the explicit `update --check`), its transport (an in-process ktor client with a
per-OS engine), and its configuration home (a user-level config so the global binary honours the flag
anywhere). ADR-005 also fixed the flag's **default to off**, and explicitly rejected "check by default
on `--version`" for one concrete reason: `--version` is scraped by scripts and CI, so a default
network call there would leak usage from automation, add latency to a command meant to be instant, and
break the "no network calls by default" posture — with ephemeral CI the worst case, since a fresh home
each run means no cache, so *every* invocation would make a live call.

This ADR revises that single default. It flips `update_check` **on** by default while **gating the
passive `--version` check to an interactive terminal**, which removes exactly the automation-leak that
justified default-off. It revises the notifier default of ADR-002 and ADR-005; both of those bodies
are left untouched and both Follow-up fields point here.

A coherence note this ADR must own plainly: it reverses text written **on this same branch** — ADR-005's
rejected alternative "check by default on `--version`" and the first PRD §15 amendment ("no network
calls by default; `--version` stays offline unless the user opts in"). That is legitimate because the
branch is still a draft: no release ever shipped the default-off behaviour, so no user experiences a
default flipping under them. But the record owns the reversal explicitly rather than quietly editing
the earlier reasoning.

---

## Decision

Make the opt-in `update_check` notifier **on by default**, and gate its passive surface to an
interactive terminal:

- **Default flips off → on.** `resolveUpdateCheckEnabled(...)` falls back to `true` when neither the
  workspace nor the user config sets the key. Every binary — installer-placed or manually downloaded —
  treats the check as on unless a config turns it off.
- **The passive `--version` notifier fires only on an interactive TTY.** It runs only when stdout is a
  terminal; CI, pipes, `| grep`, docker-build, and every other non-interactive context stay fully
  offline and silent — **no network call and no cache write** — decided before any IO happens.
- **`update --check` stays always-on, regardless of TTY.** Invoking it *is* the consent, so it ignores
  both the flag and the interactivity gate. The actual `update` action is unaffected by any of this.
- **The off switch is discoverable.** Installers materialize a user-level `config.toml` (write-if-absent)
  carrying `update_check = true  # set false to disable`, so opting out is a visible one-line edit
  rather than arcane per-OS path knowledge. The materialized file is a discoverability aid only; the
  effective default lives in compiled code (the mechanism is its own task).

---

## Rationale

The lead insight is that ADR-005's reason for default-off was **narrow and specific**: the `--version`
scrape leak in automation, sharpest under ephemeral CI. TTY-gating dissolves precisely that concern —
automation is non-interactive by construction, so a gated check never fires there; it makes no call and
writes no cache in CI, pipes, or scripts. What remains after the gate is a human typing `--version` in
a real terminal, which is exactly where a "you're a version behind" courtesy belongs. This is the
established pattern for `npm`, `gh`, and `rustup`, which all check-on-interactive and stay quiet when
piped.

On-by-default matches the plain user expectation that a tool tells you when it is out of date, without
first discovering and setting a flag. The opt-out is preserved and made *more* discoverable than before
(a materialized, commented file) rather than less — so the small minority who want zero background
checks have a one-line, obvious switch. Keeping the effective default in compiled code (not in the
materialized file's contents) means a binary downloaded by hand, with no config file at all, still
defaults on and behaves identically to an installer-placed one; the file exists only so the toggle is
easy to find.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Keep ADR-005's default off; only materialize the config for discoverability | Discoverability alone doesn't change that most users never flip an off-by-default flag on; the human wants updates surfaced by default, not merely surface-able. |
| Have the installer write `update_check = true` into the file, so the value lives in the file rather than in compiled code | A manually downloaded binary has no such file and would then default off — a split-brain default. A compiled default is uniform across install paths; the file is a toggle aid, written write-if-absent. |
| Fire the passive `--version` check regardless of TTY (treat any `--version` as consent) | That is exactly the universal phone-home — including CI and scripts — that ADR-005 rejected. TTY-gating is the specific mechanism that makes default-on safe; without it, default-on is not defensible. |

---

## Consequences

**Positive:**

- A human running `taskkling --version` in a terminal is told when a newer release exists, with zero
  setup; the opt-out is a single discoverable line in a materialized config.
- CI, pipes, and scripts stay byte-for-byte offline and silent on `--version` — the scrape-leak concern
  is structurally eliminated (the check cannot fire), not merely mitigated by a cache.
- `update --check` and the `update` action are unchanged; the reversal is confined to the passive
  notifier's default and its gate.

**Negative / open:**

- Adds a third per-OS primitive — stdout interactivity — to the `expect`/`actual` matrix alongside the
  executable-path and user-paths splits.
- A human's first interactive `--version` after install can incur one network round-trip before the
  ~24 h cache is warm; silent-fail keeps any failure invisible.
- This reverses ADR-005's default and the first PRD §15 amendment, so the PRD must be re-amended (a
  follow-up task) and the record now carries an explicit flip: a reader must follow the chain forward to
  ADR-006 to know the live default.
- "Interactive" is detected via `isatty`, a heuristic; an unusual setup (a PTY allocated in CI, or an
  interactive session with stdout redirected) could misclassify — at worst one harmless cached call, or
  a single missed notification.
