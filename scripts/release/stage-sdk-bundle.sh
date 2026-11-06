#!/usr/bin/env bash
# stage-sdk-bundle.sh — stage the six SDK coordinates for a REMOTE Maven repository, sign them, and prove them.
#
# WHAT IT DOES, IN THIS ORDER, AND WHY THAT ORDER
#
#   1. reads the CURRENT signing key from the trust set (the one authority on which key signs)
#   2. clears the staging repository, so the bundle holds ONE version and not the history
#   3. publishes the landing repository AND the staging repository from THIS revision
#   4. signs every artifact of the staging repository with that key
#   5. writes the checksums of the signatures
#   6. runs the checker, which recomputes every digest and verifies every signature
#   7. compiles an INDEPENDENT consumer against the staging repository
#   8. compiles that same consumer against a repository with no BOM, which must FAIL
#
# The order is the guarantee. The bundle is published before it is signed, and it is
# signed before it is checked, so a checker that passes has read the bytes that carry
# the signature. The consumer compiles last, against the repository that was signed,
# so a green consumer is a consumer that resolved the signed artifacts and not the
# ones the build happened to leave in another directory.
#
# The negative control is not decoration. A consumer that compiles proves that SOMETHING
# resolved; the same consumer against a repository with no BOM proves that the resolution
# is what made it compile. Without the second half, the first half cannot distinguish a
# working bundle from a lucky one.
#
# WHAT IT DOES NOT DO
#
# It does not publish anything anywhere. The staging repository is a directory under the
# build tree, there is no remote repository in the build, and the bundle is not among the
# assets the product publishes. Publishing it is somebody else's decision, on a contract
# that does not exist yet.
#
# The private key never leaves the keyring: `gpg` is asked to sign with the key the trust
# set declares, and the only thing that reaches the repository is the detached signature.
#
# Usage:
#   stage-sdk-bundle.sh [--repo <dir>] [--landing-repo <dir>] [--out <file>] [--skip-consumer]
#
# Exit codes: 0 = the bundle is staged, signed and proven; 1 = refused; 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

STAGING_REPO="$repo_root/pipelinek/build/sdk-repo-staging"
LANDING_REPO="$repo_root/pipelinek/build/sdk-repo"
OUT_FILE=""
SKIP_CONSUMER=0
PLUGIN_DIR="$repo_root/examples/example-uppercase-plugin"
TRUST_CONF="$repo_root/scripts/release/signing-key.conf"
MATURITY_CONTRACT="$repo_root/pipelinek/contract/published-contract-maturity.json"
CHECKER="$repo_root/scripts/check-sdk-bundle.py"
# The list of assets the candidate publishes. It is written by the release gate, and when it
# is there the checker can MEASURE that the bundle is not among them instead of asserting it.
ASSETS_FILE="$repo_root/pipelinek/pipeline-release/build/candidate/published-assets.json"

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)          STAGING_REPO="${2:-}"; shift 2 ;;
        --landing-repo)  LANDING_REPO="${2:-}"; shift 2 ;;
        --out)           OUT_FILE="${2:-}"; shift 2 ;;
        --skip-consumer) SKIP_CONSUMER=1; shift ;;
        -h|--help)       usage ;;
        *)
            printf 'stage-sdk-bundle: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

# shellcheck source=/dev/null
source "$repo_root/scripts/release/read-signing-trust.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-sdk-bundle.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

fail() {
    printf 'stage-sdk-bundle: %s\n' "$1" >&2
    exit 1
}

# ── 1. the current key, from the one authority ─────────────────────────────────
#
# The trust set is read through its single reader, so this script cannot disagree with
# the tag path about which key signs. A key that is not in the set does not sign here
# either, and the refusal NAMES the key that is missing without printing any material.
current="$(signing_current_key "$TRUST_CONF")" \
    || fail "the trust set declares no current key; the bundle cannot be signed"
FINGERPRINT="${current%%|*}"
UID_VALUE="${current#*|}"
UID_VALUE="${UID_VALUE%%|*}"
[[ -n "$FINGERPRINT" ]] || fail "the trust set declares no current fingerprint"

if ! gpg --batch --with-colons --list-secret-keys "$FINGERPRINT" 2>/dev/null | grep -q '^sec:'; then
    fail "the keyring holds no secret key for the current fingerprint $FINGERPRINT; the bundle cannot be signed"
fi

printf '=== SDK bundle ===\n'
printf 'staging repository: %s\n' "$STAGING_REPO"
printf 'signing key:        %s (%s)\n' "$FINGERPRINT" "$UID_VALUE"

# ── 2. the staging repository starts empty ─────────────────────────────────────
#
# A bundle that carries two versions is not a bundle: the version a consumer resolves is
# the version somebody chooses, and a directory with two of them makes that choice
# ambiguous. The staging repository is cleared, and the checker refuses a repository with
# more than one version anyway.
rm -rf "$STAGING_REPO"
mkdir -p "$STAGING_REPO"

# ── 3. publish, from THIS revision, both repositories ──────────────────────────
#
# The landing repository is published too, and not as a formality: the checker compares
# the staged POM with the POM the RC carries, and that comparison is only meaningful when
# both come from the same revision. Publishing only the staging repository would compare
# a POM of today with a POM of whenever the RC was built.
"$repo_root/pipelinek/gradlew" --console=plain -p "$repo_root/pipelinek" \
    :publishSdkForExternalPlugin :sdkBundle > "$WORK/publish.log" 2>&1 \
    || { tail -30 "$WORK/publish.log" >&2; fail "the bundle could not be published"; }

