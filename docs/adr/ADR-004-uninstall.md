# ADR-004: Uninstall

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-01
**Follow-up ADRs:** —

---

## Context

ADR-001 made the CLI installable in two tiers — a **global** binary on a PATH directory
(`~/.local/bin` on Unix, `%LOCALAPPDATA%\Programs\taskkling` on Windows) and, alongside it, a
**per-project** copy under a workspace's `.taskkling/bin/`. ADR-002 closed the install → update
loop. Removal was left unspecified, which is a real gap: a tool that can be installed and updated
but not cleanly removed strands the binary and, on Windows, a `PATH` entry the installer wrote into
the user's registry.

Three forces make uninstall non-trivial rather than a one-line `rm`:

1. **The binary is co-located with irreplaceable user data.** A workspace's `.taskkling/` directory
   holds both the per-project binary (`bin/`) and the user's authored task graph (`tasks/`) plus
   config. A naive "remove taskkling" that deleted `.taskkling/` would destroy work that cannot be
   recovered from the tool itself.
2. **An OS asymmetry governs self-removal.** A process cannot delete its own running image on
   Windows — the executable file is locked for the life of the process — whereas Unix lets a
   running binary be `unlink`ed (the inode survives until the last handle closes). A binary asked to
   remove *itself* must therefore behave differently per OS; a binary asked to remove a *different*
   tier's copy faces no such lock.
3. **Install mutates shared Windows state.** The Windows installer appends its directory to the
   user's `PATH` registry value. A correct uninstall must invert exactly that, without dropping
   unrelated entries or changing the value's type.

The question: what does an uninstall remove by default, how is the user's task data protected from
accidental destruction, and how does a running binary remove itself on each OS.

---

## Decision

Add an interactive, tier-aware **`uninstall`** verb.

- **Scope.** By default it removes only what the installer created — the binary for the targeted
  tier and the `PATH` entry added on install — and **never** touches the task graph or config.
  Deleting the `.taskkling/` workspace requires an explicit **`--purge`**.
- **Consent.** It is interactive by default: it presents the removal choices and states their
  consequences (including how many tasks a purge would destroy). A `-y` flag runs the **safe scope**
  (binary + `PATH`) non-interactively; authored data is deleted only via `--purge`, so `--purge -y`
  is the single non-interactive form that destroys data and the destruction is explicit on the
  command line.
- **Tiers.** It resolves which binary it is acting on from the running executable's own path, with
  `--global` / `--local` to target a tier explicitly; removing the per-project copy also clears that
  tier's pinned-version stamp.
- **Self-removal, split by OS.** When the target is the running binary: Unix `unlink`s it directly;
  Windows renames the live executable to a `.old` sibling, removes the `PATH` entry immediately, and
  schedules the `.old` file for deletion on the next reboot via the OS delete-on-reboot facility.
  When the target is a *different* tier's copy, it is a plain delete on both systems.
- **Windows `PATH`.** De-entry rewrites the user `PATH` registry value preserving its type and every
  other entry.

---

## Rationale

The core insight is that the only irreplaceable thing in play is the user's task graph, not the
binary — a binary is reinstalled in one command, a deleted task history cannot be. So the safe
default is "remove what the installer added, never what the user authored," and any destruction of
authored data must be **opt-in and visible**: gated behind a distinct `--purge` flag rather than
reachable through a general `-y`. That keeps the verb scriptable (`-y` for the safe scope) while
making it structurally impossible for unattended automation to erase a task graph without saying
`--purge` out loud. Interactive-by-default with consequence reminders suits a destructive,
rarely-run operation.

The per-OS split is forced by the platform, not chosen: Windows locks a running image, so it cannot
delete itself in place — renaming is permitted, and the OS delete-on-reboot facility is the minimal
reliable way to clear the leftover. Unlike an in-place *update*, which has a subsequent invocation to
sweep its renamed predecessor (see ADR-002), an uninstall has no next run, so deferring the final
file removal to reboot is the natural cleanup. Removing the `PATH` entry immediately means the tool
is effectively gone from the user's environment the moment the command returns, even while the locked
file lingers until reboot. Inverting the installer's `PATH` edit while preserving the value type and
sibling entries is required precisely because install wrote into shared machine state.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Ship uninstall as a script, symmetric with the install scripts, instead of a verb | Removal belongs with the tool already on the user's PATH; a verb is discoverable and resolves its own tier from the running binary. The verb absorbs the self-lock handling, so a script buys nothing. |
| Remove everything (binary + PATH + workspace) by default behind a confirm prompt | A single mis-confirmed prompt — or a `--yes` in a script — destroys an irreplaceable task graph. Data deletion must be opt-in, not opt-out. |
| Remove only the binary, leave the PATH entry | Dangling PATH entries are exactly the install residue the verb exists to clean up. |
| Windows: spawn a detached helper that waits for the process to exit, then deletes the file immediately | More moving parts than delete-on-reboot for a marginal gain; immediate de-PATH already makes the tool "gone." Revisit if a lingering `.old` file proves a real problem. |
| Delete the running executable in place on Windows | Impossible — the image file is locked while the process runs. |

---

## Consequences

**Positive:**

- Removal is symmetric with install and update, discoverable as a verb, and tier-aware — a global
  uninstall never disturbs a project's pinned copy, and vice versa.
- The user's task graph is safe by construction: only an explicit `--purge` can delete it.
- Windows `PATH` state is left clean, with unrelated entries and the value type preserved.

**Negative / open:**

- On Windows a renamed `.old` file lingers until the next reboot — a cosmetic artifact, not a
  functional one (the tool is already off PATH). If this proves unacceptable, a detached-helper
  immediate-delete would supersede this in a later ADR.
- Interactive prompting introduces a mode the CLI did not previously have; it must be suppressed
  correctly under `-y` and in non-interactive contexts.
- `--purge` is irreversible by design.
