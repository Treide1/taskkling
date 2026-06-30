# Architecture Decision Records

This directory is an **append-only, feed-forward log** of architectural decisions.
Each `ADR-NNN-*.md` file records one decision: its context, the choice made, why,
and the consequences. The log is read forward — you never rewrite history, you
supersede it.

This record is self-contained. It depends on no other document and references no
external planning artifact. Anyone can understand the project's architectural
history from this directory alone.

## Core Rule: ADRs Are Immutable

Once an ADR is written, **its body is never edited.** Not to fix a decision, not to
reflect a change of mind, not to record that it was later reversed. The body is a
permanent snapshot of the reasoning at the moment the decision was made.

Only two header fields may ever change after writing:

- **Creation Date** — set once, on creation.
- **Follow-up ADRs** — a forward pointer that only ever *gains* references. When a
  later ADR revises or supersedes this one, add the later ADR's number here.

## Amend by Follow-up, Never by Edit

To correct, revise, or reverse a past decision:

1. Write a **new** ADR with the next sequential number.
2. In its **Context**, name the ADR(s) it revises or supersedes and why.
3. In the predecessor's **Follow-up ADRs** field, add the new ADR's number.
4. Leave the predecessor's body untouched and the file in place.

A decision's current status is *derived by reading forward*: an ADR with no
follow-ups is live; an ADR whose follow-up supersedes it has been replaced. The
chain, not an edited status line, is the source of truth.

## Conventions

- **Filename:** `ADR-NNN-kebab-case-title.md`, where `NNN` is zero-padded and
  monotonically increasing. Numbers are never reused, even for superseded ADRs.
- **One decision per ADR.** If you are deciding two separable things, write two ADRs.
- **New ADRs start from `TEMPLATE.md`** in this directory.
- **Scope-relative reasoning, not plan-relative.** State assumptions as
  first-principles constraints so the record stays valid as plans evolve.

## Creating a New ADR

1. Copy `TEMPLATE.md` to `ADR-NNN-your-title.md` with the next free number.
2. Fill in the body. Set the Creation Date. Leave Follow-up ADRs as `—`.
3. If it supersedes an earlier ADR, update that ADR's Follow-up ADRs field.
