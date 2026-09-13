#!/usr/bin/env bash
# Collect the XML evidence for the B10/W1c receipt.
#
# Run from the slice worktree with both worktrees present:
#   HEAD worktree  = this repo (the W1c slice)
#   BASE worktree  = ../pipeline-w1c-base (detached at the slice parent)
#
# Result truth is the JUnit XML in build/test-results/test/, never the console.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
head_repo="$(cd "$here/../../../../.." && pwd)"
base_repo="${W1C_BASE_WORKTREE:-$(cd "$head_repo/.." && pwd)/pipeline-w1c-base}"
raw="$here/raw/xml"
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

[ -d "$base_repo/v2" ] || { echo "base worktree not found at $base_repo" >&2; exit 1; }

# Head: the complete suite inventory of the three modules this slice touches.
mkdir -p "$staging/head/domain" "$staging/head/arch" "$staging/head/app"
cp "$head_repo"/v2/pipeline-domain/build/test-results/test/TEST-*.xml "$staging/head/domain/"
cp "$head_repo"/v2/pipeline-architecture-tests/build/test-results/test/TEST-*.xml "$staging/head/arch/"
cp "$head_repo"/v2/pipeline-application/build/test-results/test/TEST-*.xml "$staging/head/app/"

# Base: every class that is red at head, so the regression claim is base-vs-head and not
# a statement about the base suite as a whole.
mkdir -p "$staging/base/app" "$staging/base/arch"
for xml in "$head_repo"/v2/pipeline-application/build/test-results/test/TEST-*.xml; do
    class="$(basename "$xml" | sed 's/^TEST-//; s/\.xml$//')"
    short="${class##*.}"
    # Include parameterised/ nested-class files too, matching on the class name prefix.
    for candidate in "$base_repo"/v2/pipeline-application/build/test-results/test/TEST-*.xml; do
        if basename "$candidate" | sed 's/^TEST-//; s/\.xml$//' | grep -qF "$short"; then
            cp "$candidate" "$staging/base/app/"
        fi
    done
done
for xml in "$head_repo"/v2/pipeline-architecture-tests/build/test-results/test/TEST-*.xml; do
    short="$(basename "$xml" | sed 's/^TEST-//; s/\.xml$//')"
    for candidate in "$base_repo"/v2/pipeline-architecture-tests/build/test-results/test/TEST-*.xml; do
        if basename "$candidate" | sed 's/^TEST-//; s/\.xml$//' | grep -qF "${short##*.}"; then
            cp "$candidate" "$staging/base/arch/"
        fi
    done
done

mkdir -p "$raw"
tar -czf "$raw/module-suites-xml.tar.gz" -C "$staging/head" .
tar -czf "$raw/base-failing-classes-xml.tar.gz" -C "$staging/base" .

# Whole-repo inventories, derived from the XML and restricted to the modules declared in
# settings.gradle.kts (a build directory left over from an older tree state is not evidence).
python3 "$here/suite-inventory.py" dump "$head_repo" > "$raw/head-inventory.json" 2>/dev/null
python3 "$here/suite-inventory.py" dump "$base_repo" > "$raw/base-inventory.json" 2>/dev/null

ls -l "$raw"
