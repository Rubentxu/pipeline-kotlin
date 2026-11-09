#!/usr/bin/env bash
# upload-sdk-bundle.sh — upload the SIGNED bundle to the remote destination.
#
# WHAT IT DOES, IN THIS ORDER, AND WHY THAT ORDER
#
#   1. refuses a bundle that is not a directory, and a verdict that is missing
#   2. refuses a verdict that does not ADMIT the publication, naming the reason
#   3. re-verifies the bundle: the digests and the signatures are recomputed
#   4. packages the bundle into a ZIP with the layout of a Maven repository
#   5. reads the credentials of the destination from the environment
#   6. uploads the ZIP and writes the receipt of the deployment
#
# The order is the guarantee. The verdict is read BEFORE anything is packaged,
# so a request the door refused never becomes a ZIP. The bundle is re-verified
# before it is packaged, so what travels is what was signed and not what the
# build happened to leave on disk. The credentials are read before the upload,
# so a missing secret does not become a half-published deployment.
#
# WHAT IT DOES NOT DO
#
# It does not decide whether the publication may happen: that decision is the
# verdict of the admission gate, and this script only reads it. It does not
# publish to a Maven-layout URL: the destination is ONE endpoint that accepts a
# bundle, because the service that accepted a push per file is retired.
#
# The bytes that travel are the bytes of the bundle that was signed. There is
# no second copy of them here, and a second copy would be a second set of
# bytes to keep in sync.
#
# The credentials never reach the command line: they are read from the
# environment and handed to the transport through its configuration on standard
# input, so they do not appear in the process list.
#
# Usage:
#   upload-sdk-bundle.sh --bundle <dir> --verdict <file> --url <url> \
#       --deployment <name> [--out <file>]
#
# Exit codes: 0 = the bundle was uploaded; 1 = refused; 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

BUNDLE=""
VERDICT=""
URL=""
DEPLOYMENT=""
OUT_FILE=""

# The declaration of the key that signs the artifacts, and the reader that is
# the only one allowed to read it. The bundle is verified against the key the
# declaration names, so a bundle signed with another key is refused here and
# not after the upload.
TRUST_CONF="$repo_root/scripts/release/maven-signing-key.conf"
CHECKER="$repo_root/scripts/check-sdk-bundle.py"
LANDING_REPO="$repo_root/pipelinek/build/sdk-repo"
MATURITY_CONTRACT="$repo_root/pipelinek/contract/published-contract-maturity.json"

# The list of assets the candidate publishes. It is written by the release gate,
# and when it is there the checker also proves that the bundle is NOT one of
# the assets already published: the bundle that travels is not the ZIP of the
# RC, and the digest of that ZIP does not move.
ASSETS_FILE="$repo_root/pipelinek/pipeline-release/build/candidate/published-assets.json"

# The credentials of the destination. They are read from the environment and
# never from a file of this repository.
USERNAME_VAR="SONATYPE_USERNAME"
PASSWORD_VAR="SONATYPE_PASSWORD"

fail() {
    printf 'upload-sdk-bundle: %s\n' "$1" >&2
    exit 1
}

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --bundle)     BUNDLE="${2:-}"; shift 2 ;;
        --verdict)    VERDICT="${2:-}"; shift 2 ;;
        --url)        URL="${2:-}"; shift 2 ;;
        --deployment) DEPLOYMENT="${2:-}"; shift 2 ;;
        --out)        OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)    usage ;;
        *)
            printf 'upload-sdk-bundle: unknown argument %s\n' "$1" >&2
            usage ;;
    esac
done

if [[ -z "$BUNDLE" || -z "$VERDICT" || -z "$URL" || -z "$DEPLOYMENT" ]]; then
    printf 'upload-sdk-bundle: --bundle, --verdict, --url and --deployment are all required\n' >&2
    usage
fi

# ── 1. the inputs ───────────────────────────────────────────────────────────────

[[ -d "$BUNDLE" ]] || fail "the bundle $BUNDLE is not a directory, so there is nothing to upload"
[[ -f "$VERDICT" ]] || fail "the verdict $VERDICT does not exist, so no publication was admitted"

# ── 2. the verdict ──────────────────────────────────────────────────────────────
#
# The verdict is the decision of the admission gate, and it is read here as the
# decision it is. A verdict that refuses is not a warning: the request must go
# back with its violations, and the bundle must not travel.
#
# The words are the model's, not this script's: the admission writes
# "[release] maven publication REFUSED" when it refuses a request whose
# documents it could not read, "maven publication REJECTED:" when it refuses a
# request it could read and could not admit, and "maven publication ADMITTED:"
# when it admits it. The two refusals are both refusals, and the marker of an
# admission is the one the model renders, so this script reads the decision
# instead of guessing it.
if grep -qE "maven publication (REFUSED|REJECTED)" "$VERDICT" 2>/dev/null; then
    printf 'upload-sdk-bundle: the publication was REFUSED, so nothing is uploaded.\n' >&2
    sed -n '1,40p' "$VERDICT" >&2
    exit 1
