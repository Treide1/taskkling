# ADR-008: Contract keeps stored vocabulary; the UI translates at render time

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-05
**Follow-up ADRs:** —

---

## Context

taskkling's vocabulary was codified into a canonical language document
(`docs/DOMAIN_LANGUAGE.md`). Two vocabularies coexist for the dependency relation: the
stored one (`depends`, `dependents` — mirroring the on-disk frontmatter keys, which are
frozen user data) and the spoken/UI one (blocker vocabulary: "blocked by", "blocker of" —
adopted as the only relationship language on UI surfaces, with *dependency/depends/
dependents* purged from labels).

The export JSON contract sits exactly on the boundary: the CLI writes it, the UI reads it.
Its fields today are `depends` (all upstream ids), `computed.blockers` (the unmet subset),
and `computed.dependents` (downstream ids). The question: which language does the wire
format speak — and does codifying the language force renames (e.g.
`computed.blockers → unmetDepends` for a pure stored-vocabulary wire, or
`computed.dependents → blocking` for a pure blocker-vocabulary wire)?

Any contract rename is a breaking wire change for every consumer, costs golden-test and UI
churn, and — per this repo's contract discipline — requires its own ADR with tests and UI
updated in the same commit.

---

## Decision

The contract mirrors the stored vocabulary and does not follow UI wording: `depends` and
`computed.dependents` keep the stored family's names, `computed.blockers` keeps its name as
the literal statement "the tasks blocking this one right now", and the UI translates to its
own labels at render time. **No field is renamed.** Each future contract change remains
ADR-gated, one ADR per change, golden tests + UI updated in the same commit.

---

## Rationale

The core insight is that the wire format's job is fidelity to the data, not to the
presentation. Stored fields are frozen user data; a contract that mirrors them stays
self-describing against the files it serializes (`depends` on disk is `depends` on the
wire). UI labels are presentation and legitimately drift with design passes — binding the
wire format to them would convert every future label change into a breaking wire change.

Every current field name is also *correct* in the codified language: `depends`/`dependents`
are the stored relation's two directions, and `blockers` is precisely the set of tasks
blocking right now (a `done` upstream task no longer blocks, so "unmet depends" and
"current blockers" are the same set — the existing name already says so in the spoken
vocabulary). A rename in either direction would trade zero semantic gain for a breaking
change across CLI, golden tests, and UI.

The translation cost is one small mapping table at the UI boundary, recorded in
`docs/DOMAIN_LANGUAGE.md` §7 — cheap, explicit, and in the one component whose job is
rendering.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Pure stored-vocabulary wire (`computed.blockers → unmetDepends`) | Breaking change for zero semantic gain; "blockers" already names that set correctly; the one place wire and UI meet would then disagree with the UI's spoken vocabulary everywhere. |
| Pure blocker-vocabulary wire (`computed.dependents → blocking`, etc.) | Breaks the contract↔frontmatter mirror for stored fields; couples the wire format to presentation wording, so future label changes become wire changes. |
| Rename lazily whenever a field "feels off" | Exactly the drift a language document exists to prevent; contract changes must stay deliberate, ADR-gated events. |

---

## Consequences

**Positive:** zero wire churn from the language codification — existing exports, golden
tests, and consumers stay valid; the contract remains self-describing against the on-disk
format; UI wording can evolve freely without touching the wire.

**Negative / open:** readers of raw export JSON meet `dependents` where the UI says
"blocker of" — the mapping table in `docs/DOMAIN_LANGUAGE.md` §7 is the required bridge.
If a future client needs the UI vocabulary on the wire, that is a new deliberate contract
change: a follow-up ADR, golden tests, and UI in the same commit.
