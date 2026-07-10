# ADR-013: Dependency storage stays single-entry inline `depends`, block-list encoded

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-10
**Follow-up ADRs:** —

---

## Context

Dependencies are stored as a single-line YAML flow list (`depends: [t-a, t-b]`)
in the dependent task's frontmatter; the inverse edge (`dependents`) is always
computed at read time. For the v2 task structure, two alternative homes for the
edges were explicitly on the table:

- **double-ledger** — store each edge in BOTH endpoints (`depends` in the
  dependent, `dependents` in the blocker), by analogy to double-entry
  bookkeeping, hoping for error detection and read locality;
- **hoisting** — move all edges out of task files into a separate file or
  directory (a central ledger), hoping for cheaper graph reads and fewer
  merge conflicts in task files.

Evidence gathered for this decision (design/bench/RESULTS.md, 2026-07-10):
CLI benchmarks on synthetic stores of 1k-10k tasks at three dependency
densities (up to 80k edges); a git merge lab running seven 3-way merge
scenarios against five storage schemes; and a prior-art survey of six
file-based trackers (docs/research/task-store-prior-art.md).

---

## Decision

An edge is stored exactly once, inline, in the dependent task's frontmatter —
`depends` remains the sole stored truth and `dependents` remains computed. The
v2 encoding changes from a single flow line to a sorted YAML block list (one
`- t-xxxx` line per edge; readers accept both encodings, writers emit block
form). Double-ledger and hoisted central ledgers are rejected. Any future
performance index must be a pure, rebuildable cache under `.taskkling/cache/`
— never a second source of truth.

---

## Rationale

The core insight from the benchmarks: **dependency density is not the cost
driver — file count is.** 80k edges cost only ~20-30% more than 2k edges at
equal file count; per-file open+parse dominates everything. So the supposed
performance win of hoisting (read the graph from one file) attacks the wrong
term: readiness needs every task's `status` anyway, which lives in the task
files. Hoisting saves nothing on the hot reads while costing the property that
makes a markdown store worth having — a task file a human or agent can read
alone and see what blocks it.

The merge lab settles the collaboration axis empirically. The single hot
central file (`_deps.txt`) is the *worst* scheme measured: it false-conflicts
even when two branches link deps to unrelated tasks. Double-ledger converts one
silent corruption (delete-vs-link produces a dangling edge) into a visible git
conflict — its only win — but pays for it with false conflicts whenever two
branches add edges toward the same popular blocker, and doubles the files
touched by every edge mutation. Per the prior-art survey, the bookkeeping
analogy also does not hold: double-entry detects errors because the two
postings are independent facts; `dependents` is merely the transpose of
`depends`, so a mismatch can only ever mean "stale copy" — redundancy without
information. Every surveyed system (Taskwarrior, git-bug, Obsidian Tasks,
org-edna, Fossil, beancount) stores the edge once, on the dependent, and builds
central structures only as derived caches.

The block-list encoding is the cheap, evidenced improvement: concurrent dep
additions to the same task conflict under every scheme, but a block list
resolves by keeping both `- id` lines, where the status-quo single flow line
must be merged by hand — and the fat-gate fan-in pattern is the dogfood store's
most common combinatorial shape. Sorted emission gives a canonical form so
equal graphs produce equal bytes.

What double-ledger was hoped to buy is provided cheaper elsewhere: integrity
detection belongs in a `doctor` assertion pass (dangling `depends`, cycles,
duplicate ids) run on demand and after merges — the merge lab shows no storage
format prevents post-merge dangling edges, so a checker is needed regardless.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Double-ledger (edge stored in both endpoints) | Transpose redundancy detects nothing that a doctor pass cannot; false merge conflicts on popular blockers; 2x write amplification; drift becomes a new failure mode needing repair tooling. |
| Hoisted single edge file (`tasks/_deps.txt`) | Empirically worst merge behavior (hot-file false conflicts on unrelated edits); task files stop being self-contained; no read-path win since statuses still require the full file scan. |
| Hoisted per-task dep files (`tasks/_deps/<id>.txt`) | Merge behavior only equals inline-block; still forfeits self-contained task files; doubles the file count the scan pays for. |
| Keep single-line flow list encoding | Guaranteed hand-merge on every concurrent dep addition to the same task (the most common conflict in practice); no upside vs block list. |
| SQLite/index as primary store | Abandons the product's foundation (human-readable markdown, git-diffable history). Prior art uses databases only as rebuildable caches. |

---

## Consequences

**Positive:** task files stay self-contained and human-mergeable; one stored
truth means no drift class; canonical sorted encoding stabilizes diffs; the
cache rule gives a sanctioned escape hatch if a store outgrows the full scan
(git's stat-cache index over these same 10k files runs in ~60 ms — proof the
approach works when needed).

**Negative / open:** dangling edges after merges remain possible under any
scheme and are now explicitly `doctor`'s job (implementation milestone);
`dependents` queries keep requiring the full scan until a cache exists; the
full-scan cost model caps comfortable active-set size at roughly 2k tasks
(mutations ~270 ms) — acceptable because the archive sweep (ADR-014) exists
precisely to keep the active set small, and op-level fixes (skip graph
validation when `depends` is unchanged; direct filename lookup) recover the
common paths independent of storage.
