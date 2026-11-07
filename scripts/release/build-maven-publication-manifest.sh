#!/usr/bin/env bash
# build-maven-publication-manifest.sh — write the publication manifest of the
# Maven channel, and the request that publishes it.
#
# What it does. It reads the candidate's handoff and the bundle that was
# staged for that candidate, and it writes the two documents of the channel:
#
#   maven-publication-manifest.json  what is published, with the digest of
#                                    every byte, bound to the candidate, to
#                                    its SDK ZIP and to its version
#   maven-publication-request.json   the ask: publish THAT manifest
#
# Why it is a program and not a document written by hand. Every digest in the
# manifest has to be the digest of the bytes, and the only way to know that
# is to measure them. A manifest written by hand is a promise that nobody
# keeps, and a promise that nobody keeps is what a publication is made of.
#
# The candidate's identity is NOT decided here. It is READ from the handoff,
# which is the document that already binds the candidate to its SDK ZIP and
# to its version. This script measures the bundle and transcribes the
# binding; it does not invent one.
#
# What it does NOT do. It does not publish. The publication is a separate
# door (scripts/release/verify-maven-publication.sh), and it decides whether
# the bundle may be published at all. There is no remote repository in this
# tree, so there is nothing to publish to: the account and the credentials of
# Maven Central are facts that do not come from the source tree.
#
# Usage:
#   build-maven-publication-manifest.sh --handoff <file> --bundle <dir> --out <dir>
#
# Exit codes: 0 = the manifest and the request were written; 1 = refused;
# 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

HANDOFF=""
BUNDLE=""
OUT_DIR=""

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --handoff) HANDOFF="${2:-}"; shift 2 ;;
        --bundle)  BUNDLE="${2:-}"; shift 2 ;;
        --out)     OUT_DIR="${2:-}"; shift 2 ;;
        -h|--help) usage ;;
        *)
            printf 'build-maven-publication-manifest: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

if [[ -z "$HANDOFF" || -z "$BUNDLE" || -z "$OUT_DIR" ]]; then
    printf 'build-maven-publication-manifest: --handoff, --bundle and --out are all required\n' >&2
    usage
fi

if [[ ! -f "$HANDOFF" ]]; then
    printf 'build-maven-publication-manifest: the handoff %s does not exist\n' "$HANDOFF" >&2
    printf '           a manifest that names no candidate is not a manifest\n' >&2
    exit 1
fi

if [[ ! -d "$BUNDLE" ]]; then
    printf 'build-maven-publication-manifest: the bundle %s is not a directory\n' "$BUNDLE" >&2
    printf '           the bytes were not measured, and a manifest of unmeasured bytes\n' >&2
    printf '           is a promise nobody keeps\n' >&2
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

HANDOFF="$(absolute_path "$HANDOFF")"
BUNDLE="$(absolute_path "$BUNDLE")"
OUT_DIR="$(absolute_path "$OUT_DIR")"

LOG_FILE="$(mktemp "${TMPDIR:-/tmp}/build-maven-publication-manifest.XXXXXX")"

set +e
"${repo_root}/pipelinek/gradlew" --no-daemon --quiet -p "${repo_root}/pipelinek" \
    :pipeline-release:mavenPublicationManifest \
    -Pmaven.handoff="$HANDOFF" \
    -Pmaven.bundle="$BUNDLE" \
    -Pmaven.out="$OUT_DIR" > "$LOG_FILE" 2>&1
STATUS=$?
set -e

if [[ "$STATUS" -eq 0 ]]; then
    cat "$LOG_FILE"
else
    printf 'build-maven-publication-manifest: the manifest was NOT written.\n' >&2
    printf '           what the build said is below.\n' >&2
    cat "$LOG_FILE" >&2
fi

rm -f -- "$LOG_FILE"

exit "$STATUS"
