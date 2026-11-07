#!/usr/bin/env bash
# verify-published-tags.sh — audit the tags this repository has PUBLISHED.
#
# The question it answers:
#   "For the release tags we published, is each one an annotated tag whose
#    signature verifies against the key this repository declares?"
#
# Why it exists. The published stable tag v0.90.0 is a LIGHTWEIGHT tag: it
# points straight at a commit, with no tagger, no message and no signature.
# `gh release create` created it, because that command creates the tag when
# the tag does not exist yet. Nothing in the publication path looked at what
# the remote actually holds afterwards, so the release page showed a tag whose
# provenance nobody could check and the fact went unnoticed. This script is
# the look: it reads the remote's own listing and reports, for every tag in
# scope, whether that tag is annotated and whether it is signed.
#
# WHICH OBJECT IS AUDITED: the one the REMOTE publishes.
#
# `git ls-remote --tags` publishes one line per tag object and, for an
# ANNOTATED tag, a second line ending in `^{}` that carries the commit the
# tag points at. A lightweight tag has no peeled line — that absence IS the
# fact, read from the remote itself, and it needs no object to be fetched.
#
# The SIGNATURE lives in the tag OBJECT, and the object that matters is the
# one the remote publishes. This repository may hold a tag object of its own
# under the same name: MEASURED on this tree, 94 of the remote's 205 tags
# have a local object that the remote does not publish, and every one of them
# is left over from a rewritten history, pointing at commits the remote does
# not hold. Auditing the LOCAL object and calling the published tag verified
# would be the same lie in a new place.
#
# So the audit BRINGS the remote's object: it fetches the objects it does not
# have, BY THEIR IDENTIFIER, which creates no reference and leaves this
# repository's own refs/tags/* exactly as it found them. An object that
# cannot be obtained is reported as NOT MEASURED, counted apart, and never
# counted as a pass.
#
# THE CLASS OF A SIGNATURE is read from the REASON the checker gives, not from
# WHICH of its rows failed. "The tag carries no signature" and "the content
# was altered after it was signed" are different facts, and a verdict that
# calls both of them "does not verify" accuses a manipulation that did not
# happen. The classes are:
#
#   VERIFIED            the signature verifies and comes from a trusted key
#   UNSIGNED            the tag object carries no signature at all
#   DOES_NOT_VERIFY     it carries one, and the content was altered after
#   FROM_ANOTHER_KEY    it carries one, and it is not from a trusted key
#   UNCHECKABLE         it carries one, and THIS machine cannot check it
#   NOT_MEASURED        the audit could not obtain the object
#
# The first five are facts about what was published. The last one is a
# limitation of the instrument, and it is counted apart so that a reader can
# tell the two apart.
#
# A TAG THE CALLER NAMED AND THE REMOTE DOES NOT PUBLISH is a red of its own:
# the tag is not there. The release path asks about the tag it has just
# pushed, and a push that had not landed left the audit with nothing to
# check -- and, before this was measured, with a green. A PATTERN is only a
# filter, so a pattern that matches nothing says nothing about the remote.
#
# Why a gap is not a pass. A gap means the audit did not measure that tag.
# Reporting it as a pass would let a caller read "the published tags verify"
# over a tag nobody checked, which is the same silence this script exists to
# end. A scope that matched nothing at all is a gap too. The exit code is
# therefore nonzero when any tag in scope is a FAIL or a GAP, and the summary
# counts them apart.
#
# Why it is neither a scenario in examples/ nor a unit test:
#   - the scenarios in examples/ run the DEVELOPMENT distribution built by
#     installDist; this check consumes the REMOTE's published refs, which no
#     distribution can produce;
#   - a unit test may not shell out, and this check runs `git` and `gpg`.
# It is therefore a RELEASE gate, like the tag-signature check it reuses.
#
# Usage:
#   verify-published-tags.sh --expect-fingerprint <FPR> [--expect-uid <uid>]
#       [--remote <name>] [--tag <name>]... [--pattern <glob>]... [--out <file>]
#
#   verify-published-tags.sh --trust-conf <file>
#       [--remote <name>] [--tag <name>]... [--pattern <glob>]... [--out <file>]
#
# The two ways of naming the trusted key answer different questions, and the
# difference matters HERE more than anywhere else. --expect-fingerprint names
# ONE key: "every tag in scope was signed by this key". --trust-conf names the
# SET this repository trusts: "every tag in scope was signed by a key we
# trust", which is the question an audit of the WHOLE history has to ask once
# the key has been rotated. The tags already published were signed by the key
# that signed then, and an audit that only accepts the key that signs now
# would report every one of them as signed by a stranger.
#
# At least one --tag or --pattern is required: an audit with no scope
# measures nothing and would print a green over nothing.
#
# Exit codes: 0 = every tag in scope is annotated and signed; 1 = at least one
# tag FAILED or could not be measured (GAP); 2 = usage error.

