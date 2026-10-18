#!/usr/bin/env bash
# test_release_publisher_assets.sh — H2 TODO 2 compliance test for release-pipelinek.sh.
#
# Verifies that the refactored publisher:
# 1. Does NOT hardcode the §3-prohibited assets (HANDOFF.md, git-head.txt)
#    in the upload list or verification loop. (HANDOFF.md as --notes-file is OK.)
# 2. DOES read the asset set from the manifest (the §8 contract source of truth).
# 3. Uses the asset/sbom/sdk/bom/sha256sums fields.
#
# This is a static analysis test: it greps the script and asserts on the
# pattern. It does NOT run the publisher (the existing
# test_release_publisher.sh covers the pre-gradle early-exit checks).
#
# Usage:
#   bash scripts/test_release_publisher_assets.sh
set -uo pipefail

SCRIPT="scripts/release-pipelinek.sh"
pass=0
fail=0

assert_not_in_lines() {
    # $1 = pattern that MUST NOT appear; $2 = section; $3 = name.
    local pattern="$1"
    local section="$2"
    local name="$3"
    if printf '%s\n' "$section" | grep -qE "$pattern"; then
        echo "  FAIL  $name: forbidden pattern found: $pattern" >&2
        fail=$((fail+1))
    else
        echo "  PASS  $name: forbidden pattern absent"
        pass=$((pass+1))
    fi
}

assert_in_lines() {
    # $1 = pattern that MUST appear; $2 = section; $3 = name.
    local pattern="$1"
    local section="$2"
    local name="$3"
    if printf '%s\n' "$section" | grep -qE "$pattern"; then
        echo "  PASS  $name: required pattern present"
        pass=$((pass+1))
    else
        echo "  FAIL  $name: required pattern missing: $pattern" >&2
        fail=$((fail+1))
    fi
}

# Extract the upload + verification section: from `gh release create` to the
# final "verified complete" line. Use sed with line numbers to be precise.
UPLOAD_START=$(grep -n '^gh release create ' "$SCRIPT" | head -1 | cut -d: -f1)
UPLOAD_END=$(grep -n 'verified complete' "$SCRIPT" | head -1 | cut -d: -f1)
UPLOAD_SECTION=$(sed -n "${UPLOAD_START},${UPLOAD_END}p" "$SCRIPT")

# Test 1: HANDOFF.md is NOT a hardcoded upload path (only --notes-file is OK).
# Look for HANDOFF.md as a positional argument (preceded by whitespace or backslash-newline,
# not by --notes-file=).
HANDOFF_AS_ASSET=$(printf '%s\n' "$UPLOAD_SECTION" | grep -E 'HANDOFF\.md' | grep -v 'notes-file' || true)
if [ -n "$HANDOFF_AS_ASSET" ]; then
    echo "  FAIL  HANDOFF.md as release asset: forbidden (only as --notes-file is allowed):" >&2
    echo "$HANDOFF_AS_ASSET" | sed 's/^/        /' >&2
    fail=$((fail+1))
else
    echo "  PASS  HANDOFF.md is only --notes-file, not a release asset (§3 compliant)"
    pass=$((pass+1))
fi

# Test 2: git-head.txt MUST NOT appear anywhere in the upload section
assert_not_in_lines 'git-head\.txt' \
    "$UPLOAD_SECTION" \
    "git-head.txt is not in upload/verify section (§3 forbidden)"

# Test 3: hardcoded `for required in ... HANDOFF.md ... git-head.txt ...` loop
assert_not_in_lines 'HANDOFF\.md.*git-head\.txt|SHA256SUMS.*HANDOFF' \
    "$UPLOAD_SECTION" \
    "verification loop does not hardcode HANDOFF.md or git-head.txt"

# Test 4: SDK ZIP must be referenced (manifest-driven staging)
STAGING_START=$(grep -n '^# 5b\. Manifest-driven asset staging' "$SCRIPT" | head -1 | cut -d: -f1)
# End at the start of section 6 (the next "# ----" comment block)
STAGING_END=$(grep -n '^# 6\. Tag' "$SCRIPT" | head -1 | cut -d: -f1)
STAGING_SECTION=$(sed -n "${STAGING_START},${STAGING_END}p" "$SCRIPT")

