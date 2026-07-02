# ADR-007: `update` `--global` / `--local` Tier Parity

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-01
**Follow-up ADRs:** —

---

## Context

ADR-001 established two install tiers — a **global** binary on a `PATH` directory and a **per-project**
copy under a workspace's `.taskkling/bin/`. ADR-002 gave the tool a self-replacing `update` verb, which
today resolves its tier *implicitly* from the running executable's own path: `taskkling update` (the
one on `PATH`) updates the global install, and `./taskkling update` (the project's wrapper) updates that
project's local-bin copy. ADR-004 then gave `uninstall` **explicit** `--global` / `--local` flags to
target a tier regardless of which binary happens to be running.

That leaves `update` and `uninstall` asymmetric, and leaves one case unreachable: repairing a local-bin
binary that is too broken — or built for the wrong architecture — to run `./taskkling update` itself. If
the local copy won't execute, there is no way to fix it from within its own tier. This ADR extends
ADR-002's `update` with the same explicit tier flags `uninstall` already has. It extends ADR-002; that
body is left untouched and its Follow-up field points here.

---

## Decision

Give `update` the tier-targeting flags `uninstall` already has:

- **`--global` / `--local`, mutually exclusive**, mirroring `uninstall`. With no flag, behaviour is
  unchanged: the tier is resolved from the running executable's path.
- **Update-only, never install.** If the targeted tier has no binary present, `update` **errors** with
  a hint — `--global` with no global install → "no global install found; install it with the install
  script"; `--local` with no local-bin copy → "no local-bin install here; run `init --local-bin` first".
  `update` must never silently create an install in a new location.
- **Self vs other, forced by the OS.** When the resolved/targeted binary **is** the running one, use the
  existing self-replace split (Unix atomic rename; Windows rename-then-swap with deferred `.old`
  cleanup). When it is a **different** tier's copy — not running, therefore unlocked — use a plain
  overwrite via a new `installOtherExecutable(path, bytes)` primitive, mirroring uninstall's
  `uninstallOtherBinary`.
- **`--check` is tier-agnostic.** It reports latest-vs-running and touches no binary, so combining it
  with `--global` / `--local` is a **usage error (exit 2)**, not a silent no-op.
- **`--local` re-stamps the pinned version.** Updating a local-bin copy re-writes its sibling
  `.taskkling/bin/.version`, which the existing self-replace already does by path when the target lives
  under a local-bin.

---

## Rationale

The case for the flags is mostly **consistency with `uninstall`**: both tiers are already reachable
today by choosing the invocation, so `--global` / `--local` add symmetry rather than new reach — plus
one genuinely unique capability. That unique case is repairing a local-bin binary too broken or
wrong-architecture to run `./taskkling` at all, by driving it from the working **global** binary:
`taskkling update --local`. Asset resolution is unchanged either way — the same host triple, since you
cannot retarget another OS/arch from one machine — so the only thing the flag changes is *which file on
this host* gets the freshly-downloaded, checksum-verified bytes.

Refusing to install into a missing tier keeps `update`'s contract narrow: it **repairs and upgrades
existing installs; it never spawns new ones**. Creating an install is the job of `install.sh` /
`install.ps1` (global) and `init --local-bin` (local); letting `update --global` create a global
install would both overlap those and turn a typo into a stray second install in the wrong place. The
missing-tier error enforces that boundary. Rejecting `--check` + a tier flag as a usage error follows
from `--check` being inherently about the running binary versus the latest release — a tier is
meaningless there, and a meaningless flag should be a loud error, not a silent no-op.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Leave `update` implicit-only — pick the tier by which binary you invoke | Cannot repair a local-bin copy that won't run, and stays asymmetric with `uninstall`'s explicit flags for no principled reason. |
| Let `--global` / `--local` create an install in a tier that has none (install-if-absent) | Overlaps `install.sh` / `init --local-bin` and turns a typo into a stray install in the wrong location; `update` stays strictly update-only. |
| Accept `--check` with a tier flag and silently ignore the flag | `--check` is about the running binary vs latest; a tier flag there is meaningless. A silent no-op hides a user's mistaken mental model — a usage error surfaces it. |

---

## Consequences

**Positive:**

- `update` and `uninstall` share one tier-targeting model (`--global` / `--local`, resolve-from-running
  default), so the two verbs are learned once.
- A broken or wrong-arch local-bin copy can be repaired from the working global binary — the one thing
  implicit resolution cannot do.
- `update`'s contract stays "repair/upgrade existing installs, never create new ones", enforced by the
  missing-tier error rather than left to convention.

**Negative / open:**

- Adds an `installOtherExecutable` primitive and a self-vs-other decision to `update`, widening its
  surface and its QA-gated (byte-write / self-replace) paths.
- Cross-architecture repair is still impossible from a single machine — the same host triple applies to
  every tier — so `update --local` can fix a same-arch corruption but not a local-bin binary built for a
  different OS/arch.
