#!/usr/bin/env bash
# verify-tag-signature.sh — the tag-signature gate, run BEFORE the tag is pushed.
#
# The question it answers:
#   "Is the release tag a signed tag, does the signature verify, and does it come
#    from the key this repository declares?"
#
# Why it exists. The published tag was ANNOTATED but NOT SIGNED. The SHA-256
# digest authority proves the bytes are the ones the manifest names; it says
# nothing about WHO published them. A digest is integrity, not provenance: a
# consumer can verify that a ZIP matches a SHA256SUMS file and still have no
# answer to "who cut this tag". A signature closes that half, and this script
# is the thing that says the signature was checked rather than merely made.
#
# Why it is neither a scenario in examples/ nor a unit test:
#   - the scenarios in examples/ run the DEVELOPMENT distribution built by
#     installDist; this check consumes a GIT TAG, which is not an artefact that
#     distribution can produce;
#   - a unit test may not shell out, and this check runs `git` and `gpg`.
# It is therefore a RELEASE gate: scripts/release-pipelinek.sh signs the tag,
# runs this check, and only then pushes the tag. A red row stops the push.
#
# The rows:
#   A  the tag is annotated, carries a signature, and that signature VERIFIES
#   B  the signature comes from the EXPECTED fingerprint (and uid, when given)
#   C  the tag points at the EXPECTED commit
#
# Row B is the one that makes the check worth running. Without it, "the
# signature verifies" would only mean "gpg is happy with whatever key it found",
# and a machine with the wrong key in its ring would sign, verify and pass.
# The fingerprint the signature must carry is declared in the repository
# (scripts/release/signing-key.conf), so rotating the key is a reviewed change
# and the release path cannot be silently redirected to another key by editing
# a local git config.
#
# What the check does NOT do: it does not judge whether the key belongs to the
# publisher. A signature proves POSSESSION of the private key. Binding that key
# to a person or to a hosting account is a separate fact, established outside
# the repository (GitHub's "Verified" badge, a published fingerprint on a
# channel the publisher controls). This script reports the fingerprint and the
# uid the key carries and leaves the attribution to the reader.
#
# Usage:
#   verify-tag-signature.sh --tag <name|sha> --expect-fingerprint <FPR> \
#       [--expect-commit <sha>] [--expect-uid <uid>] [--out <file>]
#
# Exit codes: 0 = every row PASS, 1 = at least one row FAIL, 2 = usage error.

set -Eeuo pipefail

TAG=""
EXPECT_FINGERPRINT=""
EXPECT_COMMIT=""
EXPECT_UID=""
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
        --tag)              TAG="${2:-}"; shift 2 ;;
        --expect-fingerprint) EXPECT_FINGERPRINT="${2:-}"; shift 2 ;;
        --expect-commit)    EXPECT_COMMIT="${2:-}"; shift 2 ;;
        --expect-uid)       EXPECT_UID="${2:-}"; shift 2 ;;
        --out)              OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)          usage ;;
        *) printf 'verify-tag-signature: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

if [[ -z "$TAG" || -z "$EXPECT_FINGERPRINT" ]]; then
    printf 'verify-tag-signature: --tag and --expect-fingerprint are required\n' >&2
    usage
fi

if [[ ! "$EXPECT_FINGERPRINT" =~ ^[0-9A-Fa-f]{40}$ ]]; then
    printf 'verify-tag-signature: --expect-fingerprint must be a 40-hex fingerprint\n' >&2
    printf '    (got %s).\n' "$EXPECT_FINGERPRINT" >&2
    exit 2
fi

EXPECT_FINGERPRINT_UPPER="${EXPECT_FINGERPRINT^^}"

STARTED_AT=$(date +%s)

# The verdict is assembled in a scratch file so it can be copied to --out and
# printed without running the printer twice. It is removed on every exit path:
# this check leaves nothing behind that a later run could mistake for evidence.
VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/tag-signature-verdict.XXXXXX")"
cleanup() { rm -f -- "$VERDICT_FILE"; }
trap cleanup EXIT

