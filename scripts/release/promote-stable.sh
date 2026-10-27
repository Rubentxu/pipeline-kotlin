#!/usr/bin/env bash
# promote-stable.sh — cut a STABLE release tag from a signed RC tag.
#
# The incident this file exists to close. The published stable tag v0.90.0 is
# a LIGHTWEIGHT tag: it points straight at a commit, with no tagger, no
# message and no signature. It was created by `gh release create`, which
# creates the tag itself when the tag does not exist yet. Nothing in the
# publication path ever looked at what the remote actually holds, so the
# defect was invisible from the inside: the release page showed a tag, the
# assets were byte-identical to the RC's, and nothing about the BYTES was
# wrong. What was wrong was the PROVENANCE — nobody could say who cut the
# tag, when, or with what key.
#
# The correction policy this file implements, in one line:
#   a published stable tag is NEVER moved and NEVER replaced; if one already
#   exists, the correct action is a new version, not a rewritten tag.
# So this script REFUSES to promote onto a tag name that is already taken,
# locally or on the remote, and it says why.
#
# What this script guarantees, before it creates anything:
#   1. the STABLE tag name is free — absent locally and absent on the remote,
#      and the remote is READ to say so: a remote that could not be read
#      refuses the promotion instead of passing the check;
#   2. the RC tag is published on the remote, is ANNOTATED (the remote
#      publishes a peeled line for it), and its tag OBJECT is the one this
#      repository holds — a signature is never verified against a different
#      object than the one the remote published;
#   3. the RC tag's signature VERIFIES against the key this repository
#      declares (scripts/release/signing-key.conf), checked by the same
#      checker the release path already uses: one authority, not two;
#   4. the stable tag is placed on the RC's COMMIT, so the stable release
#      and the release candidate describe the same tree;
#   5. the candidate_id is the RC's OWN. The stable release carries the RC's
#      assets, so the digest that travels with the stable tag is the digest
#      the RC already names. The --candidate-id the caller types is a CLAIM
#      about that digest, not a second authority: it is checked against the
#      RC tag's own message, and a promotion whose claim does not match it
#      is refused instead of publishing a digest of nothing.
#
# The tag itself is cut by scripts/release/publish-tag.sh, which signs it,
# verifies the signature and only then pushes it. This file does not re-implement
# that ordering; it adds the PROMOTION semantics on top of it.
#
# What this script does NOT do: it does not publish the release. The release
# is a separate step, on purpose — scripts/release/publish-github-release.sh
# refuses to publish a release for a tag that is not already published and
# signed, so the order "signed tag first, release second" is enforced by the
# door rather than by a convention:
#
#   promote-stable.sh --rc-tag v1.2.3-rc1 --stable-tag v1.2.3 \
#       --candidate-id sha256:<digest> --out <file>
#   publish-github-release.sh --tag v1.2.3 --title '...' --notes-file <file> \
#       --asset <rc asset>... --expect-sums <rc SHA256SUMS>
#
# The stable release carries the RC's assets, and --expect-sums makes the
# door check them against the RC's own digest authority, so a stable release
# cannot carry bytes the RC did not publish.
#
# Why it is neither a scenario in examples/ nor a unit test:
#   - the scenarios in examples/ run the DEVELOPMENT distribution built by
#     installDist; this check consumes TAGS and the REMOTE's refs, which no
#     distribution can produce;
#   - a unit test may not shell out, and this runs `git` and `gpg`.
# It is therefore a RELEASE gate, like the tag-signature check it reuses.
#
# Usage:
#   promote-stable.sh --rc-tag <name> --stable-tag <name> \
#       --candidate-id <sha256:...> --out <file> [--remote <name>]
#
# --candidate-id is the digest of the candidate the stable release carries. It
# is not free text: the RC tag names the same digest, and this script refuses
# a claim that does not match it.
#
# Exit codes: 0 = the stable tag was signed, verified and pushed; 1 = refused;
# 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

