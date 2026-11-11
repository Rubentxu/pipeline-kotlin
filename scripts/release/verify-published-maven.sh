#!/usr/bin/env bash
# verify-published-maven.sh — the consumer-side door of the Maven channel.
#
# What it does. It reads the publication manifest of a candidate and asks a
# repository whether it serves the bytes that manifest declares: every
# artifact, at its path, with its digest. The answer is a verdict that names
# what the repository served and what it did not.
#
# Why it exists. The receipt of an upload says that the bundle was handed
# over, and the registry that receives it processes it apart: for a while the
# repository answers that it holds nothing of the version, and a consumer
# that resolves in that window gets nothing at all. Nothing in the tree
# answered the question of what the repository serves AFTER the publication,
# so the statement "the SDK is published" rested on a measurement that lived
# outside the repository. This door is that measurement, and it can be run by
# anybody who has the manifest.
#
# The verdict is written by the model, not by this script: the script resolves
# the paths, invokes the task that runs the decision and propagates the exit
# code. It derives no rule, so the check and the tested model cannot disagree.
#
# The credential of a repository that asks for one is read from the
# environment and travels in the environment: PIPELINEK_REPOSITORY_USER and
# PIPELINEK_REPOSITORY_TOKEN. It is never an argument of this script, never a
# file in the repository and never part of the verdict.
#
# Usage:
#   verify-published-maven.sh --manifest <file> --repository <url> [--out <file>]
#
# Exit codes: 0 = the repository serves what the manifest declares; 1 = the
# repository does not serve it; 2 = the state could not be measured.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

MANIFEST=""
REPOSITORY=""
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
        --manifest)   MANIFEST="${2:-}"; shift 2 ;;
        --repository) REPOSITORY="${2:-}"; shift 2 ;;
        --out)        OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)    usage ;;
        *)
            printf 'verify-published-maven: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

if [[ -z "$MANIFEST" || -z "$REPOSITORY" ]]; then
    printf 'verify-published-maven: --manifest and --repository are both required\n' >&2
    usage
fi

if [[ ! -f "$MANIFEST" ]]; then
    printf 'verify-published-maven: the manifest %s does not exist\n' "$MANIFEST" >&2
    printf '           a manifest that names nothing says nothing about what the\n' >&2
    printf '           repository must serve\n' >&2
    exit 1
fi

# Every path handed to the build is absolute. The build resolves a relative
# path against its OWN project directory, and a path that means one thing to
# the caller and another to the build is a path that reads a file nobody
# meant.
absolute_path() {
    local path="$1"
    local dir base
    dir="$(dirname -- "$path")"
    base="$(basename -- "$path")"
    if [[ -d "$dir" ]]; then
        printf '%s/%s\n' "$(cd -- "$dir" && pwd)" "$base"
    else
        printf '%s\n' "$path"
    fi
}

MANIFEST="$(absolute_path "$MANIFEST")"
if [[ -n "$OUT_FILE" ]]; then
    OUT_FILE="$(absolute_path "$OUT_FILE")"
fi

LOG_FILE="$(mktemp "${TMPDIR:-/tmp}/verify-published-maven.XXXXXX")"

set +e
"${repo_root}/pipelinek/gradlew" --no-daemon --quiet -p "${repo_root}/pipelinek" \
    :pipeline-release:mavenResolution \
    -Pmaven.manifest="$MANIFEST" \
    -Pmaven.repository="$REPOSITORY" \
    -Pmaven.verdict="$OUT_FILE" > "$LOG_FILE" 2>&1
STATUS=$?
set -e

if [[ -n "$OUT_FILE" && -f "$OUT_FILE" ]]; then
    cat "$OUT_FILE"
else
    printf 'verify-published-maven: the check produced no verdict.\n' >&2
    printf '           what the build said is below.\n' >&2
    cat "$LOG_FILE" >&2
fi

rm -f -- "$LOG_FILE"

exit "$STATUS"
