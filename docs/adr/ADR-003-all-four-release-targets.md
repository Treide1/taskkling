# ADR-003: Build and Publish All Four Release Targets

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-06-30
**Follow-up ADRs:** ADR-016

---

## Context

ADR-001 decided to ship all four `:cli` targets (linux-x64, macos-arm64, macos-x64, windows-x64),
but in its Targets note and a "macOS-x64 build gap" consequence it recorded that CI and
`release.yml` built only **three**, with `macos-x64` an unfinished, separately-tracked
release-pipeline task. That transitional note was already inconsistent with reality when ADR-001
was written: both `ci.yml` and `release.yml` include a `MacosX64` row in their build matrices, and
`install.sh` resolves and serves the `macos-x64` asset. This ADR corrects that point of ADR-001;
ADR-001's body is left untouched (per the immutability rule) and its Follow-up ADRs field points
here.

---

## Decision

All four release targets — `linux-x64`, `macos-arm64`, `macos-x64`, `windows-x64` — are built and
published by both CI (`ci.yml`) and the release pipeline (`release.yml`); macOS Intel is served
alongside Apple silicon. ADR-001's "three targets today / macos-x64 gap" note no longer holds.

---

## Rationale

The decision in ADR-001 was always "ship all four"; only its implementation-status note lagged
behind the code. The workflows and `install.sh` already realise the four-target build, so the
record is brought into line with the shipped reality. Per the append-only convention an ADR body is
a permanent snapshot, so the stale note is not edited away — it is superseded by this follow-up,
and a reader derives the current state by reading the chain forward.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Edit ADR-001's body to fix the stale note | Violates the immutability rule — ADR bodies are permanent snapshots; corrections are recorded as follow-up ADRs. |
| Leave the stale note uncorrected | The record would contradict the shipped workflows and `install.sh`, misleading future readers. |

---

## Consequences

**Positive:** the ADR record matches the actual build matrix; macOS Intel coverage is unambiguous,
and the correction is traceable through ADR-001 → ADR-003.

**Negative / open:** none beyond the documentation reconciliation itself. If target coverage
changes again (e.g. adding `linux-arm64`), a further ADR supersedes this one.
