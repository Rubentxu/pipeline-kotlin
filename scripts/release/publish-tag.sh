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
#       --out <file> [--remote <name>] [--at <commit>] [--promotes <tag>] \
#       [--attestation <file>]
#
# --at <commit> places the tag on that commit instead of on HEAD. It is what
# a STABLE promotion needs: the stable tag must point at the commit the
# candidate was built from, and the release may be cut from a different HEAD.
# --promotes <tag> names the tag this one promotes, and the name goes into
# the tag's own message, so the tag says what it promotes instead of leaving
# a reader to guess.
#
# --attestation <file> is the attestation of the promotion: the claims the
# tag carries, one per line, in the spelling the consumer of a promotion
# reads. A stable tag without them says nothing a consumer can compare, so
# the tag that carries the claims is the one that can be promoted; the
# claims themselves are checked by the consumer that has the signed result
# to compare them against.
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
AT_COMMIT=""
PROMOTES=""
ATTESTATION=""

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
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
        --at)            AT_COMMIT="${2:-}"; shift 2 ;;
        --promotes)      PROMOTES="${2:-}"; shift 2 ;;
        --attestation)   ATTESTATION="${2:-}"; shift 2 ;;
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

# The commit the tag is placed on, when the caller names one. It is resolved
# HERE, before anything is signed, so a typo is a usage error and not a tag
# that points somewhere surprising.
if [[ -n "$AT_COMMIT" ]]; then
    at_named="$AT_COMMIT"
    if ! AT_COMMIT="$(git rev-parse --verify --quiet "${at_named}^{commit}" 2>/dev/null)"; then
        printf 'publish-tag: the commit %s to tag is not in this repository\n' "$at_named" >&2
        exit 2
    fi
    if [[ "$AT_COMMIT" != "$EXPECT_COMMIT" ]]; then
        printf 'publish-tag: the commit to tag (%s) is not the expected commit (%s)\n' \
            "$AT_COMMIT" "$EXPECT_COMMIT" >&2
        printf '           a tag that points somewhere other than the expected commit is a\n' >&2
        printf '           tag that says one thing and means another\n' >&2
        exit 2
    fi
fi

# ----------------------------------------------------------------------------
# 1b. The attestation, when the tag carries one.
#
#     A promotion is read by a consumer that compares the tag's claims with
#     the signed certification that binds the bytes the tag carries. Those
#     claims are therefore written IN the tag, one per line, in the spelling
#     that reader looks for: a claim inside a sentence is not a line, and a
#     line that is not there is a claim nobody can compare.
#
#     What is checked HERE is what the publisher knows the truth about: the
#     shape of the claims, and the three of them this repository can check
#     against itself -- the candidate the tag names, the prerelease it
#     promotes, and the format it claims to speak. The digests are claims
#     about bytes somebody else measured, and the consumer that holds the
#     signed result is the one that compares them.
# ----------------------------------------------------------------------------

ATTESTATION_CLAIMS=""

