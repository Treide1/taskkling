# ADR-005: Update-Notifier Surface and Network Mechanism

<!-- IMMUTABLE: Do not edit the body of this ADR once it is written.
     Corrections and superseding decisions go into a NEW, higher-numbered ADR.
     Only the two header fields below may be updated, and the Follow-up ADRs
     field only ever gains references — it never loses them. -->

**Creation Date:** 2026-07-01
**Follow-up ADRs:** —

---

## Context

ADR-002's third part introduced an opt-in `update_check` flag — a best-effort, cached,
silent-on-failure notifier that prints that a newer version is available and never installs it. It
was framed as the project's first sanctioned network call and the single narrow exception to the
default "no auto-update, no telemetry, no network calls" posture. ADR-002 fixed that the check is
opt-in, cached, and notify-only, but left three things unsettled that must be decided before it can
ship. This ADR settles them and thereby revises the notifier facet of ADR-002; ADR-002's body is
left untouched and its Follow-up field points here.

The three open points, and the forces on each:

1. **Where the notification surfaces.** The tool emits machine-readable output — a JSON export and
   field dumps consumed by other programs. A notification line mixed into those would corrupt them,
   so the notifier cannot fire indiscriminately on "any command."
2. **How the binary makes the request.** The CLI is a single static Kotlin/Native binary across four
   targets (Linux x64, macOS arm64 + x64, Windows x64) with no runtime and **no network code today**
   — so "make an HTTPS request" is a cross-target build decision, not a library import. And because
   the default posture is no network calls, a check wired to fire by default (for example on every
   `--version`, which scripts and CI scrape) would both violate that posture and leak usage from
   automation while slowing a command meant to be instant.
3. **Where the opt-in flag and cache live.** Configuration to date is per-workspace, but the binary
   is most often run as an installed global tool from outside any workspace, where a per-workspace
   flag is unreadable.

---

## Decision

Make the opt-in update check the project's single sanctioned network call, and pin its surface,
transport, and configuration:

- **Surface.** When enabled, the "newer version available" line is printed only by `taskkling
  --version` and by an explicit `taskkling update --check`. It never appears on listing, reading, or
  export/JSON output. With the flag off (the default), `--version` is purely local and offline.
- **Consent and freshness.** The check is gated by an opt-in `update_check` flag (default off),
  best-effort, cached for ~24 h, and silent on any failure; it only notifies, never installs.
  `update --check` is an explicit, always-on one-shot — it ignores the flag, because invoking it is
  itself the consent.
- **Transport.** An in-process HTTP client (ktor) with a per-OS engine — a libcurl-backed engine on
  Linux, the system URL-loading stack on macOS, the system HTTP stack on Windows — selected through
  the same `expect`/`actual` split the binary already uses for OS-specific calls. The request
  carries a `User-Agent` header (the GitHub API rejects requests without one).
- **Version lookup.** Query the GitHub Releases API for the latest release and compare its tag to the
  binary's compiled-in version. Downloading a *named* version for an actual update needs no lookup —
  that asset URL is constructed directly.
- **Configuration.** Introduce a user-level config plus a sibling user cache directory (for the ~24 h
  timestamp) so the flag is honoured for the global binary anywhere; a workspace config, when
  present, overrides it.

---

## Rationale

The lead insight is that the check is a *consented courtesy*, so every choice biases toward "off,
quiet, and out of the machine's way." Confining the notification to `--version` and `update --check`
— the two places a human is explicitly asking about versions — keeps it away from machine-readable
output entirely and out of the hot path of scripted commands; pinning it there, rather than a broad
"any command," is the safe and predictable resolution of ADR-002's notifier. Gating on an opt-in
flag that defaults off preserves the no-network-by-default posture, and the ~24 h cache with
silent-fail keeps the check from ever becoming a latency or noise tax. Letting `update --check`
ignore the flag is consistent: typing it *is* the request.

An in-process client keeps the fetch and its error handling inside the binary rather than shelling
out to whatever `curl`/`wget`/PowerShell happens to exist on the host, buying uniform behaviour and
typed failures across all four targets — at the cost of a per-OS engine and the build infrastructure
to back it (notably a libcurl link dependency on Linux). For the lookup, the Releases API returns the
latest tag as a documented field, which is sturdier than scraping a redirect; the per-IP rate limit
that comes with it is rendered a non-issue for any single user by the 24 h cache. A global tool needs
a configuration home that exists independent of any workspace, so a user-level config is required;
letting a workspace config override it preserves the existing per-project model where a repository
can pin its own behaviour.

---

## Alternatives Considered

| Alternative | Reason not chosen |
|---|---|
| Notify after any command (a broad reading of ADR-002's notifier) | Would have to be scrubbed from every machine-readable code path; surfacing only on `--version` / `update --check` is simpler and cannot leak into JSON. |
| Check by default on `--version`, treating the invocation as consent | `--version` is scraped by scripts and CI; a default network call there leaks usage, adds latency, and breaks the no-network-by-default posture. |
| Shell out to `curl` / `wget` / PowerShell for the request | No new link dependency, but behaviour and error handling vary by whatever is installed on the host; an in-process client gives uniform, typed results across targets. |
| Hand-write per-OS HTTP via platform APIs (WinHTTP / NSURLSession / libcurl) directly | Maximally dependency-free, but three separate bindings to build and maintain; one client with per-OS engines is less surface for the same result. |
| Resolve the latest version by following the `releases/latest` redirect | Avoids the API rate limit and JSON parsing, but depends on an undocumented redirect shape; the API tag field is a stable contract and the 24 h cache neutralises the rate limit. |
| Keep `update_check` in the per-workspace config only | The binary usually runs as a global tool outside any workspace, where a per-workspace flag is unreadable, so the check would silently never run. |

---

## Consequences

**Positive:**

- The check is consented, quiet, and confined to two human-facing surfaces; machine output stays
  clean and the default "no network calls" posture is preserved.
- A single in-process client yields uniform fetch and error behaviour across all four targets.
- The flag works for the global binary anywhere, with per-workspace override retained.

**Negative / open:**

- This introduces the project's first network call and its first network dependency; the PRD's "no
  network calls" wording must be amended to record this one opt-in exception.
- The HTTP client adds a per-OS engine matrix and a Linux libcurl link/build dependency to a binary
  that previously had no network code — new build and CI surface.
- The Releases API is rate-limited per IP; the 24 h cache keeps a single user well clear, but shared
  egress (CI, large NATs) could be throttled, which silent-fail degrades to "no notification."
- A user-level config adds a second configuration source and a precedence rule (workspace over user)
  that the config loader must implement consistently.
