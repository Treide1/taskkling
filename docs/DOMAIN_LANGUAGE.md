# taskkling — domain language

This document is the canonical vocabulary of taskkling. Every surface — code identifiers,
docs prose, UI labels, CLI help and output — is aligned against it: when a surface and this
document disagree on wording, this document is the contract — fix the surface, or amend this
document deliberately. Structural semantics stay specified in PRD §8/§11/§12; visual language
in `docs/DESIGN.md`; this document owns the *words*.

Two rules produce the whole document:

1. **One name per concept per layer.** No synonyms within a layer; a concept crossing a
   layer boundary may change its name only at that boundary, and the mapping is recorded here.
2. **Renames are tiered.** Some names are user data and frozen; some are wire contract and
   ADR-gated; the rest are free. See §9.

---

## 1. The ladder — one entity, three layers

The core entity has exactly one name per layer:

| layer | name | meaning |
|-----------------|--------|---------|
| domain | **task** | one markdown file; the unit of work. THE word in docs prose, CLI help/output, and the detail panel. |
| DAG abstraction | **node** | the task seen as a vertex of the dependency graph. Reserved for graph-math contexts: layout, layering, cycle detection, edge geometry. Never user-facing. |
| UI | **card** | the visual representation of one task on the canvas. |

One task ↔ one node (its graph view) ↔ one card (its pixels). "Node" in domain prose and
"task" in layout math are both wording bugs.

## 2. Domain terms (stored)

Stored fields are human-decided and written to disk; they are never derived.

- **task** — see §1.
- **id** — immutable handle (`t-` + 4 alphanumerics); the only thing edges reference.
- **title** — one-line summary.
- **body** — the markdown below the frontmatter; the task's brief.
- **thread** — optional grouping label. A label, not a task: threads have no status and no
  edges.
- **status** — stored lifecycle state: `open | waiting | done | dropped`. Human-decided,
  never computed.
- **priority** — `low | normal | high`.
- **due** — deadline; drives urgency/overdue only. **Due never gates readiness.**
- **defer** — "not before" datetime; suppresses readiness until it passes.
- **waiting_on** — free text naming the **external requirement** a waiting task needs (§4).
- **created / closed** — lifecycle timestamps; `closed` stamps leaving the active set.
- **frontmatter** — the YAML block holding the stored fields of one task file.

## 3. Relationships — the blocker vocabulary

The dependency relation has a stored side and a spoken side:

- **depends** (stored key / contract field) — the list of upstream task ids that must be
  `done` before this task is ready.
- **blocker** (role) — an upstream task that another task depends on. A blocker *of* X.
- **blocked by** (UI/prose, upstream direction) — X is blocked by its `depends`.
- **blocker of** (UI/prose, downstream direction) — Y is a blocker of everything that
  depends on Y.
- **dependents** (contract field, computed) — ids of tasks whose `depends` contains this
  task; the downstream inverse edge. UI: **"blocker of"**.
- **blockers** (contract field, computed) — the *unmet* subset of `depends`: the tasks
  blocking this one **right now** (a `done` upstream task no longer blocks, whether it is
  still active or already swept to the archive — graph-neutral archive, ADR-014). A
  dangling reference counts as unmet.
- **upstream / downstream** — direction words: from blocker toward dependent. Edges point
  upstream → downstream (left → right in the graph).

Surface rules:

- **UI surfaces speak blocker vocabulary only** — "blocked by", "blocker of". The words
  *dependency / depends / dependents* do not appear in the UI.
- **Stored fields and the contract speak the stored vocabulary** (`depends`, `dependents`;
  `blockers` is kept as the literal truth "blocking right now") — see §6 and ADR-008.
- CLI relationship flags speak blocker vocabulary and read as English:
  `list --blocking <id>` = the tasks blocking `<id>` (upstream);
  `list --blocked-by <id>` = the tasks blocked by `<id>` (downstream).

## 4. Gating — why a task isn't actionable

A task can be gated three ways, and the three are distinct concepts:

| gate | mechanism | vocabulary |
|-------------|--------------------------|------------|
| by tasks | some `depends` not `done` | **blocked** (computed: `open` ∧ unmet depends) |
| by the world | human set `status: waiting` | **waiting**, on an **external requirement** (`waiting_on`) |
| by time | `defer` in the future | **deferred** (computed; status-independent) |

- **ready** (computed) — `open` ∧ no unmet depends ∧ not deferred. The **ready set** is
  what's actionable now.
- **external requirement** — the outside-the-graph thing a waiting task needs: a reply, an
  approval, a delivery. Free text, never a task id. **Anti-pattern: never model an external
  requirement as a placeholder task** — tasks are units of *your* work; the world's moves
  live in `waiting_on`.
- **deferral** is a time gate, not a requirement: it names no blocker and no external
  requirement, it only says "not before".
- **resurfaced** (computed) — `waiting` ∧ `defer` elapsed: the tickler is due for a human
  decision.
