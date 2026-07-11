#!/bin/sh
# Shell-level regression harness for install.sh's PATH-hint selection (t-dywy).
#
# Runs the ACTUAL install.sh end-to-end (fake download -> checksum -> install -> PATH hint) as a
# child process, for each scenario below, against:
#   - a scratch $HOME (a fresh mktemp -d per case) -- the harness asserts NOTHING is ever created
#     there, since install.sh's own comment promises the hint "never mutates a shell profile
#     itself"; this is the regression net for that promise.
#   - a local file:// "release" directory instead of GitHub, via TASKKLING_BASE_URL, so the run is
#     fully offline.
#   - a tiny PATH-prepended `uname` shim reporting Linux/x86_64, so this harness behaves
#     identically whether it runs on the ubuntu-latest CI leg (real uname already says
#     Linux/x86_64) or locally under git-bash on Windows (real uname says MINGW64_NT/x86_64,
#     which install.sh's OS/arch gate rejects before ever reaching the PATH-hint block). No
#     changes to install.sh itself were needed for this -- it is exercised completely unmodified.
#
# Bug class pinned down here: t-38b1 (a hardcoded `>> ~/.profile` hint that zsh/fish silently
# ignore). Covers: zsh hint, fish hint, default (bash/other) hint, shell resolved from a full
# $SHELL path (not just a bare name), no hint when INSTALL_DIR is already on PATH, and --no-path
# suppressing the hint.
#
# Run:  bash scripts/tests/install-sh-path.tests.sh    (or: sh scripts/tests/install-sh-path.tests.sh)
# Exits non-zero (and prints a FAIL: line per failure) if any assertion fails.

set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/../.." && pwd)"
install_script="$repo_root/install.sh"
[ -f "$install_script" ] || { echo "install.sh not found at $install_script" >&2; exit 1; }

pass=0
fail=0
failures=""

record_pass() { pass=$((pass + 1)); }
record_fail() {
  fail=$((fail + 1))
  failures="${failures}
FAIL: $1"
}

contains() {
  # contains <haystack> <needle>
  case "$1" in
    *"$2"*) return 0 ;;
    *) return 1 ;;
  esac
}

assert_contains() {
  # assert_contains <output> <needle> <label>
  if contains "$1" "$2"; then record_pass; else record_fail "$3
  expected output to contain: [$2]
  actual output:
$1"; fi
}

assert_not_contains() {
  # assert_not_contains <output> <needle> <label>
  if contains "$1" "$2"; then record_fail "$3
  expected output NOT to contain: [$2]
  actual output:
$1"; else record_pass; fi
}

assert_empty_dir() {
  # assert_empty_dir <dir> <label> -- no files created/mutated anywhere under <dir>.
  count="$(find "$1" -type f | wc -l | tr -d ' ')"
  if [ "$count" = "0" ]; then record_pass; else
    record_fail "$2
  expected $1 to contain no files, found $count:
$(find "$1" -type f)"
  fi
}

# --- uname shim: force Linux/x86_64 regardless of the host this harness runs on -----------------

shim_dir="$(mktemp -d)"
cat > "$shim_dir/uname" <<'EOF'
#!/bin/sh
case "$1" in
  -s) echo "Linux" ;;
  -m) echo "x86_64" ;;
  *) echo "Linux" ;;
esac
EOF
chmod +x "$shim_dir/uname"
test_path="$shim_dir:$PATH"

# --- Build a fake "release" served over file:// --------------------------------------------------

fake_release="$(mktemp -d)"
dl_dir="$fake_release/latest/download"
mkdir -p "$dl_dir"
printf 'fake taskkling binary for t-dywy shell tests\n' > "$dl_dir/taskkling-linux-x64"
(cd "$dl_dir" && sha256sum taskkling-linux-x64 > SHA256SUMS)

if command -v cygpath >/dev/null 2>&1; then
  # git-bash on Windows: curl there needs a drive-letter, forward-slash path after file:///.
  fake_base_url="file:///$(cygpath -m "$fake_release")"
else
  fake_base_url="file://$fake_release"
fi

cleanup() {
  rm -rf "$shim_dir" "$fake_release"
}
trap cleanup EXIT

# --- Test-case runner ------------------------------------------------------------------------

# run_install <install_dir> <shell> <path_prefix_or_empty> [--no-path]
# Runs install.sh in a scratch $HOME, with $SHELL and $PATH controlled. Sets the globals $output,
# $exit_code and $scratch_home for the caller to assert on. Deliberately NOT run inside a `$(...)`
# command substitution -- that would fork a subshell and the variable assignments below would be
# lost to the caller.
run_install() {
  install_dir="$1"
  shell_value="$2"
  extra_path="$3"
  no_path_flag="${4:-}"
  scratch_home="$(mktemp -d)"
  if [ -n "$extra_path" ]; then
    run_path="$extra_path:$test_path"
  else
    run_path="$test_path"
  fi
  # Override just the variables install.sh reads (HOME/SHELL/PATH/TASKKLING_*) rather than
  # clearing the whole environment (`env -i`) -- on Windows/git-bash, curl and friends can depend
  # on ambient vars (SYSTEMROOT etc.) that have nothing to do with what this harness is isolating.
  set +e
  output="$(HOME="$scratch_home" \
    SHELL="$shell_value" \
    PATH="$run_path" \
    TASKKLING_BASE_URL="$fake_base_url" \
    TASKKLING_VERSION="latest" \
    TASKKLING_INSTALL_DIR="$install_dir" \
    sh "$install_script" $no_path_flag 2>&1)"
  exit_code=$?
  set -e
}