RC_TAG=""
STABLE_TAG=""
CANDIDATE_ID=""
OUT_FILE=""
REMOTE="origin"

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rc-tag)       RC_TAG="${2:-}"; shift 2 ;;
        --stable-tag)   STABLE_TAG="${2:-}"; shift 2 ;;
        --candidate-id) CANDIDATE_ID="${2:-}"; shift 2 ;;
        --out)          OUT_FILE="${2:-}"; shift 2 ;;
        --remote)       REMOTE="${2:-}"; shift 2 ;;
        -h|--help)      usage ;;
        *) printf 'promote-stable: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

if [[ -z "$RC_TAG" || -z "$STABLE_TAG" || -z "$CANDIDATE_ID" || -z "$OUT_FILE" ]]; then
    printf 'promote-stable: --rc-tag, --stable-tag, --candidate-id and --out are all required\n' >&2
    usage
fi

# The tag names become refs this script asks git about, so they are validated
# with git's own ref rule rather than a hand-written approximation.
for tag in "$RC_TAG" "$STABLE_TAG"; do
    if ! git check-ref-format "refs/tags/${tag}" >/dev/null 2>&1; then
        printf 'promote-stable: refusing a tag name git would not accept (%s)\n' "$tag" >&2
        exit 2
    fi
done

if [[ "$RC_TAG" == "$STABLE_TAG" ]]; then
    printf 'promote-stable: the stable tag and the RC tag are the same name (%s).\n' "$RC_TAG" >&2
    printf '           a tag cannot promote itself.\n' >&2
    exit 2
fi

# ----------------------------------------------------------------------------
# 1. The declared key. It is read from the repository, never from a local git
#    config: a value that lives on one machine is a value no reviewer can see.
# ----------------------------------------------------------------------------

SIGNING_KEY_CONF="${repo_root}/scripts/release/signing-key.conf"

if [[ ! -f "$SIGNING_KEY_CONF" ]]; then
    printf 'promote-stable: the signing key declaration is missing at %s\n' "$SIGNING_KEY_CONF" >&2
    exit 2
fi

SIGNING_FINGERPRINT="$(sed -n 's/^fingerprint=//p' "$SIGNING_KEY_CONF" | head -1 | tr -d '[:space:]')"
SIGNING_UID="$(sed -n 's/^uid=//p' "$SIGNING_KEY_CONF" | head -1 | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"

if ! printf '%s' "$SIGNING_FINGERPRINT" | grep -Eq '^[0-9A-Fa-f]{40}$'; then
    printf 'promote-stable: %s does not declare a 40-hex fingerprint\n' "$SIGNING_KEY_CONF" >&2
    exit 2
fi

if [[ -z "$SIGNING_UID" ]]; then
    printf 'promote-stable: %s does not declare the uid of the key\n' "$SIGNING_KEY_CONF" >&2
    exit 2
fi

# ----------------------------------------------------------------------------
# 2. The stable tag name must be FREE.
#    This is the correction policy in code: a published stable tag is never
#    moved and never replaced. Both halves are checked, because either one
#    alone leaves a hole: a tag absent locally but present on the remote is a
#    tag the remote already published, and moving it is exactly the defect
#    this file exists to prevent.
#
#    The remote's refs are read ONCE, and the read is CHECKED. The check used
#    to be a pipeline whose status was grep's: a remote that could not be
#    read produced no output, grep found nothing, and the check concluded
#    that the tag was ABSENT — a conclusion nobody had observed. A check
#    that could not be performed is not a check that passed, and a remote
#    that cannot be read is not a remote without the tag. So the listing is
#    read once, its status is checked, and a remote that cannot be read
#    refuses the promotion saying so.
#
#    ONE listing answers BOTH questions, because two reads of the same remote
#    can disagree — a transport that fails once and then answers is ordinary
#    — and a disagreement nobody sees is the same defect with a different
#    shape. The listing asks for both tags at once.
# ----------------------------------------------------------------------------

