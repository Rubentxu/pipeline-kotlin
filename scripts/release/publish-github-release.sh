#!/usr/bin/env bash
# publish-github-release.sh — publish a GitHub release for a tag that ALREADY
# EXISTS, signed by the key this repository declares.
#
# Why this file exists. The published stable tag v0.90.0 is a LIGHTWEIGHT
# tag: `gh release create` creates the tag itself when the tag does not exist
# yet — "If a matching git tag does not yet exist, one will automatically get
# created from the latest state of the default branch" (`gh release create
# --help`). A tag created that way carries no tagger, no message and no
# signature, so the release page showed a tag whose provenance nobody could
# check, and the repository's own publication path had no way to notice.
#
# The fix is one flag — `--verify-tag` — in ONE place. This file is that
# place: the flag is added BY THE SCRIPT, not by the caller, so no caller can
# publish a release for a tag this repository did not publish. There is no
# "unsigned release" mode and no fallback.
#
# What this script guarantees, before it calls gh:
#   1. the tag is ANNOTATED and its SIGNATURE VERIFIES against the key this
#      repository declares (scripts/release/signing-key.conf), checked by the
#      same checker the release path already uses — one authority, not two;
#   2. the tag already exists in the REMOTE, which is what --verify-tag
#      requires and what makes the release point at a published tag;
#   3. gh is asked to CREATE A RELEASE for that tag, never to create a tag.
#
# And after gh: the published release is asked for its asset list, and the
# release is a failure if any asset the caller staged is missing from it. `gh`
# has exited zero on a partial upload, and a release missing its digest
# authority is exactly the defect this check exists to close.
#
# What the staged bytes must be. --expect-sums names the digest authority of
# the release (SHA256SUMS, the file scripts/release/build-release-manifest.sh
# produces and scripts/release/resolve-release-digest.sh consumes). Every
# staged asset is re-measured against it, and every entry of the authority must
# be staged: a release that carries bytes the authority does not name, or
# omits bytes it does name, is refused. This is what makes a STABLE promotion
# honest — the stable release carries the RC's assets, checked against the
# RC's own authority, not a re-measured set of whatever happened to be on disk.
# The authority is REQUIRED: a release published without a named digest
# authority is a release nobody can check.
#
# Why it is a file of its own and not a flag in release-pipelinek.sh: the
# guarantee worth keeping is that EVERY publication passes through the same
# door, and a door that two callers can each forget to close is not a door.
# Split out, the same code that publishes the release can be pointed at a
# recording `gh` and observed: what exactly was asked of GitHub.
#
# Usage:
#   publish-github-release.sh --tag <name> --title <title> --notes-file <file>
#       --expect-sums <file> [--asset <path>]... [--prerelease]
#       [--remote <name>] [--gh <path>] [--out <file>]
#
# Exit codes: 0 = the release was created and carries every staged asset;
# 1 = refused; 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

TAG=""
TITLE=""
NOTES_FILE=""
EXPECT_SUMS=""
ASSETS=()
PRERELEASE=0
REMOTE="origin"
GH="gh"
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
        --tag)         TAG="${2:-}"; shift 2 ;;
        --title)       TITLE="${2:-}"; shift 2 ;;
        --notes-file)  NOTES_FILE="${2:-}"; shift 2 ;;
        --expect-sums) EXPECT_SUMS="${2:-}"; shift 2 ;;
        --asset)       ASSETS+=("${2:-}"); shift 2 ;;
        --prerelease)  PRERELEASE=1; shift ;;
        --remote)      REMOTE="${2:-}"; shift 2 ;;
        --gh)          GH="${2:-}"; shift 2 ;;
        --out)         OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)     usage ;;
        *) printf 'publish-github-release: unknown argument %s\n' "$1" >&2; usage ;;
    esac
done

if [[ -z "$TAG" || -z "$TITLE" || -z "$NOTES_FILE" ]]; then
    printf 'publish-github-release: --tag, --title and --notes-file are required\n' >&2
    usage
fi

if [[ -z "$EXPECT_SUMS" ]]; then
    printf 'publish-github-release: --expect-sums is required\n' >&2
    printf '           a release published without a named digest authority is a release\n' >&2
    printf '           nobody can check\n' >&2
    usage
fi

