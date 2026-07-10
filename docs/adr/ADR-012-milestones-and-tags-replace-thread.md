# ADR-012: Milestones and tags replace the single `thread` label

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-10
**Follow-up ADRs:** —

---

## Context

A task carries one optional grouping label, `thread` — a bare slug with no
semantics: threads have no order, no status, and no relationship to each other.
Real usage (the taskkling dogfood store, ~40 active tasks) shows the field serving
two orthogonal concepts at once: chronological release milestones
(`v0.5.2-tests`, `wf-v0.6.0-ui`, `v0.6.0`) and timeless topics (`dx`, `ui`,
`proposals`, `friction`). One field cannot express both: a task can belong to a
milestone AND be a dx concern; milestones have an inherent order ("v0.2 comes
after v0.1") that a flat label namespace cannot state, so any "what's next /
what was then" view is impossible to derive. Slug typos also fragment the
namespace silently (`v0.6.0` vs `wf-v0.6.0-ui`), because thread values are
free-form and never validated.

The question: what replaces `thread` so that chronology and topical grouping are
both expressible, orderable where order exists, and cheap to filter — without
inventing a task taxonomy (one task type is a settled product principle)?

---

## Decision

Replace `thread` with two stored fields plus one registry file:

1. `milestone` — optional, single slug naming the chronological bucket the task
   belongs to. Values must exist in the milestone registry (validated on write,
   lenient on read).
2. `tags` — optional list of free-form topic slugs. No registry; a folksonomy.
3. `tasks/_milestones.md` — an ordered, human-editable registry of milestone
   slugs; document order IS the chronology. Filenames beginning with `_` inside
   the tasks directory are reserved for such non-task store files and excluded
   from the active task set.

---

## Rationale

The core insight is that the two uses of `thread` differ in exactly one
property: **order**. Milestones are a sequence (a task belongs to at most one
point on the project's timeline); topics are an unordered set (a task can have
many). Encoding that difference in the data model — one scalar with a registry
that owns the ordering, one list without — gives each concept its natural shape,
and the whole "overhaul threads into chronological milestones and inner topics"
requirement falls out of it.

Ordering lives in a registry file rather than in the milestone names because
names cannot be trusted to sort (`v0.10` < `v0.2` lexicographically), and rather
than in per-milestone metadata files because a single ordered list is the
smallest thing that can state a chronology and is trivially human-editable. The
registry doubles as the validation set: `add`/`set` reject unregistered
milestone slugs, which kills the typo-fragmentation failure mode at the write
boundary. Tags deliberately get no registry — topics emerge; pre-registering
them adds friction with no integrity payoff, since a mistyped tag breaks
nothing.

The registry lives in the tasks directory (not `.taskkling/`) because milestone
order is authored user data that must travel with the tasks through git; the
`_` prefix carves a reserved namespace out of the existing "every `.md` in
`tasks/` is a task" rule at zero directory-layout cost, and leaves room for
future non-task store files.

Milestones stay labels, not tasks: a "milestone gate" task with `depends` on the
milestone's work remains the way to make a milestone *block* things (settled
pattern, used by every dogfood release so far). The registry adds the ordering
that gates cannot express; it does not touch the graph.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Keep single `thread`, add a naming convention for milestone-threads | Convention cannot express order, cannot be validated, and still forbids "milestone + topic" on one task — the observed failure stays. |
| Milestone-as-task (promote gates; tasks reference the gate id) | Conflates grouping with the dependency graph: filtering by milestone becomes a graph query, archiving a done gate would orphan the grouping, and it invents a de-facto second task type. |
| Order milestones by name (semver/lexicographic) | `v0.10` sorts before `v0.2`; forces a naming scheme on users; order still unstatable for non-version milestones ("launch", "cleanup"). |
| Per-milestone metadata files (`tasks/_milestones/<slug>.md`) | Heavier than needed: chronology needs one ordered list, not N files whose order must then be stated elsewhere anyway. |
| `tags` only (milestone = a special tag) | Loses single-valuedness (a task in two milestones is a modeling error the schema should reject) and loses ordering. |

---

## Consequences

**Positive:** chronology becomes first-class (a "current / past / future
milestone" axis the CLI and UI can sort and lane by); topics become multi-valued
and orthogonal; milestone typos die at the write boundary; `_` namespace opens
extensibility room in the store; the migration path from `thread` is mechanical
(ADR-015).

**Negative / open:** one more file to know about (`tasks/_milestones.md`); write
validation needs the registry read on every milestone-touching mutation (one
small file — negligible); `default_thread` config key is superseded (migration
maps it); the contract field rename `thread` → `milestone` + new `tags` is a
wire change and follows ADR-008 (one ADR-gated rename, golden tests + UI in the
same commit — part of the implementation milestone). Milestone *status*
(active/released) is deliberately not modeled yet; if needed later, a follow-up
ADR can extend the registry line format.
