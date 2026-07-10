# Task-structure redesign — empirical results

Evidence gathered 2026-07-10 for the v2 task-structure design (ADR-012..015).
Reproduce with the scripts in this directory: `gen_store.py` (synthetic stores),
`bench.py` (CLI timings), `merge_lab.py` (git-merge behavior per storage scheme).

Binary under test: installed `taskkling 0.5.1` (mingwX64 release, 2026-07-09) on
Windows 11, NTFS, warm cache. The storage layer is unchanged through 0.5.2, so the
scaling shape carries over. All timings are medians of 3 runs, in ms, and include
~12 ms of process-spawn overhead (`spawn_help` baseline).

## 1. CLI timings vs store size

Stores: `sparse` (backlog-like, ~0.2 edges/task), `gates` (milestone-like groups of
50 with 15-dep gate tasks — mirrors the real dogfood store), `dense` (combinatorial
stress, 8 edges/task ⇒ 80k edges at 10k tasks).

| op | 1k sparse | 1k dense | 5k gates | 10k sparse | 10k gates | 10k dense |
|---|---|---|---|---|---|---|
| spawn (baseline) | 11 | 12 | 11 | 14 | 12 | 13 |
| export | 111 | 137 | 503 | 1091 | 1104* | 1440 |
| list --ready | 96 | 113 | 442 | 940 | 966* | 1035 |
| get <id> | 33 | 34 | 115 | 227 | 232* | 253 |
| add | 55 | 56 | 233 | 511 | 488 | 498 |
| done | 135 | 134 | 687 | 1470 | 1455 | 1569 |
| link | 131 | 138 | 686 | 1483 | 1533 | 1766 |
| delete | 117 | 124 | 547 | 1159 | 1393 | 1259 |
| done --export-on-success | 240 | 247 | 1179 | 2538 | 2780 | 2849 |

*gates-10k rows read from full results.csv.

Findings:

- **Scaling is linear in file count and I/O-dominated.** 80k edges (dense) add only
  ~20–30% over 2k edges (sparse) at the same file count: per-file open+parse
  dominates, graph math is noise. Combinatorial dependency density is NOT the
  bottleneck; store size is.
- **1k tasks is comfortable** (every op ≤ 250 ms). **~2k active is the comfort
  ceiling** for mutations (~270 ms). At 10k, `done`/`link` take ~1.5 s and
  `--export-on-success` ~2.8 s — unusable interactively.
- **The O(N) cost of mutations is mostly incidental, not inherent**: `done`/`set`
  don't change edges but still pay a full-store parse for the cycle check
  (`validateInvariants` → `loadTasks`), and `get` pays a full directory listing to
  find one filename. Op-level fixes (skip graph validation when `depends` is
  unchanged; direct prefix lookup) cut the hot paths independent of any storage
  change.
- `add` pays 3 directory listings (active + archive + trash for id minting) — ~500 ms
  at 10k before writing one file.

## 2. git on the same stores

On gates-10k (10,001 files, one commit): `git status` 62 ms, `git add -A` 77 ms,
`git log -1 -- tasks` 20 ms. Git handles the identical file layout ~20x faster than
the tool's own scan, via its stat-cache index — the precedent for any future
taskkling index: **a pure, rebuildable cache keyed on stat data, never a second
source of truth.**

## 3. Merge lab — storage schemes under 3-way git merges

Schemes: `inline-flow` (`depends: [a, b]`, status quo), `inline-block` (YAML block
list, one id per line), `double-ledger` (block `depends` + mirrored `dependents` in
the other endpoint), `hoisted-file` (single `tasks/_deps.txt`, sorted
`child <- parent` lines), `hoisted-dir` (`tasks/_deps/<id>.txt` per task).

Conflict = git textual conflict. Dangling = clean merge but broken graph.

| scenario | inline-flow | inline-block | double-ledger | hoisted-file | hoisted-dir |
|---|---|---|---|---|---|
| A and B each link a different dep to the SAME task | conflict | conflict | conflict | conflict | conflict |
| A and B link deps to DIFFERENT tasks | clean | clean | clean | **conflict** | clean |
| A deletes task P, B links X→P | clean, **dangling** | clean, **dangling** | **conflict (surfaced!)** | conflict | clean, **dangling** |
| A closes task, B retitles it (file rename) | conflict | conflict | conflict | conflict | conflict |
| A and B each link disjoint dep sets to the same gate | conflict | conflict | conflict | conflict | conflict |
| A and B add different new tasks (both dep on t-0001) | clean | clean | **conflict** | **conflict** | clean |
| A marks done, B marks dropped (true semantic conflict) | conflict | conflict | conflict | conflict | conflict |

Findings:

- **No scheme survives concurrent edits to the same task's deps** — but resolution
  difficulty differs: a block list resolves by keeping both `- id` lines; the
  status-quo single flow line must be hand-merged. Same for the gate-fanin case,
  the dogfood store's most common combinatorial pattern.
- **hoisted-file is strictly worst**: the single hot file false-conflicts even on
  edits to unrelated tasks. Rejected on evidence.
- **double-ledger's one real win**: delete-vs-link surfaces as a modify/delete git
  conflict instead of a silent dangling edge. Its cost: false conflicts whenever
  two branches add dependents to the same popular task (write amplification: every
  edge mutation touches two files). The same protection is available cheaper as a
  post-merge `doctor` check.
- **hoisted-dir ≈ inline-block on merge behavior**, but loses the self-contained
  task file (a task's deps no longer visible when reading its .md). No win to pay
  that UX cost for.
- The delete-vs-link dangling edge (1 edge) appears in every non-conflicting
  scheme: **post-merge integrity cannot be guaranteed by any storage format** —
  it needs a `doctor` assertion pass (beancount's pattern).

## 4. Archive semantics gap (live repro, taskkling 0.5.1)

```
add "upstream done task"            -> t-iqpd
add "dependent open task" --depends t-iqpd
done t-iqpd
cleanup                             # sweeps t-iqpd to tasks/archive/
export: t-3aaj open ready:False blockers:[t-iqpd]
```

`cleanup` moves a `done` task out of the active set without touching its
dependents' `depends`; the resolver only sees the active set, so the completed
dependency now counts as unmet and the dependent is **spuriously blocked forever**.
Any archive redesign must make archiving graph-neutral: either resolve `depends`
across the archive or prune/rewrite at sweep time.

## 5. Unknown-key data loss (code-confirmed)

`parseTask` ignores unknown frontmatter keys and `toMarkdown` re-emits only known
fields, so ANY mutation to a task silently deletes hand-added or future-version
frontmatter keys (`core/Frontmatter.kt`, `core/TaskFile.kt`). PRD §8.1 promises an
extensible schema; v2 must round-trip unknown keys verbatim.
