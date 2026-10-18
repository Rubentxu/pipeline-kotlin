#!/usr/bin/env bash
# test_release_publisher.sh — PK-NEXT R0.1 negative tests for the release script.
#
# The release script must REFUSE to advance in documented bad states. Each
# test sets up a sandbox git repo with a controlled state, runs the
# release script, and asserts the script rejected with a non-zero exit
# BEFORE the gradle gate runs.
#
# Scope: the early-exit checks (env, dirty tree, local tag, remote tag).
# The post-gradle checks (receipt SHA, ZIP digest) are tested separately
# in :pipeline-release:test as the DistributionMandatoryPluginsTest
# surface; that surface has direct access to the gate's outputs.
#
# Usage:
#   ./scripts/test_release_publisher.sh
#
# Exit 0 if all negative tests pass; non-zero otherwise.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/scripts/release-pipelinek.sh"

if [ ! -f "$TARGET_SCRIPT" ]; then
    echo "test_release_publisher: target script not found at $TARGET_SCRIPT" >&2
    exit 1
fi

PASS_COUNT=0
FAIL_COUNT=0

# Use a sentinel file for subshell-safe counting.
COUNTER_DIR="$(mktemp -d)"
: > "$COUNTER_DIR/pass"
: > "$COUNTER_DIR/fail"

# ----------------------------------------------------------------------------
# Helper: run the target script in a sandbox with a small budget. The
# script's first 60 seconds are dominated by git + bash; if it gets
# past the pre-conditions into the gradle call, kill it and treat the
# test as a setup failure (the negative test should have rejected
# already).
# ----------------------------------------------------------------------------
run_negative_test() {
    local name="$1"
    local setup_fn="$2"
    local expect="$3"
    local tmp
    tmp="$(mktemp -d)"
    if ! "$setup_fn" "$tmp"; then
        echo "  SETUP FAILED: $name" >&2
        echo x >> "$COUNTER_DIR/fail"
        rm -rf "$tmp"
        return
    fi
    (
        # The script under test resolves REPO_ROOT from its own path and then
        # `cd "$REPO_ROOT"` (line 51), so running it from outside the sandbox
        # made every git command act on the REAL repository and left the
        # sandbox decorative: all three tests refused on the real tree's
        # dirty state, so two of them never reached the pre-condition they
        # claim to certify -- and one clean tree away from pushing a tag to
        # origin. Copying the script into the sandbox is what makes REPO_ROOT
        # resolve there and the test mean what it says.
        mkdir -p "$tmp/scripts"
        cp "$TARGET_SCRIPT" "$tmp/scripts/release-pipelinek.sh"
        cd "$tmp"
        local out
        out="$(timeout 15 env PIPELINEK_RELEASE_TAG="v0.56.0-rc999" \
                 PIPELINEK_RELEASE_TRAIN="0.56.0" \
                 bash "$tmp/scripts/release-pipelinek.sh" 2>&1)"
        local rc=$?
        if [ "$rc" -eq 124 ]; then
            echo "  FAIL  $name (script did not refuse in 15s; it leaked into the gradle call when it should have failed the pre-condition)" >&2
            echo x >> "$COUNTER_DIR/fail"
        elif [ "$rc" -ne 0 ]; then
            # Refusing is necessary but not sufficient: any earlier refusal
            # satisfies it. Without this, a test named for the tag pre-conditions
            # passes while being refused by the dirty-tree check that runs first.
            if printf '%s\n' "$out" | grep -qF "$expect"; then
                echo "  PASS  $name (refused with exit=$rc on its own pre-condition: $expect)"
                echo x >> "$COUNTER_DIR/pass"
            else
                echo "  FAIL  $name (refused with exit=$rc, but NOT on '$expect'; it was stopped" >&2
                echo "        by an earlier pre-condition. Last line: ${out##*$'\n'}" >&2
                echo x >> "$COUNTER_DIR/fail"
            fi
        else
            echo "  FAIL  $name (script accepted a state it should have refused; output: $out)" >&2
            echo x >> "$COUNTER_DIR/fail"
        fi
    )
    rm -rf "$tmp"
}

# ----------------------------------------------------------------------------
# Setup: clean repo on a single commit, no tag, dummy local remote.
# ----------------------------------------------------------------------------
setup_clean_repo() {
    local d="$1"
    cd "$d"
    git init -q -b main
    git config user.email test@test
    git config user.name test
    echo "x" > f && git add f && git commit -q -m initial
    git remote add origin "$d"
}

# ----------------------------------------------------------------------------
# 1. A dirty working tree is refused BEFORE the gradle call.
# ----------------------------------------------------------------------------
setup_dirty_tree() {
    local d="$1"
    setup_clean_repo "$d" || return 1
    cd "$d"
    echo "dirty" > f
    # f is now modified; HEAD is clean but the working tree is not.
}

# ----------------------------------------------------------------------------
# 2. A tag that already exists locally is refused BEFORE the gradle call.
# ----------------------------------------------------------------------------
setup_local_tag_exists() {
    local d="$1"
    setup_clean_repo "$d" || return 1
    cd "$d"
    git tag "v0.56.0-rc999"
}

# ----------------------------------------------------------------------------
# 3. A tag that already exists on origin is refused BEFORE the gradle call.
#    We use a "real" local bare repo as origin so git ls-remote finds it.
# ----------------------------------------------------------------------------
setup_remote_tag_exists() {
    local d="$1"
    setup_clean_repo "$d" || return 1
    cd "$d"
    # Push HEAD and the conflicting tag to origin (a real bare repo).
    #
    # The bare repo lives INSIDE the sandbox. It used to be a separate
    # mktemp -d that this function deleted before returning, which destroyed
    # the remote it had just created: `git ls-remote --tags origin` then found
    # nothing, the remote-tag pre-condition never fired, and the script ran on
    # into the gradle call. The test still reported PASS because it only asked
    # for a non-zero exit, and the real repository's dirty state was supplying
    # that. Cleanup is the caller's `rm -rf "$tmp"`.
    local bare="$d/origin.git"
    git init -q --bare "$bare" -b main
    git remote set-url origin "$bare"
    git push -q origin main
    # Create the tag on origin via direct push.
    git tag "v0.56.0-rc999"
    git push -q origin "v0.56.0-rc999"
    # Remove the local tag so the script's "local tag" check is irrelevant;
    # only the remote check fires.
    git tag -d "v0.56.0-rc999"
}

# ----------------------------------------------------------------------------
# Run all tests.
# ----------------------------------------------------------------------------
echo "PK-NEXT R0.1 — release-pipelinek.sh pre-condition negative tests"
echo
run_negative_test "dirty working tree is refused"      setup_dirty_tree \
    "working tree is dirty"
run_negative_test "tag already exists locally"          setup_local_tag_exists \
    "already exists locally"
run_negative_test "tag already exists on origin"         setup_remote_tag_exists \
    "already exists on origin"

echo
PASS_COUNT=$(wc -l < "$COUNTER_DIR/pass")
FAIL_COUNT=$(wc -l < "$COUNTER_DIR/fail")
rm -rf "$COUNTER_DIR"
echo "summary: pass=$PASS_COUNT fail=$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