ROW_NAMES=()
ROW_VERDICTS=()
ROW_DETAILS=()

record() {
    ROW_NAMES+=("$1")
    ROW_VERDICTS+=("$2")
    ROW_DETAILS+=("$3")
}

# ----------------------------------------------------------------------------
# 1. The tag must exist, and it must be an ANNOTATED tag.
#    A lightweight tag points straight at the commit and carries no tag object,
#    so there is nothing to sign and nothing to verify. git says so explicitly
#    ("cannot verify a non-tag object of type commit"), and that is a distinct
#    fact from "the tag exists but its signature does not verify".
# ----------------------------------------------------------------------------

if ! git rev-parse --verify --quiet "${TAG}^{tag}" >/dev/null 2>&1; then
    record A FAIL "the tag does not exist locally, or it is not an annotated tag"
    record B FAIL "not measured: there is no tag object to read the signature from"
    record C FAIL "not measured: there is no tag object to read the target from"
else
    # ----------------------------------------------------------------------------
    # 2. The signature.
    #    `git verify-tag --raw` speaks GnuPG's status protocol on stderr, which
    #    is machine-readable and does not depend on the human wording of gpg's
    #    output (which changes with the locale). The lines that matter:
    #      VALIDSIG <fpr> ...   the signature verified and this is the key
    #      GOODSIG <keyid> <uid>   the signature is good, from that key
    #      BADSIG <keyid> <uid>    the signature does NOT verify
    #      NO_PUBKEY / ERRSIG      the key is not available here: UNCHECKABLE
    #      "no signature found"    the tag object carries no signature at all
    # ----------------------------------------------------------------------------

    VERIFY_OUTPUT=""
    VERIFY_RC=0
    VERIFY_OUTPUT="$(git verify-tag --raw "$TAG" 2>&1)" || VERIFY_RC=$?

    SIGNER_FPR=""
    SIGNER_UID=""
    if printf '%s\n' "$VERIFY_OUTPUT" | grep -q '^\[GNUPG:\] VALIDSIG '; then
        SIGNER_FPR="$(printf '%s\n' "$VERIFY_OUTPUT" | awk '/^\[GNUPG:\] VALIDSIG /{print $3; exit}')"
    fi
    if printf '%s\n' "$VERIFY_OUTPUT" | grep -q '^\[GNUPG:\] GOODSIG '; then
        SIGNER_UID="$(printf '%s\n' "$VERIFY_OUTPUT" | sed -n 's/^\[GNUPG:\] GOODSIG [0-9A-Fa-f]* //p' | head -1)"
    fi

    if printf '%s\n' "$VERIFY_OUTPUT" | grep -q '^\[GNUPG:\] BADSIG '; then
        record A FAIL "the signature does not verify: the tag content was altered after it was signed"
    elif printf '%s\n' "$VERIFY_OUTPUT" | grep -q '^\[GNUPG:\] NO_PUBKEY '; then
        record A FAIL "the signing key is not available in this keyring, so the signature cannot be checked"
    elif printf '%s\n' "$VERIFY_OUTPUT" | grep -qi 'no signature found'; then
        record A FAIL "the tag carries no signature"
    elif [[ "$VERIFY_RC" != "0" ]]; then
        record A FAIL "git verify-tag failed (exit ${VERIFY_RC}): $(printf '%s\n' "$VERIFY_OUTPUT" | tr '\n' ' ')"
    elif [[ -z "$SIGNER_FPR" ]]; then
        record A FAIL "git verify-tag exited zero but reported no VALIDSIG: the signature was not checked"
    else
        record A PASS "the signature verifies cryptographically"
    fi

    # ----------------------------------------------------------------------------
    # 3. The identity of the signer.
    #    The fingerprint that signed must be the one this repository declares.
    #    The uid is compared too when the caller asks for it: the fingerprint
    #    identifies the key, the uid is what that key CLAIMS, and a key whose
    #    uid changed is a key somebody else should look at.
    # ----------------------------------------------------------------------------

    if [[ -z "$SIGNER_FPR" ]]; then
        record B FAIL "not measured: no signature was read from the tag"
    elif [[ "${SIGNER_FPR^^}" != "$EXPECT_FINGERPRINT_UPPER" ]]; then
        record B FAIL "the signature comes from ${SIGNER_FPR}, not from the declared ${EXPECT_FINGERPRINT_UPPER}"
    elif [[ -n "$EXPECT_UID" && "$SIGNER_UID" != "$EXPECT_UID" ]]; then
        record B FAIL "the signing key claims uid '${SIGNER_UID}', not the declared '${EXPECT_UID}'"
    else
        record B PASS "the signature comes from the declared key"
    fi

    # ----------------------------------------------------------------------------
    # 4. The target of the tag.
    #    A signature proves the tag was signed; it says nothing about what the
    #    tag points at. The tag must still land on the commit the artefacts were
    #    built from, which is the other half of the release's identity claim.
    # ----------------------------------------------------------------------------

    TAG_COMMIT="$(git rev-parse --verify --quiet "${TAG}^{commit}" 2>/dev/null || true)"
    if [[ -z "$TAG_COMMIT" ]]; then
        record C FAIL "the tag does not resolve to a commit"
    elif [[ -z "$EXPECT_COMMIT" ]]; then
        record C PASS "the tag points at ${TAG_COMMIT} (no expected commit was given)"
    elif [[ "$TAG_COMMIT" != "$EXPECT_COMMIT" ]]; then
        record C FAIL "the tag points at ${TAG_COMMIT}, not at the expected ${EXPECT_COMMIT}"
    else
        record C PASS "the tag points at the expected commit"
    fi
