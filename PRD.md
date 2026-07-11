# taskkling — Product Requirements Document

> This PRD is the founding artifact of a standalone,
> reusable tool. It is deliberately **agnostic to any other layer or tool** — no knowledge base,
> calendar, agent runtime, or host project is assumed or named. Where examples are needed, they
> are generic.

---

## 1. Summary

**taskkling** is a lightweight, git-native, **directed-acyclic-graph (DAG) task manager** for
solo operators and **human + agent** teams. Work is modelled as tasks connected by
`depends` edges. The source of truth is **one markdown file per task** (YAML frontmatter +
freeform body), recommended in a git repository. There is no database, daemon, server, or background process.

It ships two artifacts:

1. A **Kotlin/Native CLI binary** (`taskkling`) — the single read **and** write interface. Fast
   cold start, no runtime to install. This is what both humans and agents drive.
2. A **light Compose Multiplatform Desktop UI** — a *pure client* of the CLI: it reads the CLI's
   JSON export for display and performs every mutation by invoking the CLI. It's not a second writer.

The design north star: **"file I/O with editable metadata."** The CLI is thin, validated sugar
over plain markdown files; everything it does, a human could do by hand-editing — the CLI just
makes it safe, concurrent, and queryable.

---

## 2. Motivation & problem

Flat checklists (a single ordered list of `- [ ]` items) collapse under real work because they
force a **binary, sequential** shape onto items that are not binary or sequential:

- Some items are **informational**, some **actionable**, some **waiting on an external party**.
- Items have **dependencies** ("can't start B until A is done") that a linear list can't express,
  leading to awkward hacks like renumbered "phase 0 / 0.5 / 1" sequences.
- Items have **deadlines** and **defer-until** dates that are orthogonal to dependency order.

A DAG models all of this directly: ordering becomes edges, grouping becomes a label, deadlines
and defers become per-task metadata, and "what can I work on right now" becomes a computed query.

Existing tools in this space either pull in a **heavy backbone** (a sync server, an embedded SQL
database, background daemons, git-hook takeovers) that generates exactly the drift and operational
friction we want to avoid, or they omit the dependency graph entirely. taskkling keeps the
backbone **dumb** — plain files + a tiny binary — and invests only where there's genuine value.

---

## 3. Goals & non-goals

### Goals
- Model work as a DAG with first-class **dependencies**, **time** (`due`/`defer`), and
  **status**.
- **One source of truth** (markdown files in git), mutated through **one write path** (the CLI),
  safely shared by humans and agents across independent OS processes.
- **Zero infrastructure**: no server, no database, no daemon, no git hooks.
- **Reusable**: drop the binary into any repo; it discovers its root like git.
- **Agent-ergonomic**: terse, scriptable, JSON-capable, dependency-aware interop.
- A **light** desktop UI that visualises the graph and mutates only via the CLI.

### Non-goals
- **No coupling to any external system** — knowledge bases, calendars, agent frameworks, chat
  tools, issue trackers. Integrations are downstream glue, out of scope here.
- **No multi-user collaboration / real-time sync** — git is the only sync; solo/low-contention
  is assumed.
- **No scheduling engine / notifications / recurrence.** taskkling stores `due`/`defer` and can
  emit them; acting on time is a consumer's job.
- **No web/mobile client.** (A future Ktor shim fronting the CLI could enable one; explicitly
  deferred.)
- **No execution** — taskkling tracks work; it does not run it (it is not a pipeline runner).

---

## 4. Principles

1. **The backbone stays dumb.** Plain files + a small binary. Any feature that wants a daemon,
   a database, or a git hook must be rejected or redesigned.
2. **File I/O with editable metadata.** Anything the CLI does is expressible as editing a
   markdown file. Hand-editing remains valid; the CLI adds safety, concurrency, validation, and
   queries.
3. **One write path.** All mutations funnel through the CLI, which validates at the write
   boundary. The UI and agents are clients, never parallel writers.
4. **Store facts; derive the rest.** Stored state is only what a human/agent decides. `ready`,
   `blocked`, `overdue`, etc. are **computed at read**, never persisted — no cache to drift.