fi
if ! grep -q "maven publication ADMITTED:" "$VERDICT" 2>/dev/null; then
    fail "the verdict $VERDICT does not say that the publication was admitted, so nothing is uploaded"
fi

# ── 3. the bundle is re-verified ────────────────────────────────────────────────
#
# The signatures are re-verified and the digests recomputed against the bytes
# that are about to travel, not against what a previous run measured. A bundle
# whose bytes changed after it was signed is a bundle nobody can verify, and
# the verification is what says so before the upload and not after it.
source "$repo_root/scripts/release/read-signing-trust.sh"

current="$(signing_current_key "$TRUST_CONF")" \
    || fail "the declaration of the Maven signing key names no current key; the bundle cannot be verified"
FINGERPRINT="${current%%|*}"
[[ -n "$FINGERPRINT" ]] || fail "the declaration of the Maven signing key names no current fingerprint"

# The group the SDK publishes under, and the one it published under before the
# rename. A bundle carries ONE of them: the releases before the rename carry the
# old group and the releases after it carry the current one. Both are looked for,
# because a tool that only knew the current one would answer "there is nothing
# here" about a bundle of a previous release, and that answer would be a lie
# about the bundle and not about the tool.
group_path_in() {
    local repo="$1" candidate
    for candidate in dev/rubentxu/pipelinek dev/rubentxu/pipeline/v2; do
        if [[ -d "$repo/$candidate" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

GROUP_PATH="$(group_path_in "$BUNDLE")" \
    || fail "the bundle carries no SDK group (neither dev/rubentxu/pipelinek nor the dev/rubentxu/pipeline/v2 of the releases before the rename)"
VERSION="$(find "$BUNDLE/$GROUP_PATH" -mindepth 2 -maxdepth 2 -type d -printf '%f\n' 2>/dev/null | sort -u | head -1)"
[[ -n "$VERSION" ]] || fail "the bundle holds no version, so there is nothing to upload"

checker_args=(
    --repo "$BUNDLE"
    --maturity "$MATURITY_CONTRACT"
    --version "$VERSION"
    --fingerprint "$FINGERPRINT"
)
if [[ -d "$LANDING_REPO" ]]; then
    checker_args+=(--landing-repo "$LANDING_REPO")
fi
if [[ -f "$ASSETS_FILE" ]]; then
    checker_args+=(--assets "$ASSETS_FILE")
fi
# The report of the checker is written to a file of its own, and not to a
# fixed path: two uploads that run at the same time would share it, and the
# report of one would be the report of the other.
CHECK_REPORT="$(mktemp "${TMPDIR:-/tmp}/upload-sdk-bundle-check.XXXXXX")"
trap 'rm -f -- "$CHECK_REPORT"' EXIT

if ! python3 "$CHECKER" "${checker_args[@]}" > "$CHECK_REPORT" 2>&1; then
    cat "$CHECK_REPORT" >&2
    fail "the bundle does not pass its own checker, so it is not uploaded"
fi
grep -E "^(PASS|FAIL) (9|11)" "$CHECK_REPORT" || true

# ── 4. the bundle is packaged ───────────────────────────────────────────────────
#
# The ZIP carries the layout of a Maven repository, because that is what the
# destination accepts. The timestamps are fixed so that two packagings of the
# same bytes are the same file: a bundle whose digest changes with the clock is
# a bundle nobody can compare.
ZIP_DIR="$repo_root/pipelinek/build/sdk-upload"
mkdir -p -- "$ZIP_DIR"
ZIP_FILE="$ZIP_DIR/pipelinek-${VERSION}-bundle.zip"
rm -f -- "$ZIP_FILE"

find "$BUNDLE" -type f -exec env TZ=UTC touch -h -t 198001010000 {} + 2>/dev/null || true
(
    cd -- "$BUNDLE"
    find . -type f | sed 's|^\./||' | LC_ALL=C sort | zip -X -q "$ZIP_FILE" -@
) || fail "the bundle could not be packaged"

BUNDLE_DIGEST="$(sha256sum "$ZIP_FILE" | cut -d' ' -f1)"
printf 'bundle:            %s\n' "$ZIP_FILE"
printf 'bundle sha256:     %s\n' "$BUNDLE_DIGEST"

# ── 5. the credentials ──────────────────────────────────────────────────────────
#
# They are read AFTER the bundle is packaged, so the packaging is not lost, and
# they are read from the environment, so no secret is written down anywhere.
USERNAME="${!USERNAME_VAR:-}"
PASSWORD="${!PASSWORD_VAR:-}"
missing=()
[[ -n "$USERNAME" ]] || missing+=("$USERNAME_VAR")
[[ -n "$PASSWORD" ]] || missing+=("$PASSWORD_VAR")
if [[ ${#missing[@]} -gt 0 ]]; then
    printf 'upload-sdk-bundle: the bundle was packaged and NOT uploaded.\n' >&2
    printf '           the missing credentials: %s\n' "${missing[*]}" >&2
    printf '           They are read from the environment and never from a file of this\n' >&2
    printf '           repository, and the account is a fact about the database of\n' >&2
    printf '           somebody else: nothing depends on it, and no byte travels\n' >&2
    printf '           without them.\n' >&2
    exit 1
fi

# ── 6. the upload ───────────────────────────────────────────────────────────────
#
# The token goes to the transport through its configuration on standard input,
# so it does not appear in the process list of a machine with other users.
BEARER="$(printf '%s:%s' "$USERNAME" "$PASSWORD" | base64 -w0)"

set +e
RESPONSE="$(printf 'header = "Authorization: Bearer %s"\n' "$BEARER" \
    | curl --config - --fail-with-body --silent --show-error \
        -X POST "$URL" \
        -F "bundle=@${ZIP_FILE}" \
        -F "name=${DEPLOYMENT}" \
        -F "publishingType=AUTOMATIC" 2>&1)"
STATUS=$?
set -e

if [[ $STATUS -ne 0 ]]; then
    printf 'upload-sdk-bundle: the destination refused the bundle.\n' >&2
    printf '%s\n' "$RESPONSE" >&2
    printf '           The bundle was NOT published.\n' >&2
    exit 1
fi

# The destination answers with the identifier of the deployment it created, and the
# shape of that answer is not fixed: the endpoint this script posts to answers with
# the identifier ALONE, as a bare UUID, and other shapes of the answer carry it
# inside a JSON object. A parser that only knows one of them reads "unknown" for an
# upload that succeeded, and a receipt that names nothing is a receipt nobody can
# look the deployment up with.
#
# So the identifier is read from every shape that has ever been measured, and a
# response with NO identifier in it is REFUSED: an upload whose identifier cannot be
# read is not an upload this script can account for, and the destination may well
# have published the bytes.
deployment_id_from_response() {
    local response="$1"

    # A bare identifier: the body is the identifier and nothing else.
    local bare
    bare="$(printf '%s' "$response" | tr -d '[:space:]')"
    if [[ "$bare" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
        printf '%s' "$bare"
        return 0
    fi

    # An object that carries the identifier in a field.
    local field
    field="$(printf '%s' "$response" \
        | sed -n 's/.*"deploymentId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
    [[ -n "$field" ]] && { printf '%s' "$field"; return 0; }

    # A JSON string that carries the identifier.
    local quoted
    quoted="$(printf '%s' "$response" | sed -n 's/^[[:space:]]*"\([^"]*\)"[[:space:]]*$/\1/p' | head -1)"
    [[ -n "$quoted" ]] && { printf '%s' "$quoted"; return 0; }

    return 1
}

DEPLOYMENT_ID="$(deployment_id_from_response "$RESPONSE")" || {
    printf 'upload-sdk-bundle: the bundle was uploaded, but the destination did not name the deployment.\n' >&2
    printf '%s\n' "$RESPONSE" >&2
    printf '           The bytes MAY be published. The deployment is called %s and the state of\n' "$DEPLOYMENT" >&2
    printf '           the destination is read from its deployments list. This script does not\n' >&2
    printf '           guess an identifier, and it does not report an upload it cannot name.\n' >&2
    exit 1
}

printf 'deployment id:     %s\n' "$DEPLOYMENT_ID"
printf 'destination:       %s\n' "$URL"

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
    {
        printf 'deployment_id=%s\n' "$DEPLOYMENT_ID"
        printf 'deployment_name=%s\n' "$DEPLOYMENT"
        printf 'bundle_sha256=%s\n' "$BUNDLE_DIGEST"
        printf 'product_version=%s\n' "$VERSION"
        printf 'signing_key=%s\n' "$FINGERPRINT"
    } > "$OUT_FILE"
fi

printf 'upload-sdk-bundle: the bundle was uploaded (%s).\n' "$DEPLOYMENT_ID"
