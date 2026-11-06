#!/usr/bin/env bash
# verify-signing-key-attribution.sh — is the declared signing key REGISTERED
# on the account that owns this repository?
#
# The question it answers:
#   "Is the key this repository declares registered as a GPG key on the
#    account that owns the repository?"
#
# WHY THE QUESTION EXISTS. A signature proves POSSESSION of the private key.
# It says nothing about WHO holds it, and a consumer who cannot place the key
# has to treat the publisher as unattributed. That binding is a fact about the
# account, not about the artifact: an account publishes a list of the GPG
# keys it vouches for, and a key on that list is one the account stands
# behind. The check reads that list from the public API, without credentials.
#
# WHY THE CHANNEL IS OUTSIDE THE ARTIFACT. A file that carries its own
# fingerprint and declares itself trusted proves nothing: anybody able to
# write the file is able to write the declaration, so the declaration is not
# evidence of the declaration. The channel is the account that owns the
# repository, and it is read from the account itself.
#
# TWO FACTS, NOT ONE. "The key is not registered" and "the registration could
# not be measured" are different facts, and they are reported differently. A
# check that could not run is NOT MEASURED, and it is never green: an
# unreachable API and an empty list look alike to a reader who only sees a
# boolean, and only one of the two means that the publisher is unattributed.
#
# The identity a key carries is a claim the key makes about itself. This
# check does not judge that claim and does not repeat it as an attribution:
# what it reports is whether the account vouches for the key.
#
# Usage:
#   verify-signing-key-attribution.sh [--owner <login>] [--fingerprint <FPR>]
#       [--conf <file>] [--remote <name>] [--api-base <url>] [--out <file>]
#
# Exit codes: 0 = the key is registered on the account; 1 = the key is not
# registered, or the registration could not be measured; 2 = usage error.

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/../.." && pwd)"
# The ONE reader of the declaration of the signing keys.
# shellcheck source=read-signing-trust.sh
source "${script_dir}/read-signing-trust.sh"

OWNER=""
FINGERPRINT=""
CONF="${repo_root}/scripts/release/signing-key.conf"
REMOTE="origin"
API_BASE="https://api.github.com"
OUT_FILE=""
TIMEOUT_SECONDS=20

usage() {
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --owner)      OWNER="${2:-}"; shift 2 ;;
        --fingerprint) FINGERPRINT="${2:-}"; shift 2 ;;
        --conf)       CONF="${2:-}"; shift 2 ;;
        --remote)     REMOTE="${2:-}"; shift 2 ;;
        --api-base)   API_BASE="${2:-}"; shift 2 ;;
        --out)        OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)    usage ;;
        *) printf 'verify-signing-key-attribution: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

# ----------------------------------------------------------------------------
# 1. The key, and the account.
# ----------------------------------------------------------------------------

if [[ -z "$FINGERPRINT" ]]; then
    if [[ ! -r "$CONF" ]]; then
        printf 'verify-signing-key-attribution: the declaration %s cannot be read\n' "$CONF" >&2
        exit 2
    fi
    CURRENT_KEY="$(signing_current_key "$CONF")" || {
        printf 'verify-signing-key-attribution: %s declares no current key\n' "$CONF" >&2
        exit 2
    }
    FINGERPRINT="${CURRENT_KEY%%|*}"
fi

if ! printf '%s' "$FINGERPRINT" | grep -Eq '^[0-9A-Fa-f]{40}$'; then
    printf 'verify-signing-key-attribution: the fingerprint must be forty hexadecimal characters\n' >&2
    exit 2
fi

FINGERPRINT="${FINGERPRINT^^}"

# The account is the one that owns the repository, read from the remote this
# repository publishes to. It is not a name a caller types: a caller that
# named another account would be checking a different question.
if [[ -z "$OWNER" ]]; then
    REMOTE_URL="$(git remote get-url "$REMOTE" 2>/dev/null || true)"
    if [[ -z "$REMOTE_URL" ]]; then
        printf 'verify-signing-key-attribution: the remote %s has no address to read an owner from\n' "$REMOTE" >&2
        exit 2
    fi
    if [[ "$REMOTE_URL" != *"github.com"* ]]; then
        printf 'verify-signing-key-attribution: the remote %s does not point at an account this check can read\n' "$REMOTE" >&2
        printf '    address: %s\n' "$REMOTE_URL" >&2
        printf '    The registration of a key is a fact about an account, and there is no account to ask.\n' >&2
        exit 2
    fi
    OWNER="$(printf '%s' "${REMOTE_URL%.git}" | sed -E 's#^.*[:/]([^/:]+)/[^/:]+$#\1#')"
fi

if [[ -z "$OWNER" ]]; then
    printf 'verify-signing-key-attribution: the owner of the repository could not be read\n' >&2
    exit 2
fi

# ----------------------------------------------------------------------------
# 2. The list the account publishes.
# ----------------------------------------------------------------------------

BODY_FILE="$(mktemp "${TMPDIR:-/tmp}/signing-attribution.XXXXXX")"
PARSED_FILE="$(mktemp "${TMPDIR:-/tmp}/signing-attribution-parsed.XXXXXX")"
cleanup() { rm -f -- "$BODY_FILE" "$PARSED_FILE"; }
trap cleanup EXIT