5. **Safety across processes.** Correctness is enforced at the filesystem level (advisory lock +
   atomic rename), because the CLI runs as independent processes.

---

## 5. Users & core use cases

- **Solo operator (human)** browses and triages at the terminal (`list`, `get`) and via the
  desktop UI; hand-edits when convenient.
- **Agent** creates/decomposes/links tasks, queries the **ready set** and dependency
  relationships (`list --ready`, `list --blocked-by <id> --id-only`), records progress, all
  through terse CLI calls with `--json` / `--export-on-success`.
- **Desktop UI** renders the DAG from `export`, lets the human act; each action shells out to the
  CLI.

Representative flows: capture a task; link it behind a prerequisite; park it `wait`-ing on an
external party until a date; ask "what's actionable now?"; mark done; review the agenda by `due`;
delete a task and have its edges cleaned up; restore it.

---

## 6. Architecture

### 6.1 Module topology (KMP)

| Module | Targets | Responsibility | Depends on |
|---|---|---|---|
| `:contract` | native + JVM | `@Serializable` DTOs for the export/JSON contract shared by CLI output ↔ UI input | — |
| `:core` | native + JVM | domain model, frontmatter parse/serialize, graph + ready-set + validation, lock/atomic-write primitives | `:contract` |
| `:cli` | native only | the `taskkling` binary; kotlinx-cli arg parsing; thin command layer over `:core` | `:core`, `:contract` |
| `:ui` | JVM (Compose Desktop) | desktop app; spawns the binary; renders `export`; mutates via CLI | `:contract` |

(JVM target on `:core`/`:contract` exists primarily for fast unit testing and the UI's DTO
sharing; the shipped CLI is native-only.)

Key consequence: the UI links **only `:contract`** (the DTOs), never `:core`. It is *physically
incapable* of parsing or writing task files, which structurally guarantees the single-write-path
principle.

### 6.2 CLI runtime — Kotlin/Native

The CLI is a **standalone native binary** (one per platform), chosen because it is invoked
constantly by agents and the UI: cold start in tens of ms, **no JVM/JRE dependency**, trivially
vendored onto `PATH` in any repo. Library stack — all native-capable: **kotlinx-cli** (args),
**kotlinx-serialization** (JSON), **Okio** (file I/O). Platform specifics (file locking, atomic
replace) live in a small `expect/actual` shim.

### 6.3 UI runtime — Compose Multiplatform Desktop (JVM)

A **light** Compose Desktop app (JVM — the supported Compose desktop target; *not* native-desktop
Compose). It is a **pure CLI client (Option A)**:

