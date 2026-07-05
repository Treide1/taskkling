# Releasing

How a `vX.Y.Z` release is cut and verified. The pipeline itself is wire-only
(`.github/workflows/release.yml`): a human pushes the tag; CI builds the four binaries,
generates `SHA256SUMS`, and publishes them with both install scripts. This runbook is the
checklist around that wire — every step is deterministic; none are optional.

## Pre-cut

Run these **before** bumping/tagging. They catch state the backlog and CI cannot see.

1. **Every live feature branch is fully merged.** For each branch not yet deleted:
   `git log main..<branch>` must be **empty**. A non-empty range means commits are stranded
   (a partial PR merge left v0.3.0 nearly missing its headline features — task status alone
   does not prove code reached main).
2. **Local main matches the remote.** `git fetch && git status` — working tree clean, no
   ahead/behind against `origin/main`.
3. **Version matches the intended tag.** `version=` in `gradle.properties` must equal the
   `X.Y.Z` of the tag you are about to push. The workflow's tag/version guard fails the run
   otherwise — catch it before burning a tag name.

Then: push the bump commit, tag `vX.Y.Z`, push the tag. Tagging is human-owned.

## Post-publish

Verify **anonymously** — no `gh`, no auth. An authenticated client sees a private repo's
release perfectly while the world gets 404 (this happened on v0.2.0: the release "succeeded"
yet was uninstallable for everyone).

For `BASE = https://github.com/Treide1/taskkling/releases/latest/download`:

1. **HEAD 200 on all 7 assets:** `taskkling-linux-x64`, `taskkling-macos-arm64`,
   `taskkling-macos-x64`, `taskkling-windows-x64.exe`, `SHA256SUMS`, `install.sh`,
   `install.ps1`. A 200 on `latest/download/...` also proves `latest` advanced to the new tag.
2. **`SHA256SUMS` is well-formed:** fetch it anonymously; assert 4 entries, one per binary.
3. **The install path works end-to-end:** anonymously fetch and run one install script
   (`curl -fsSL $BASE/install.sh | sh` on a scratch HOME, or the ps1 equivalent) and check
   `taskkling --version` reports the new version.