- **overdue** (computed) — `due` passed and the task isn't closed. Urgency, not gating —
  an overdue task can be ready.

## 5. DAG abstraction

Graph-math vocabulary, internal only (§1 ladder):

- **node / edge** — vertex; directed edge from blocker to dependent (upstream → downstream).
- **acyclicity** — the graph is a DAG; `add`/`link` are cycle-checked.
- **dangling reference** — a `depends` id with no active *or archived* task file behind it
  (ADR-014); counts as unmet (blocks) until resolved, and is rejected on the write path.
- **layer / column** — layout depth of a node (longest upstream chain); rendered as a column.
- **ready set** — see §4; the set the whole tool exists to compute.

## 6. Contract / export

- **export** — the full JSON projection of the workspace (stored + computed), produced by
  `taskkling export`; the only thing the UI reads.
- **stored fields vs computed attributes** — stored is human-decided and on disk (§2);
  computed is derived at read time and **never stored** (`ready`, `blocked`, `deferred`,
  `overdue`, `resurfaced`, `blockers`, `dependents`).
- **the contract** — the `:contract` DTOs / export JSON shape shared by CLI (writer) and UI
  (reader).
- **Layer boundary (ADR-008):** contract field names mirror the stored vocabulary
  (`depends`, `waitingOn`, `dependents`; `blockers` for the unmet subset); the UI translates
  to its labels at render time (§7). A contract rename is a wire change: one ADR per change,
  golden tests + UI updated in the same commit.

## 7. UI model

Vocabulary of the rendered surface (visual spec: `docs/DESIGN.md`):

- **card** — see §1. **canvas** — the pannable graph surface. **edge** — the drawn S-curve
  with an arrowhead at the dependent end.
- **detail panel** — the right-hand inspector for the selected task.
- **primary state** — the one presentation state a card renders in (precedence in DESIGN §2);
  a pure presentation choice, not a domain concept.
- **state pill / tag / flag chip / count chip / legend** — per DESIGN §8/§9.
- **selection / dimming / pan** — focus by dimming, never rearranging.

Pinning & focus:

- **Star(A)** — internal-only notation: the star-topology subgraph centred on node A — A,
  its upstream neighbours (`depends`), its downstream neighbours (`dependents`), and the
  edges between them. Graph-math vocabulary (§5); never user-facing.
- **highlighted** — the node whose Star is visually prominent while everything else dims.
  Exactly one highlight source: the pinned node if any, else the selected node.
- **pinned** — the node that stays highlighted until unpinned. Single pin; pinning another
  node transfers it. Session-only UI state — never persisted to task files (the CLI stays
  the single write path).
- **selected** — the node whose content the detail panel shows. Selection and pin are
  orthogonal: a selected node outside Star(pinned) keeps its selection ring at the dimmed
  alpha.

Label mapping (contract → UI), the render-time translation of §3/§4:

| contract | UI label | rendering rule |
|---------------------|--------------------------|----------------|
| `depends` | **blocked by** | one list of all upstream ids; ids whose task is `done` render *resolved* (muted + strikethrough), unmet ones render hot. No separate unmet list. |
| `computed.blockers` | — | not its own section; it is the *unmet* styling inside "blocked by". |
| `computed.dependents` | **blocker of** | downstream ids. |
| `waitingOn` | **external requirement** | free text field. |

## 8. Storage & lifecycle

- **workspace** — a `.taskkling/` config + its `tasks_dir` of task files.
- **active set** — tasks in `tasks_dir`; what `list`/`export` show by default.
- **archive** — flat store for closed (`done`/`dropped`) tasks, populated by the `cleanup`
  sweep. **trash** — flat store for deleted tasks; restorable. `delete` is a move to trash
  plus a **cascade prune** of the id from dependents' `depends`; `restore` moves back and
  reports non-rewired edges.

## 9. Renaming rules (the tiers)

| tier | what | rule |
|------------|------|------|
| **FROZEN** | frontmatter keys and stored enum values (`depends`, `waiting_on`, `status: waiting`, …) | on-disk user data. Never renamed by alignment work; a desirable rename is filed as a proposal task for the human. |
| **ADR-gated** | contract field names (the export JSON shape) | wire contract. One ADR per change; golden tests + UI in the same commit (ADR-008). |
| **FREE** | internal code names, docs prose, UI labels, CLI help/output wording | keep aligned with this document autonomously. |

## 10. CLI surface

- **verb** — a subcommand (`add`, `get`, `set`, `done`, `wait`, `export`, …). **Reads** never
  take the lock; **mutations** follow the write protocol (PRD §7).
- Help/output wording: "task" per §1 (never "node"); relationship flags per §3; `wait --on`
  takes the external requirement text (§4).
- **wrapper / binary discovery / tier (global vs local)** — installation vocabulary, defined
  in ADR-001/002/007; out of scope here beyond the names.
