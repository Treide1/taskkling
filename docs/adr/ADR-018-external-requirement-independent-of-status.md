# ADR-018: External requirement is independent of status

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-13
**Follow-up ADRs:** —

---

## Context

Historically a task's external requirement (the stored `waiting_on` field, surfaced
in the UI as "external requirement") was defined *as* the reason a task was in the
`waiting` status. Three couplings enforced that meaning:

1. A write-path invariant — `waiting_on may be set only when status=waiting` —
   rejected the field on any non-waiting task.
2. The lifecycle verbs `done`/`drop`/`reopen` force-cleared `waiting_on` on every
   transition, and `restore` cleared it when reopening a closed task.
3. `wait --on "<text>"` was the *only* way to set it, so setting a requirement
   always dragged the task into `waiting`.

This made it impossible to express two ordinary situations: an *open* task that is
nonetheless gated on an outside party ("open, but I'm waiting on legal to sign
off"), and a *waiting* task parked for a reason that isn't a discrete external
requirement. It also blocked a UI create-a-card form from offering status and
requirement as the independent fields users expect. Notably, `waiting_on` already
had **zero** effect on the computed graph (readiness keys off `status==open`,
`resurfaced` off `status==waiting`); the couplings were structural, not semantic.

---

## Decision

External requirement and status become independent, orthogonal attributes. The
`waiting_on ⇒ waiting` invariant is removed; the requirement persists across every
status transition and is removed only by an explicit clear. Status becomes a
first-class editable field on both `add` (`--status`, default `open`) and `set`
(`--status`), and the requirement becomes a first-class field on both `add`
(`--req`) and `set` (`--req` / `--clear req`). The `wait --on` flag is renamed
`--req` (with `--on` kept as a deprecated alias). The lifecycle verbs
`done`/`drop`/`reopen`/`wait` are retained as sugar over the shared status path.

The **stored** key stays `waiting_on` and the DTO field stays `waitingOn`; `req` is
exposed only at the boundaries (CLI flags, a `get -f req` alias, the UI label). The
physical storage rename is deferred to the v0.7.0 attribute overhaul, which owns
schema migration.

---

## Rationale

The core insight is that `waiting_on` was never a computed input — it was a note
whose only constraint was an invariant we chose to impose. Lifting the invariant
therefore changes no graph semantics; it only removes an artificial barrier. Making
status a plain field everywhere (rather than reachable solely through lifecycle
verbs) is the symmetric move that lets a create form, the panel, and scripts all
treat the two attributes the same way. Keeping the lifecycle verbs as sugar
preserves every existing script, agent habit, and the panel's status editor.

Persist-on-transition (rather than clear-on-close) is what "independent" means: a
field the user owns, changed only when the user changes it. The `closed` timestamp
keeps its own rule (restamped on every entry into a closed state) because it *is*
status-derived.

Renaming only the boundary keeps a data migration — which must touch every stored
`.md` file and the dogfood store — out of what is otherwise a behavioural change,
and hands that migration to the milestone (v0.7.0) already scoped to own it.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Keep the `waiting_on ⇒ waiting` invariant | The entire motivating requirement is to set the two independently; the invariant is exactly what blocks it. |
| Clear the requirement on close (keep one coupling) | A hidden coupling that contradicts the stated independence and surprises anyone who set a requirement on a task later closed and reopened. |
| Rename the stored key `waiting_on → req` now | Forces a frontmatter migration of every task file plus parser back-compat, dragging a data migration into a behavioural change and colliding with v0.7.0's scope. |
| Add `--status` to `add` only, keep status out of `set` | Asymmetric: existing tasks could only change status via lifecycle verbs, so the field wouldn't be "first-class" and the model stays lopsided. |

---

## Consequences

**Positive:** Status and external requirement can be set independently on both
create and edit. A task may carry a requirement in any status; the UI create-a-card
popup and the detail panel edit both as plain fields (the panel's requirement
editor is now `set --req`, no longer flipping to waiting). No graph/computed
semantics change. Existing scripts keep working (`wait --on` aliased; lifecycle
verbs retained).

**Negative / open:** The stored key (`waiting_on`) and its user-facing name (`req`)
diverge until the v0.7.0 storage rename — a documented naming smell. A closed task
can now retain a requirement note, which may read as stale; that is the accepted
cost of independence. Tests that asserted `waiting_on` auto-clears on close were
updated to assert persistence.