if git rev-parse --verify --quiet "refs/tags/${STABLE_TAG}" >/dev/null 2>&1; then
    printf 'promote-stable: refusing — the tag %s already exists in this repository.\n' "$STABLE_TAG" >&2
    printf '           A published tag is never moved and never replaced: cut a new version.\n' >&2
    exit 1
fi

REMOTE_LISTING="$(git ls-remote --tags "$REMOTE" \
    "refs/tags/${STABLE_TAG}" \
    "refs/tags/${RC_TAG}" "refs/tags/${RC_TAG}^{}")" || {
    printf 'promote-stable: refusing — the listing of %s could not be read.\n' "$REMOTE" >&2
    printf '           The tag %s could not be checked against it, and a remote that\n' "$STABLE_TAG" >&2
    printf '           cannot be read is not a remote without the tag.\n' >&2
    exit 1
}

STABLE_REMOTE_OBJECT="$(printf '%s\n' "$REMOTE_LISTING" | awk -v r="refs/tags/${STABLE_TAG}" '$2 == r { print $1; exit }')"

if [[ -n "$STABLE_REMOTE_OBJECT" ]]; then
    printf 'promote-stable: refusing — the tag %s already exists in %s.\n' "$STABLE_TAG" "$REMOTE" >&2
    printf '           A published tag is never moved and never replaced: cut a new version.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 3. The RC must be a PUBLISHED, ANNOTATED tag, and this repository must hold
#    the very object the remote published.
#    `git ls-remote --tags` prints a peeled line (`refs/tags/<name>^{}`) only
#    for an annotated tag. Its absence is the fact, and it is read from the
#    remote without fetching anything — from the listing read above, so that
#    the RC and the stable tag are answered by the SAME observation.
# ----------------------------------------------------------------------------

RC_REMOTE_OBJECT="$(printf '%s\n' "$REMOTE_LISTING" | awk -v r="refs/tags/${RC_TAG}" '$2 == r { print $1; exit }')"
RC_PEELED="$(printf '%s\n' "$REMOTE_LISTING" | awk -v r="refs/tags/${RC_TAG}^{}" '$2 == r { print $1; exit }')"

if [[ -z "$RC_REMOTE_OBJECT" ]]; then
    printf 'promote-stable: refusing — the RC tag %s does not exist in %s.\n' "$RC_TAG" "$REMOTE" >&2
    printf '           A stable tag is promoted from a PUBLISHED RC, never from a local one.\n' >&2
    exit 1
fi

if [[ -z "$RC_PEELED" ]]; then
    printf 'promote-stable: refusing — the RC tag %s in %s is a LIGHTWEIGHT tag.\n' "$RC_TAG" "$REMOTE" >&2
    printf '           It carries no tagger, no message and no signature, so there is no\n' >&2
    printf '           provenance to promote. That is the defect this path refuses to copy.\n' >&2
    exit 1
fi

RC_LOCAL_OBJECT="$(git rev-parse --verify --quiet "refs/tags/${RC_TAG}^{tag}" 2>/dev/null || true)"

if [[ -z "$RC_LOCAL_OBJECT" ]]; then
    printf 'promote-stable: refusing — the RC tag object for %s is not in this repository.\n' "$RC_TAG" >&2
    printf '           The signature can only be checked against the object that was published,\n' >&2
    printf '           so the promotion stops here instead of promoting an unchecked tag.\n' >&2
    exit 1
fi

if [[ "$RC_LOCAL_OBJECT" != "$RC_REMOTE_OBJECT" ]]; then
    printf 'promote-stable: refusing — this repository holds tag object %s for %s,\n' \
        "$RC_LOCAL_OBJECT" "$RC_TAG" >&2
    printf '           and %s publishes %s.\n' "$REMOTE" "$RC_REMOTE_OBJECT" >&2
    printf '           Verifying a different object and calling it verified is the same lie\n' >&2
    printf '           this path exists to stop.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 4. The candidate's digest is the RC's, and the caller's claim is checked