HTTP_STATUS=""
CURL_RC=0
HTTP_STATUS="$(curl -sS --max-time "$TIMEOUT_SECONDS" \
    -H 'Accept: application/vnd.github+json' \
    -o "$BODY_FILE" \
    -w '%{http_code}' \
    "${API_BASE}/users/${OWNER}/gpg_keys")" || CURL_RC=$?

if [[ "$CURL_RC" != "0" ]]; then
    printf 'verify-signing-key-attribution: the list of keys of %s could not be read\n' "$OWNER" >&2
    printf '    The registration is NOT MEASURED, and a measurement that did not happen is not a pass.\n' >&2
    exit 1
fi

if [[ "$HTTP_STATUS" != "200" ]]; then
    printf 'verify-signing-key-attribution: the account %s answered %s where a list of keys was expected\n' "$OWNER" "$HTTP_STATUS" >&2
    printf '    The registration is NOT MEASURED, and a measurement that did not happen is not a pass.\n' >&2
    exit 1
fi

# The list is read into lines of "key id | full fingerprint | address". The
# full fingerprint comes from the public key the account publishes, so the
# comparison is exact and not an approximation.
python3 - "$BODY_FILE" > "$PARSED_FILE" <<'PYTHON_EOF'
import json
import os
import subprocess
import sys
import tempfile

with open(sys.argv[1], encoding="utf-8") as handle:
    body = json.load(handle)

entries = body if isinstance(body, list) else []

for entry in entries:
    key_id = str(entry.get("key_id") or "").strip()
    raw_key = str(entry.get("raw_key") or "")
    emails = entry.get("emails") or []
    address = ""
    if emails and isinstance(emails[0], dict):
        address = str(emails[0].get("email") or "")
    fingerprint = ""
    if raw_key.strip().startswith("-----BEGIN"):
        descriptor, path = tempfile.mkstemp(prefix="signing-attribution-key-")
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as out:
                out.write(raw_key if raw_key.endswith("\n") else raw_key + "\n")
            done = subprocess.run(
                [
                    "gpg", "--batch", "--with-colons",
                    "--import-options", "show-only", "--import", path,
                ],
                capture_output=True, text=True, check=False,
            )
            for line in done.stdout.splitlines():
                fields = line.split(":")
                if fields[0] == "fpr" and len(fields) > 9:
                    fingerprint = fields[9]
                    break
        finally:
            os.unlink(path)
    print("%s|%s|%s" % (key_id, fingerprint, address))
PYTHON_EOF

KEYS_FOUND=0
MATCHED=""

while IFS='|' read -r key_id observed_fingerprint address; do
    [[ -n "$key_id" || -n "$observed_fingerprint" ]] || continue
    KEYS_FOUND=$((KEYS_FOUND + 1))
    [[ -z "$MATCHED" ]] || continue
    if [[ -n "$observed_fingerprint" && "${observed_fingerprint^^}" == "$FINGERPRINT" ]]; then
        MATCHED="${observed_fingerprint^^}"
        continue
    fi
    # Without the public key the account publishes, the long key id is what
    # the list carries, and it is the last sixteen characters of the
    # fingerprint of the primary key.
    if [[ -z "$observed_fingerprint" && -n "$key_id" ]]; then
        if [[ "${FINGERPRINT: -16}" == "${key_id^^}" ]]; then
            MATCHED="${FINGERPRINT}"
        fi
    fi
done < "$PARSED_FILE"

VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/signing-attribution-verdict.XXXXXX")"

{
    if [[ -n "$MATCHED" ]]; then
        printf 'signing-attribution: PASS owner=%s fingerprint=%s registered=yes keys=%s\n' \
            "$OWNER" "$FINGERPRINT" "$KEYS_FOUND"
        printf '  row A verdict=PASS the account %s vouches for the key %s\n' "$OWNER" "$MATCHED"
        printf '  row B verdict=PASS the registration is measured, and it is a registration\n'
    else
        printf 'signing-attribution: FAIL owner=%s fingerprint=%s registered=no keys=%s\n' \
            "$OWNER" "$FINGERPRINT" "$KEYS_FOUND"
        printf '  row A verdict=FAIL the account %s publishes %s key(s) and none of them is %s\n' \
            "$OWNER" "$KEYS_FOUND" "$FINGERPRINT"
        printf '  row B verdict=PASS the registration is measured, and it is the absence of one\n'
    fi
} > "$VERDICT_FILE"

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
    cp "$VERDICT_FILE" "$OUT_FILE"
fi

cat "$VERDICT_FILE" || true
rm -f -- "$VERDICT_FILE"

if [[ -z "$MATCHED" ]]; then
    printf 'verify-signing-key-attribution: the key is not registered on the account that owns this repository\n' >&2
    printf '    A publisher whose key is not registered on its own account stays unattributed,\n' >&2
    printf '    and a consumer has to treat every release it cuts as unattributed.\n' >&2
    exit 1
fi

printf 'verify-signing-key-attribution: the key is registered on the account that owns this repository\n' >&2
