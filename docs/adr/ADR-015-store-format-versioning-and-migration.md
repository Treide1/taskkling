# ADR-015: Store format versioning, one-shot migration, and round-trip preservation

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-10
**Follow-up ADRs:** —

---

## Context

Frontmatter keys and stored enum values are FROZEN-tier user data
(docs/DOMAIN_LANGUAGE.md §9): alignment work never renames them, and a
desirable rename is filed as a proposal for the human. The v2 task structure
(ADR-012/013/014) is that human-approved break: `thread` gives way to
`milestone` + `tags`, `depends` changes encoding, the trash relocates, and a
registry file appears. Existing stores must cross this gap exactly once, and
the format must be able to evolve again later without repeating the ad-hoc
reasoning. A second, latent defect surfaces with the same theme: the current
reader drops unknown frontmatter keys and the writer re-emits only known
fields, so ANY mutation silently deletes hand-added or newer-version keys
(design/bench/RESULTS.md §5) — the promised "extensible schema" does not
actually survive a round trip.

The decision needed: how do stores declare their format, how do they move
between formats, and what does the tool guarantee about data it does not
understand?

---

## Decision

1. **Format marker.** The workspace config (`.taskkling/config.toml`) carries
   `format = 2`. A missing key means format 1. A tool refuses to operate on a
   store whose format is NEWER than it understands, and refuses to MUTATE a
   store whose format is older — pointing to `migrate` in both cases (reads of
   older formats stay best-effort).
2. **One-shot migration.** `taskkling migrate` converts a store in place, once:
   v1 → v2 rewrites every task file to the v2 schema, relocates
   `tasks/trash/`, seeds `tasks/_milestones.md`, writes the marker and
   `.taskkling/.gitignore`. It first copies the store to
   `.taskkling/backup-pre-migrate/` and refuses to run on an already-v2 store.
   By default every `thread` value becomes a tag; `--milestones <a,b,c>`
   promotes the named values to registered milestones (in the given order)
   instead.
3. **Round-trip preservation.** From v2 on, unknown frontmatter keys are
   preserved verbatim across every read-modify-write, emitted after the known
   keys in their original order. Known keys are emitted in a fixed canonical
   order. This is the schema's extensibility contract.

---

## Rationale

A one-shot migration keeps the codebase honest: after `migrate`, exactly one
format exists at runtime, so parsers, writers, tests, and docs describe one
truth. The alternative — a dual-format reader kept forever — is permanent
complexity purchased to avoid a single explicit command, and it silently
defers the moment of truth to whichever code path meets an old file last.
Pre-1.0 with a handful of known stores, the explicit step costs nearly nothing;
the marker makes it safe by turning "wrong binary meets wrong store" from
silent misreads into a refusal with instructions. (One residual hazard is
inherent: binaries OLDER than the marker convention ignore unknown config keys
by lenient design and cannot be made to refuse; pointing a pre-v2 binary at a
v2 store misparses block-list `depends` as empty. The marker cannot protect
the past, only the future — documented, accepted.)

The backup-before-migrate default exists because the migration's natural
audience includes the one store that provably is NOT in git (the dogfood
store); for committed stores the backup is redundant with git and cheap enough
not to special-case.

Threads default to tags because it is the lossless direction: every thread
value survives as a filterable label, nothing is guessed about chronology, and
promoting a tag to a milestone later is a cheap follow-up edit. Guessing which
threads were "really" milestones (pattern-matching `v*`) would bake a heuristic
into a one-shot destructive step.

Round-trip preservation is what "extensible schema" must mean in a format users
own: the tool is a guest in these files. Preserving unknown keys makes future
format additions backward-safe (a v2.1 tool's new key survives a v2.0 tool's
edit), makes hand annotation safe, and costs only carrying a small ordered map
through the parse-render cycle.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Dual-read forever (reader accepts v1+v2, writer emits v2) | Permanent parser complexity and ambiguity (mixed-format stores possible indefinitely); FROZEN-tier semantics of old keys would haunt every future change. |
| Big-bang script outside the tool, no `migrate` verb | Every existing store is a manual, error-prone conversion; the tool gains a format marker anyway, so the verb is the marker's natural counterpart. |
| Per-file format marker in frontmatter | N stamps that can disagree with each other; format is a store-level property (registry file, trash location are not per-file facts). |
| Auto-migrate on first touch | A read-only-looking command mutating every file violates least surprise, breaks concurrent old binaries mid-flight, and hides the one moment a backup matters. |
| Keep dropping unknown keys (status quo) | Confirmed silent data loss; contradicts the schema's stated extensibility; forecloses safe minor-version evolution. |

---

## Consequences

**Positive:** exactly one live format at runtime; safe, explicit, reversible
(backup + git) upgrade with a lossless default; future format bumps have a
paved road (bump marker, extend `migrate`); user and future-version frontmatter
survives every mutation.

**Negative / open:** pre-marker binaries cannot be stopped from misreading v2
stores (documented hazard; the update notifier and short binary half-life
mitigate); `migrate` is a new verb to test to destruction (it rewrites every
file — the implementation milestone gates on adversarial tests and a dogfood
dry run); the backup dir must not be scanned by id minting or reads (it lives
under `.taskkling/`, which is already outside the tasks dir); `format = 2`
joins config keys that v1's lenient config parser ignores rather than rejects.
