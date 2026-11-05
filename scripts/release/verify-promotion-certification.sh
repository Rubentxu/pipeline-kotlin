#!/usr/bin/env bash
# verify-promotion-certification.sh — the certification gate of a promotion.
#
# The question it answers:
#   "Is there a certification of THIS candidate, and does it cover everything a
#    stable release demands?"
#
# Why it exists. A release candidate can be real, signed, coherent with itself
# and never certified: the tag is on the remote, it is annotated, its
# signature verifies, and it names the digest the stable release is about to
# carry. All of that is true of bytes nobody ever ran the certification
# battery against. Promoting those bytes publishes a stable release that no
# certification stands behind, and nothing in the promotion path would say so.
#
# So the promotion asks for the certification, and this gate is what answers.
# It refuses when the certification is not there, when it is about another
# candidate, and when it is PARTIAL: the canaries and nothing else is not a
# complete certification, it is a certification of something else. The refusal
# NAMES the part that is missing, because "the certification is not complete"
# is not something an operator can act on.
#
# What it does NOT do: it does not judge whether the certifier certified well.
# It checks the STRUCTURE of the certification — which candidate, which parts,
# which verdicts — and the honesty of the certifier is answered where the
# trust anchor lives, which is not here.
#
# The decision lives in the product (`:pipeline-release`), not in this script.
# This one resolves the paths, hands them to the task that runs the decision,
# and propagates the exit code. It derives no rule, so the promotion path and
# the tested model cannot disagree.
#
# The verdict is written by the model itself, and not by this script: the
# build machinery's noise around a refusal ("BUILD FAILED") is not a verdict,
# and a report that has to carry it around is a report nobody reads to the
# end. When the model produced no verdict at all, the build's own output is
# shown instead, because that is the evidence of what broke.
#
# Why it is neither a scenario in examples/ nor a unit test:
#   - the scenarios in examples/ run the DEVELOPMENT distribution built by
#     installDist; this check consumes a document produced by an external
#     certifier, which no distribution can produce;
#   - a unit test may not shell out, and this one builds a module.
# It is therefore a RELEASE gate, like the tag-signature check it sits beside.
#
# Usage:
#   verify-promotion-certification.sh --certification <file> \
#       --expect-candidate <sha256:...> [--out <file>]
#
# Exit codes: 0 = the certification admits the candidate; 1 = refused;
# 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

CERTIFICATION=""
EXPECT_CANDIDATE=""
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
        --certification)   CERTIFICATION="${2:-}"; shift 2 ;;
        --expect-candidate) EXPECT_CANDIDATE="${2:-}"; shift 2 ;;
        --out)             OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)         usage ;;
        *) printf 'verify-promotion-certification: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

if [[ -z "$CERTIFICATION" || -z "$EXPECT_CANDIDATE" ]]; then
    printf 'verify-promotion-certification: --certification and --expect-candidate are required\n' >&2
    usage
fi

if [[ ! -f "$CERTIFICATION" ]]; then
    printf 'verify-promotion-certification: the certification %s does not exist\n' "$CERTIFICATION" >&2
    printf '           a promotion without the certification of its candidate is not a\n' >&2
    printf '           promotion that anybody can account for\n' >&2
    exit 1
fi

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
fi

LOG_FILE="$(mktemp "${TMPDIR:-/tmp}/verify-promotion-certification.XXXXXX")"

set +e
"${repo_root}/pipelinek/gradlew" --no-daemon --quiet -p "${repo_root}/pipelinek" \
    :pipeline-release:promotionCertification \
    -Ppromotion.certification="$CERTIFICATION" \
    -Ppromotion.candidate="$EXPECT_CANDIDATE" \
    -Ppromotion.verdict="$OUT_FILE" > "$LOG_FILE" 2>&1
STATUS=$?
set -e

if [[ -n "$OUT_FILE" && -f "$OUT_FILE" ]]; then
    cat "$OUT_FILE"
else
    printf 'verify-promotion-certification: the gate produced no verdict.\n' >&2
    printf '           what the build said is below.\n' >&2
    cat "$LOG_FILE" >&2
fi

rm -f -- "$LOG_FILE"

exit "$STATUS"
