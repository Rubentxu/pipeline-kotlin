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
#   B  the signature comes from a TRUSTED key (and uid, when given)
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
# TWO WAYS OF NAMING THE TRUSTED KEY, AND THEY ASK DIFFERENT QUESTIONS
#
#   --expect-fingerprint FPR   this tag HAS to be signed by this key. It is
#                              the check the release path runs on the tag it
#                              has just cut, and it is deliberately narrow: the
#                              tag that leaves now carries the key that signs
#                              now.
#   --trust-conf FILE          this tag may be signed by ANY key this
#                              repository trusts. It is the check an audit of
#                              the tags already published needs, because the
#                              key that signed those is not the key that signs
#                              now, and a rotation that dropped it would turn
#                              every published release into a tag nobody can
#                              verify.
#
# The two are mutually exclusive: a caller that names one key and a set at the
# same time is asking two questions and gets one answer.
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
#   verify-tag-signature.sh --tag <name|sha> --trust-conf <file> \
#       [--expect-commit <sha>] [--out <file>]
#
# Exit codes: 0 = every row PASS, 1 = at least one row FAIL, 2 = usage error.

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# The ONE reader of the trust file. Sourced, never run: a second reader of the
# same format is a second authority, and the two would drift apart.
# shellcheck source=read-signing-trust.sh
source "${script_dir}/read-signing-trust.sh"

TAG=""
EXPECT_FINGERPRINT=""
EXPECT_COMMIT=""
EXPECT_UID=""
TRUST_CONF=""
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
        --trust-conf)       TRUST_CONF="${2:-}"; shift 2 ;;
        --out)              OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)          usage ;;
        *) printf 'verify-tag-signature: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

if [[ -z "$TAG" ]]; then
    printf 'verify-tag-signature: --tag is required\n' >&2
    usage
fi

# The TRUSTED set: the keys whose signature this tag may carry. It is named
# two ways, and the ways are different questions (see the header).
TRUSTED_FINGERPRINTS=()
TRUSTED_UIDS=()
TRUSTED_ROLES=()

