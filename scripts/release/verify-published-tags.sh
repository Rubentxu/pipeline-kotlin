#!/usr/bin/env bash
# verify-published-tags.sh — audit the tags this repository has PUBLISHED.
#
# The question it answers:
#   "For the release tags we published, is each one an annotated tag whose
#    signature verifies against the key this repository declares?"
#
# Why it exists. The published stable tag v0.90.0 is a LIGHTWEIGHT tag: it
# points straight at a commit, with no tagger, no message and no signature.
# `gh release create` created it, because that command creates the tag when
# the tag does not exist yet. Nothing in the publication path looked at what
# the remote actually holds afterwards, so the release page showed a tag whose
# provenance nobody could check and the fact went unnoticed. This script is
# the look: it reads the remote's own listing and reports, for every tag in
# scope, whether that tag is annotated and whether it is signed.
#
# How it reads the remote WITHOUT fetching. `git ls-remote --tags` publishes
# one line per tag object and, for an ANNOTATED tag, a second line ending in
# `^{}` that carries the commit the tag points at. A lightweight tag has no
# peeled line — that absence IS the fact, read from the remote itself, and it
# needs no object to be fetched. The signature itself can only be read from
# the tag object, so it is verified when the object is in this repository, and
# reported as a GAP when it is not.
#
# Why a gap is not a pass. A gap means the audit did not measure that tag.
# Reporting it as a pass would let a caller read "the published tags verify"
# over a tag nobody checked, which is the same silence this script exists to
# end. The exit code is therefore nonzero when any tag in scope is a FAIL or
# a GAP, and the summary counts them apart.
#
# Why it is neither a scenario in examples/ nor a unit test:
#   - the scenarios in examples/ run the DEVELOPMENT distribution built by
#     installDist; this check consumes the REMOTE's published refs, which no
#     distribution can produce;
#   - a unit test may not shell out, and this check runs `git` and `gpg`.
# It is therefore a RELEASE gate, like the tag-signature check it reuses.
#
# Usage:
#   verify-published-tags.sh --expect-fingerprint <FPR> [--expect-uid <uid>]
#       [--remote <name>] [--tag <name>]... [--pattern <glob>]... [--out <file>]
#
# At least one --tag or --pattern is required: an audit with no scope
# measures nothing and would print a green over nothing.
#
# Exit codes: 0 = every tag in scope is annotated and signed; 1 = at least one
# tag FAILED or could not be measured (GAP); 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

EXPECT_FINGERPRINT=""
EXPECT_UID=""
REMOTE="origin"
SCOPE_TAGS=()
SCOPE_PATTERNS=()
OUT_FILE=""

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)                SCOPE_TAGS+=("${2:-}"); shift 2 ;;
        --pattern)            SCOPE_PATTERNS+=("${2:-}"); shift 2 ;;
        --expect-fingerprint) EXPECT_FINGERPRINT="${2:-}"; shift 2 ;;
        --expect-uid)         EXPECT_UID="${2:-}"; shift 2 ;;
        --remote)             REMOTE="${2:-}"; shift 2 ;;
        --out)                OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)            usage ;;
        *) printf 'verify-published-tags: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

if [[ -z "$EXPECT_FINGERPRINT" ]]; then
    printf 'verify-published-tags: --expect-fingerprint is required\n' >&2
    usage
fi

if ! printf '%s' "$EXPECT_FINGERPRINT" | grep -Eq '^[0-9A-Fa-f]{40}$'; then
    printf 'verify-published-tags: --expect-fingerprint must be a 40-hex fingerprint\n' >&2
    exit 2
fi

