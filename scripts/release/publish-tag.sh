#!/usr/bin/env bash
# publish-tag.sh — cut the release tag, verify its signature, and only then push it.
#
# The three steps are in this order for one reason: a tag that exists on the
# remote cannot be taken back. So the signature is checked while the tag is
# still local, and a tag that cannot be verified never becomes a tag somebody
# else can see. There is no unsigned fallback anywhere in this file, because an
# unsigned tag is the thing this whole path exists to stop shipping.
#
# Why it is a file of its own and not three lines inside release-pipelinek.sh:
# the guarantee worth keeping is an ORDERING ("verified before pushed"), and an
# ordering that can only be checked by reading the publication script is an
# ordering nobody can falsify. Split out, the same code that publishes the tag
# can be pointed at a local bare repository and observed: does the remote end
# up holding a tag whose signature does not verify? That question is asked of
# the shipping code, not of a copy of it.
#
# What the signature proves, and what it does not:
#   it PROVES possession of the private key;
#   it does NOT prove that the holder of that key owns the repository. The
#   handoff says so in words, so nobody reads a valid signature as a
#   confirmed identity.
#
# Usage:
#   publish-tag.sh --tag <name> --candidate-id <sha256:...> \
#       --expect-commit <sha> --fingerprint <FPR> --uid <uid> \
#       --out <file> [--remote <name>]
#
# Exit codes: 0 = the tag was signed, verified and pushed; 1 = refused;
# 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

TAG=""
CANDIDATE_ID=""
EXPECT_COMMIT=""
FINGERPRINT=""
UID_VALUE=""
OUT_FILE=""
REMOTE="origin"

usage() {
    sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)           TAG="${2:-}"; shift 2 ;;
        --candidate-id)  CANDIDATE_ID="${2:-}"; shift 2 ;;
        --expect-commit) EXPECT_COMMIT="${2:-}"; shift 2 ;;
        --fingerprint)   FINGERPRINT="${2:-}"; shift 2 ;;
        --uid)           UID_VALUE="${2:-}"; shift 2 ;;
        --out)           OUT_FILE="${2:-}"; shift 2 ;;
        --remote)        REMOTE="${2:-}"; shift 2 ;;
        -h|--help)       usage ;;
        *) printf 'publish-tag: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

if [[ -z "$TAG" || -z "$CANDIDATE_ID" || -z "$EXPECT_COMMIT" \
   || -z "$FINGERPRINT" || -z "$UID_VALUE" || -z "$OUT_FILE" ]]; then
    printf 'publish-tag: --tag, --candidate-id, --expect-commit, --fingerprint,\n' >&2
    printf '           --uid and --out are all required\n' >&2
    usage
fi

if [[ ! "$FINGERPRINT" =~ ^[0-9A-Fa-f]{40}$ ]]; then
    printf 'publish-tag: --fingerprint must be a 40-hex fingerprint\n' >&2
    exit 2
fi

if ! git rev-parse --verify --quiet "${EXPECT_COMMIT}^{commit}" >/dev/null 2>&1; then
    printf 'publish-tag: the expected commit %s is not in this repository\n' "$EXPECT_COMMIT" >&2
    exit 2
fi

# ----------------------------------------------------------------------------
# 1. Sign. If the key is not usable there is no fallback: the tag is not
#    created at all, and the caller stops.
# ----------------------------------------------------------------------------

TAG_MESSAGE="PipelineK ${TAG} (candidate_id ${CANDIDATE_ID})"

# The tagger is the repository's own identity, stated explicitly so the tag
# does not inherit whatever identity the machine happens to be configured with.
tagger_args=()
tagger_name="$(git config user.name || true)"
tagger_email="$(git config user.email || true)"
if [[ -n "$tagger_name" && -n "$tagger_email" ]]; then
    tagger_args=(-c "user.name=${tagger_name}" -c "user.email=${tagger_email}")
fi

if ! git "${tagger_args[@]}" -c "user.signingkey=${FINGERPRINT}" \
    tag -s "$TAG" -m "$TAG_MESSAGE"; then
    printf 'publish-tag: refusing — the tag could not be signed.\n' >&2
    printf '           An unsigned tag is what this path exists to stop shipping.\n' >&2
    printf '           No tag was created and nothing was pushed.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 2. Verify, while the tag is still local. A refusal deletes the tag: a tag
#    that does not verify must not survive to be pushed by hand later.
# ----------------------------------------------------------------------------

if ! "${repo_root}/scripts/release/verify-tag-signature.sh" \
    --tag "$TAG" \
    --expect-fingerprint "$FINGERPRINT" \
    --expect-commit "$EXPECT_COMMIT" \
    --expect-uid "$UID_VALUE" \
    --out "$OUT_FILE"; then
    printf 'publish-tag: refusing — the tag signature did not verify.\n' >&2
    printf '           The local tag is deleted and nothing is pushed.\n' >&2
    printf '           The verdict is at %s\n' "$OUT_FILE" >&2
    git tag -d "$TAG" >&2 || true
    exit 1
fi

# ----------------------------------------------------------------------------
# 3. Push. Only now the tag is allowed to become visible.
# ----------------------------------------------------------------------------

if ! git push "$REMOTE" "$TAG"; then
    printf 'publish-tag: refusing — the tag could not be pushed to %s.\n' "$REMOTE" >&2
    printf '           The tag was signed and verified, and it exists locally.\n' >&2
    printf '           Delete it before retrying: this path refuses to reuse a tag.\n' >&2
    exit 1
fi

printf 'publish-tag: published %s (signed with %s, verified, pushed to %s)\n' \
    "$TAG" "$FINGERPRINT" "$REMOTE"
