# ADR-017: `init --demo-mode` — a self-contained sandbox workspace

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-11
**Follow-up ADRs:** —

---

## Context

Developing the UI (or the CLI) from an agent worktree has no usable data path.
A bare `gradlew :ui:run` resolves everything from the cwd: binary discovery
falls through to whatever `taskkling` is on `PATH`, and that CLI's workspace
walk-up finds no `.taskkling/` (it is git-ignored, so fresh worktrees never
carry one) and exits 2 — the UI renders a permanent error state. Running from
the main checkout instead resolves to the LIVE dogfood store, where the UI's
mutation buttons write into the same backlog concurrent agents are driving.
Both modes are wrong: one is dead, the other is live-fire. Ad-hoc scratch
workspaces (the v0.5 QA pattern) work but are hand-built every time.

The need: one command a worktree-creation hook (or a curious human) can run
that yields a real, fully-formed, freely-mutable task graph — isolated from
any real store by construction.

---

## Decision

`taskkling init --demo-mode` (short `-dm`) scaffolds the workspace as usual
and seeds a representative demo backlog through the normal write path. A
demo-initialized workspace keeps its tasks INSIDE the meta dir
(`tasks_dir = ".taskkling/tasks"` in the freshly written config); seeding only
ever happens when the workspace holds zero known tasks (active + archive +
trash), otherwise init prints a skip notice and exits 0.

---

## Rationale

The core insight: the cheapest credible sandbox is a *real* workspace, not a
mock. Seeding ordinary task files via the ordinary ops (`add`, `done`, `drop`,
`wait`) means the CLI, the export contract, the lock, and the UI all run their
production code paths — nothing demo-specific exists downstream of `init`, so
there is no second data model to maintain (the UI stays a pure CLI client,
PRD §6.1).

Keeping the demo's `tasks_dir` inside `.taskkling/` makes the standard ignore
entry cover the whole sandbox: git status stays clean in any repo worktree,
and `rm -rf .taskkling/` removes every trace. The zero-tasks guard plus init's
existing idempotency make the command safe as a fire-and-forget hook — a
re-run (or a run in a workspace someone started using for real) can never dump
demo data into it. The dataset is built to show every reachable primary state
at once with dates relative to seed time, so a demo opened weeks later still
looks alive.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| UI-side mock mode (bundled `ExportDto` fixture, no subprocess) | Exercises none of the real CLI/contract path; mutations need a parallel in-memory model, eroding the single-write-path principle; helps only the UI, not CLI dev. |
| Gradle task seeding a scratch workspace under `build/` | Couples the dev loop to Gradle and to having a built/installed CLI at build time; invisible to non-Gradle consumers (a human in a scratch dir, the worktree hook). |
| Committed fixture workspace in the repo | `.taskkling/` is git-ignored by design; carving exceptions invites accidental commits of real-looking task files and gives static dates that go stale. |

---

## Consequences

**Positive:** worktree hooks get a one-command sandbox; `:ui:run` in a
demo-initialized worktree shows a full graph with zero risk to the live store;
doubles as the "first look" onboarding path for new users; the seed dataset is
a ready-made fixture for visual QA.

**Negative / open:** the demo layout deviates from the documented default
(`tasks/` at the root) — tools that assume the default layout must honor
`tasks_dir` (init itself was taught to, t-wqwt); the seed dataset is
maintained by hand and must gain a state whenever the model gains one, or the
demo silently under-represents the product.
