# Releasing

How a `vX.Y.Z` release is cut and verified. The pipeline is wire-only
(`.github/workflows/release.yml`): pushing a version tag runs the test suites, builds the
four native binaries, checksums them, and publishes them with both install scripts to the
project's public GitHub Releases. Tagging is human-owned — no tag is cut by automation.

## Pre-cut

Run before tagging; each catches state the pipeline's own guard cannot:

1. **Feature branches are merged** — `git log main..<branch>` is empty for every branch.
   A non-empty range strands commits, so the release silently omits them.
2. **`main` matches the remote** — clean tree, not behind `origin/main` (ahead is expected;
   those are the commits shipping).
3. **`version=` in `gradle.properties` equals the tag's `X.Y.Z`** — the publish job asserts
   this and fails the run on a mismatch, after the tag name is already spent.

Then bump `gradle.properties`, commit, push `main`, and push the tag `vX.Y.Z`. The tag runs
`test → build → publish`; a failing suite blocks the publish, so a tag can never ship an
untested commit.

## Post-publish

Confirm the release actually landed and is downloadable — fetch the published URLs
directly, not any local build output.

For `BASE = https://github.com/Treide1/taskkling/releases/latest/download`:

1. **HEAD 200 on all 7 assets** — the four binaries (`taskkling-linux-x64`,
   `taskkling-macos-arm64`, `taskkling-macos-x64`, `taskkling-windows-x64.exe`) plus
   `SHA256SUMS`, `install.sh`, `install.ps1`. A 200 on `latest/download/…` also proves
   `latest` advanced to the new tag.
2. **`SHA256SUMS` is well-formed** — exactly four entries, one per binary.
3. **The install path works end-to-end** — fetch and run one install script from the
   published URL on a scratch `HOME` and confirm `taskkling --version` reports the new version.

   > **Windows hazard:** overriding `HOME`/env vars does **not** isolate `install.ps1`'s
   > PATH step — it writes the **real** `HKCU\Environment\Path` regardless (and that rewrite
   > carries the t-359h empty-segment normalization). Running it naively pollutes the real
   > user registry. Snapshot `HKCU\Environment\Path` before and byte-exact-restore it after,
   > or verify the artifact directly (download + extract + `--version`) without invoking the
   > PATH registration. See `dx` task for an `install.ps1` `-NoPath`/isolation flag that
   > would make this safe by construction.