set -Eeuo pipefail

# A reader that closes the pipe early must not turn this audit's exit code
# into a SIGPIPE. A verdict that says FAIL and exits 141 says "the reader
# stopped listening", which is not what happened: the verdict was written
# before the reader left. SIGPIPE is ignored instead of caught, so a write
# to a closed pipe is an ERROR the command reports, and the exit code stays
# the one this audit decided.
trap '' PIPE

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# The ONE reader of the trust file, shared with the checker this script calls.
# Sourced, never run: a second reader of the same format is a second
# authority, and the two would drift apart.
# shellcheck source=read-signing-trust.sh
source "${script_dir}/read-signing-trust.sh"

EXPECT_FINGERPRINT=""
EXPECT_UID=""
TRUST_CONF=""
REMOTE="origin"
SCOPE_TAGS=()
SCOPE_PATTERNS=()
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
        --tag)                SCOPE_TAGS+=("${2:-}"); shift 2 ;;
        --pattern)            SCOPE_PATTERNS+=("${2:-}"); shift 2 ;;
        --expect-fingerprint) EXPECT_FINGERPRINT="${2:-}"; shift 2 ;;
        --expect-uid)         EXPECT_UID="${2:-}"; shift 2 ;;
        --trust-conf)         TRUST_CONF="${2:-}"; shift 2 ;;
        --remote)             REMOTE="${2:-}"; shift 2 ;;
        --out)                OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)            usage ;;
        *) printf 'verify-published-tags: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

# The TRUSTED set, named one way or the other. The two are mutually
# exclusive: naming one key and a set at the same time asks two questions and
# gets one answer.
TRUSTED_FINGERPRINTS=()
TRUSTED_FINGERPRINTS_CSV=""

