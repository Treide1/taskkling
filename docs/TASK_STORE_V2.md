# taskkling — task store v2 specification (design draft)

Status: **design draft, pre-implementation.** Decided by ADR-012 (milestones &
tags), ADR-013 (dependency storage), ADR-014 (archive & delete lifecycle),
ADR-015 (format versioning & migration); evidence in `design/bench/RESULTS.md`
and `docs/research/task-store-prior-art.md`. When implemented, PRD §8/§9/§10 and
DOMAIN_LANGUAGE are rewritten from this document and it dissolves into them.

---

## 1. Filesystem layout

```
<root>/
├─ tasks/                          # config.tasks_dir; user data, designed to be committed
│   ├─ _milestones.md              # ordered milestone registry (ADR-012)
│   ├─ t-a1z9--acquire-liability-insurance.md
│   └─ archive/                    # closed tasks, moved by `cleanup` (flat)
│       └─ t-….md
└─ .taskkling/                     # tool-owned
    ├─ config.toml                 # committable workspace policy (carries format = 2)
    ├─ .gitignore                  # written by init: lock, tmp/, trash/, cache/, backup-*
    ├─ lock
    ├─ tmp/
    ├─ trash/                      # deleted tasks; convenience net, purgeable (ADR-014)
    └─ cache/                      # reserved: derived indexes only, always rebuildable (ADR-013)
```

Changes vs v1: `tasks/trash/` is gone (trash is tool scratch now); `_`-prefixed
files in `tasks/` are reserved store files, never tasks; `.taskkling/` is
self-managing under git via its own `.gitignore`.

## 2. Task file: frontmatter v2

```markdown
---
id: t-a1z9
title: Acquire liability insurance
milestone: v0.7.0
tags:
  - legal
  - ops
status: waiting
waiting_on: provider quote callback
depends:
  - t-9f3c
  - t-k2d7
due: 2026-07-31T23:59:00Z
defer: 2026-07-05T00:00:00Z
priority: high
created: 2026-06-28T10:15:00Z
closed:                            # only when set
archived:                          # only when set; stamped by the cleanup sweep
x-custom-key: preserved verbatim   # unknown keys survive round-trips (ADR-015)
---

Body unchanged: human-owned freeform markdown.
```

Field changes vs v1:

| change | detail |
|---|---|
| `thread` removed | replaced by `milestone` (single, optional, registry-validated) + `tags` (list, optional, free-form slugs) |
| `depends` encoding | sorted YAML block list, one `- id` per line; empty ⇒ key omitted; reader accepts v1 flow lists too |
| `tags` encoding | same block-list convention as `depends` |
| `archived` added | datetime stamped when the sweep moves the file; provenance, never affects readiness |
| unknown keys | preserved verbatim through every mutation, emitted after known keys in original order |
| key order | canonical: id, title, milestone, tags, status, waiting_on, depends, due, defer, priority, created, closed, archived, then extras |

Everything else (id shape, datetimes, status/priority enums, computed
attributes, `waiting_on ⇒ waiting` invariant) is unchanged from PRD §8.

## 3. Milestone registry — `tasks/_milestones.md`

```markdown
# Milestones
- v0.6.0 — ui packaging & distribution
- v0.7.0 — task store v2
- v1.0
```

- One `- <slug>` per line, optional ` — <note>` after the slug. Document order
  is chronology (top = oldest). Anything else in the file is ignored prose.
- Write validation: `add --milestone X` / `set --milestone X` require `X`
  registered; reads are lenient (unregistered value renders, `doctor` flags it).