if [[ ${#SCOPE_TAGS[@]} -eq 0 && ${#SCOPE_PATTERNS[@]} -eq 0 ]]; then
    printf 'verify-published-tags: --tag or --pattern is required\n' >&2
    printf '           an audit with no scope measures nothing\n' >&2
    usage
fi

# ----------------------------------------------------------------------------
# 1. The remote's own listing. Two lines per ANNOTATED tag (the tag object,
#    then the peeled commit), one line per LIGHTWEIGHT tag (the commit).
# ----------------------------------------------------------------------------

LISTING="$(git ls-remote --tags "$REMOTE")" || {
    printf 'verify-published-tags: the listing of %s could not be read\n' "$REMOTE" >&2
    exit 1
}

in_scope() {
    local name="$1" pattern
    for pattern in "${SCOPE_PATTERNS[@]}"; do
        # The glob IS the pattern here, on purpose: the scope is written the
        # way a reader would write it, not the way grep would take it.
        if [[ "$name" == $pattern ]]; then return 0; fi
    done
    for pattern in "${SCOPE_TAGS[@]}"; do
        if [[ "$name" == "$pattern" ]]; then return 0; fi
    done
    return 1
}

# ----------------------------------------------------------------------------
# 2. Classify and check each tag in scope.
# ----------------------------------------------------------------------------

ROWS=()
FAILED=0
GAPS=0
CHECKED=0
ANNOTATED=0
LIGHTWEIGHT=0

while IFS= read -r line; do
    object="${line%%[[:space:]]*}"
    ref="${line##*[[:space:]]}"
    [[ -n "$object" && -n "$ref" ]] || continue
    [[ "$ref" == refs/tags/* ]] || continue

    name="${ref#refs/tags/}"
    case "$name" in
        *'^{}') continue ;;   # the peeled line is read together with its tag
    esac
    in_scope "$name" || continue

    CHECKED=$((CHECKED + 1))

    # The peeled line, when the remote publishes one, is the commit the tag
    # points at. Its ABSENCE is the fact: the tag is lightweight.
    peeled="$(printf '%s\n' "$LISTING" | awk -v r="refs/tags/${name}^{}" '$2 == r { print $1; exit }')"

    if [[ -z "$peeled" ]]; then
        LIGHTWEIGHT=$((LIGHTWEIGHT + 1))
        FAILED=$((FAILED + 1))
        ROWS+=("  tag ${name} verdict=FAIL type=LIGHTWEIGHT commit=${object} signature=NONE")
        continue
    fi

    ANNOTATED=$((ANNOTATED + 1))

    # The signature can only be read from the tag object. The local object is
    # compared with the remote's first: verifying a DIFFERENT object and
    # calling the tag verified would be the same lie in a new place.
    local_object="$(git rev-parse --verify --quiet "refs/tags/${name}^{tag}" 2>/dev/null || true)"

    if [[ -z "$local_object" ]]; then
        if git rev-parse --verify --quiet "refs/tags/${name}" >/dev/null 2>&1; then
            FAILED=$((FAILED + 1))
            ROWS+=("  tag ${name} verdict=FAIL type=ANNOTATED commit=${peeled} signature=NOT-MEASURED (this repository holds a lightweight tag of that name, not the annotated one the remote publishes)")
        else
            GAPS=$((GAPS + 1))
            ROWS+=("  tag ${name} verdict=GAP type=ANNOTATED commit=${peeled} signature=NOT-MEASURED (the tag object is not in this repository)")
        fi
        continue
    fi

    if [[ "$local_object" != "$object" ]]; then
        FAILED=$((FAILED + 1))
        ROWS+=("  tag ${name} verdict=FAIL type=ANNOTATED commit=${peeled} signature=NOT-MEASURED (this repository holds tag object ${local_object}, the remote publishes ${object})")
        continue
    fi

    # The checker's own row A says WHY a signature failed — "the tag carries
    # no signature" and "the signature comes from another key" are different
    # facts, and the audit reports the one that was measured.
    check_output=""
    check_rc=0
    check_output="$("${repo_root}/scripts/release/verify-tag-signature.sh" \
        --tag "$name" \
        --expect-fingerprint "$EXPECT_FINGERPRINT" \
        --expect-commit "$peeled" \
        ${EXPECT_UID:+--expect-uid "$EXPECT_UID"} 2>&1)" || check_rc=$?

    check_detail="$(printf '%s\n' "$check_output" | sed -n 's/^  row A verdict=[A-Z]* //p' | head -1)"

    if [[ "$check_rc" == "0" ]]; then
        ROWS+=("  tag ${name} verdict=PASS type=ANNOTATED commit=${peeled} signature=VERIFIED (declared key)")
    else
        FAILED=$((FAILED + 1))
        ROWS+=("  tag ${name} verdict=FAIL type=ANNOTATED commit=${peeled} signature=DOES-NOT-VERIFY (${check_detail:-the signature check failed})")
    fi
done <<< "$LISTING"

# ----------------------------------------------------------------------------
# 3. The verdict. It says what ran, and it counts the gaps apart from the
#    failures so that neither can hide behind the other.
# ----------------------------------------------------------------------------

if [[ "$FAILED" -eq 0 && "$GAPS" -eq 0 ]]; then
    VERDICT="PASS"
else
    VERDICT="FAIL"
fi

SUMMARY="published-tags: ${VERDICT} checked=${CHECKED} annotated=${ANNOTATED} lightweight=${LIGHTWEIGHT} failed=${FAILED} gaps=${GAPS} remote=${REMOTE} fingerprint=${EXPECT_FINGERPRINT^^}"

VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/published-tags-verdict.XXXXXX")"
cleanup() { rm -f -- "$VERDICT_FILE"; }
trap cleanup EXIT

{
    printf '%s\n' "$SUMMARY"
    for row in "${ROWS[@]}"; do printf '%s\n' "$row"; done
    if [[ "$GAPS" -gt 0 ]]; then
        printf '  %d tag(s) were NOT MEASURED: a gap is not a pass\n' "$GAPS"
    fi
} > "$VERDICT_FILE"

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
    cp "$VERDICT_FILE" "$OUT_FILE"
fi

cat "$VERDICT_FILE" || true

if [[ "$VERDICT" != "PASS" ]]; then
    printf 'verify-published-tags: the published-tag audit FAILED\n' >&2
    exit 1
fi

printf 'verify-published-tags: the published-tag audit PASSED\n' >&2