if [[ -n "$ATTESTATION" ]]; then
    if [[ ! -f "$ATTESTATION" ]]; then
        printf 'publish-tag: the attestation %s does not exist\n' "$ATTESTATION" >&2
        exit 2
    fi
    ATTESTATION_CLAIMS="$(cat -- "$ATTESTATION")"
    if [[ -z "${ATTESTATION_CLAIMS//[[:space:]]/}" ]]; then
        printf 'publish-tag: the attestation %s is empty\n' "$ATTESTATION" >&2
        printf '           a tag with no claims carries nothing a consumer can compare.\n' >&2
        exit 1
    fi

    # Every claim is read as a WHOLE LINE, and a claim that appears twice is
    # a document that says two things: the reader takes the first one and
    # nobody notices the second.
    for claim in Promotion-Format RC-Tag RC-Commit Candidate-ID \
                 Certification-Result-ID ZIP-SHA256 Manifest-SHA256 SBOM-SHA256; do
        claim_count="$(printf '%s\n' "$ATTESTATION_CLAIMS" | grep -c "^${claim}: " || true)"
        if [[ "$claim_count" != "1" ]]; then
            printf 'publish-tag: the attestation does not carry the claim %s exactly once.\n' \
                "$claim" >&2
            printf '           it carries it %s time(s): a claim that is missing is a claim\n' \
                "$claim_count" >&2
            printf '           nobody can read, and a claim that appears twice is a document\n' >&2
            printf '           that says two things about the same fact.\n' >&2
            exit 1
        fi
    done

    attestation_value() {
        printf '%s\n' "$ATTESTATION_CLAIMS" | grep "^$1: " | head -1 | sed "s/^$1: //"
    }

    if [[ "$(attestation_value Promotion-Format)" != "pipelinek-ga-attestation-v1" ]]; then
        printf 'publish-tag: the attestation does not speak the pipelinek-ga-attestation-v1 format.\n' >&2
        printf '           it says %s, and a tag that speaks another format cannot be\n' \
            "$(attestation_value Promotion-Format)" >&2
        printf '           compared with the certification it claims.\n' >&2
        exit 1
    fi

    if [[ "$(attestation_value Candidate-ID)" != "$CANDIDATE_ID" ]]; then
        printf 'publish-tag: the attestation names candidate %s and the tag names %s.\n' \
            "$(attestation_value Candidate-ID)" "$CANDIDATE_ID" >&2
        printf '           a tag that carries a claim about other bytes says one thing and\n' >&2
        printf '           means another.\n' >&2
        exit 1
    fi

    if [[ -z "$PROMOTES" ]]; then
        printf 'publish-tag: the attestation names the prerelease it promotes, but the tag\n' >&2
        printf '           was not told which one. A promotion tag that does not say what\n' >&2
        printf '           it promotes is a tag nobody can bind to a certification.\n' >&2
        exit 1
    fi

    if [[ "$(attestation_value RC-Tag)" != "$PROMOTES" ]]; then
        printf 'publish-tag: the attestation promotes %s and the tag promotes %s.\n' \
            "$(attestation_value RC-Tag)" "$PROMOTES" >&2
        printf '           the two have to be the same prerelease: a tag that promotes one\n' >&2
        printf '           and claims another cannot be compared with either.\n' >&2
        exit 1
    fi

    for digest_claim in ZIP-SHA256 Manifest-SHA256 SBOM-SHA256; do
        if ! printf '%s' "$(attestation_value "$digest_claim")" | grep -Eq '^[0-9a-f]{64}$'; then
            printf 'publish-tag: the attestation carries no usable %s: it says %s.\n' \
                "$digest_claim" "$(attestation_value "$digest_claim")" >&2
            printf '           a claim that is not a digest is not a claim about bytes.\n' >&2
            exit 1
        fi
    done

    if ! printf '%s' "$(attestation_value RC-Commit)" | grep -Eq '^[0-9a-f]{40}$'; then
        printf 'publish-tag: the attestation carries no usable RC-Commit: it says %s.\n' \
            "$(attestation_value RC-Commit)" >&2
        printf '           a claim that is not a commit is not a claim about the tree.\n' >&2
        exit 1
    fi
fi

# ----------------------------------------------------------------------------
# 1. Sign. If the key is not usable there is no fallback: the tag is not
#    created at all, and the caller stops.
# ----------------------------------------------------------------------------

# The candidate is stated as a CLAIM, one per line, in the spelling the
# promotion gate reads: the tag that names the candidate is the evidence a
# consumer checks, so the claim has to be a line a reader can find and a
# machine can compare, not a word inside a sentence.
#
# The line is written by the tool that MEASURED the bytes, so the digest in
# the published provenance is the digest of the artifact that was certified.
TAG_MESSAGE="PipelineK ${TAG}"
if [[ -n "$PROMOTES" ]]; then
    TAG_MESSAGE="${TAG_MESSAGE} (promotes ${PROMOTES})"
fi
if [[ -n "$ATTESTATION" ]]; then
    # The attestation IS the message of a promotion tag: the claims it
    # carries are what the consumer compares, and a tag that carries them
    # AND says something else is a tag with two messages.
    TAG_MESSAGE="${TAG_MESSAGE}

${ATTESTATION_CLAIMS}"
else
    TAG_MESSAGE="${TAG_MESSAGE}
Candidate-ID: ${CANDIDATE_ID}"
fi

# The tagger is the repository's own identity, stated explicitly so the tag
# does not inherit whatever identity the machine happens to be configured with.
tagger_args=()
tagger_name="$(git config user.name || true)"
tagger_email="$(git config user.email || true)"
if [[ -n "$tagger_name" && -n "$tagger_email" ]]; then
    tagger_args=(-c "user.name=${tagger_name}" -c "user.email=${tagger_email}")
fi

SIGN_RC=0
if [[ -n "$AT_COMMIT" ]]; then
    git "${tagger_args[@]}" -c "user.signingkey=${FINGERPRINT}" \
        tag -s "$TAG" -m "$TAG_MESSAGE" "$AT_COMMIT" || SIGN_RC=$?
else
    git "${tagger_args[@]}" -c "user.signingkey=${FINGERPRINT}" \
        tag -s "$TAG" -m "$TAG_MESSAGE" || SIGN_RC=$?
fi

if [[ "$SIGN_RC" != "0" ]]; then
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