if [[ -n "$TRUST_CONF" ]]; then
    if [[ -n "$EXPECT_FINGERPRINT" || -n "$EXPECT_UID" ]]; then
        printf 'verify-published-tags: --trust-conf names the trusted SET and\n' >&2
        printf '    --expect-fingerprint names ONE key. Pass one or the other.\n' >&2
        usage
    fi
    if [[ ! -r "$TRUST_CONF" ]]; then
        printf 'verify-published-tags: the trust file %s cannot be read\n' "$TRUST_CONF" >&2
        exit 2
    fi
    # The reader refuses a trust file with no current key. The refusal is
    # carried: a set that cannot sign anything cannot vouch for what was
    # signed, and saying "no usable key" would hide the real reason.
    if ! trust_rows="$(signing_trust_set "$TRUST_CONF")"; then
        printf 'verify-published-tags: the trust file %s declares no current key\n' "$TRUST_CONF" >&2
        exit 2
    fi
    while IFS='|' read -r trusted_fingerprint _trusted_uid _trusted_role; do
        if [[ -z "$trusted_fingerprint" ]]; then continue; fi
        TRUSTED_FINGERPRINTS+=("$trusted_fingerprint")
    done <<< "$trust_rows"
    if [[ ${#TRUSTED_FINGERPRINTS[@]} -eq 0 ]]; then
        printf 'verify-published-tags: the trust file %s declares no usable key\n' "$TRUST_CONF" >&2
        exit 2
    fi
    TRUSTED_FINGERPRINTS_CSV="$(printf '%s\n' "${TRUSTED_FINGERPRINTS[@]}" | paste -sd, -)"
    # The audit named a SET, not a key: the field that used to carry one
    # fingerprint says so out loud instead of printing nothing, because an
    # empty field reads as "the audit did not look".
    EXPECTED_FINGERPRINT_FIELD="<none: the audit named the trusted SET>"
    TRUST_LABEL="a trusted key"
else
    if [[ -z "$EXPECT_FINGERPRINT" ]]; then
        printf 'verify-published-tags: --expect-fingerprint or --trust-conf is required\n' >&2
        usage
    fi
    if ! printf '%s' "$EXPECT_FINGERPRINT" | grep -Eq '^[0-9A-Fa-f]{40}$'; then
        printf 'verify-published-tags: --expect-fingerprint must be a 40-hex fingerprint\n' >&2
        exit 2
    fi
    TRUSTED_FINGERPRINTS_CSV="${EXPECT_FINGERPRINT^^}"
    EXPECTED_FINGERPRINT_FIELD="${EXPECT_FINGERPRINT^^}"
    TRUST_LABEL="the declared key"
fi

if [[ ${#SCOPE_TAGS[@]} -eq 0 && ${#SCOPE_PATTERNS[@]} -eq 0 ]]; then
    printf 'verify-published-tags: --tag or --pattern is required\n' >&2
    printf '           an audit with no scope measures nothing\n' >&2
    usage
fi

# ----------------------------------------------------------------------------
# 1. The remote's own listing. Two lines per ANNOTATED tag (the tag object,
#    then the peeled commit), one line per LIGHTWEIGHT tag (the commit).
# ----------------------------------------------------------------------------

LISTING="$(git ls-remote --tags "$REMOTE")" || {
    printf 'verify-published-tags: the listing of %s could not be read\n' "$REMOTE" >&2
    exit 1
}

in_scope() {
    local name="$1" pattern
    for pattern in "${SCOPE_PATTERNS[@]}"; do
        # The glob IS the pattern here, on purpose: the scope is written the
        # way a reader would write it, not the way grep would take it.
        # shellcheck disable=SC2053
        if [[ "$name" == $pattern ]]; then return 0; fi
    done
    for pattern in "${SCOPE_TAGS[@]}"; do
        if [[ "$name" == "$pattern" ]]; then return 0; fi
    done
    return 1
}

# The listing, read ONCE, into two maps: the object the remote publishes for
# each tag, and the commit it points at.
#
# It used to be read per tag through a pipeline that quit at the first
# match. On a listing of the whole history the writer of that listing was
# still writing when the reader had already found its match and left, so
# the writer died with a broken pipe, and under `set -o pipefail` the audit
# stopped with a signal instead of reporting a verdict. Reading the map
# once removes the race and the quadratic pass, and the ABSENCE of a
# peeled line stays what it was: the fact that the tag is lightweight.
declare -A REMOTE_OBJECTS=()
declare -A PEELED_COMMITS=()
while IFS=$'\t' read -r object ref; do
    [[ "$ref" == refs/tags/* ]] || continue
    tag_name="${ref#refs/tags/}"
    if [[ "$tag_name" == *'^{}' ]]; then
        # The key is the name of the tag WITHOUT the marker of the peeled
        # line: the lookup asks for the name the reader wrote.
        PEELED_COMMITS["${tag_name%'^{}'}"]="$object"
    else
        REMOTE_OBJECTS["$tag_name"]="$object"
    fi
done <<< "$LISTING"

# ----------------------------------------------------------------------------
# 2. The SCOPE, and the objects the audit needs from the remote.
# ----------------------------------------------------------------------------

SCOPE_NAMES=()
MISSING_OBJECTS=()

for name in "${!REMOTE_OBJECTS[@]}"; do
    in_scope "$name" || continue
    SCOPE_NAMES+=("$name")
done

# The order the scope is walked in. The listing's own order is the order the
# remote publishes, which is not sorted and not stable between two reads of
# the same remote; a verdict whose rows move between two runs of the same
# audit is a verdict nobody can diff.
if [[ ${#SCOPE_NAMES[@]} -gt 0 ]]; then
    mapfile -t SCOPE_NAMES < <(printf '%s\n' "${SCOPE_NAMES[@]}" | sort)
fi

for name in "${SCOPE_NAMES[@]}"; do
    object="${REMOTE_OBJECTS[$name]}"
    # A tag whose object this repository already holds needs nothing. The
    # object is asked for BY ITS TYPE, because a lightweight tag publishes a
    # commit and there is no tag object to bring.
    if [[ -n "${PEELED_COMMITS[$name]:-}" ]] && ! git cat-file -e "${object}^{tag}" 2>/dev/null; then
        MISSING_OBJECTS+=("$object")
    fi
done

# A tag named EXPLICITLY is a CLAIM about the remote: "the remote publishes
# this one". A tag the remote does not publish is a fact about the
# publication, and it is a red one.
#
# This is not a corner case. The release path asks about the tag it has just
# pushed, and before this was measured the audit answered PASS over an empty
# scope: a push that had not landed left nothing to check, and nothing to
# check was a green. A pattern is only a FILTER, so a pattern that matches
# nothing says nothing about the remote and is not a red by itself.
ABSENT_NAMES=()
for name in "${SCOPE_TAGS[@]}"; do
    if [[ -z "${REMOTE_OBJECTS[$name]:-}" ]]; then
        ABSENT_NAMES+=("$name")
    fi
done

# The objects the remote publishes and this repository does not have.
#
# They are brought BY IDENTIFIER: a fetch by identifier creates no reference,
# so the audit leaves this repository's refs/tags/* exactly as it found them.
# A batch that the remote refuses is retried one object at a time, because a
# batch that fails takes the whole batch with it, and one object that cannot
# be reached would leave the whole published history unmeasured.
fetch_missing_objects() {
    local remote="$1"
    shift
    local -a wanted=("$@")
    local -a failed=()
    local object

    if [[ ${#wanted[@]} -eq 0 ]]; then
        return 0
    fi

    if git fetch --no-tags --quiet "$remote" "${wanted[@]}" 2>/dev/null; then
        return 0
    fi

    for object in "${wanted[@]}"; do
        git fetch --no-tags --quiet "$remote" "$object" 2>/dev/null || failed+=("$object")
    done
    if [[ ${#failed[@]} -gt 0 ]]; then
        printf 'verify-published-tags: %d object(s) could not be obtained from %s\n' \
            "${#failed[@]}" "$remote" >&2
        return 1
    fi
    return 0
}

fetch_missing_objects "$REMOTE" "${MISSING_OBJECTS[@]}" || true

# ----------------------------------------------------------------------------
# 3. Classify and check each tag in scope.
# ----------------------------------------------------------------------------

ROWS=()
SIGNERS=()
FAILED=0
GAPS=0
CHECKED=0
# The tags the caller named and the remote does not publish. They are a fact
# about the publication, and they are counted apart from the classes of a
# signature because no signature is involved in them.
ABSENT=0
# Whether the scope matched nothing at all. A measurement that did not happen
# is not a pass, and a green over an empty scope says exactly that.
SCOPE_EMPTY=0
ANNOTATED=0
LIGHTWEIGHT=0
VERIFIED=0
UNSIGNED=0
DOES_NOT_VERIFY=0
FROM_ANOTHER_KEY=0
UNCHECKABLE=0
# The newest tag of each class, with the moment it was cut. The reader of a
# red audit has to be able to see whether the red reaches the publication of
# today or only the history of yesterday, and the two facts below answer it
# without reading a single row.
NEWEST_VERIFIED_EPOCH=""
NEWEST_VERIFIED_NAME=""
NEWEST_VERIFIED_AT=""
NEWEST_UNSIGNED_EPOCH=""
NEWEST_UNSIGNED_NAME=""
NEWEST_UNSIGNED_AT=""

# Whether the text carries a line that BEGINS with the given prefix. The
# answer is read in the shell and not through a pipeline whose reader quits
# at the first match: such a pipeline makes the writer fail with a broken
# pipe, and under `set -o pipefail` a failed pipeline is a failed audit.
has_line_starting_with() {
    local prefix="$1" text="$2" line
    while IFS= read -r line; do
        if [[ "$line" == "$prefix"* ]]; then
            return 0
        fi
    done <<< "$text"
    return 1
}

# The detail of a refused row, read in the shell for the same reason.
row_detail() {
    local row="$1" text="$2" line
    while IFS= read -r line; do
        case "$line" in
            "  row ${row} verdict=FAIL "*)
                printf '%s\n' "${line#*verdict=FAIL }"
                return 0
                ;;
        esac
    done <<< "$text"
    return 1
}

# The CLASS of a signature, from the reason the checker gave.
#
# The reason is read, not the row that failed: the checker names what it
# found, and a class that ignores the name accuses a manipulation that did
# not happen.
classify_signature() {
    local row_a="$1" row_b="$2" a_failed="$3"

    if [[ "$a_failed" == "1" ]]; then
        case "$row_a" in
            "the tag carries no signature")
                printf 'UNSIGNED\n'
                return 0
                ;;
            "the signature does not verify"*)
                printf 'DOES_NOT_VERIFY\n'
                return 0
                ;;
            "the tag does not exist locally"*)
                # The audit could not read the object. It is a limitation of
                # the instrument, and not a fact about what was published.
                printf 'NOT_MEASURED\n'
                return 0
                ;;
        esac
        # The signature IS there and THIS machine cannot check it: the key is
        # not in this keyring, or gpg itself failed. The signature EXISTS; the
        # audit could not read it here.
        printf 'UNCHECKABLE\n'
        return 0
    fi

    # The signature verifies. The check can still have refused for another
    # reason -- the tag pointing at another commit -- and then the class of
    # the SIGNATURE is not the thing that refused.
    if [[ -n "$row_b" ]]; then
        printf 'FROM_ANOTHER_KEY\n'
    else
        printf 'VERIFIED\n'
    fi
}

# When a tag object was cut, as an ISO-8601 moment in UTC.
#
# The moment is read from the OBJECT, because the object is what was
# published: the date of the local ref of the same name says when this
# repository made the tag, which is a different fact from when the published
# tag was cut.
tag_cut_at() {
    local sha="$1" header line rest epoch iso
    header="$(git cat-file tag "$sha" 2>/dev/null || true)"
    while IFS= read -r line; do
        case "$line" in
            tagger\ *)
                rest="${line#tagger }"
                epoch="${rest% *}"
                epoch="${epoch##* }"
                if [[ "$epoch" =~ ^[0-9]+$ ]]; then
                    # The EPOCH is the fact; the rendering is a convenience.
                    # A machine that cannot render it prints the epoch, and
                    # the two are compared as epochs, never as text.
                    iso="$(date -u -d "@${epoch}" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || true)"
                    if [[ -z "$iso" ]]; then
                        iso="$epoch"
                    fi
                    printf '%s\t%s\n' "$epoch" "$iso"
                fi
                return 0
                ;;
        esac
    done <<< "$header"
    return 1
}

# The tags the caller named and the remote does not publish. They have no
# signature to classify: the fact is that the tag is not there.
for name in "${ABSENT_NAMES[@]}"; do
    ABSENT=$((ABSENT + 1))
    FAILED=$((FAILED + 1))
    ROWS+=("  tag ${name} verdict=FAIL type=ABSENT signature=NONE (the remote does not publish a tag with this name)")
done

for name in "${SCOPE_NAMES[@]}"; do
    object="${REMOTE_OBJECTS[$name]}"
    peeled="${PEELED_COMMITS[$name]:-}"

    CHECKED=$((CHECKED + 1))

    # The peeled line, when the remote publishes one, is the commit the tag
    # points at. Its ABSENCE is the fact: the tag is lightweight.
    if [[ -z "$peeled" ]]; then
        LIGHTWEIGHT=$((LIGHTWEIGHT + 1))
        FAILED=$((FAILED + 1))
        ROWS+=("  tag ${name} verdict=FAIL type=LIGHTWEIGHT commit=${object} signature=NONE (the remote publishes a lightweight tag: there is no tag object and no signature to verify)")
        continue
    fi

    ANNOTATED=$((ANNOTATED + 1))

    # The object this repository holds under that name, when it holds one.
    # It is a LOCAL fact and never the audited one.
    local_object="$(git rev-parse --verify --quiet "refs/tags/${name}^{tag}" 2>/dev/null || true)"

    # The object the remote publishes. When it is not in this repository and
    # could not be brought, the audit did not measure this tag: that is a
    # limitation of the instrument, and it is named as one.
    if ! git cat-file -e "${object}^{tag}" 2>/dev/null; then
        GAPS=$((GAPS + 1))
        ROWS+=("  tag ${name} verdict=GAP type=ANNOTATED commit=${peeled} signature=NOT_MEASURED (the remote publishes the tag object ${object} and this repository could not obtain it)")
        continue
    fi

    # Why this repository holds a DIFFERENT object under the same name. It
    # is a fact about this tree, and it is recorded because a reader that
    # finds two objects has to know which one was audited and why the other
    # one is here.
    divergence=""
    if [[ -n "$local_object" && "$local_object" != "$object" ]]; then
        local_commit="$(git rev-parse --verify --quiet "${local_object}^{commit}" 2>/dev/null || true)"
        if [[ -n "$local_commit" && "$local_commit" != "$peeled" ]]; then
            divergence=" | this repository holds the tag object ${local_object} over the commit ${local_commit}, and the remote does not publish it"
        else
            divergence=" | this repository holds the tag object ${local_object}, and the remote does not publish it"
        fi
    fi

    # The checker's own rows say WHY it refused. The reason quoted must be the
    # reason of the row that REFUSED. Quoting the crypto row's detail whatever
    # failed produces a sentence that contradicts itself -- a claim that the
    # signature does not verify, justified by the detail that it does -- and it
    # hides which half of the check refused.
    check_output=""
    check_rc=0
    # The checker is asked with the SAME question this audit was asked: the
    # trusted set when the audit named the set, the one key when it named one.
    # It is asked about the object the REMOTE publishes, by its identifier.
    check_args=(--tag "$object" --expect-commit "$peeled")
    if [[ -n "$TRUST_CONF" ]]; then
        check_args+=(--trust-conf "$TRUST_CONF")
    else
        check_args+=(--expect-fingerprint "$EXPECT_FINGERPRINT")
        if [[ -n "$EXPECT_UID" ]]; then
            check_args+=(--expect-uid "$EXPECT_UID")
        fi
    fi
    check_output="$("${repo_root}/scripts/release/verify-tag-signature.sh" \
        "${check_args[@]}" 2>&1)" || check_rc=$?

    row_a="$(row_detail A "$check_output" || true)"
    row_b="$(row_detail B "$check_output" || true)"
    row_a_failed=0
    if [[ -n "$row_a" ]]; then
        row_a_failed=1
    fi
    check_detail="$(printf '%s\n' "$check_output" \
        | sed -n 's/^  row \([A-Z]\) verdict=FAIL \(.*\)/\1: \2/p' \
        | awk 'BEGIN { separator = "" } { printf "%s%s", separator, $0; separator = " | " }')"

    # The signer the checker OBSERVED. The declared key is what this audit
    # asked for; the signer is who actually held the pen, and it is the fact
    # a reader needs when the two differ.
    #
    # The verdict of the checker is read in the shell, for the same reason
    # the peeled map is: a pipeline whose reader quits at the first match
    # makes the writer fail, and a failed pipeline is a failed audit.
    first_line="${check_output%%$'\n'*}"
    check_signer=""
    if [[ "$first_line" == *" signer="* ]]; then
        signer_field="${first_line#* signer=}"
        if [[ "$signer_field" =~ ^([0-9A-F]{40}) ]]; then
            check_signer="${BASH_REMATCH[1]}"
        fi
    fi
    check_signer="${check_signer:-<none>}"

    # The signer is recorded whether the check passed or refused: the signer
    # that caused the refusal is exactly the one a reader needs to see.
    if [[ "$check_signer" != "<none>" ]]; then
        SIGNERS+=("$check_signer")
    fi

    cut="$(tag_cut_at "$object" || true)"
    cut_epoch=""
    cut_at=""
    if [[ "$cut" == *$'\t'* ]]; then
        cut_epoch="${cut%%$'\t'*}"
        cut_at="${cut#*$'\t'}"
    fi

    if [[ "$check_rc" == "0" ]]; then
        VERIFIED=$((VERIFIED + 1))
        if [[ -n "$cut_epoch" && ( -z "$NEWEST_VERIFIED_EPOCH" || "$cut_epoch" -gt "$NEWEST_VERIFIED_EPOCH" ) ]]; then
            NEWEST_VERIFIED_EPOCH="$cut_epoch"
            NEWEST_VERIFIED_NAME="$name"
            NEWEST_VERIFIED_AT="$cut_at"
        fi
        ROWS+=("  tag ${name} verdict=PASS type=ANNOTATED commit=${peeled} signer=${check_signer} signature=VERIFIED (${TRUST_LABEL}) audited=${object}")
        continue
    fi

    signature_state="$(classify_signature "$row_a" "$row_b" "$row_a_failed")"
    case "$signature_state" in
        VERIFIED)
            # The signature verified and the check refused for another
            # reason. The class of the signature is counted as verified, and
            # the refusal is named in the row.
            VERIFIED=$((VERIFIED + 1))
            if [[ -n "$cut_epoch" && ( -z "$NEWEST_VERIFIED_EPOCH" || "$cut_epoch" -gt "$NEWEST_VERIFIED_EPOCH" ) ]]; then
                NEWEST_VERIFIED_EPOCH="$cut_epoch"
                NEWEST_VERIFIED_NAME="$name"
                NEWEST_VERIFIED_AT="$cut_at"
            fi
            ;;
        UNSIGNED)
            UNSIGNED=$((UNSIGNED + 1))
            FAILED=$((FAILED + 1))
            if [[ -n "$cut_epoch" && ( -z "$NEWEST_UNSIGNED_EPOCH" || "$cut_epoch" -gt "$NEWEST_UNSIGNED_EPOCH" ) ]]; then
                NEWEST_UNSIGNED_EPOCH="$cut_epoch"
                NEWEST_UNSIGNED_NAME="$name"
                NEWEST_UNSIGNED_AT="$cut_at"
            fi
            ;;
        DOES_NOT_VERIFY)
            DOES_NOT_VERIFY=$((DOES_NOT_VERIFY + 1))
            FAILED=$((FAILED + 1))
            ;;
        FROM_ANOTHER_KEY)
            FROM_ANOTHER_KEY=$((FROM_ANOTHER_KEY + 1))
            FAILED=$((FAILED + 1))
            ;;
        UNCHECKABLE)
            UNCHECKABLE=$((UNCHECKABLE + 1))
            FAILED=$((FAILED + 1))
            ;;
        *)
            # The audit did not measure this tag: it is a gap, and a gap is
            # not a failure of the publication nor a pass.
            GAPS=$((GAPS + 1))
            ;;
    esac

    # The label says WHICH half refused and WHY, in the checker's own words:
    # a signature that does not verify and a tag that was never signed are
    # different facts, and one label for both of them hides the difference.
    ROWS+=("  tag ${name} verdict=FAIL type=ANNOTATED commit=${peeled} signer=${check_signer} signature=${signature_state} audited=${object} (${check_detail:-the signature check failed}${divergence})")
done

# ----------------------------------------------------------------------------
# 4. The verdict. It says what ran, and it counts the gaps apart from the
#    failures so that neither can hide behind the other.
# ----------------------------------------------------------------------------

if [[ "$CHECKED" -eq 0 && "$ABSENT" -eq 0 ]]; then
    # The scope matched nothing: the audit measured nothing, and a green over
    # an empty scope is the silence this script exists to end.
    SCOPE_EMPTY=1
    ROWS+=("  the scope matched no tag: the audit measured nothing, and that is not a pass")
fi

if [[ "$FAILED" -eq 0 && "$GAPS" -eq 0 && "$SCOPE_EMPTY" -eq 0 ]]; then
    VERDICT="PASS"
else
    VERDICT="FAIL"
fi

# The summary separates what the audit ASKED for from what it SAW.
# `expected_fingerprint` is the key this audit was told to look for (or the
# explicit statement that it was told a SET instead); `trusted_fingerprints`
# is that set spelled out; `signers` are the keys that actually signed the
# published tags, and a difference between the two is the whole point of the
# audit.
if [[ ${#SIGNERS[@]} -gt 0 ]]; then
    SUMMARY_SIGNERS="$(printf '%s\n' "${SIGNERS[@]}" | sort -u | paste -sd',' -)"
else
    SUMMARY_SIGNERS="<none>"
fi

# The two newest facts are NAMED with their date: a reader of the summary has
# to know WHICH tag is the boundary, and a date alone does not name a tag.
if [[ -n "$NEWEST_VERIFIED_NAME" ]]; then
    NEWEST_VERIFIED_FIELD="${NEWEST_VERIFIED_NAME}@${NEWEST_VERIFIED_AT}"
else
    NEWEST_VERIFIED_FIELD="<none>"
fi
if [[ -n "$NEWEST_UNSIGNED_NAME" ]]; then
    NEWEST_UNSIGNED_FIELD="${NEWEST_UNSIGNED_NAME}@${NEWEST_UNSIGNED_AT}"
else
    NEWEST_UNSIGNED_FIELD="<none>"
fi

SUMMARY="published-tags: ${VERDICT} checked=${CHECKED} annotated=${ANNOTATED} lightweight=${LIGHTWEIGHT} verified=${VERIFIED} unsigned=${UNSIGNED} from_another_key=${FROM_ANOTHER_KEY} does_not_verify=${DOES_NOT_VERIFY} uncheckable=${UNCHECKABLE} not_measured=${GAPS} absent=${ABSENT} failed=${FAILED} remote=${REMOTE} expected_fingerprint=${EXPECTED_FINGERPRINT_FIELD} trusted_fingerprints=${TRUSTED_FINGERPRINTS_CSV} signers=${SUMMARY_SIGNERS} newest_verified=${NEWEST_VERIFIED_FIELD} newest_unsigned=${NEWEST_UNSIGNED_FIELD}"

VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/published-tags-verdict.XXXXXX")"
cleanup() { rm -f -- "$VERDICT_FILE"; }
trap cleanup EXIT

{
    printf '%s\n' "$SUMMARY"
    for row in "${ROWS[@]}"; do printf '%s\n' "$row"; done
    if [[ "$GAPS" -gt 0 ]]; then
        printf '  %d tag(s) were NOT MEASURED: a gap is not a pass\n' "$GAPS"
    fi
    if [[ "$UNSIGNED" -gt 0 ]]; then
        printf '  %d tag(s) carry NO SIGNATURE: the remote publishes them without one\n' "$UNSIGNED"
    fi
    if [[ "$UNCHECKABLE" -gt 0 ]]; then
        printf '  %d tag(s) carry a signature this machine could not check\n' "$UNCHECKABLE"
    fi
    # Where the frontier lies. The red of an audit over the whole history is
    # only alarming if it reaches the publication of today, and the two
    # moments below say whether it does.
    if [[ -n "$NEWEST_VERIFIED_EPOCH" && -n "$NEWEST_UNSIGNED_EPOCH" ]]; then
        if [[ "$NEWEST_UNSIGNED_EPOCH" -gt "$NEWEST_VERIFIED_EPOCH" ]]; then
            printf '  the newest unsigned tag (%s) is LATER than the newest verified one (%s): the publication of today is unsigned\n' \
                "$NEWEST_UNSIGNED_FIELD" "$NEWEST_VERIFIED_FIELD"
        else
            printf '  every tag cut after %s carries a signature: the frontier of the unsigned history is %s\n' \
                "$NEWEST_VERIFIED_FIELD" "$NEWEST_UNSIGNED_FIELD"
        fi
    fi
} > "$VERDICT_FILE"

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
    cp "$VERDICT_FILE" "$OUT_FILE"
fi

cat "$VERDICT_FILE" || true

if [[ "$VERDICT" != "PASS" ]]; then
    # `|| true`: a reader that closes the pipe early must not turn this
    # audit's exit code into a SIGPIPE. The verdict is already written.
    printf 'verify-published-tags: the published-tag audit FAILED\n' >&2 || true
    exit 1
fi

printf 'verify-published-tags: the published-tag audit PASSED\n' >&2 || true