#    against it.
#    The stable release carries the RC's ASSETS, checked against the RC's own
#    digest authority (see publish-github-release.sh --expect-sums). So the
#    digest that travels with the stable tag is the digest the RC already
#    names — and the RC tag says which one, in its own message, because the
#    tool that cut it measured the bytes.
#    The --candidate-id the caller types is therefore a CLAIM about that
#    digest and not a second authority. It is checked against the tag object
#    this repository was just proven to hold, and a claim that does not match
#    is a refusal: publishing a stable tag that names a digest the RC does
#    not name would put a digest of nothing in the published provenance.
# ----------------------------------------------------------------------------

RC_TAG_BODY="$(git cat-file tag "$RC_LOCAL_OBJECT" 2>/dev/null || true)"
RC_TAG_SUBJECT="$(printf '%s\n' "$RC_TAG_BODY" | awk 'body { print; exit } /^$/ { body = 1 }')"
RC_CANDIDATE_ID="$(printf '%s\n' "$RC_TAG_SUBJECT" \
    | grep -oE 'candidate_id sha256:[0-9a-f]{64}' | head -1 | sed 's/^candidate_id //' || true)"

if [[ -z "$RC_CANDIDATE_ID" ]]; then
    printf 'promote-stable: refusing — the RC tag %s does not name a candidate_id.\n' "$RC_TAG" >&2
    printf '           A stable tag that names no candidate is a tag whose digest nobody\n' >&2
    printf '           can check, so the promotion stops here.\n' >&2
    exit 1
fi

if [[ "$CANDIDATE_ID" != "$RC_CANDIDATE_ID" ]]; then
    printf 'promote-stable: refusing — the candidate_id does not match the one the RC names.\n' >&2
    printf '           you passed   %s\n' "$CANDIDATE_ID" >&2
    printf '           the RC tag %s names %s\n' "$RC_TAG" "$RC_CANDIDATE_ID" >&2
    printf '           The stable release carries the RC'"'"'s assets, so the stable tag must\n' >&2
    printf '           carry the RC'"'"'s digest. A digest typed at the keyboard that the RC\n' >&2
    printf '           does not name is a digest of nothing.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 5. The RC's signature must VERIFY against the declared key.
#    The checker is the one the release path already uses.
# ----------------------------------------------------------------------------

RC_COMMIT="$(git rev-parse --verify --quiet "refs/tags/${RC_TAG}^{commit}" 2>/dev/null || true)"

if [[ -z "$RC_COMMIT" ]]; then
    printf 'promote-stable: refusing — the RC tag %s does not resolve to a commit.\n' "$RC_TAG" >&2
    exit 1
fi

RC_VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/promote-stable-rc.XXXXXX")"

if ! "${repo_root}/scripts/release/verify-tag-signature.sh" \
    --tag "$RC_TAG" \
    --expect-fingerprint "$SIGNING_FINGERPRINT" \
    --expect-commit "$RC_COMMIT" \
    --expect-uid "$SIGNING_UID" \
    --out "$RC_VERDICT_FILE"; then
    printf 'promote-stable: refusing — the RC tag %s does not carry a verified signature.\n' "$RC_TAG" >&2
    printf '           A stable tag is promoted from a VERIFIED RC. The verdict is at %s\n' \
        "$RC_VERDICT_FILE" >&2
    rm -f -- "$RC_VERDICT_FILE"
    exit 1
fi

# ----------------------------------------------------------------------------
# 6. Cut the stable tag: sign it, verify it, push it. The ordering lives in
#    publish-tag.sh, which is the shipping code for that ordering.
# ----------------------------------------------------------------------------

TAG_VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/promote-stable-tag.XXXXXX")"