- **Reads**: invokes `taskkling export` (and `get --body` for a task's body on demand), renders
  the DAG and detail panels from the returned JSON.
- **Writes**: every mutation is a `taskkling <verb> … --export-on-success` subprocess call; the
  UI refreshes from the returned full export. It does not diff server-side or hold a parallel
  model.
- Locates the binary via `PATH`, overridable by config.

The UI's look is codified in **`docs/DESIGN.md`** — the canonical visual language.

### 6.4 Data flow

```
            ┌────────────── reads ──────────────┐
agent ──┐   │  taskkling export / list / get    │   ┌── UI (Compose Desktop)
        ├──►│  ───────────────────────────────► │◄──┤   reads export JSON
human ──┘   │  taskkling <mutation verb>        │   └── writes via CLI subprocess
            └──────────── one write path ───────┘
                         │
                         ▼
                 markdown files (git)         ← single source of truth
```

---

## 7. Concurrency & integrity

The CLI runs as **independent OS processes**, so safety is at the filesystem level. No daemon, no
long-lived state; every invocation reads fresh from disk.

### 7.1 Write protocol (every mutation)
1. **Acquire** an exclusive advisory lock on `.taskkling/lock` (`flock` on POSIX, `LockFileEx` on
   Windows — via the `expect/actual` shim).
2. **Read fresh** the affected task file(s) from disk and parse.
3. **Apply the minimal field change** in memory (field-scoped). The whole task is carried over
   from the *fresh* read — never from a stale caller-supplied blob — so concurrent edits to
   *different* fields of the same task both survive.
4. **Serialize** the updated task to markdown.
5. **Write to a temp file** in `.taskkling/tmp/`, `fsync`.
6. **Atomically rename** temp → target (`rename` / `MoveFileEx(REPLACE_EXISTING)`).
7. **Release** the lock.

The lock guarantees writer-vs-writer atomicity of steps 2–6; the temp+rename guarantees
writer-vs-reader atomicity (lock-free readers never see a torn file). Temp files live under
`.taskkling/tmp/` (same filesystem → atomic cross-dir rename) and are **structurally excluded**
from reads, never appearing in `tasks/`.

### 7.2 Reads
**Lock-free.** `export` reads the files, computes derived attributes, and **streams JSON to
stdout** — there is **no persisted snapshot** to race on. Refresh = run `export` again.

### 7.3 `--export-on-success`
Any mutation may pass `--export-on-success`: the full, **lock-consistent** post-mutation export is
computed *before releasing the lock* and emitted on stdout — a transactional read-after-write in a
single process (no TOCTOU gap). Default **off** (a plain mutation prints a terse confirmation +
the affected id) to keep agent output token-cheap. Any incremental/diff view is the **client's**
job: the UI diffs its previous full export against the new one locally. There is no server-side
diff or revision protocol (that would require stored mutable state — rejected).

### 7.4 Stale-lock guard
The lock holder records its PID + timestamp. A lock older than `lock_timeout` whose PID is no
longer alive is reclaimable, so a crashed process cannot wedge the repo.

### 7.5 Two-tier integrity
- **Preventive — write-path validation** (every mutation; cheap; always on): rejects enum
  violations, dangling `depends` (valid targets: active **and archived** tasks — ADR-014),
  **dependency cycles**, and the `waiting_on ⇒ status=waiting`
  invariant. The single exception is `delete` (§9.5), which is validation-free by design.
- **Curative — `doctor`** *(verb denoted; internals deferred post-PRD)*: a full scan for **file
  integrity** (parse/schema corruption, orphaned temp files, git conflict markers) and **logical
  resolution** (cycles, dangling/contradictory links, invariant violations from hand-edits),
  offering a **deterministic A/B fix per conflict type**. The fix catalogue is specified later.

### 7.6 Accepted limits
- Same-task writes are **serialized** (global lock); throughput is irrelevant at solo scale.
- A genuine concurrent edit of the *same task* on two machines surfaces as a **git merge** for the
  human to resolve; conflict markers fail validation rather than corrupt silently.
- The scan cost model is **measured, linear in file count** (dependency density is noise: 80k
  edges cost ~25% over 2k at equal file count). Benchmarks, the git-merge conflict matrix per
  dependency-storage scheme, and reproduction scripts live in `design/bench/RESULTS.md`;
  prior-art survey in `docs/research/task-store-prior-art.md`. Store-v2 consequences are decided
  in ADR-012..015 and specified in `docs/TASK_STORE_V2.md`.

---

## 8. Data model — the task

One task = one markdown file: **YAML frontmatter (machine-owned metadata) + body (human-owned
freeform markdown)**. There is exactly one task type; "phases" are expressed by `thread` +
dependencies + ordinary gate tasks (not a task taxonomy).

### 8.1 Stored fields

| Field | Required | Type | Semantics |
|---|---|---|---|
| `id` | ✅ | string | Immutable. `id_prefix` (default `t-`) + **4 case-agnostic alphanumerics** `[a-z0-9]` (lowercased), e.g. `t-a1z9`. ~1.68M space, collision-checked under the lock at creation. The only thing `depends` references. |
| `title` | ✅ | string | One-line summary (frontmatter, not a body heading). |
| `thread` | ➖ | slug | Single optional grouping label. |
| `status` | ✅ | `open` \| `waiting` \| `done` \| `dropped` | Default `open`. Stored, human-decided. |
| `waiting_on` | ➖ | string | Free text naming the **external requirement** the task waits on — never a task id (that's an edge); **may be set only when `status = waiting`** (optional even then). |
| `depends` | ➖ | list\<id> | Upstream predecessors that must be `done` for this task to be ready. |
| `due` | ➖ | datetime | Deadline → drives urgency / overdue / agenda. Never gates readiness. |
| `defer` | ➖ | datetime | "Not before" — suppresses readiness until then (set via `wait --until`). |
| `priority` | ➖ | `low` \| `normal` \| `high` | Default `normal`. |
| `created` | ✅ | datetime | Stamped at creation. |
| `closed` | ➖ | datetime | Stamped when the task leaves the active set (`done` / `dropped` / `delete`). |

**Datetimes**: ISO-8601 UTC, minute granularity, e.g. `2026-07-31T23:59:00Z`. Day-level values are
`…T00:00:00Z`. Storage is always full datetime; **display/working granularity (day ↔ minute ↔
second) is a configurable feature deferred to later** so nothing migrates. Human surfaces drop the
`T`/`Z` (`2026-07-31 23:59`); JSON keeps canonical ISO-8601.

The schema is intentionally **extensible**: new optional metadata fields can be added later and
surfaced through `set --<field>` / `get --<field>` without breaking existing files.

### 8.2 Computed attributes (derived at read; never stored)

| Attribute | Definition |
|---|---|
| `ready` | `status=open` ∧ every `depends` is `done` ∧ (`defer` unset ∨ `defer ≤ now`) |
| `blocked` | `status=open` ∧ some `depends` is not `done` |
| `deferred` | `defer` set ∧ `defer > now` |
| `overdue` | `due` set ∧ `due < now` ∧ `status ∉ {done, dropped}` |
| `resurfaced` | `status=waiting` ∧ `defer` set ∧ `defer ≤ now` (tickler is due for a decision) |
| `blockers` | the subset of this task's `depends` that are not yet `done` — the tasks blocking it right now |
| `dependents` | ids of tasks whose `depends` contains this task (downstream) |

Resolution is **graph-neutral across the archive** (ADR-014): a `depends` id absent from the
active set is looked up in `archive/`, and an archived `done` task satisfies the edge exactly as
an active one would — `cleanup` never changes what the graph means. An archived `dropped` task,
like a dangling id, counts as unmet.

### 8.3 On-disk example

```
tasks/t-a1z9--acquire-liability-insurance.md
```
```markdown
---
id: t-a1z9
title: Acquire liability insurance
thread: legal
status: waiting
waiting_on: provider quote callback
depends: [t-9f3c]
due: 2026-07-31T23:59:00Z
defer: 2026-07-05T00:00:00Z
priority: high
created: 2026-06-28T10:15:00Z
---

Need professional-indemnity + custody-damage cover. Comparing three providers.
```

Filename = `<id>--<slug>.md` (double-dash separates id from slug). `depends` references `id`,
not filename, so titles/slugs can change freely.

---

## 9. Storage & filesystem layout

```
<repo-root>/                       # discovered by walking up for .taskkling/ (git-style)
├─ tasks/                          # = config.tasks_dir; ACTIVE tasks (top level only)
│   ├─ t-a1z9--acquire-liability-insurance.md
│   ├─ archive/                    # done/dropped, moved here by `cleanup` (flat)
│   │   └─ t-….md
│   └─ trash/                      # deleted, restorable via `restore` (flat)
│       └─ t-….md
└─ .taskkling/                     # tool-owned; never a task
    ├─ config.toml
    ├─ lock                        # advisory global write lock (PID + timestamp)
    └─ tmp/                        # temp files for atomic temp→rename
```

- **Active set** = `tasks/*.md` (top level). The `archive/` and `trash/` subtrees are excluded
  structurally; included only with `--archived` (reads) where applicable.
- `archive/` and `trash/` are **flat** (no date bucketing).
- Root discovery walks up from the cwd for `.taskkling/`, so the binary and UI work from anywhere
  in the tree and the tool drops cleanly into any repo.

### 9.5 Lifecycle, archive & trash semantics
- `done` / `drop` set `status` + stamp `closed`; the file **stays in `tasks/`** (recent work
  visible) until `cleanup` sweeps it to `archive/`.
- `delete` **immediately moves** the file to `trash/`, stamps `closed`, and **prunes this id from
  the `depends` of every dependent** (§10). It is **validation-free**; referential integrity is
  preserved by the prune (no dangling edges).
- `restore` moves a task from `trash/` (or `archive/`) back to active `tasks/` and clears
  `closed`. It **does not re-add pruned edges** — the task returns, severed dependencies do not;
  git history is the only full undo. `restore` reports how many edges it could not re-wire.
- `cleanup` moves closed tasks → `archive/` (mechanical). `cleanup --delete-before <dt>`
  permanently purges **trash** entries with `closed < dt`. Archive is kept indefinitely unless
  `--include-archive` is also passed.

---

## 10. CLI surface

Canonical command: `taskkling` — the only binary name; no short alias ships (`tk`/`tkl` were
considered and rejected). The CLI is the single read and write interface.

### 10.1 Conventions
- **Output**: human-readable by default; `--json` for machine output where applicable. `export`
  is **always JSON** (no flag).
- **`--export-on-success`** is accepted by every mutation (§7.3).
- **Exit codes**: `0` success · `2` usage error · `3` validation/integrity failure · `4` lock
  timeout.
- **Global flags**: `--root <path>` (override discovery), `--no-color`, `--quiet`.

### 10.2 Reads

| Command | Returns |
|---|---|
| `export [--include-body] [--archived]` | Full JSON: all active tasks (stored + computed). `--include-body` adds a per-task `body` field (markdown sans frontmatter, JSON-escaped). `--archived` includes the archive subtree. |
| `list [filters] [sorts] [--id-only] [--json] [--archived]` | A filtered/sorted **collection**, `ls -la`-style (no body). `--json` → array of per-task objects (= `export.tasks[]` subset). |
| `get <id> [--body] [--info] [--<field>…] [--json]` | Read a task — the single read verb. Bare → the raw `.md` (frontmatter + body) printed **verbatim** (the common case; the full brief in one read). `--body`/`-b` → the body only (the `write`/`append`-symmetric read); `--info`/`-i` → parsed **field values**, stored **and** computed (read-only ones can't be `set`); `--<field>…` plucks named fields; `--json` → the structured task (body unless `--info`). Symmetric with `set`. |

**`list` filters**: `--ready` · `--blocked` · `--overdue` · `--status <s>` · `--thread <t>` ·
`--due-before <dt>` · `--blocking <id>` · `--blocked-by <id>`.
**`list` sorts**: `--sort due|created|priority`.

**`list` human format** — columns: **id · title · thread · status · attributes**, where
*attributes* folds the non-empty metadata into a comma-separated string (`due <dt>`,
`defer <dt>`, `depends on <ids>`, `waiting on '<text>'`). No emojis; computed states are queried
via filters, not decorated inline. Example:
```
t-a1z9  Acquire liability insurance   legal   waiting  due 2026-07-31 23:59, depends on t-9f3c, waiting on 'provider quote callback'
t-7e1d  File business registration    legal   open     depends on t-9f3c
t-9f3c  Tax questionnaire             legal   open     due 2026-07-05 00:00
t-3b2c  Draft flyer copy              flyer   done
```
`--id-only` emits ids only (newline list, or JSON array with `--json`), composable with any
filter for piping.

### 10.3 Dependency queries (shared core with `delete`)

| Query | Meaning |
|---|---|
| `list --blocking <id>` | the tasks **blocking** `<id>` — upstream (= `<id>`'s `depends`). |
| `list --blocked-by <id>` | the tasks **blocked by** `<id>` — its downstream dependents. |

These are backed by two single core functions — `dependencies(id)` (`--blocking`) and
`dependents(id)` (`--blocked-by`). **`delete` consumes `dependents(id)`** for its cascade prune,
so an agent can preview impact with the very function delete will run:
`taskkling list --blocked-by t-X --id-only`.

### 10.4 Create & data

| Command | Effect |
|---|---|
| `add "<title>" [--thread t] [--depends a,b] [--due dt] [--defer dt] [--priority p] [--body txt]` | Create a task; print the new id (cycle/dangling-checked). `--body -` reads the body from stdin (symmetric with `write <id> -`) — a full multi-line body in one call. |
| `set <id> [--<field> <value>…] [--clear <field>…]` | Atomic multi-field metadata edit (`--due/--defer/--priority/--thread/--title`, extensible). `--clear` (or `--<field> ""`) unsets a field. |
| `get <id> [--info] [--<field>…]` | (read; §10.2) symmetric counterpart (`--info`/`-f` for parsed fields). |

### 10.5 Lifecycle

| Command | Effect |
|---|---|
| `done <id>` / `drop <id>` | Set status + stamp `closed` (file stays until `cleanup`). |
| `reopen <id>` | Return to `open`; clear `closed`. |
| `wait <id> [--until <dt>] [--on "<text>"]` | Set `status=waiting`; optionally set `defer` (`--until`) and/or the external requirement `waiting_on` (`--on`). Both optional (folds the former separate `defer` verb). |
| `delete <id>` | Move → `trash/`, stamp `closed`, prune id from dependents. **No validation.** Reversible via `restore`. |
| `restore <id>` | Move from `trash/`/`archive/` → active; clear `closed`; report non-rewired edges. |

### 10.6 Relations & content

| Command | Effect |
|---|---|
| `link <id> --depends <id>[,…]` / `unlink <id> --depends <id>[,…]` | Add/remove dependency edges (cycle-checked on add); `--depends` is comma-separated and/or repeatable. |
| `write <id> "<text>"` | Replace the body in full. |
| `append <id> "<text>"` | Append to the body. |
| `get <id> --body` | (read; §10.2) the body — symmetric counterpart. |

### 10.7 Maintenance

| Command | Effect |
|---|---|
| `init [--demo-mode]` | Scaffold `.taskkling/` (config, lock, tmp) + `tasks_dir` in the cwd; a pre-existing config's `tasks_dir` is honored. `--demo-mode`/`-dm` additionally seeds a self-contained sandbox backlog kept under `.taskkling/tasks` — every state on display, freely mutable, gone with the meta dir; only a task-free workspace is ever seeded (ADR-017). |
| `config init` | Write the user-level `config.toml` (write-if-absent — never clobbers your edits) and print its path. Surfaces the on-by-default `update_check` toggle so its OFF switch is discoverable (ADR-006); both installers run it. |
| `validate [--json]` | Read-only report of all preventive checks across the set. |
| `cleanup [--delete-before <dt>] [--include-archive]` | Sweep closed → `archive/`; with `--delete-before`, purge `trash` (and, with `--include-archive`, archive) entries by `closed`. |
| `update [--global \| --local] [--version vX.Y.Z] [--check]` | Self-replace the running binary — or, with `--global`/`--local` (mutually exclusive), another tier's copy — with the latest (or a pinned `--version`) GitHub release, SHA-256-verified; prints old → new. **Update-only:** a targeted tier with no install errors rather than creating one (ADR-007). `--check` reports whether a newer release exists without installing it (an explicit, always-on lookup — ignores `update_check`, and rejects `--global`/`--local`). |
| `uninstall [--global \| --local] [-y] [--purge]` | Remove the binary and the installer's `PATH` entry for the resolved (or targeted) tier. Interactive by default, prompting through the removal choices; `-y` runs the safe scope (binary + `PATH`) non-interactively. Never touches workspace data — `.taskkling/` (config, caches) and the resolved `tasks_dir` — unless `--purge` is given explicitly; `--purge` erases both (a `tasks_dir` resolving to the workspace root or outside it is never followed). |
| `doctor [--fix]` | *Stub (post-PRD).* Integrity + logical-resolution scan with deterministic A/B fixes. |
| `export --ics` | *Stub (post-PRD).* Emit a standards-based iCalendar feed from `due` (open-standard output; no calendar-tool coupling). |

---

## 11. Ready-set & time semantics

The ready-set is the product's core query. A task is **ready** iff it is `open`, all its `depends`
are `done`, and it is not deferred (`defer` unset or in the past). **Time has two independent
roles**: `defer` gates *readiness* (a "not before"); `due` drives a separate *urgency/agenda* axis
and **never** affects readiness. A `waiting` task whose `defer` has elapsed is **`resurfaced`** —
flagged for a human/agent decision (it is not auto-actioned), matching tickler semantics. The
agenda is simply `list --sort due` / `list --due-before <dt>`.

---

## 12. Export / contract schema

`export` (and `--export-on-success`) emit:

```json
{
  "generatedAt": "2026-06-28T12:00:00Z",
  "counts": { "ready": 4, "blocked": 7, "waiting": 2, "done": 11 },
  "tasks": [
    {
      "id": "t-a1z9", "title": "Acquire liability insurance", "thread": "legal",
      "status": "waiting", "waitingOn": "provider quote callback",
      "depends": ["t-9f3c"], "due": "2026-07-31T23:59:00Z", "defer": "2026-07-05T00:00:00Z",
      "priority": "high", "created": "2026-06-28T10:15:00Z", "closed": null,
      "computed": {
        "ready": false, "blocked": false, "deferred": true, "overdue": false,
        "resurfaced": false, "blockers": [], "dependents": ["t-7e1d"]
      }
      // "body": "…escaped markdown…"   // only with --include-body
    }
  ]
}
```

DTOs live in `:contract`; the UI deserializes exactly what the CLI serializes, so the two cannot
drift.

---

## 13. UI requirements

- **Light Compose Desktop (JVM)** app; pure CLI client (§6.3). Reads `export`; renders the DAG as
  a node-link graph with computed state (ready/blocked/deferred/overdue/resurfaced) shown
  visually; a detail panel lazy-loads a task's body via `get --body`.
- **All mutations** go through `taskkling <verb> … --export-on-success`; the UI refreshes from the
  returned export and may diff old-vs-new **client-side** for incremental updates.
- Visual language: `docs/DESIGN.md` is the canonical contract for the UI's look and
  interaction design.
- Graph layout: a layered DAG layout (Sugiyama-style); threads shown as visual clusters/colour.
  *(Layout-library choice is an implementation detail, tracked under §17/§18.)*
- Binary discovery: `PATH`, overridable via config.

---

## 14. Configuration — `config.toml`

```toml
tasks_dir       = "tasks"      # active-task directory (archive/ and trash/ are subdirs)
id_prefix       = "t-"         # task id prefix
granularity     = "minute"     # day | minute | second (display/working; deferred feature)
default_thread  = ""           # applied by `add` when --thread omitted
lock_timeout    = 30           # seconds before a dead-PID lock is reclaimable
binary_path     = ""           # optional explicit path the UI uses to find the CLI
# update_check  = true         # newer-version notifier, on by default (ADR-006); set false to disable, or leave unset to inherit the user-level config
```

The `update_check` key is also honoured in a **user-level** `config.toml` (so the global binary
respects it outside any workspace; a workspace's value overrides it) — see §15 and `config init`.

---

## 15. Distribution & packaging

- **CLI**: per-platform Kotlin/Native binaries (`mingwX64`, `macosArm64`, `macosX64`, `linuxX64`)
  published on **GitHub Releases**. Install = drop on `PATH`. Vendorable per-repo for reuse.
- **UI**: packaged via Compose's native distribution (jpackage) into `.msi` / `.dmg`, **bundling a
  JRE** so nothing extra is installed. The UI requires the CLI binary present (`PATH` or
  `binary_path`).
- **No telemetry; reads never touch the network.** Nothing phones home. `list`, `get`, `export`,
  and every machine-readable/`--json` output are fully offline, full stop — the tool's only
  sanctioned network call is the opt-outable update check below.
- **`update_check` notifier — on by default, terminal-gated (ADR-006, reversing ADR-005's
  default-off).** A best-effort, ~24h-cached, silent-on-failure check against GitHub Releases for a
  newer tag. It surfaces on exactly two human-facing surfaces: `taskkling --version` **only in an
  interactive terminal**, and the always-on explicit `taskkling update --check` (invoking it *is*
  the consent). A **non-interactive `--version`** — CI, pipes, scripts, docker-build — and all
  machine-readable output stay fully offline: **no network call and no cache write**. It only
  notifies (`vX.Y.Z available — run 'taskkling update'`); it never installs. Opt out by setting
  `update_check = false` in a `config.toml` (a workspace's overrides the user-level one); the
  installers materialize the user-level file via `config init` so that switch is discoverable.
  Updating and uninstalling remain separate, user-initiated verb calls (`update`, `uninstall`;
  §10.7).

---

## 16. Repository & build

- **Standalone repo `taskkling`** (this directory), independent of any host project.
- **Gradle Kotlin Multiplatform**, single version catalog (`gradle/libs.versions.toml`), modules
  `:contract`, `:core`, `:cli`, `:ui` per §6.1.
- Toolchain: Kotlin (latest stable), Compose Multiplatform plugin, kotlinx-cli,
  kotlinx-serialization, Okio.
- CI: build/test `:core`/`:contract` on JVM; cross-compile `:cli` per platform; build `:ui`
  package; attach binaries to releases.

---

## 17. Testing strategy

- **Unit (`:core`, JVM target for speed)**: frontmatter round-trip; graph construction;
  ready-set/computed attributes; cycle and dangling detection; the `waiting_on ⇒ waiting`
  invariant; id generation/collision.
- **Golden tests**: `export` JSON snapshots; `list` human formatting.
- **Concurrency tests**: spawn multiple CLI processes contending the lock; assert no torn files,
  no lost different-field edits, correct serialization, stale-lock reclaim.
- **CLI integration**: every verb against a temp repo, including `delete` cascade prune + `restore`
  non-rewire, `cleanup`/trash purge, exit codes.

---

## 18. Milestones

| # | Milestone | Definition of done |
|---|---|---|
| **M0** | **First CLI + read-only `index.html`** | `init`, `add`, `list`, `export` on K/N; an HTML page renders an `export` dump. First visible graph. |
| **M1** | **Core CLI** | Full read surface; lifecycle (`done/drop/reopen/wait`); `link/unlink`; concurrency protocol + stale-lock; write-path validation; `--export-on-success`. |
| **M2** | **Content, delete/restore, maintenance** | `set/get`, `write/append/read`; `delete`→trash + cascade prune; `restore`; `cleanup` (+`--delete-before`); `doctor`/`export --ics` as stubs. |
| **M3** | **Compose Desktop UI** | DAG render from `export`; detail panel; mutation via CLI with refresh; binary discovery; packaging. |
| **M4** | **Distribution & hardening** | Per-platform release binaries; UI installers; docs; concurrency/golden test coverage. |

---

## 19. Open / deferred decisions

- **`doctor` fix catalogue** — the deterministic A/B resolution per conflict type.
- **`export --ics`** internals.
- **Configurable datetime granularity** (day ↔ minute ↔ second).
- **`milestone: true`** optional flag (derived-done aggregator) — recover only if a real need
  appears.
- **First-class thread/project nodes** — graduate from labels only if parallel workstreams
  explode.
- **Web/mobile client** via a Ktor shim fronting the CLI.
- **`priority` in the `list` row** (currently omitted to keep rows quiet).
- **Archive purge policy** beyond `--include-archive`.

---

## 20. Glossary

The canonical vocabulary is **`docs/DOMAIN_LANGUAGE.md`** (grouped by layer, with the
cross-layer mappings and renaming tiers); this list is the short form.

- **task** — one markdown file; the unit of work. Its graph vertex is a **node** (internal
  DAG vocabulary only); its rendering in the UI is a **card**.
- **thread** — optional grouping label (not a task).
- **ready** — open, every `depends` done, not deferred (computed).
- **blocked** — open with at least one task still blocking it (computed).
- **blocker** — an upstream task another task depends on. UI direction labels: "blocked by"
  (upstream) / "blocker of" (downstream); *dependency/depends/dependents* stay off UI surfaces.
- **blockers** (computed) — the unmet subset of `depends`: the tasks blocking one right now.
- **dependents / `--blocked-by`** — tasks that depend on a given task (downstream).
- **`--blocking`** — the tasks blocking a given task (upstream, = its `depends`).
- **external requirement** — the outside-the-graph thing a `waiting` task needs (`waiting_on`
  free text); never modelled as a placeholder task.
- **deferred** — has a `defer` date in the future (computed); a time gate, not a requirement.
- **resurfaced** — a waiting task whose `defer` has elapsed; due for a decision (computed).
- **archive** — flat store for closed (done/dropped) tasks.
- **trash** — flat store for deleted tasks; restorable.
- **the contract** — the `:contract` DTOs / `export` JSON shape shared by CLI and UI; field
  names mirror the stored vocabulary, never UI labels (ADR-008).