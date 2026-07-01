#!/bin/sh
# taskkling installer for macOS and Linux (POSIX sh).
#
# Quick install:
#   curl -fsSL https://github.com/Treide1/taskkling/releases/latest/download/install.sh | sh
#
# Pin a version (defaults to the latest release):
#   TASKKLING_VERSION=v0.1.0 sh install.sh
#
# Gatekeeper note (macOS): binaries fetched with curl do NOT receive the
# `com.apple.quarantine` extended attribute — that flag is applied by GUI
# downloaders (browsers), not by curl. So this piped-install path launches
# without a Gatekeeper prompt; no `xattr -d com.apple.quarantine` is required.
#
# POSIX sh only: `curl | sh` runs under /bin/sh (dash on Debian/Ubuntu), which has
# no `pipefail`. Use `set -eu` — every pipeline below is checked explicitly anyway.
set -eu

REPO="Treide1/taskkling"
VERSION="${TASKKLING_VERSION:-latest}"
BASE_URL="${TASKKLING_BASE_URL:-https://github.com/${REPO}/releases}"
INSTALL_DIR="${TASKKLING_INSTALL_DIR:-${HOME}/.local/bin}"

err() { printf 'error: %s\n' "$1" >&2; exit 1; }
info() { printf '%s\n' "$1"; }

# --- Resolve the release asset for this OS/arch --------------------------------
os="$(uname -s)"
arch="$(uname -m)"

case "$os" in
  Darwin) os_part="macos" ;;
  Linux)  os_part="linux" ;;
  *) err "unsupported OS '${os}' (only macOS and Linux are supported; use install.ps1 on Windows)" ;;
esac

case "$arch" in
  x86_64|amd64)  arch_part="x64" ;;
  arm64|aarch64) arch_part="arm64" ;;
  *) err "unsupported architecture '${arch}'" ;;
esac

asset="taskkling-${os_part}-${arch_part}"

# Only three native binaries are published. linux-arm64 in particular is NOT built.
case "$asset" in
  taskkling-linux-x64|taskkling-macos-arm64|taskkling-macos-x64) ;;
  *) err "no prebuilt taskkling binary for ${os_part}-${arch_part} (supported: linux-x64, macos-arm64, macos-x64)" ;;
esac

# --- Build the download base URL ----------------------------------------------
if [ "$VERSION" = "latest" ]; then
  dl="${BASE_URL}/latest/download"
else
  # Accept both `0.1.0` and `v0.1.0`; release tags are `vX.Y.Z`.
  case "$VERSION" in
    v*) tag="$VERSION" ;;
    *)  tag="v${VERSION}" ;;
  esac
  dl="${BASE_URL}/download/${tag}"
fi

# --- Helpers -------------------------------------------------------------------
download() {
  # download <url> <dest>
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$2" "$1"
  else
    err "neither curl nor wget is available"
  fi
}

compute_sha256() {
  # compute_sha256 <file> -> prints lowercase hex digest
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d ' ' -f1
  else
    err "neither sha256sum nor shasum found; cannot verify the download"
  fi
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# --- Download ------------------------------------------------------------------
info "Downloading ${asset} (${VERSION}) ..."
download "${dl}/${asset}" "${tmp}/${asset}"
download "${dl}/SHA256SUMS" "${tmp}/SHA256SUMS"

# --- Verify checksum against the matching SHA256SUMS line ----------------------
expected="$(awk -v a="$asset" '{ f=$NF; sub(/^\*/, "", f); if (f == a) { print $1; exit } }' "${tmp}/SHA256SUMS")"
[ -n "$expected" ] || err "no checksum entry for ${asset} in SHA256SUMS"
actual="$(compute_sha256 "${tmp}/${asset}")"
if [ "$expected" != "$actual" ]; then
  err "checksum mismatch for ${asset} (expected ${expected}, got ${actual}) — aborting"
fi
info "Checksum OK (${actual})"

# --- Install -------------------------------------------------------------------
mkdir -p "$INSTALL_DIR"
dest="${INSTALL_DIR}/taskkling"
chmod +x "${tmp}/${asset}"
mv -f "${tmp}/${asset}" "$dest"

info ""
info "Installed taskkling to ${dest}"
"$dest" --version 2>/dev/null || true

# Materialize the user-level config.toml (ADR-006) so the on-by-default
# update_check notifier's OFF switch is discoverable right after install.
# Best-effort: a config-write hiccup must never fail the install.
"$dest" config init >/dev/null 2>&1 || true

# --- PATH hint -----------------------------------------------------------------
case ":${PATH}:" in
  *":${INSTALL_DIR}:"*) ;;
  *)
    # Tailor the suggestion to the user's shell. ~/.profile is read only by bash/sh
    # login shells; zsh (the macOS default) ignores it and fish needs different
    # syntax -- so a hardcoded ~/.profile hint silently does nothing on a stock Mac.
    # Pick the file/command from $SHELL.
    info ""
    info "Note: ${INSTALL_DIR} is not on your PATH. Add it, e.g.:"
    case "$(basename "${SHELL:-}")" in
      zsh)
        info "  echo 'export PATH=\"${INSTALL_DIR}:\$PATH\"' >> ~/.zshrc"
        ;;
      fish)
        info "  fish_add_path \"${INSTALL_DIR}\""
        ;;
      *)
        info "  echo 'export PATH=\"${INSTALL_DIR}:\$PATH\"' >> ~/.profile"
        ;;
    esac
    info "then restart your shell (or apply it to the current session)."
    ;;
esac