if ! "${repo_root}/scripts/release/publish-tag.sh" \
    --tag "$STABLE_TAG" \
    --candidate-id "$CANDIDATE_ID" \
    --expect-commit "$RC_COMMIT" \
    --at "$RC_COMMIT" \
    --promotes "$RC_TAG" \
    --fingerprint "$SIGNING_FINGERPRINT" \
    --uid "$SIGNING_UID" \
    --out "$TAG_VERDICT_FILE"; then
    printf 'promote-stable: refusing — the stable tag %s was not published.\n' "$STABLE_TAG" >&2
    printf '           The verdict is at %s\n' "$TAG_VERDICT_FILE" >&2
    rm -f -- "$RC_VERDICT_FILE"
    exit 1
fi

# ----------------------------------------------------------------------------
# 7. The closing audit: what does the REMOTE hold now? The promotion is only
#    complete when the remote's own listing shows an annotated, signed tag.
#    The audit is the same script that would have caught v0.90.0.
# ----------------------------------------------------------------------------

AUDIT_VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/promote-stable-audit.XXXXXX")"

if ! "${repo_root}/scripts/release/verify-published-tags.sh" \
    --tag "$STABLE_TAG" \
    --expect-fingerprint "$SIGNING_FINGERPRINT" \
    --expect-uid "$SIGNING_UID" \
    --remote "$REMOTE" \
    --out "$AUDIT_VERDICT_FILE"; then
    printf 'promote-stable: refusing — the published-tag audit does not pass for %s.\n' "$STABLE_TAG" >&2
    printf '           The tag was pushed; the audit says what the remote holds. Verdict at %s\n' \
        "$AUDIT_VERDICT_FILE" >&2
    rm -f -- "$RC_VERDICT_FILE" "$TAG_VERDICT_FILE"
    exit 1
fi

# ----------------------------------------------------------------------------
# 8. The verdict. It carries what was checked, not only that it passed.
# ----------------------------------------------------------------------------

VERDICT_FILE="$(mktemp "${TMPDIR:-/tmp}/promote-stable-verdict.XXXXXX")"
cleanup() { rm -f -- "$VERDICT_FILE"; }
trap cleanup EXIT

{
    printf 'promote-stable: PASS stable=%s rc=%s commit=%s candidate_id=%s remote=%s\n' \
        "$STABLE_TAG" "$RC_TAG" "$RC_COMMIT" "$RC_CANDIDATE_ID" "$REMOTE"
    printf '  the stable tag name was free locally and in %s before the promotion\n' "$REMOTE"
    printf '  the RC tag is annotated and its signature verifies against %s (%s)\n' \
        "$SIGNING_FINGERPRINT" "$SIGNING_UID"
    printf '  the stable tag points at the RC commit and names the RC it promotes\n'
    printf '  the candidate_id %s is the one the RC tag names, and the claim was checked against it\n' \
        "$RC_CANDIDATE_ID"
    printf '  the published-tag audit on the remote agrees\n'
    printf '\n'
    printf '%s\n' '--- the RC signature check ---'
    cat "$RC_VERDICT_FILE"
    printf '\n'
    printf '%s\n' '--- the stable tag signature check ---'
    cat "$TAG_VERDICT_FILE"
    printf '\n'
    printf '%s\n' '--- the published-tag audit ---'
    cat "$AUDIT_VERDICT_FILE"
    printf '\n'
    printf '%s\n' '--- what comes next ---'
    printf 'publish the release for %s with scripts/release/publish-github-release.sh,\n' "$STABLE_TAG"
    printf 'carrying the RC assets and the RC digest authority:\n'
    printf '  publish-github-release.sh --tag %s --title <title> --notes-file <file> \\\n' "$STABLE_TAG"
    printf '      --asset <rc asset>... --expect-sums <rc SHA256SUMS>\n'
} > "$VERDICT_FILE"

rm -f -- "$RC_VERDICT_FILE" "$TAG_VERDICT_FILE" "$AUDIT_VERDICT_FILE"

mkdir -p -- "$(dirname -- "$OUT_FILE")"
cp "$VERDICT_FILE" "$OUT_FILE"

cat "$VERDICT_FILE"

printf 'promote-stable: promoted %s from %s (signed, verified, published)\n' \
    "$STABLE_TAG" "$RC_TAG"
