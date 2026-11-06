#!/usr/bin/env bash
# verify-maven-publication.sh — the publication gate of the Maven channel.
#
# The question it answers:
#   "May this request publish this Maven bundle?"
#
# Why it exists. A Maven repository is a set of files with digests, and a
# version that is published is a version consumers resolve. Nothing stops a
# publisher from writing bytes that were never certified, from publishing a
# version that was published before under another candidate, or from
# overwriting a version that consumers already resolve. Every one of those is
# a publication that cannot be taken back, because the bytes are already in
# the hands of the consumers that resolved them.
#
# So the publication asks, and this gate is what answers. It refuses when:
#   - the request is not presented with the COMPLETE certification of its
#     candidate: a certification is judged by the model the promotion path
#     already uses, so a partial one — the canaries and nothing else — is
#     refused with the missing part NAMED;
#   - the request names a candidate the manifest does not name, or a version
#     the manifest does not describe: a request for a version that is not
#     this candidate's is a request to publish a version that was published
#     before;
#   - the manifest on disk is not the manifest the request names;
#   - the contracts of the bundle are not the contracts of the candidate's
#     SDK ZIP, or the bytes to publish are not the bytes the manifest
#     describes: a path that rebuilds an artifact after the certification is
#     a path that publishes something nobody certified;
#   - the target repository already holds that version with DIFFERENT bytes:
#     a published version is never replaced, the correct action is a new
#     version.
#
# The decision lives in the product (`:pipeline-release`), not in this script.
# This one resolves the paths, hands them to the task that runs the decision,
# and propagates the exit code. It derives no rule, so the publication path
# and the tested model cannot disagree.
#
# The verdict is written by the model itself, and not by this script: the
# build machinery's noise around a refusal ("BUILD FAILED") is not a verdict,
# and a report that has to carry it around is a report nobody reads to the
# end. When the model produced no verdict at all, the build's own output is
# shown instead, because that is the evidence of what broke.
#
# Why it is neither a scenario in examples/ nor a unit test:
#   - the scenarios in examples/ run the DEVELOPMENT distribution, and this
#     check consumes a bundle, a manifest and a certification that no
#     distribution produces;
#   - a unit test may not shell out, and this one builds a module and reads
#     directories.
# It is therefore a RELEASE gate, like the certification gate it sits beside.
#
# Usage:
#   verify-maven-publication.sh --request <file> --manifest <file> \
#       --certification <file> --bundle <dir> --sdk-zip <file> \
#       --published <dir> [--out <file>]
#
# --published is the state of the target repository, read from a directory
# that holds the layout of a Maven repository. There is no remote configured
# in this tree, so the state is the state of the mirror the operator names.
# A directory that cannot be read is NOT a repository without the version:
# the gate refuses instead.
#
# Exit codes: 0 = the request may publish the bundle; 1 = refused;
# 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

REQUEST=""
MANIFEST=""
CERTIFICATION=""
BUNDLE=""
SDK_ZIP=""
PUBLISHED=""
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
        --request)       REQUEST="${2:-}"; shift 2 ;;
        --manifest)      MANIFEST="${2:-}"; shift 2 ;;
        --certification) CERTIFICATION="${2:-}"; shift 2 ;;
        --bundle)        BUNDLE="${2:-}"; shift 2 ;;
        --sdk-zip)       SDK_ZIP="${2:-}"; shift 2 ;;
        --published)     PUBLISHED="${2:-}"; shift 2 ;;
        --out)           OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)       usage ;;
        *)
            printf 'verify-maven-publication: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

if [[ -z "$REQUEST" || -z "$MANIFEST" || -z "$CERTIFICATION" || -z "$BUNDLE" || -z "$SDK_ZIP" || -z "$PUBLISHED" ]]; then
    printf 'verify-maven-publication: --request, --manifest, --certification, --bundle, --sdk-zip and --published are all required\n' >&2
    usage
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

REQUEST="$(absolute_path "$REQUEST")"
MANIFEST="$(absolute_path "$MANIFEST")"
CERTIFICATION="$(absolute_path "$CERTIFICATION")"
BUNDLE="$(absolute_path "$BUNDLE")"
SDK_ZIP="$(absolute_path "$SDK_ZIP")"
PUBLISHED="$(absolute_path "$PUBLISHED")"

if [[ ! -f "$REQUEST" ]]; then
    printf 'verify-maven-publication: the request %s does not exist\n' "$REQUEST" >&2
    printf '           a request that names no bundle publishes nothing\n' >&2
    exit 1
fi

if [[ ! -f "$MANIFEST" ]]; then
    printf 'verify-maven-publication: the manifest %s does not exist\n' "$MANIFEST" >&2
    printf '           a request without a manifest publishes nothing anybody can check\n' >&2
    exit 1
fi

if [[ ! -d "$BUNDLE" ]]; then
    printf 'verify-maven-publication: the bundle %s is not a directory\n' "$BUNDLE" >&2
    printf '           the bytes that would be published were not measured\n' >&2
    exit 1
fi

if [[ ! -d "$PUBLISHED" ]]; then
    printf 'verify-maven-publication: the target repository %s could not be read\n' "$PUBLISHED" >&2
    printf '           the state of the version was not measured, and a repository that\n' >&2
    printf '           cannot be read is not a repository without the version\n' >&2
    exit 1
fi

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
fi

LOG_FILE="$(mktemp "${TMPDIR:-/tmp}/verify-maven-publication.XXXXXX")"

set +e
"${repo_root}/pipelinek/gradlew" --no-daemon --quiet -p "${repo_root}/pipelinek" \
    :pipeline-release:mavenPublication \
    -Pmaven.request="$REQUEST" \
    -Pmaven.manifest="$MANIFEST" \
    -Pmaven.certification="$CERTIFICATION" \
    -Pmaven.bundle="$BUNDLE" \
    -Pmaven.sdkZip="$SDK_ZIP" \
    -Pmaven.published="$PUBLISHED" \
    -Pmaven.verdict="$OUT_FILE" > "$LOG_FILE" 2>&1
STATUS=$?
set -e

if [[ -n "$OUT_FILE" && -f "$OUT_FILE" ]]; then
    cat "$OUT_FILE"
else
    printf 'verify-maven-publication: the gate produced no verdict.\n' >&2
    printf '           what the build said is below.\n' >&2
    cat "$LOG_FILE" >&2
fi

rm -f -- "$LOG_FILE"

exit "$STATUS"
