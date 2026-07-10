# ADR-014: Graph-neutral archive; git-native delete with a tool-owned trash net

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-10
**Follow-up ADRs:** —

---

## Context

The lifecycle stores both `archive/` and `trash/` as flat subdirectories of the
user's tasks directory: `cleanup` sweeps closed tasks to `archive/`; `delete`
moves a task to `trash/` (restorable) after cascade-pruning its id from every
dependent. Three problems force a redesign:

1. **Archiving corrupts readiness.** Reproduced live (design/bench/RESULTS.md
   §4): `cleanup` moves a `done` task out of the active set without touching
   its dependents, and the resolver reads only the active set — so a completed
   dependency counts as unmet and every dependent is spuriously blocked
   forever. Archiving must not change what the graph means.
2. **Trash is user data that behaves like a cache.** Deleted tasks sit in the
   committed store, churn git history, and duplicate a guarantee git already
   makes (any committed file is recoverable from history) — while stores
   outside git, like taskkling's own gitignored dogfood store, still need SOME
   safety net because for them deletion would otherwise be unrecoverable.
3. Both dirs grow unboundedly inside the data the tool scans for id minting.

The store is designed to live in git; the question is how far deletion and
recovery may LEAN on git without breaking stores that are not in git. The
options explored: keep tool-owned trash as user data (status quo), go fully
git-native (delete = remove, recovery = git only, non-git stores get a warning),
or split the mechanisms. Archive options explored: in-place flag (no move),
move + resolve across archive, move + prune edges at sweep time.

---

## Decision

**Archive:** stays a move (`tasks/archive/`), but becomes graph-neutral: the
resolver treats a `depends` id missing from the active set as a targeted lookup
into `archive/` — an archived `done` task satisfies the dependency exactly as
an active one would. The sweep stamps `archived: <datetime>` provenance into
frontmatter.

**Delete:** the trash directory leaves the user's data. `delete` cascade-prunes
as today, then moves the file to `.taskkling/trash/` — tool-owned scratch,
excluded from version control by a `.taskkling/.gitignore` that `init` writes
(alongside `lock`, `tmp/`, `cache/`). In a committed store, a delete therefore
shows in git as a true deletion, and git history is the durable undo; the
tool-owned trash is a uniform convenience net (restore works everywhere,
including non-git stores) that `cleanup` may purge by age. `tasks/trash/` is
abolished.

---

## Rationale

The archive decision follows from what archive is FOR. The benchmarks show the
full-scan cost model caps the comfortable active set at ~2k tasks — the archive
sweep is the performance lever that keeps working stores small (org-mode's
manual makes the same argument for move-based archiving; Taskwarrior's
pending/completed split is the same hot/cold idea). So the move stays. The bug
is that moving changed *graph semantics*; the fix is to make resolution
location-transparent for the one case that matters. Targeted lookup keeps this
cheap: only ids actually referenced by active tasks and missing from the active
set trigger an archive file read — filename prefix matching finds them without
parsing the archive wholesale, so export stays O(active), not O(all-history).
Pruning edges at sweep time was rejected because it destroys authored data
(the gate's `depends` list is its record of what the milestone contained) and
would make `cleanup` a semantic mutation rather than a mechanical move.

The delete decision resolves the git-coupling question by splitting *what
deletion means* from *how recovery works*. Deletion is a user-data mutation:
remove the file, prune the edges — identical in and out of git. Recovery is
layered: git history where the store is committed (the durable, complete undo —
including the pruned edges, which no trash can restore), and the tool-owned net
for the recent-mistake case everywhere else. Keeping trash *inside the user's
committed data* was the worst of both worlds: it polluted history with noise git
would have kept anyway, made "delete" a lie in diffs (the file just moved), and
still couldn't restore severed edges. Going fully git-native with NO net was
rejected because the tool must not require git (the dogfood store itself is
gitignored), and a plain irreversible delete in a non-git store is a data-loss
footgun. Moving the net into `.taskkling/` makes its true nature — a cache-like
convenience, not user data — structural: it is never scanned, never committed,
and purging it is safe by construction.

`init` writing `.taskkling/.gitignore` makes the meta-dir self-managing in
git-committed stores: `config.toml` (shared workspace policy) gets committed,
while `lock`, `tmp/`, `trash/`, and `cache/` never can be — no user gitignore
editing required.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| In-place archive flag (`archived:` field, no move) | Active-set scan cost grows with total history forever — surrenders the performance lever that is archive's main job (mutations already ~1.5 s at 10k files). |
| Prune/rewire dependents' edges at sweep time | Destroys authored graph history; makes `cleanup` semantically destructive; restore could not reconstruct the edges. |
| Fully git-native deletion (no net; warn outside git) | Tool must stay functional without git; taskkling's own store is gitignored; one mistyped id would be unrecoverable data loss there. |
| Keep `tasks/trash/` as committed user data (status quo) | Duplicates git's guarantee inside git; churns history; makes deletes invisible in diffs; still can't restore pruned edges; grows inside scanned user data. |
| Tool shells out to git for restore (`restore --from-git`) | Makes git a runtime dependency of core verbs and drags a process boundary into the write path; the recipe belongs in docs/help text, the mechanism in git itself. |

---

## Consequences

**Positive:** the spurious-blocking bug class dies (archiving a done dependency
keeps dependents ready); `cleanup` becomes safe to run at any time, so active
sets can be kept small aggressively (the performance model depends on this);
deletes are honest in git history; non-git stores keep a restore path;
`.taskkling/` becomes cleanly committable.

**Negative / open:** the resolver gains a second lookup location (bounded:
archive dir listing + parsing only referenced files); `restore` from the
tool-owned trash cannot restore severed inbound edges — git history remains the
only full undo, and outside git that residual loss is accepted and documented;
id minting still lists archive + trash to avoid reuse; the archive itself still
grows unboundedly (acceptable: it is off the hot path; a future `cleanup
--delete-before --include-archive` retention policy already exists). This ADR
decides the direction the user asked to have explored and flagged: **git-first
recovery with a degraded-but-safe non-git story** — review welcome before the
implementation milestone locks the contract.