# 1. zsh, resolved from a FULL $SHELL path (not a bare name) -> .zshrc export hint.
install_dir="$(mktemp -u)/tk-install-1"
run_install "$install_dir" "/usr/bin/zsh" ""
assert_contains "$output" '>> ~/.zshrc' 'zsh (full $SHELL path) prints the .zshrc hint'
assert_not_contains "$output" 'fish_add_path' 'zsh output does not contain the fish hint'
assert_not_contains "$output" '>> ~/.profile' 'zsh output does not contain the default/.profile hint'
[ "$exit_code" -eq 0 ] && record_pass || record_fail "zsh case: install.sh exits 0 (got $exit_code)"
assert_empty_dir "$scratch_home" 'zsh case: scratch HOME has no created/mutated files'

# 2. fish, resolved from a full path -> fish_add_path hint.
install_dir="$(mktemp -u)/tk-install-2"
run_install "$install_dir" "/usr/local/bin/fish" ""
assert_contains "$output" "fish_add_path \"$install_dir\"" 'fish prints the fish_add_path hint'
assert_not_contains "$output" '.zshrc' 'fish output does not contain the zsh hint'
assert_not_contains "$output" '>> ~/.profile' 'fish output does not contain the default/.profile hint'
[ "$exit_code" -eq 0 ] && record_pass || record_fail "fish case: install.sh exits 0 (got $exit_code)"
assert_empty_dir "$scratch_home" 'fish case: scratch HOME has no created/mutated files'

# 3. bash (and by extension any non-zsh/fish shell) -> the default ~/.profile hint.
install_dir="$(mktemp -u)/tk-install-3"
run_install "$install_dir" "/bin/bash" ""
assert_contains "$output" '>> ~/.profile' 'bash prints the default .profile hint'
assert_not_contains "$output" '.zshrc' 'bash output does not contain the zsh hint'
assert_not_contains "$output" 'fish_add_path' 'bash output does not contain the fish hint'
[ "$exit_code" -eq 0 ] && record_pass || record_fail "bash case: install.sh exits 0 (got $exit_code)"
assert_empty_dir "$scratch_home" 'bash case: scratch HOME has no created/mutated files'

# 4. $SHELL entirely unset -> also falls through to the default ~/.profile hint.
install_dir="$(mktemp -u)/tk-install-4"
run_install "$install_dir" "" ""
assert_contains "$output" '>> ~/.profile' 'unset $SHELL prints the default .profile hint'
[ "$exit_code" -eq 0 ] && record_pass || record_fail "unset \$SHELL case: install.sh exits 0 (got $exit_code)"
assert_empty_dir "$scratch_home" 'unset $SHELL case: scratch HOME has no created/mutated files'

# 5. INSTALL_DIR already on $PATH -> no hint at all, regardless of shell.
install_dir="$(mktemp -d)/tk-install-5"
mkdir -p "$install_dir"
run_install "$install_dir" "/usr/bin/zsh" "$install_dir"
assert_not_contains "$output" 'is not on your PATH' 'already-on-PATH case prints no hint'
assert_not_contains "$output" '.zshrc' 'already-on-PATH case prints no zsh hint line'
[ "$exit_code" -eq 0 ] && record_pass || record_fail "already-on-PATH case: install.sh exits 0 (got $exit_code)"
assert_empty_dir "$scratch_home" 'already-on-PATH case: scratch HOME has no created/mutated files'

# 6. --no-path suppresses the hint entirely, even when the shell is known and INSTALL_DIR is
#    absent from PATH (i.e. --no-path takes precedence over the would-be hint).
install_dir="$(mktemp -u)/tk-install-6"
run_install "$install_dir" "/usr/bin/zsh" "" "--no-path"
assert_contains "$output" '--no-path set: skipping the PATH hint.' '--no-path prints the skip notice'
assert_not_contains "$output" '.zshrc' '--no-path suppresses the zsh hint'
assert_not_contains "$output" 'fish_add_path' '--no-path suppresses the fish hint'
assert_not_contains "$output" '>> ~/.profile' '--no-path suppresses the default hint'
[ "$exit_code" -eq 0 ] && record_pass || record_fail "--no-path case: install.sh exits 0 (got $exit_code)"
assert_empty_dir "$scratch_home" '--no-path case: scratch HOME has no created/mutated files'

# --- Summary -----------------------------------------------------------------------------------

if [ -n "$failures" ]; then
  printf '%s\n' "$failures" >&2
fi
echo ""
echo "install-sh-path.tests.sh: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
