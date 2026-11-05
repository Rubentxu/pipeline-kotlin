#!/usr/bin/env bash
# emit-certification-requested.sh — leave the certification request event for a
# release candidate that has just been published.
#
# The event is what crosses the boundary between the two repositories: it names
# the candidate by digest, names the release it was published under, says when
# it was published, and carries the request that asks the receiver to certify
# exactly those bytes.
#
# It is emitted AT the publication and not at the build. A request about bytes
# the receiver cannot download yet is worse than no request, so the receipt of
# the publication is one of the inputs: a candidate that was built and never
# published leaves no receipt, and no receipt means no event.
#
# The composition lives in the product (`:pipeline-release`), not here. This
# script resolves the paths, hands them over to the task that runs the
# composition, and propagates the exit code. It derives no rule, so the
# release path and the tested model cannot disagree.
#
# The script does NOT say where the message lands. The reception contract
# does: it declares the inbox the receiver observes and the name the file
# carries, and the product lands the message there. A script that named the
# inbox would be a second authority about the same fact, and the two would
# disagree the day somebody changed one of them.
#
# Usage:
#   emit-certification-requested.sh --handoff <file> --request <file>
#       --publication-receipt <file> --published-at <instant>
#       --contract <file> --receipt-file <file>
#
# Exit codes: 0 = the event was emitted, or it was already emitted; 1 = the
# request was refused; 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

HANDOFF=""
REQUEST=""
PUBLICATION_RECEIPT=""
PUBLISHED_AT=""
CONTRACT=""
RECEIPT_FILE=""

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --handoff)           HANDOFF="${2:-}"; shift 2 ;;
        --request)           REQUEST="${2:-}"; shift 2 ;;
        --publication-receipt) PUBLICATION_RECEIPT="${2:-}"; shift 2 ;;
        --published-at)      PUBLISHED_AT="${2:-}"; shift 2 ;;
        --contract)          CONTRACT="${2:-}"; shift 2 ;;
        --receipt-file)      RECEIPT_FILE="${2:-}"; shift 2 ;;
        -h|--help)           usage ;;
        *) printf 'emit-certification-requested: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

for required in HANDOFF REQUEST PUBLICATION_RECEIPT PUBLISHED_AT CONTRACT RECEIPT_FILE; do
    if [[ -z "${!required}" ]]; then
        printf 'emit-certification-requested: --%s is required\n' \
            "$(printf '%s' "$required" | tr '[:upper:]' '[:lower:]')" >&2
        usage
    fi
done

for document in "$HANDOFF" "$REQUEST" "$PUBLICATION_RECEIPT" "$CONTRACT"; do
    if [[ ! -f "$document" ]]; then
        printf 'emit-certification-requested: the document %s does not exist\n' "$document" >&2
        printf '           an emission that reads nothing emits nothing, and says so\n' >&2
        exit 2
    fi
done

mkdir -p -- "$(dirname -- "$RECEIPT_FILE")"

"${repo_root}/pipelinek/gradlew" --no-daemon -p "${repo_root}/pipelinek" \
    :pipeline-release:certificationRequested \
    -Pcertification.handoff="$HANDOFF" \
    -Pcertification.request="$REQUEST" \
    -Pcertification.publicationReceipt="$PUBLICATION_RECEIPT" \
    -Pcertification.publishedAt="$PUBLISHED_AT" \
    -Pcertification.contract="$CONTRACT" \
    -Pcertification.receiptFile="$RECEIPT_FILE"
