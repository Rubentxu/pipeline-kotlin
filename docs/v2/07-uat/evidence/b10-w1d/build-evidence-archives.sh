#!/usr/bin/env bash
# Collect the XML evidence for the B10/W1d receipt.
#
# Run from the slice worktree with both worktrees present:
#   HEAD worktree  = this repo (the W1d slice)
#   BASE worktree  = ../pipeline-w1d-base (detached at the slice parent 45b26c49)
#
# Result truth is the JUnit XML in v2/<module>/build/test-results/test/, never the console.
# The round gate is `check --continue --rerun-tasks`, so every module's `test` task really
# ran and its XML is fresh (AGENTS.md rules 4, 23-27).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
head_repo="$(cd "$here/../../../../.." && pwd)"
base_repo="${W1D_BASE_WORKTREE:-$(cd "$head_repo/.." && pwd)/pipeline-w1d-base}"
raw="$here/raw/xml"
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

[ -d "$base_repo/v2" ] || { echo "base worktree not found at $base_repo" >&2; exit 1; }

# Head: every module that produced XML, so the inventory is whole-repository.
mkdir -p "$staging/head"
while IFS= read -r xml; do
    module="$(printf '%s' "$xml" | sed "s|^$head_repo/v2/||; s|/build/test-results/test/.*||")"
    mkdir -p "$staging/head/$module"
    cp "$xml" "$staging/head/$module/"
done < <(find "$head_repo/v2" -path '*/build/test-results/test/TEST-*.xml' | sort)

# Base: the same module set, so a class red at head has a base counterpart to compare with.
mkdir -p "$staging/base"
while IFS= read -r xml; do
    module="$(printf '%s' "$xml" | sed "s|^$base_repo/v2/||; s|/build/test-results/test/.*||")"
    mkdir -p "$staging/base/$module"
    cp "$xml" "$staging/base/$module/"
done < <(find "$base_repo/v2" -path '*/build/test-results/test/TEST-*.xml' | sort)

mkdir -p "$raw"
tar -czf "$raw/module-suites-xml.tar.gz" -C "$staging/head" .
tar -czf "$raw/base-module-suites-xml.tar.gz" -C "$staging/base" .

# Whole-repo inventories, derived from the XML and restricted to the modules declared in
# settings.gradle.kts (a build directory left over from an older tree state is not evidence).
mkdir -p "$here/raw"
python3 "$here/suite-inventory.py" dump "$head_repo" 2>/dev/null > "$here/raw/head-inventory.json"
python3 "$here/suite-inventory.py" dump "$base_repo" 2>/dev/null > "$here/raw/base-inventory.json"

ls -l "$raw" "$here/raw"