if [[ -n "$TRUST_CONF" ]]; then
    if [[ -n "$EXPECT_FINGERPRINT" || -n "$EXPECT_UID" ]]; then
        printf 'verify-tag-signature: --trust-conf names the trusted SET and\n' >&2
        printf '    --expect-fingerprint names ONE key. Pass one or the other.\n' >&2
        usage
    fi
    if [[ ! -r "$TRUST_CONF" ]]; then
        printf 'verify-tag-signature: the trust file %s cannot be read\n' "$TRUST_CONF" >&2
        exit 2
    fi
    while IFS='|' read -r trusted_fingerprint trusted_uid trusted_role; do
        [[ -n "$trusted_fingerprint" ]] || continue
        TRUSTED_FINGERPRINTS+=("$trusted_fingerprint")
        TRUSTED_UIDS+=("$trusted_uid")
        TRUSTED_ROLES+=("$trusted_role")
    done < <(signing_trust_set "$TRUST_CONF")
    if [[ ${#TRUSTED_FINGERPRINTS[@]} -eq 0 ]]; then
        printf 'verify-tag-signature: the trust file %s declares no usable key\n' "$TRUST_CONF" >&2
        exit 2
    fi
    EXPECT_FINGERPRINT_UPPER="<any trusted key>"
    EXPECT_UID="<any trusted key>"
else
    if [[ -z "$EXPECT_FINGERPRINT" ]]; then
        printf 'verify-tag-signature: --tag and --expect-fingerprint are required\n' >&2
        usage
    fi
    if [[ ! "$EXPECT_FINGERPRINT" =~ ^[0-9A-Fa-f]{40}$ ]]; then
        printf 'verify-tag-signature: --expect-fingerprint must be a 40-hex fingerprint\n' >&2
        printf '    (got %s).\n' "$EXPECT_FINGERPRINT" >&2
        exit 2
    fi
    EXPECT_FINGERPRINT_UPPER="${EXPECT_FINGERPRINT^^}"
    TRUSTED_FINGERPRINTS=("$EXPECT_FINGERPRINT")
    TRUSTED_UIDS=("$EXPECT_UID")
    TRUSTED_ROLES=("current")
fi

TRUSTED_FINGERPRINTS_CSV="$(printf '%s\n' "${TRUSTED_FINGERPRINTS[@]}" | paste -sd, -)"
TRUSTED_UIDS_CSV="$(printf '%s\n' "${TRUSTED_UIDS[@]}" | paste -sd, -)"

STARTED_AT=$(date +%s)

# Whether the text carries a line that BEGINS with the given prefix.
#
# The answer is read in the shell and not through a pipeline whose reader
# quits at the first match. A pipeline like that makes the WRITER fail with
# a broken pipe when it still had text to write, and a command substitution
# whose pipeline failed is a command substitution that failed: under
# `set -o pipefail` the audit of the published history died with a signal
# instead of reporting a verdict, and a check that dies is not a check that
# passed.
has_line_starting_with() {
    local prefix="$1" text="$2" line
    while IFS= read -r line; do
        if [[ "$line" == "$prefix"* ]]; then
            return 0
        fi
    done <<< "$text"
    return 1
}

# The verdict is assembled in a scratch file so it can be copied to --out and
# printed without running the printer twice. It is removed on every exit path:
# this check leaves nothing behind that a later run could mistake for evidence.
VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/tag-signature-verdict.XXXXXX")"
cleanup() { rm -f -- "$VERDICT_FILE"; }
trap cleanup EXIT

ROW_NAMES=()
ROW_VERDICTS=()
ROW_DETAILS=()

# What the tag TURNED OUT to be. These are OBSERVATIONS and they stay empty
# until a check reads them; the verdict line names them, so a reader of the
# verdict sees who actually signed rather than who was asked for.
SIGNER_FPR=""
SIGNER_UID=""
TAG_COMMIT=""

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

    # The signer and the identity it claims are read from the status lines,
    # one line at a time. The FIRST VALIDSIG and the FIRST GOODSIG are the
    # ones that count, and the first is the one a reader would quote.
    while IFS= read -r status_line; do
        case "$status_line" in
            "[GNUPG:] VALIDSIG "*)
                if [[ -z "$SIGNER_FPR" ]]; then
                    rest="${status_line#'[GNUPG:] VALIDSIG '}"
                    SIGNER_FPR="${rest%% *}"
                fi
                ;;
            "[GNUPG:] GOODSIG "*)
                if [[ -z "$SIGNER_UID" ]]; then
                    rest="${status_line#'[GNUPG:] GOODSIG '}"
                    SIGNER_UID="${rest#* }"
                fi
                ;;
        esac
    done <<< "$VERIFY_OUTPUT"

    if has_line_starting_with "[GNUPG:] BADSIG " "$VERIFY_OUTPUT"; then
        record A FAIL "the signature does not verify: the tag content was altered after it was signed"
    elif has_line_starting_with "[GNUPG:] NO_PUBKEY " "$VERIFY_OUTPUT"; then
        record A FAIL "the signing key is not available in this keyring, so the signature cannot be checked"
    elif [[ "${VERIFY_OUTPUT,,}" == *"no signature found"* ]]; then
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

    # WHICH of the trusted keys signed, and with what role. The role says
    # whether this is the key that signs now or one that signed the tags
    # already published, because a reader who sees a signature from a key that
    # is not the current one has to know WHICH history it belongs to.
    if [[ -z "$SIGNER_FPR" ]]; then
        record B FAIL "not measured: no signature was read from the tag"
    else
        trusted_index=-1
        for index in "${!TRUSTED_FINGERPRINTS[@]}"; do
            if [[ "${TRUSTED_FINGERPRINTS[$index]^^}" == "$SIGNER_FPR" ]]; then
                trusted_index="$index"
                break
            fi
        done
        if [[ "$trusted_index" -lt 0 ]]; then
            record B FAIL "the signature comes from ${SIGNER_FPR}, not from a trusted key (${TRUSTED_FINGERPRINTS_CSV})"
        else
            trusted_uid="${TRUSTED_UIDS[$trusted_index]}"
            if [[ -n "$trusted_uid" && "$SIGNER_UID" != "$trusted_uid" ]]; then
                record B FAIL "the signing key claims uid '${SIGNER_UID}', not the declared '${trusted_uid}'"
            else
                record B PASS "the signature comes from the declared key (${TRUSTED_ROLES[$trusted_index]})"
            fi
        fi
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
#    The line names the tag's OBSERVED identity — who signed it, under which
#    uid, at which commit — and separately what the caller ASKED for. The two
#    are not the same fact: a verdict that prints the requested fingerprint in
#    the field that reads as "the signer" says a tag signed by one key was
#    signed by another, and the reader of the line has no way to tell.
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
    printf 'tag-signature: %s rows=%s tag=%s signer=%s uid=%s commit=%s expected_fingerprint=%s expected_uid=%s trusted_fingerprints=%s trusted_uids=%s expected_commit=%s elapsed=%ss\n' \
        "$VERDICT" "$SUMMARY_ROWS" "$TAG" \
        "${SIGNER_FPR:-<none>}" "${SIGNER_UID:-<none>}" "${TAG_COMMIT:-<none>}" \
        "$EXPECT_FINGERPRINT_UPPER" "${EXPECT_UID:-<any>}" \
        "$TRUSTED_FINGERPRINTS_CSV" "$TRUSTED_UIDS_CSV" \
        "${EXPECT_COMMIT:-<any>}" "$ELAPSED"
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
