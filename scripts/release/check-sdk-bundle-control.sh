#!/usr/bin/env bash
# check-sdk-bundle-control.sh — prove that the bundle checker can REFUSE.
#
# WHAT IT DOES. It copies the bundle, removes from the COPY one of the files
# that the Gradle module metadata of a coordinate resolves, and runs the
# checker against the copy. The checker MUST refuse it, and the refusal MUST
# name the file that is missing.
#
# WHY IT EXISTS. A checker that has never refused anything proves nothing: the
# bundle it accepted could be accepted just as well by a checker that accepts
# everything. The consumer's negative control says the same about the
# consumer, and it runs in the same lane.
#
# WHY THE METADATA AND NOT THE JAR. The Gradle module metadata is the document
# a consumer of Gradle resolves in PREFERENCE to the POM: it says which
# variant resolves and which file its bytes come from. A bundle that carries
# the metadata of a variant whose file it does not carry certifies a
# coordinate that no consumer can resolve, and the checker has to refuse it by
# name.
#
# WHAT IT DOES NOT DO. It does not touch the bundle that will be published:
# the COPY is what is tampered, and the copy lives in a temporary directory
# that is removed when this control ends. It does not publish anything, and it
# does not sign anything.
#
# Usage:
#   check-sdk-bundle-control.sh --repo <dir> [--checker <file>] [--version <v>]
#       [--fingerprint <key>] [--maturity <file>] [--landing-repo <dir>]
#       [--assets <file>]
#
# Exit codes: 0 = the checker refused the tampered bundle naming the file;
# 1 = the control failed (the checker accepted it, or refused it blind);
# 2 = usage error.

set -Eeuo pipefail

REPO=""
CHECKER=""
VERSION=""
FINGERPRINT=""
MATURITY=""
LANDING_REPO=""
ASSETS=""

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)         REPO="${2:-}"; shift 2 ;;
        --checker)      CHECKER="${2:-}"; shift 2 ;;
        --version)      VERSION="${2:-}"; shift 2 ;;
        --fingerprint)  FINGERPRINT="${2:-}"; shift 2 ;;
        --maturity)     MATURITY="${2:-}"; shift 2 ;;
        --landing-repo) LANDING_REPO="${2:-}"; shift 2 ;;
        --assets)       ASSETS="${2:-}"; shift 2 ;;
        -h|--help)      usage ;;
        *)
            printf 'check-sdk-bundle-control: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

[[ -n "$REPO" ]] || { printf 'check-sdk-bundle-control: --repo is required\n' >&2; exit 2; }
[[ -d "$REPO" ]] || { printf 'check-sdk-bundle-control: the bundle at %s is not a directory\n' "$REPO" >&2; exit 2; }

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ -n "$CHECKER" ]] || CHECKER="$repo_root/scripts/check-sdk-bundle.py"
[[ -f "$CHECKER" ]] || { printf 'check-sdk-bundle-control: there is no checker at %s\n' "$CHECKER" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-sdk-bundle-control.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

control_repo="$WORK/bundle"
cp -a "$REPO" "$control_repo"

# The metadata of the first coordinate of the copy, and the file its first
# variant resolves: the file that will be taken away.
metadata="$(find "$control_repo" -name '*.module' -type f | sort | head -1)"
[[ -n "$metadata" ]] || {
    printf 'check-sdk-bundle-control: the bundle carries no Gradle module metadata\n' >&2
    exit 1
}

victim="$(python3 - "$metadata" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
for variant in document.get("variants", []):
    for entry in variant.get("files", []):
        url = str(entry.get("url", ""))
        if url:
            print(url)
            raise SystemExit(0)
raise SystemExit("the metadata names no file")
PY
)" || {
    printf 'check-sdk-bundle-control: %s names no file to take away\n' "$metadata" >&2
    exit 1
}

rm -f -- "$(dirname "$metadata")/$victim"

checker_args=(--repo "$control_repo")
[[ -n "$VERSION" ]] && checker_args+=(--version "$VERSION")
[[ -n "$FINGERPRINT" ]] && checker_args+=(--fingerprint "$FINGERPRINT")
[[ -n "$MATURITY" ]] && checker_args+=(--maturity "$MATURITY")
[[ -n "$LANDING_REPO" ]] && checker_args+=(--landing-repo "$LANDING_REPO")
[[ -n "$ASSETS" ]] && checker_args+=(--assets "$ASSETS")

if python3 "$CHECKER" "${checker_args[@]}" > "$WORK/check.out" 2>&1; then
    cat "$WORK/check.out" >&2
    printf 'check-sdk-bundle-control: the checker accepted a bundle whose Gradle module metadata resolves a file the bundle does not carry\n' >&2
    exit 1
fi

if ! grep -E -- "variant.*$victim" "$WORK/check.out" > /dev/null; then
    cat "$WORK/check.out" >&2
    printf 'check-sdk-bundle-control: the checker refused the tampered bundle without naming the variant that resolves the file it does not carry\n' >&2
    exit 1
fi

printf 'negative control:    the checker refused a bundle whose metadata resolves a missing file, naming it (%s)\n' "$victim"