assert_in_lines "['\"]sdk\.name['\"]" \
    "$STAGING_SECTION" \
    "staging reads sdk.name from manifest (§8 SDK ZIP)"

assert_in_lines "['\"]bom\.name['\"]" \
    "$STAGING_SECTION" \
    "staging reads bom.name from manifest (§8 BOM JSON)"

assert_in_lines "['\"]asset\.name['\"]" \
    "$STAGING_SECTION" \
    "staging reads asset.name from manifest (§8 ZIP)"

assert_in_lines "['\"]sbom\.name['\"]" \
    "$STAGING_SECTION" \
    "staging reads sbom.name from manifest (§8 SBOM)"

assert_in_lines "['\"]sha256sums\.name['\"]" \
    "$STAGING_SECTION" \
    "staging reads sha256sums.name from manifest (§8 digest authority)"

# Test 5: verification uses the manifest-driven STAGED_PATHS, not a hardcoded list
assert_in_lines 'for staged in' \
    "$UPLOAD_SECTION" \
    "verification iterates over STAGED_PATHS (manifest-driven)"

# Test 6: SHA256SUMS is in the upload (as a separate staged path)
# (STAGED_PATHS is appended with $SUMS after the SHA256SUMS is generated)
assert_in_lines 'STAGED_PATHS.+SUMS' \
    "$STAGING_SECTION" \
    "SHA256SUMS is appended to STAGED_PATHS for upload"

# Test 7: no hardcoded asset-name list in the upload (everything is ${STAGED_PATHS[@]})
assert_in_lines '\$\{STAGED_PATHS\[@\]\}' \
    "$UPLOAD_SECTION" \
    "upload uses \${STAGED_PATHS[@]} (no hardcoded asset list)"

# ----------------------------------------------------------------------------
# Test 8: a null manifest field must REFUSE, not skip.
#
# This is the completeness property that tests 1-7 do not cover, and its
# absence made test 5 circular. The verification loop iterates STAGED_PATHS,
# which is populated only by the fields that were non-null, so a manifest that
# omitted the SDK produced a release without one AND a verification loop that
# never asked for it. §8 fixes the asset set exactly, so every field
# stage_manifest_asset is called with is mandatory.
#
# STRUCTURAL, not behavioural: reaching this block requires the real
# releaseGate (distZip + cyclonedxBom + candidateAdmission + test) to have run,
# so a behavioural test here would cost a full distribution build per run. The
# claim asserted is a property of the text, and it is labelled as such.
# ----------------------------------------------------------------------------
FN_START=$(grep -n '^stage_manifest_asset() {' "$SCRIPT" | head -1 | cut -d: -f1)
FN_END=$(awk -v s="$FN_START" 'NR>s && /^}/ {print NR; exit}' "$SCRIPT")
if [ -z "$FN_START" ] || [ -z "$FN_END" ]; then
    echo "  FAIL  stage_manifest_asset() not found in $SCRIPT" >&2
    fail=$((fail+1))
else
    FN_BODY=$(sed -n "${FN_START},${FN_END}p" "$SCRIPT")

    if printf '%s\n' "$FN_BODY" | grep -qE 'skipping'; then
        echo "  FAIL  a null manifest field is SKIPPED, so an incomplete manifest publishes a" >&2
        echo "        release whose missing asset is also missing from the verification loop" >&2
        fail=$((fail+1))
    else
        echo "  PASS  a null manifest field does not skip; it refuses"
        pass=$((pass+1))
    fi

    # The second row is scoped to the NULL branch specifically. Asserting only
    # that the function contains `exit 1` would pass on the unrelated
    # "file does not exist" exit, which is a green row satisfied for the wrong
    # reason -- it would survive a fix that never touched this branch.
    NULL_BODY=$(printf '%s\n' "$FN_BODY" | awk '/if \[ -z "\$fname" \]; then/{f=1} f{print} f && /^    fi$/{exit}')
    if printf '%s\n' "$NULL_BODY" | grep -qE 'exit 1'; then
        echo "  PASS  the null-field branch itself fails closed"
        pass=$((pass+1))
    else
        echo "  FAIL  the null-field branch has no fail-closed exit of its own:" >&2
        printf '%s\n' "$NULL_BODY" | sed 's/^/        /' >&2
        fail=$((fail+1))
    fi
fi

echo
echo "summary: pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