- `taskkling milestone add <slug> [--note …]` appends; `taskkling milestone list`
  prints in order. Reordering/renaming is a hand edit (it's a text file).
- Contract: export gains a top-level `milestones: [slug…]` array in registry
  order so the UI can lane/sort chronologically without reading the store.

## 4. Dependency storage (ADR-013)

- `depends` on the dependent task is the ONLY stored edge; `dependents` and
  `blockers` stay computed. No central edge file; no mirrored fields.
- Cycle/dangling validation runs **only on mutations that change `depends`**
  (add --depends, link, unlink, restore). Status/metadata/body mutations skip
  graph validation entirely — this turns `done`/`set`/`append` from O(N-parse)
  into O(1) file ops (benchmarked: ~1.5 s → ~50 ms at 10k tasks).
- Any future index lives in `.taskkling/cache/`, is invalidated by
  (file-count, max-mtime, config-hash), is rebuilt from files on any doubt, and
  is written only under the write lock. Readers fall back to the full scan.

## 5. Lifecycle (ADR-014)

- `done`/`drop`: unchanged (stamp `closed`, stay active until swept).
- `cleanup`: sweeps closed tasks → `tasks/archive/`, stamping `archived`.
  Graph-neutral by construction: see resolution rule below. Purge flags
  unchanged, plus they now also purge `.taskkling/trash/`.
- **Resolution rule (fixes the spurious-blocking bug):** a `depends` id not in
  the active set is looked up in `archive/` by filename prefix; an archived
  `done` task satisfies the dependency. Archived `dropped`, and ids found
  nowhere, count as unmet blockers (`doctor` flags them). Only referenced ids
  are looked up — export stays O(active).
- `delete`: cascade-prune from dependents (unchanged), then move the file to
  `.taskkling/trash/`. In a committed store, git shows a real deletion.
- `restore <id>`: from `.taskkling/trash/` or `archive/`, as today (clears
  `closed`/`archived`, reopens, drops now-dangling deps and reports them).
  Severed inbound edges stay severed; help text documents the git recipe
  (`git log --diff-filter=D -- 'tasks/<id>--*'`) as the full undo.
- `doctor` (assertion pass, replaces the stub): dangling `depends` (after
  archive resolution), cycles, duplicate ids across dirs, unregistered
  milestones, `waiting_on` without `waiting`, malformed registry lines.
  Read-only, exit code signals findings; recommended after merges.

## 6. Format marker & migration (ADR-015)

- `.taskkling/config.toml` gains `format = 2` (absent ⇒ v1). Newer-format
  stores: refuse all ops. Older-format stores: reads best-effort, mutations
  refused; both point to `migrate`.
- `taskkling migrate` (one-shot, idempotence-guarded):
  1. copy store → `.taskkling/backup-pre-migrate/`;
  2. rewrite every task file (active + archive + old trash) to frontmatter v2 —
     default `thread: X` ⇒ `tags: [X]`; with `--milestones a,b,c` those values
     become `milestone:` instead and seed `_milestones.md` in the given order;
     archive files get `archived:` backfilled from `closed`;
  3. move `tasks/trash/*` → `.taskkling/trash/`; delete the empty dir;
  4. write `_milestones.md` (empty scaffold if no `--milestones`),
     `.taskkling/.gitignore`, and `format = 2`.
- Config key changes: `default_thread` ⇒ `default_milestone` (migrate rewrites
  it; value must then be registered), new optional `trash_retention_days`.
- Known hazard (accepted): pre-v2 binaries ignore the marker and would misread
  v2 files. Don't point old binaries at migrated stores.

## 7. Contract & UI (implementation milestone, per ADR-008)

- `TaskDto`: `thread` → `milestone`, add `tags: [string]`; computed unchanged.
- `ExportDto`: add `milestones: [string]` (registry order).
- One wire-change commit: DTOs + golden tests + UI labels together. UI renders
  `milestone` as the primary chip (chronological accent) and `tags` as the
  existing outline chips; DOMAIN_LANGUAGE §2/§7 tables updated in the same
  change.
- CLI surface: `--thread/-t` ⇒ `--milestone/-m` + `--tag` (repeatable);
  `list --milestone X`, `list --tag Y`; `set --milestone`, `set --add-tag/
  --remove-tag`; new `milestone` and `migrate` verbs; `doctor` de-stubbed.

## 8. Performance budget (evidence: design/bench/RESULTS.md)

| op | v1 @10k measured | v2 @10k target | how |
|---|---|---|---|
| export / list | ~1.1–1.4 s | unchanged (O(active)) | keep active set small via sweep; archive lookup only for referenced ids |
| done / set / append | ~1.5 s | ≤ 100 ms | skip graph validation when `depends` unchanged |
| link / add --depends | ~1.5 s / 0.5 s | unchanged | inherent O(N) cycle check; acceptable at gate frequency |
| get | ~230 ms | ≤ 100 ms | resolve `<id>--*.md` by direct prefix listing, parse one file |
| working assumption | — | ≤ 2k active tasks stay ≤ 300 ms everywhere | sweep is the lever; 10k remains functional, not snappy |

Combinatorial density is a non-issue: 80k edges cost ~25% over 2k edges at equal
file count. File count is the only scaling axis that matters.

## 9. Deliberately out of scope

- Milestone status/dates in the registry (follow-up ADR if needed).
- Typed edges (`relates`, `part-of`): the block-list encoding and reserved `_`
  namespace leave room; no requirement today.
- The derived cache itself: rules reserved (ADR-013), build only on evidence of
  a store that outgrows the sweep discipline.
- Multi-store / cross-repo references.