if [[ ${#ASSETS[@]} -eq 0 ]]; then
    printf 'publish-github-release: at least one --asset is required\n' >&2
    printf '           a release with no asset carries no bytes and no digest authority\n' >&2
    usage
fi

# The tag becomes a ref this script asks a remote about, so it is validated
# with git's own ref rule rather than a hand-written approximation.
if ! git check-ref-format "refs/tags/${TAG}" >/dev/null 2>&1; then
    printf 'publish-github-release: refusing a tag name git would not accept (%s)\n' "$TAG" >&2
    exit 2
fi

for asset in "${ASSETS[@]}"; do
    if [[ ! -f "$asset" ]]; then
        printf 'publish-github-release: the staged asset %s does not exist\n' "$asset" >&2
        exit 2
    fi
done

if [[ ! -f "$NOTES_FILE" ]]; then
    printf 'publish-github-release: the notes file %s does not exist\n' "$NOTES_FILE" >&2
    exit 2
fi

# ----------------------------------------------------------------------------
# 1. The staged bytes must be the bytes the digest authority names.
#    SHA256SUMS is the release's single digest authority: it is produced by
#    scripts/release/build-release-manifest.sh and consumed by
#    scripts/release/resolve-release-digest.sh. Every staged asset is
#    re-measured here against it, and every entry of the authority must be
#    staged: a release that carries bytes the authority does not name, or
#    omits bytes it does name, is refused.
#
#    The authority is read as the ordinary sha256sum text format it is written
#    in ("<64 hex>  <name>"). A line that is not that shape is REFUSED, not
#    skipped: an authority read half-way is an authority trusted half-way.
#
#    The authority itself travels as an asset, and it is compared byte for
#    byte with the one passed in: the release must publish the authority it
#    was checked against, not another one.
# ----------------------------------------------------------------------------

if [[ ! -f "$EXPECT_SUMS" ]]; then
    printf 'publish-github-release: the digest authority %s does not exist\n' "$EXPECT_SUMS" >&2
    exit 2
fi

declare -A SUM_BY_NAME=()

while IFS= read -r line; do
    [ -n "$line" ] || continue
    if [[ ! "$line" =~ ^([0-9a-fA-F]{64})[[:space:]][[:space:]](.+)$ ]]; then
        printf 'publish-github-release: the digest authority %s has a line that is not a sha256sum entry\n' \
            "$EXPECT_SUMS" >&2
        exit 1
    fi
    entry_digest="${BASH_REMATCH[1],,}"
    entry_name="${BASH_REMATCH[2]}"
    if [[ -n "${SUM_BY_NAME[$entry_name]:-}" ]]; then
        printf 'publish-github-release: the digest authority %s names %s twice\n' \
            "$EXPECT_SUMS" "$entry_name" >&2
        exit 1
    fi
    SUM_BY_NAME["$entry_name"]="$entry_digest"
done < "$EXPECT_SUMS"

if [[ ${#SUM_BY_NAME[@]} -eq 0 ]]; then
    printf 'publish-github-release: the digest authority %s names nothing\n' "$EXPECT_SUMS" >&2
    printf '           an authority over no assets is not an authority\n' >&2
    exit 1
fi

# The name each staged asset is published under, and whether it is the
# authority itself. The authority cannot carry its own digest, so it is
# compared byte for byte instead.
staged_names=()
for asset in "${ASSETS[@]}"; do
    staged_name="$(basename -- "$asset")"
    staged_names+=("$staged_name")

    if [[ "$staged_name" == "$(basename -- "$EXPECT_SUMS")" ]]; then
        if ! cmp -s -- "$asset" "$EXPECT_SUMS"; then
            printf 'publish-github-release: the staged %s is not the digest authority that was checked.\n' \
                "$staged_name" >&2
            printf '           The release must publish the authority it was measured against.\n' >&2
            exit 1
        fi
        continue
    fi

    expected_digest="${SUM_BY_NAME[$staged_name]:-}"
    if [[ -z "$expected_digest" ]]; then
        printf 'publish-github-release: the staged asset %s is not named by the digest authority %s.\n' \
            "$staged_name" "$EXPECT_SUMS" >&2
        printf '           A release carries the bytes the authority names, or it carries nothing.\n' >&2
        exit 1
    fi

    actual_digest="$(sha256sum -- "$asset" | awk '{print $1}')"
    if [[ "$actual_digest" != "$expected_digest" ]]; then
        printf 'publish-github-release: the staged asset %s has digest %s, the authority says %s.\n' \
            "$staged_name" "$actual_digest" "$expected_digest" >&2
        printf '           The release carries the bytes the gate admitted, or it carries nothing.\n' >&2
        exit 1
    fi
done

# The other direction: every entry of the authority must be on the release.
for entry_name in "${!SUM_BY_NAME[@]}"; do
    found=0
    for staged_name in "${staged_names[@]}"; do
        if [[ "$staged_name" == "$entry_name" ]]; then
            found=1
            break
        fi
    done
    if [[ "$found" != "1" ]]; then
        printf 'publish-github-release: the digest authority names %s, and the release does not carry it.\n' \
            "$entry_name" >&2
        printf '           A release missing part of its digest authority cannot be checked.\n' >&2
        exit 1
    fi
done

# ----------------------------------------------------------------------------
# 2. The declared key. It is read from the repository, never from a local git
#    config: a value that lives on one machine is a value no reviewer can see.
# ----------------------------------------------------------------------------

SIGNING_KEY_CONF="${repo_root}/scripts/release/signing-key.conf"

if [[ ! -f "$SIGNING_KEY_CONF" ]]; then
    printf 'publish-github-release: the signing key declaration is missing at %s\n' "$SIGNING_KEY_CONF" >&2
    exit 2
fi

SIGNING_FINGERPRINT="$(sed -n 's/^fingerprint=//p' "$SIGNING_KEY_CONF" | head -1 | tr -d '[:space:]')"
SIGNING_UID="$(sed -n 's/^uid=//p' "$SIGNING_KEY_CONF" | head -1 | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"

if ! printf '%s' "$SIGNING_FINGERPRINT" | grep -Eq '^[0-9A-Fa-f]{40}$'; then
    printf 'publish-github-release: %s does not declare a 40-hex fingerprint\n' "$SIGNING_KEY_CONF" >&2
    exit 2
fi

if [[ -z "$SIGNING_UID" ]]; then
    printf 'publish-github-release: %s does not declare the uid of the key\n' "$SIGNING_KEY_CONF" >&2
    exit 2
fi

# ----------------------------------------------------------------------------
# 3. The tag must be annotated and signed by the declared key.
#    The checker is the one the release path already uses: a second verifier
#    would be a second authority, and two authorities disagree.
# ----------------------------------------------------------------------------

if ! "${repo_root}/scripts/release/verify-tag-signature.sh" \
    --tag "$TAG" \
    --expect-fingerprint "$SIGNING_FINGERPRINT" \
    --expect-uid "$SIGNING_UID"; then
    printf 'publish-github-release: refusing — the tag %s is not a signed tag of the declared key.\n' "$TAG" >&2
    printf '           A release published for an unsigned tag is the incident this script exists to stop.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 4. The tag must already exist in the remote. --verify-tag aborts otherwise,
#    and the local check says WHY in words instead of letting gh say it in
#    gh's words.
# ----------------------------------------------------------------------------

if ! git ls-remote --tags "$REMOTE" "refs/tags/${TAG}" | grep -q .; then
    printf 'publish-github-release: refusing — the tag %s does not exist in %s.\n' "$TAG" "$REMOTE" >&2
    printf '           gh would have to CREATE the tag to publish this release, and a tag it\n' >&2
    printf '           creates is a lightweight, unsigned tag. The tag is pushed by\n' >&2
    printf '           scripts/release/publish-tag.sh, which signs it first.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 5. Publish. --verify-tag is added HERE, by this script: the caller cannot
#    omit it, and gh is never asked to create a tag.
# ----------------------------------------------------------------------------

GH_ARGS=(release create "$TAG" --verify-tag --title "$TITLE" --notes-file "$NOTES_FILE")
if [[ "$PRERELEASE" == "1" ]]; then
    GH_ARGS+=(--prerelease)
fi
GH_ARGS+=("${ASSETS[@]}")

if ! "$GH" "${GH_ARGS[@]}"; then
    printf 'publish-github-release: refusing — gh did not create the release for %s.\n' "$TAG" >&2
    printf '           The tag is published and signed; the release is not. Publish the\n' >&2
    printf '           release again rather than cutting another tag.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 6. The published asset set must be COMPLETE. gh has exited zero on a
#    partial upload, and a release missing its digest authority is a release a
#    consumer cannot check.
# ----------------------------------------------------------------------------

PUBLISHED="$("$GH" release view "$TAG" --json assets --jq '.assets[].name')"
for asset in "${ASSETS[@]}"; do
    required="$(basename -- "$asset")"
    if ! printf '%s\n' "$PUBLISHED" | grep -Fxq "$required"; then
        printf 'publish-github-release: the published release is missing the asset %s.\n' "$required" >&2
        printf '           Published:\n' >&2
        printf '%s\n' "$PUBLISHED" | sed 's/^/             /' >&2
        exit 1
    fi
done

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p -- "$(dirname -- "$OUT_FILE")"
    {
        printf 'github-release: PUBLISHED tag=%s prerelease=%s assets=%s remote=%s\n' \
            "$TAG" "$PRERELEASE" "${#ASSETS[@]}" "$REMOTE"
        printf '  signature verified against %s (%s)\n' "$SIGNING_FINGERPRINT" "$SIGNING_UID"
        printf '  the tag was published before the release; gh was asked to verify it, never to create it\n'
        printf '  every staged asset was re-measured against %s (%s entries)\n' \
            "$EXPECT_SUMS" "${#SUM_BY_NAME[@]}"
    } > "$OUT_FILE"
fi

printf 'publish-github-release: published %s (%s assets, tag signed and already in %s)\n' \
    "$TAG" "${#ASSETS[@]}" "$REMOTE"