fi

# ----------------------------------------------------------------------------
# 5. The verdict. One line, and it says what ran.
# ----------------------------------------------------------------------------

ELAPSED=$(( $(date +%s) - STARTED_AT ))
FAILED=0
SUMMARY_ROWS=""
for index in "${!ROW_NAMES[@]}"; do
    SUMMARY_ROWS+="${ROW_NAMES[$index]}:${ROW_VERDICTS[$index]} "
    if [[ "${ROW_VERDICTS[$index]}" == "FAIL" ]]; then
        FAILED=1
    fi
done
SUMMARY_ROWS="${SUMMARY_ROWS% }"

if [[ "$FAILED" == "0" ]]; then
    VERDICT="PASS"
else
    VERDICT="FAIL"
fi

{
    printf 'tag-signature: %s rows=%s tag=%s fingerprint=%s uid=%s commit=%s elapsed=%ss\n' \
        "$VERDICT" "$SUMMARY_ROWS" "$TAG" "$EXPECT_FINGERPRINT_UPPER" \
        "${EXPECT_UID:-<any>}" "${EXPECT_COMMIT:-<any>}" "$ELAPSED"
    for index in "${!ROW_NAMES[@]}"; do
        printf '  row %s verdict=%s %s\n' \
            "${ROW_NAMES[$index]}" "${ROW_VERDICTS[$index]}" "${ROW_DETAILS[$index]}"
    done
} > "$VERDICT_FILE"

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
    cp "$VERDICT_FILE" "$OUT_FILE"
fi
# `|| true`: a reader that closes the pipe early must not turn this check's
# exit code into a SIGPIPE. The verdict has already been written; the exit code
# below is the one the caller reads.
cat "$VERDICT_FILE" || true

if [[ "$FAILED" != "0" ]]; then
    printf 'verify-tag-signature: the tag signature check FAILED\n' >&2
    exit 1
fi

printf 'verify-tag-signature: the tag signature check PASSED\n' >&2