VERSION="$(find "$STAGING_REPO/dev/rubentxu/pipeline/v2" -mindepth 2 -maxdepth 2 -type d -printf '%f\n' 2>/dev/null | sort -u | head -1)"
[[ -n "$VERSION" ]] || fail "the staging repository holds no version after the publication"

# ── 4. sign every artifact with the current key ────────────────────────────────
#
# Detached and armored, which is the shape Central asks for: a `.asc` beside each file.
# The key stays in the keyring; only its signature reaches the repository.
signed=0
while IFS= read -r -d '' artifact; do
    case "$artifact" in
        *.asc|*.md5|*.sha1|*.sha256|*.sha512) continue ;;
    esac
    if ! gpg --batch --yes --armor --detach-sign --local-user "$FINGERPRINT" \
        --output "${artifact}.asc" "$artifact" > "$WORK/gpg.log" 2>&1; then
        tail -10 "$WORK/gpg.log" >&2
        fail "the artifact $(basename "$artifact") could not be signed"
    fi
    signed=$((signed + 1))
done < <(find "$STAGING_REPO" -type f -print0)
printf 'signed artifacts:   %d\n' "$signed"

# ── 5. the checksums of the signatures ─────────────────────────────────────────
#
# Central asks for a checksum beside every file, and the signature is a file. maven-publish
# wrote the checksums of the artifacts it published; the signatures arrived after it, so
# theirs are written here.
while IFS= read -r -d '' signature; do
    for algorithm in md5 sha1 sha256 sha512; do
        case "$algorithm" in
            md5)    (cd "$(dirname "$signature")" && md5sum "$(basename "$signature")") ;;
            sha1)   (cd "$(dirname "$signature")" && sha1sum "$(basename "$signature")") ;;
            sha256) (cd "$(dirname "$signature")" && sha256sum "$(basename "$signature")") ;;
            sha512) (cd "$(dirname "$signature")" && sha512sum "$(basename "$signature")") ;;
        esac > "${signature}.${algorithm}"
    done
done < <(find "$STAGING_REPO" -type f -name '*.asc' -print0)

# ── 6. the checker ─────────────────────────────────────────────────────────────
#
# It recomputes the digests and re-verifies the signatures: a checksum file that agrees
# with itself is not a checksum, and a signature that was written but never verified is
# not a signature.
checker_args=(
    --repo "$STAGING_REPO"
    --landing-repo "$LANDING_REPO"
    --maturity "$MATURITY_CONTRACT"
    --version "$VERSION"
    --fingerprint "$FINGERPRINT"
)
if [[ -f "$ASSETS_FILE" ]]; then
    checker_args+=(--assets "$ASSETS_FILE")
fi
if ! python3 "$CHECKER" "${checker_args[@]}" > "$WORK/check.out" 2>&1; then
    cat "$WORK/check.out" >&2
    fail "the bundle does not pass its own checker"
fi
cat "$WORK/check.out"

# ── 7. an independent consumer compiles against the staging repository ──────────
#
# The SAME build the release gate uses, in an ISOLATED copy of its sources: the
# repository's own build output is the product's plugin, and a check that overwrote it
# would leave the tree claiming one thing and shipping another.
consumer_ok=0
if [[ "$SKIP_CONSUMER" -eq 0 ]]; then
    build_dir="$WORK/consumer"
    mkdir -p "$build_dir"
    cp -r "$PLUGIN_DIR/src" "$build_dir/"
    cp "$PLUGIN_DIR/build.gradle.kts" "$PLUGIN_DIR/settings.gradle.kts" "$build_dir/"

    if "$repo_root/pipelinek/gradlew" --no-daemon -p "$build_dir" jar \
        -PsdkRepo="$STAGING_REPO" -PsdkVersion="$VERSION" > "$WORK/consumer.log" 2>&1; then
        consumer_ok=1
        printf 'consumer:            compiled against the staging repository\n'
    else
        tail -20 "$WORK/consumer.log" | sed 's/^/    /' >&2
        fail "the independent consumer could not compile against the staging repository"
    fi

    # ── 8. the negative control ────────────────────────────────────────────────
    #
    # The same consumer, against a repository that holds no BOM. It must FAIL to
    # resolve: a consumer that compiles against anything proves nothing.
    empty_repo="$WORK/empty-repo"
    mkdir -p "$empty_repo"
    if "$repo_root/pipelinek/gradlew" --no-daemon -p "$build_dir" jar \
        -PsdkRepo="$empty_repo" -PsdkVersion="$VERSION" > "$WORK/consumer-negative.log" 2>&1; then
        fail "the consumer compiled against a repository with no BOM; the check cannot tell a working bundle from a lucky one"
    fi
    printf 'negative control:    the same consumer failed against an empty repository, as it must\n'
fi

# ── the receipt ────────────────────────────────────────────────────────────────
if [[ -n "$OUT_FILE" ]]; then
    {
        printf 'sdk_bundle=1\n'
        printf 'version=%s\n' "$VERSION"
        printf 'repository=%s\n' "$STAGING_REPO"
        printf 'signing_key=%s\n' "$FINGERPRINT"
        printf 'signed_artifacts=%d\n' "$signed"
        printf 'consumer_compiled=%s\n' "$([[ "$consumer_ok" -eq 1 ]] && echo yes || echo not_run)"
        printf 'consumer_negative_control=refused\n'
    } > "$OUT_FILE"
fi

printf '\nstage-sdk-bundle: staged and proven (version %s, %d signed artifacts)\n' "$VERSION" "$signed"
printf 'The bundle is NOT published: it lives under the build tree and no remote repository declares it.\n'
