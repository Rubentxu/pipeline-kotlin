#!/usr/bin/env bash
# release-pipelinek.sh — the one and only release script.
# corrective: a release MUST NOT be published unless the
# releaseGate produced a PASS receipt bound to the SHA of the ZIP the script
# then uploads. This script is the only path that calls `gh release create`;
# it refuses to advance without the six signals:
#   1. Gradle:pipeline-release:releaseGate exits 0 (admission passed)
#   2. The candidateAdmission receipt (build/candidate/distribution-manifest.json)
#      names a candidate_id whose sha256 matches the distZip artifact the
#      script then uploads.
#   3. The local sha256 of the distZip artifact equals the sha256 in the receipt.
#   4. The tag name is fresh — absent locally and absent on the remote, and the
#      remote is READ to say so: a remote that cannot be read refuses the
#      release instead of passing the check — and the tag points at HEAD on a
#      clean working tree.
#   5. The tag is SIGNED with the key the repository declares
#      (scripts/release/signing-key.conf), and that signature VERIFIES before
#      the tag is pushed. There is no unsigned fallback: the digest authority
#      proves the bytes are the bytes, not who published them.
#   6. The REMOTE's own listing shows that tag as an ANNOTATED tag whose
#      signature verifies (section 6b). A local check cannot see a tag the
#      remote published on its own: the published stable tag v0.90.0 was
#      created by the publishing command itself, and every local check was
#      green while the release page showed an unsigned tag.
# Usage:
#   PIPELINEK_RELEASE_TAG=v0.56.0-rc5 PIPELINEK_RELEASE_TRAIN=0.56.0 \
#./scripts/release-pipelinek.sh
# On success: the script exits 0, the ZIP and the full asset set are
# uploaded and the Prerelease is created on GitHub. The receipt, the
# provenance manifest and SHA256SUMS are PUBLISHED as release assets and the
# HANDOFF becomes the release notes; they are not committed to the repository.
# An earlier version did `git add` them under a Markdown evidence tree, which
# reintroduced a tree this repository's policy forbids and duplicated
# information the release page already carries.
# On any refusal: non-zero exit, no upload, no Prerelease, no commit.
# Published asset set (fixed)
# ---------------------------------
# The publisher reads the asset set from the receipt the gate writes: the
# published asset set (pipelinek/pipeline-release/build/candidate/published-assets.json),
# which names the candidate's files and the digest each one must have. The
# publisher stages exactly those files, re-measures them, and refuses any file
# whose digest is not the one the gate measured. The set is fixed, so an
# incomplete candidate cannot be published:
#   pipelinek-<train>.zip          the candidate bytes
#   pipelinek-<train>.sbom.json    the SBOM
#   pipelinek-<train>.sdk.zip      the SDK contract bundle
#   pipelinek-bom-<train>.json     the SDK BOM JSON
# Plus two provenance artefacts, staged with the payload and covered by
# SHA256SUMS as well:
#   manifest.json    the consumer-facing receipt (schema
#                    `pipelinek-release-manifest-1`): the digest of the ZIP,
#                    the commit it was built from, the version and the tag.
#   git-head.txt     the same commit identity as a bare 40-hex line.
# Plus the release's own digest authority:
#   SHA256SUMS       generated over everything above except itself. A release
#                    has ONE digest authority: the candidate's own authority
#                    covers a strict subset of this one and stays in the build
#                    tree as evidence about the candidate, because publishing
#                    both would put two files with the same name and
#                    different coverage on the same release.
# HANDOFF.md stays in the EVIDENCE_DIR only: it is a report, not an asset. It
# reaches consumers as the body of the GitHub release via --notes-file.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ----------------------------------------------------------------------------
# 1. Gate env.
# ----------------------------------------------------------------------------

: "${PIPELINEK_RELEASE_TAG:?PIPELINEK_RELEASE_TAG is required (e.g. v0.56.0-rc5)}"
: "${PIPELINEK_RELEASE_TRAIN:?PIPELINEK_RELEASE_TRAIN is required (e.g. 0.56.0)}"

# The tag names a directory (the staging path below) and is interpolated into
# several `rm -rf` targets on the failure paths, all of which run BEFORE the
# `git tag -a` that would reject a malformed name. So the name is validated
# here, with git's own ref rule rather than a hand-written approximation: an
# unvalidated `../..` would otherwise let those deletes leave the state tree.
if ! git check-ref-format "refs/tags/${PIPELINEK_RELEASE_TAG}" >/dev/null 2>&1; then
    echo "release-pipelinek: refusing a tag name git would not accept." >&2
    echo "    PIPELINEK_RELEASE_TAG=${PIPELINEK_RELEASE_TAG}" >&2
    echo "    A tag becomes a staging directory name here, so a name carrying" >&2
    echo "    path segments is rejected before any path is built from it." >&2
    exit 2
fi

EXPECTED_ZIP_NAME="pipelinek-${PIPELINEK_RELEASE_TRAIN}.zip"
DIST_ZIP="pipelinek/pipeline-application/build/distributions/${EXPECTED_ZIP_NAME}"
RECEIPT="pipelinek/pipeline-release/build/candidate/distribution-manifest.json"

# ----------------------------------------------------------------------------
# 2. Refuse to advance with a dirty tree or an existing tag.
# ----------------------------------------------------------------------------

if ! git diff --quiet HEAD --; then
    echo "release-pipelinek: working tree is dirty; commit first." >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/${PIPELINEK_RELEASE_TAG}" >/dev/null; then
    echo "release-pipelinek: tag ${PIPELINEK_RELEASE_TAG} already exists locally." >&2
    exit 1
fi

# The remote is READ to say whether the tag name is free, and the READ is
# checked. The old shape asked the pipeline `git ls-remote ... | grep -q .`,
# whose status is grep's: a remote that could not be read produced an empty
# listing, grep exited 1, and the script carried on as if the name were free.
# The release gate — a fifteen-minute build — then ran on a guarantee nobody
# had checked. A remote that cannot be read is not a remote without the tag.
REMOTE_LISTING="$(git ls-remote --tags origin "refs/tags/${PIPELINEK_RELEASE_TAG}")" || {
    echo "release-pipelinek: refusing — the listing of origin could not be read." >&2
    echo "    The tag ${PIPELINEK_RELEASE_TAG} could not be checked against it, and a remote" >&2
    echo "    that cannot be read is not a remote without the tag." >&2
    exit 1
}

# The listing answers the question by exact name, not by pattern: a tag whose
# name carries a dot cannot match another tag by accident.
REMOTE_TAG_OBJECT="$(printf '%s\n' "$REMOTE_LISTING" | awk -v r="refs/tags/${PIPELINEK_RELEASE_TAG}" '$2 == r { print $1; exit }')"

if [ -n "$REMOTE_TAG_OBJECT" ]; then
    echo "release-pipelinek: tag ${PIPELINEK_RELEASE_TAG} already exists on origin." >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 2b. The signing key.
#     The tag this script cuts is SIGNED. The key is declared in the REPOSITORY
#     (scripts/release/signing-key.conf) and not in a local git config, for the
#     same reason the asset set comes from the gate's receipt: a value that
#     lives on one machine is a value no reviewer can see. Rotating the key is
#     a reviewed change to that file.
#
#     This runs BEFORE the gate on purpose. A missing key is a PREREQUISITE,
#     not a verdict, and finding it out after a fifteen-minute build would
#     have wasted the build. The publication path refuses to fall back to an
#     unsigned tag: an unsigned tag is exactly what this change exists to
#     stop shipping.
# ----------------------------------------------------------------------------

SIGNING_KEY_CONF="scripts/release/signing-key.conf"

if [ ! -f "$SIGNING_KEY_CONF" ]; then
    echo "release-pipelinek: the signing key declaration is missing at ${SIGNING_KEY_CONF}." >&2
    echo "    Without it there is no way to know WHICH key a release tag must carry." >&2
    exit 2
fi

SIGNING_FINGERPRINT="$(sed -n 's/^fingerprint=//p' "$SIGNING_KEY_CONF" | head -1 | tr -d '[:space:]')"
SIGNING_UID="$(sed -n 's/^uid=//p' "$SIGNING_KEY_CONF" | head -1 | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"

if ! printf '%s' "$SIGNING_FINGERPRINT" | grep -Eq '^[0-9A-Fa-f]{40}$'; then
    echo "release-pipelinek: ${SIGNING_KEY_CONF} does not declare a 40-hex fingerprint." >&2
    exit 2
fi

if [ -z "$SIGNING_UID" ]; then
    echo "release-pipelinek: ${SIGNING_KEY_CONF} does not declare the uid of the key." >&2
    exit 2
fi

if ! gpg --batch --list-secret-keys --with-colons "$SIGNING_FINGERPRINT" 2>/dev/null | grep -q '^sec:'; then
    echo "release-pipelinek: the declared signing key is not available in this keyring." >&2
    echo "    declared: ${SIGNING_FINGERPRINT} (${SIGNING_UID})" >&2
    echo "    The tag cannot be signed, and an unsigned tag is what this publication" >&2
    echo "    path exists to stop shipping. No tag was created and nothing was pushed." >&2
    exit 1
fi

# The public key travels with the release, so the handoff can be checked by
# somebody who has never seen this machine. It is exported from the keyring
# and then read BACK and matched against the declared fingerprint: exporting
# the wrong key would put a public key in front of every consumer that does
# not match the one the signature was made with, and the consumer would
# verify against a key that proves nothing.
SIGNING_PUBLIC_KEY="$(gpg --batch --armor --export "$SIGNING_FINGERPRINT" 2>/dev/null || true)"

if [ -z "$SIGNING_PUBLIC_KEY" ]; then
    echo "release-pipelinek: the declared signing key could not be exported." >&2
    exit 1
fi

EXPORTED_FINGERPRINT="$(printf '%s\n' "$SIGNING_PUBLIC_KEY" \
    | gpg --batch --with-colons --import-options show-only --import 2>/dev/null \
    | awk -F: '/^fpr:/{print $10; exit}')"

if [ "$EXPORTED_FINGERPRINT" != "$SIGNING_FINGERPRINT" ]; then
    echo "release-pipelinek: the exported public key is ${EXPORTED_FINGERPRINT}," >&2
    echo "    but the declared fingerprint is ${SIGNING_FINGERPRINT}. Refusing before the tag." >&2
    exit 1
fi

echo "release-pipelinek: signing key resolved: ${SIGNING_FINGERPRINT} (${SIGNING_UID})"

# ----------------------------------------------------------------------------
# 3. Run the release gate. It runs distZip, cyclonedxBom, candidateAdmission,
#    and the mandatory-plugin test, all bound to the just-built ZIP. The
#    receipt is the JSON file the gate writes to build/candidate/.
#
#    The candidate sequence is 1-based WITHIN the release train, and it used to
#    be hardcoded to 1. That was true for the first candidate of a train and
#    false for every candidate after it: the sequence travels in the published
#    handoff, and a handoff that states the wrong ordinal is a document a
#    reader cannot trust. It is derived from the tag, which names the
#    candidate inside its train, and it can be overridden for a train whose
#    candidates are not numbered that way.
# ----------------------------------------------------------------------------

CANDIDATE_SEQUENCE="${PIPELINEK_RELEASE_SEQUENCE:-}"
if [ -z "$CANDIDATE_SEQUENCE" ]; then
    case "${PIPELINEK_RELEASE_TAG}" in
        *-rc[0-9]*) CANDIDATE_SEQUENCE="${PIPELINEK_RELEASE_TAG##*-rc}" ;;
        *)          CANDIDATE_SEQUENCE=1 ;;
    esac
fi
if ! printf '%s' "$CANDIDATE_SEQUENCE" | grep -Eq '^[0-9]+$' || [ "$CANDIDATE_SEQUENCE" -lt 1 ]; then
    echo "release-pipelinek: the candidate sequence must be a positive integer" >&2
    echo "    (got '$CANDIDATE_SEQUENCE')." >&2
    exit 2
fi

echo "release-pipelinek: running releaseGate (distZip + cyclonedxBom + candidateAdmission + test)"
./pipelinek/gradlew --no-daemon -p pipelinek :pipeline-release:releaseGate -Pcandidate.tag="${PIPELINEK_RELEASE_TAG}" -Pcandidate.sequence="${CANDIDATE_SEQUENCE}" "$@"

# ----------------------------------------------------------------------------
# 4. Verify the receipt names THIS ZIP, not a stale one.
# ----------------------------------------------------------------------------

if [ ! -f "$RECEIPT" ]; then
    echo "release-pipelinek: expected receipt at $RECEIPT but it does not exist." >&2
    exit 1
fi

RECEIPT_SHA=$(python3 -c "
import json, sys
m = json.load(open(sys.argv[1]))
print(m.get('asset', {}).get('sha256') or m.get('candidateId', '').removeprefix('sha256:'))
" "$RECEIPT")

if [ ! -f "$DIST_ZIP" ]; then
    echo "release-pipelinek: expected distZip at $DIST_ZIP but it does not exist." >&2
    exit 1
fi

LOCAL_SHA=$(sha256sum "$DIST_ZIP" | awk '{print $1}')

if [ "$RECEIPT_SHA" != "$LOCAL_SHA" ]; then
    echo "release-pipelinek: receipt sha256 ($RECEIPT_SHA) != local ZIP sha256 ($LOCAL_SHA)." >&2
    echo "    The gate admitted different bytes than the file we are about to upload." >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 5. Assemble the  asset set and its digest authority.
#    The tag is created FIRST so the handoff can state a resolved tag commit
#    rather than a prediction. Note the ordering consequence, which is the
#    whole point of section 5a below: the tag lands on the commit the
#    artefacts were built from, and the evidence commit that carries the
#    handoff is a DESCENDANT of the tag, not the tag itself.
# ----------------------------------------------------------------------------

ARTIFACT_BUILD_COMMIT="$(git rev-parse HEAD)"
SBOM="pipelinek/pipeline-application/build/distributions/pipelinek-${PIPELINEK_RELEASE_TRAIN}.sbom.json"

echo "release-pipelinek: gate PASSED. candidate_id = sha256:$LOCAL_SHA"

if [ ! -f "$SBOM" ]; then
    echo "release-pipelinek: expected SBOM at $SBOM but it does not exist." >&2
    echo "    The release asset set requires the SBOM to be published alongside the ZIP." >&2
    exit 1
fi

echo "release-pipelinek: staging the asset set for ${PIPELINEK_RELEASE_TAG} (build commit ${ARTIFACT_BUILD_COMMIT})"

# Evidence directory: small text artefacts only. The ZIP itself lives as
# the GitHub release asset, not in git.
STATE_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek"
EVIDENCE_DIR="${STATE_ROOT}/release-evidence/${PIPELINEK_RELEASE_TAG}"
mkdir -p "$EVIDENCE_DIR"

# This script issues `rm -rf "$EVIDENCE_DIR"` on several failure paths, so the
# staging directory is required to be inside an ABSOLUTE STATE_ROOT before any of
# it runs. A relative STATE_ROOT would otherwise both make that delete
# unbounded and stage the upload inside whatever directory the release runs
# from, which is the repository in the common case.
case "$STATE_ROOT" in
    /*) ;;
    *)
        echo "release-pipelinek: refusing a relative state root" >&2
        echo "    STATE_ROOT=${STATE_ROOT}" >&2
        exit 1
        ;;
esac
case "$EVIDENCE_DIR" in
    "${STATE_ROOT}/"*) ;;
    *)
        echo "release-pipelinek: refusing to stage outside the state root" >&2
        echo "    STATE_ROOT=${STATE_ROOT}" >&2
        echo "    EVIDENCE_DIR=${EVIDENCE_DIR}" >&2
        exit 1
        ;;
esac
cp "$RECEIPT" "${EVIDENCE_DIR}/distribution-manifest.json"

# Sanity check the manifest's recorded digest matches the local ZIP. The
# gate already enforced this; this is the operator-facing fail-closed.
RECEIPT_DIGEST=$(python3 -c "
import json, sys
m = json.load(open(sys.argv[1]))
print(m.get('asset', {}).get('sha256') or m.get('candidateId', '').removeprefix('sha256:'))
" "${EVIDENCE_DIR}/distribution-manifest.json")
if [ "$RECEIPT_DIGEST" != "$LOCAL_SHA" ]; then
    echo "release-pipelinek: evidence manifest digest ($RECEIPT_DIGEST) != local ZIP digest ($LOCAL_SHA)" >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

# ----------------------------------------------------------------------------
# 5a. Tag ↔ artefact identity.
#     The audit of v0.61.0-rc1 recorded git-head.txt = while the tag
#     pointed at fdcadcfd, with no statement of the relationship. That release
#     was published by hand, so nothing recorded WHICH commit produced the
#     bytes and nothing recorded that the two commits were parent and child. A
#     certifier reading it had three readings available and no way to choose.
#     The rule, stated so it is checkable rather than merely documented:
#       git-head.txt records the commit the ARTEFACT was built from. Never the
#       evidence commit, never the tag commit unless they coincide, never a
#       branch name.
#     Under THIS script's ordering the two coincide BY CONSTRUCTION: the tag is
#     annotated onto the build commit, and the evidence commit below is a
#     descendant of it. So the whole asset set — git-head, handoff and digest
#     manifest — is assembled BEFORE the tag is pushed, and the resolved tag
#     commit is verified against the recorded one afterwards. That ordering is
#     deliberate: a failure after the push would leave a pushed tag and no
#     release, and step 2 refuses an existing tag, so re-running would be
#     impossible. Assemble first, push second, verify third.
# ----------------------------------------------------------------------------

PROSPECTIVE_TAG_COMMIT="$ARTIFACT_BUILD_COMMIT"

printf '%s\n' "$ARTIFACT_BUILD_COMMIT" > "${EVIDENCE_DIR}/git-head.txt"

if [ "$PROSPECTIVE_TAG_COMMIT" = "$ARTIFACT_BUILD_COMMIT" ]; then
    IDENTITY_RELATIONSHIP="IDENTICAL: the tag points at the commit the artefacts were built from. The evidence commit that adds this handoff is a DESCENDANT of the tag and is not part of the artefact."
    IDENTITY_VERDICT="IDENTICAL"
else
    IDENTITY_RELATIONSHIP="DIVERGENT: the tag is not on the artefact build commit. The tag and the bytes come from unrelated history and MUST be reconciled before promotion."
    IDENTITY_VERDICT="DIVERGENT"
fi

# The handoff is written further down, once the asset set is known: it lists
# what was published, so it is generated from the staged files rather than
# written from memory. A handoff that names an asset the release does not
# carry is worse than no handoff, because it is the document a certifier reads
# to decide what to check.

SUMS="${EVIDENCE_DIR}/SHA256SUMS"

if [ "$IDENTITY_VERDICT" = "DIVERGENT" ]; then
    echo "release-pipelinek: refusing to publish — the tag would not be on the artefact build commit." >&2
    echo "    artefact=${ARTIFACT_BUILD_COMMIT} prospective_tag=${PROSPECTIVE_TAG_COMMIT}" >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

# The asset staging happens in section 5b below, driven by the manifest.
# We deliberately do not stage here, because the manifest is the single
# source of truth for the asset set (AGENTS.md ).

# ----------------------------------------------------------------------------
# 5b. Receipt-driven asset staging.
#     The gate decides which of the candidate's files a release publishes and
#     writes that decision down, with the digest each file must have. The
#     publisher stages exactly those files, re-measures them, and refuses any
#     file whose digest is not the one the gate measured: the release carries
#     the bytes the gate admitted, or it carries nothing.
#
#     The receipt does NOT name the candidate's own digest authority. A release
#     has one digest authority — the one generated below, over everything the
#     release carries but itself — and the candidate's authority covers a
#     strict subset of it. Publishing both would put two files with the same
#     name on the release, and the one a consumer opens first might be the one
#     that does not cover the provenance.
# ----------------------------------------------------------------------------

DIST_DIR="pipelinek/pipeline-application/build/distributions"
ASSETS_RECEIPT="pipelinek/pipeline-release/build/candidate/published-assets.json"

if [ ! -f "$ASSETS_RECEIPT" ]; then
    echo "release-pipelinek: expected the published asset set at $ASSETS_RECEIPT" >&2
    echo "    but it does not exist. The gate writes it next to the candidate receipt." >&2
    echo "    Refusing before the tag is created, so no release exists to unpick." >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

# Each line: <kind> <name> <sha256>, the distribution first.
if ! PUBLISHED_SET=$(python3 - "$ASSETS_RECEIPT" <<'PY'
import json, sys

doc = json.load(open(sys.argv[1]))
if doc.get("schema_version") != "pipelinek-published-assets-1":
    raise SystemExit(f"unknown published asset set schema: {doc.get('schema_version')!r}")


def entry(kind, value):
    if not isinstance(value, dict):
        raise SystemExit(f"the {kind} entry is not an object: {value!r}")
    name = value.get("name")
    digest = value.get("sha256")
    if not name or not digest:
        raise SystemExit(f"the {kind} entry has no name or no digest: {value!r}")
    if len(digest) != 64:
        raise SystemExit(f"the digest of {name} is not a SHA-256 hex digest: {digest!r}")
    print(f"{kind}\t{name}\t{digest}")


entry("DISTRIBUTION", doc.get("distribution"))
for asset in doc.get("assets") or []:
    entry("ASSET", asset)
PY
); then
    echo "release-pipelinek: the published asset set at $ASSETS_RECEIPT could not be read." >&2
    echo "    A receipt this script cannot read is a receipt it must not guess at." >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

STAGED_PATHS=()

stage_published_asset() {
    # $1 = the name the asset is published under; $2 = the digest the gate
    # measured for the bytes behind it.
    local name="$1"
    local digest="$2"
    local src="${DIST_DIR}/${name}"
    local actual
    if [ ! -f "$src" ]; then
        echo "release-pipelinek: the published asset set names '$name' but $src does not exist." >&2
        rm -rf "$EVIDENCE_DIR"
        exit 1
    fi
    actual=$(sha256sum "$src" | awk '{print $1}')
    if [ "$actual" != "$digest" ]; then
        echo "release-pipelinek: '$name' has digest $actual but the gate measured $digest." >&2
        echo "    The release carries the bytes the gate admitted, or it carries nothing." >&2
        rm -rf "$EVIDENCE_DIR"
        exit 1
    fi
    cp "$src" "${EVIDENCE_DIR}/${name}"
    STAGED_PATHS+=("${EVIDENCE_DIR}/${name}")
    echo "release-pipelinek: staged ${name} (digest verified)"
}

DIST_NAME=""
while IFS=$'\t' read -r kind name digest; do
    [ -n "$kind" ] || continue
    if [ "$kind" = "DISTRIBUTION" ]; then
        DIST_NAME="$name"
    fi
    stage_published_asset "$name" "$digest"
done <<< "$PUBLISHED_SET"

if [ -z "$DIST_NAME" ]; then
    echo "release-pipelinek: the published asset set names no distribution." >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

# Two provenance artefacts travel with the payload, and they are staged BEFORE
# SHA256SUMS is built so the digest authority covers them too:
#
#   manifest.json    schema `pipelinek-release-manifest-1`: the digest of the
#                    ZIP, the commit it was built from, the version and the
#                    tag. It is deliberately NOT the internal manifest: the
#                    internal schema changes when the pipeline changes, and a
#                    consumer pinned to it would break on every producer-side
#                    refactor.
#   git-head.txt     the same commit identity as a bare 40-hex line, for
#                    consumers that would rather grep than parse.
#
# Both are text, not payload, and neither costs anything to upload.
#
# `tag_commit` is PROSPECTIVE_TAG_COMMIT, which is a prediction at this point.
# The tag is created further down and the script then refuses if the resolved
# commit is not the predicted one, so the value published here is a verified
# claim by the time any byte leaves the machine — it is never an unchecked
# prediction.
RELEASE_MANIFEST="${EVIDENCE_DIR}/manifest.json"
python3 - "$RELEASE_MANIFEST" "$DIST_NAME" "$LOCAL_SHA" \
                  "$PROSPECTIVE_TAG_COMMIT" "$PIPELINEK_RELEASE_TRAIN" \
                  "$PIPELINEK_RELEASE_TAG" <<'PY'
import json, sys
out, asset, digest, commit, version, tag = sys.argv[1:7]
with open(out, "w", encoding="utf-8") as fh:
    json.dump({
        "schema_version": "pipelinek-release-manifest-1",
        "contents": {asset: {"sha256": digest}},
        "tag_commit": commit,
        "version": version,
        "release_tag": tag,
    }, fh, indent=2, sort_keys=True)
    fh.write("\n")
PY
STAGED_PATHS+=("$RELEASE_MANIFEST")
echo "release-pipelinek: staged manifest.json (pipelinek-release-manifest-1) for ${PIPELINEK_RELEASE_TAG}"

STAGED_PATHS+=("${EVIDENCE_DIR}/git-head.txt")
echo "release-pipelinek: staged git-head.txt"

# Build SHA256SUMS over the staged set. The digests describe the STAGED bytes,
# which are the bytes about to be uploaded.
if [ ${#STAGED_PATHS[@]} -eq 0 ]; then
    echo "release-pipelinek: no staged assets to hash; manifest is empty." >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

# Note: the SHA256SUMS file itself is not in STAGED_PATHS at this point
# (we are about to generate it), so it is naturally excluded from the
# manifest. A digest cannot carry its own digest.
"${REPO_ROOT}/scripts/release/build-release-manifest.sh" \
    --out "$SUMS" \
    "${STAGED_PATHS[@]}"

# Now SHA256SUMS is staged. Add it to the upload list but NOT to the
# SHA256SUMS coverage (a manifest cannot carry its own digest).
STAGED_PATHS+=("$SUMS")

# ----------------------------------------------------------------------------
# 5c. The external-consumer check.
#     It runs BEFORE the tag, because a tag that exists is a tag that cannot be
#     taken back: the script refuses an existing tag, so a failure after the
#     push would leave a published tag and no release.
#     What it measures: a consumer OUTSIDE this repository builds a plugin
#     against the SDK this release publishes (and against the SDK the previous
#     release published) and loads it into the distribution this release
#     publishes. The candidate's own bytes are the ones it runs: the staged
#     copies, whose digests the gate measured and this script re-measured.
#     A red row stops the publication, and the reason is written into the
#     evidence directory rather than into a log nobody reads.
# ----------------------------------------------------------------------------

EXT_COMPAT_VERDICT="${EVIDENCE_DIR}/external-compatibility.txt"
EXT_COMPAT_TIMEOUT_SECONDS="${PIPELINEK_EXTERNAL_COMPAT_TIMEOUT_SECONDS:-600}"

echo "release-pipelinek: running the external-consumer check (timeout ${EXT_COMPAT_TIMEOUT_SECONDS}s)"

if ! timeout "${EXT_COMPAT_TIMEOUT_SECONDS}" \
    "${REPO_ROOT}/scripts/release/verify-external-compatibility.sh" \
    --candidate-zip "${EVIDENCE_DIR}/${DIST_NAME}" \
    --candidate-sdk-zip "${EVIDENCE_DIR}/pipelinek-${PIPELINEK_RELEASE_TRAIN}.sdk.zip" \
    --candidate-train "${PIPELINEK_RELEASE_TRAIN}" \
    --out "$EXT_COMPAT_VERDICT"; then
    echo "release-pipelinek: refusing to publish — the external consumer check did not pass." >&2
    echo "    the distribution this release publishes could not be verified against a" >&2
    echo "    consumer that built its plugin from a published SDK. No tag was created." >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

# ----------------------------------------------------------------------------
# 5d. The handoff.
#     It lists what the release carries, generated from the files that were
#     actually staged. A handoff that names an asset the release does not
#     carry is worse than no handoff: it is the document a certifier reads to
#     decide what to check.
# ----------------------------------------------------------------------------

cat > "${EVIDENCE_DIR}/HANDOFF.md" <<HANDOFF_EOF
# Release handoff — ${PIPELINEK_RELEASE_TAG}

> Generated by \`scripts/release-pipelinek.sh\`. Nothing in this file is a verdict;
> it records what was published and how to check it.

## Tag ↔ artefact identity

| Field | Value |
|---|---|
| Release tag | \`${PIPELINEK_RELEASE_TAG}\` |
| Tag commit (dereferenced) | \`${PROSPECTIVE_TAG_COMMIT}\` |
| Commit the artefact was built from (\`git-head.txt\`) | \`${ARTIFACT_BUILD_COMMIT}\` |
| Relationship | ${IDENTITY_RELATIONSHIP} |
| Verdict | **${IDENTITY_VERDICT}** |

\`git-head.txt\` records the commit the **bytes** were produced from. It is not
the tag commit, not a branch, and not the evidence commit — an evidence commit
carries receipts ABOUT a candidate and cannot be the source of its bytes.

## Candidate identity

| Field | Value |
|---|---|
| candidate_id | \`sha256:${LOCAL_SHA}\` |
| Release train | \`${PIPELINEK_RELEASE_TRAIN}\` |
| distZip | \`${DIST_ZIP}\` |
| Gate receipt | \`${RECEIPT}\` (copied to \`distribution-manifest.json\`) |

## Published assets

Every asset below carries an entry in \`SHA256SUMS\`, which is itself the only
asset that does not (a manifest cannot contain its own digest). The digest
shown for \`SHA256SUMS\` is the one measured for the authority file itself.
Resolve digests with \`scripts/release/resolve-release-digest.sh\`; never
consult a per-asset \`<asset>.sha256\` sidecar, which does not exist on a real
release.

| Asset | SHA-256 |
|---|---|
HANDOFF_EOF

for staged in "${STAGED_PATHS[@]}"; do
    staged_digest=$(sha256sum "$staged" | awk '{print $1}')
    printf '| `%s` | `%s` |\n' "$(basename "$staged")" "$staged_digest" \
        >> "${EVIDENCE_DIR}/HANDOFF.md"
done

{
    printf '\n## External consumer\n\n'
    printf 'A consumer outside this repository built a plugin against the SDK this release\n'
    printf 'publishes and against the SDK the previous release published, and loaded both into\n'
    printf 'this release'"'"'s own distribution. The rows below are what that run observed.\n\n'
    printf '```\n'
    cat "$EXT_COMPAT_VERDICT"
    printf '```\n'
} >> "${EVIDENCE_DIR}/HANDOFF.md"

cat >> "${EVIDENCE_DIR}/HANDOFF.md" <<HANDOFF_EOF

## What the certifier must do

1. \`sha256sum -c SHA256SUMS\` against the downloaded assets.
2. Confirm \`git-head.txt\` equals the commit named above, and that the
   relationship row above is \`IDENTICAL\` or a \`DESCENDANT\` you have reasoned
   about. A \`DIVERGENT\` verdict is a hard stop.
3. Re-run \`./pipelinek/gradlew -p pipelinek :pipeline-release:releaseGate\` at
   \`${ARTIFACT_BUILD_COMMIT}\` and confirm it admits these same bytes.
4. Re-run \`scripts/release/verify-external-compatibility.sh\` against the
   downloaded assets and confirm the rows above are reproduced. The SDK archives
   it consumes are verified against the \`SHA256SUMS\` published with them.
5. Import the public key in the \`Tag signature\` section above and check the
   tag yourself:

   \`\`\`
   gpg --import <public key block>
   git fetch origin tag ${PIPELINEK_RELEASE_TAG}
   git tag -v ${PIPELINEK_RELEASE_TAG}
   \`\`\`

   The fingerprint must be \`${SIGNING_FINGERPRINT}\` and the uid \`${SIGNING_UID}\`.
   A signature that verifies proves possession of the key; the attribution of
   that key to a person is a separate fact, established outside this release.
HANDOFF_EOF

# ----------------------------------------------------------------------------
# 6. Sign the tag, VERIFY the signature, and only then push it.
#    Everything above ran without touching the tag, so a failure here is still
#    recoverable: delete the tag and re-run. The reverse was not true.
#
#    The order is the point. A tag that exists is a tag that cannot be taken
#    back, so the signature is checked BEFORE the tag reaches the remote: a
#    tag that cannot be verified never becomes a tag somebody else can see.
#    There is no unsigned fallback. If the key is missing, the tag is not
#    created; if the signature does not verify, the tag is deleted and the
#    publication stops.
#
#    The three steps live in scripts/release/publish-tag.sh so the ORDER can be
#    observed rather than read: that script can be pointed at a local bare
#    repository, and the question "does the remote end up holding a tag whose
#    signature does not verify?" is asked of the shipping code.
# ----------------------------------------------------------------------------

if ! "${REPO_ROOT}/scripts/release/publish-tag.sh" \
    --tag "${PIPELINEK_RELEASE_TAG}" \
    --candidate-id "sha256:${LOCAL_SHA}" \
    --expect-commit "${PROSPECTIVE_TAG_COMMIT}" \
    --fingerprint "${SIGNING_FINGERPRINT}" \
    --uid "${SIGNING_UID}" \
    --out "${EVIDENCE_DIR}/tag-signature.txt"; then
    echo "release-pipelinek: refusing to publish — the release tag was not published." >&2
    echo "    The verdict is at ${EVIDENCE_DIR}/tag-signature.txt" >&2
    exit 1
fi

# The handoff is the release notes body: it is what a consumer reads to decide
# what to check, so the signature facts belong in it. The public key travels
# with it, because a consumer who has never seen the machine that cut the
# release still needs the key to check the signature at all.
{
    printf '\n## Tag signature\n\n'
    printf '| Field | Value |\n'
    printf '|---|---|\n'
    printf '| Signature | the tag is signed, and the signature verifies |\n'
    printf '| Signing key (fingerprint) | `%s` |\n' "$SIGNING_FINGERPRINT"
    printf '| Key uid | `%s` |\n' "$SIGNING_UID"
    printf '| Signed commit | `%s` |\n' "$PROSPECTIVE_TAG_COMMIT"
    printf '| Check | `scripts/release/verify-tag-signature.sh` |\n\n'
    printf 'What this proves, and what it does not:\n\n'
    printf -- '- It proves POSSESSION of the private key: the tag was signed by whoever\n'
    printf -- '  holds it, and the tag cannot be altered without breaking the signature.\n'
    printf -- '- It does NOT prove that the holder of that key owns this repository. Until\n'
    printf -- '  the fingerprint is published on a channel the publisher controls, a\n'
    printf -- '  consumer must treat the publisher as UNATTRIBUTED. The hosting platform\n'
    printf -- '  shows the tag as unverified for the same reason: this key is not\n'
    printf -- '  registered on a hosting account.\n\n'
    printf 'The public key is embedded below, so this check needs nothing from the\n'
    printf 'machine that cut the release:\n\n'
    printf '```\n'
    printf '%s\n' "$SIGNING_PUBLIC_KEY"
    printf '```\n\n'
    printf 'The verdict the release path recorded when it checked the signature:\n\n'
    printf '```\n'
    cat "${EVIDENCE_DIR}/tag-signature.txt"
    printf '```\n'
} >> "${EVIDENCE_DIR}/HANDOFF.md"

# The tag was signed, verified and pushed by publish-tag.sh above. What is
# checked here is the claim the handoff makes about it, not the push itself.
TAG_COMMIT="$(git rev-parse "${PIPELINEK_RELEASE_TAG}^{commit}")"

if [ "$TAG_COMMIT" != "$PROSPECTIVE_TAG_COMMIT" ]; then
    echo "release-pipelinek: tag ${PIPELINEK_RELEASE_TAG} resolved to ${TAG_COMMIT}," >&2
    echo "    but the handoff asserts ${PROSPECTIVE_TAG_COMMIT}. The published handoff would state a" >&2
    echo "    false relationship. Delete the tag and investigate before retrying." >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 6b. Ask the REMOTE what it holds, before the release exists.
#
#     Everything above checked what THIS repository believes: the tag was
#     signed here, the signature verified here, the commit matched here. The
#     incident this closes was not a mistake in those checks — it was that
#     nothing ever asked the remote. The published stable tag v0.90.0 was
#     created BY the publishing command, because that command creates a tag
#     when the tag does not exist yet, and a tag created that way carries no
#     tagger, no message and no signature. Every local check was green.
#
#     So the remote is asked directly, and the release is refused unless the
#     remote's own listing shows an annotated tag whose signature verifies.
#     The audit is scripts/release/verify-published-tags.sh, the same checker
#     the promotion path uses: one authority, not two.
# ----------------------------------------------------------------------------

if ! "${REPO_ROOT}/scripts/release/verify-published-tags.sh" \
    --tag "${PIPELINEK_RELEASE_TAG}" \
    --expect-fingerprint "${SIGNING_FINGERPRINT}" \
    --expect-uid "${SIGNING_UID}" \
    --remote origin \
    --out "${EVIDENCE_DIR}/published-tag-audit.txt"; then
    echo "release-pipelinek: refusing to publish — the remote does not hold a verifiable tag" >&2
    echo "    for ${PIPELINEK_RELEASE_TAG}. The verdict is at ${EVIDENCE_DIR}/published-tag-audit.txt" >&2
    echo "    The tag was pushed and signed; what failed is the REMOTE's own listing." >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 7. Publish. The Prerelease URL is the receipt's permanent address.
#: the ZIP is uploaded to the GitHub release asset store
#    (NOT copied into the git tree). The git tree carries only the small
#    text artefacts (manifest, handoff, git-head).
#
#    The publication goes through scripts/release/publish-github-release.sh,
#    which is the ONE door every release passes through. That script adds
#    `--verify-tag` ITSELF, so gh is never asked to create a tag: the tag
#    above was signed, verified and pushed before this point, and gh is
#    asked to publish a release FOR it. It also re-measures every staged
#    asset against the SHA256SUMS authority built in section 5b, and it
#    checks the published asset set afterwards. Those checks used to live
#    here, in the release script, where the stable-promotion path could not
#    reach them.
# ----------------------------------------------------------------------------

GH_RELEASE_ARGS=(
    --tag "${PIPELINEK_RELEASE_TAG}"
    --title "PipelineK ${PIPELINEK_RELEASE_TAG} (release-gate PASS sha256:${LOCAL_SHA})"
    --notes-file "${EVIDENCE_DIR}/HANDOFF.md"
    --expect-sums "${SUMS}"
    --prerelease
    --out "${EVIDENCE_DIR}/github-release.txt"
)
for staged in "${STAGED_PATHS[@]}"; do
    GH_RELEASE_ARGS+=(--asset "$staged")
done

if ! "${REPO_ROOT}/scripts/release/publish-github-release.sh" "${GH_RELEASE_ARGS[@]}"; then
    echo "release-pipelinek: refusing to publish — the release was not created." >&2
    echo "    The tag is published and signed. The verdict is at ${EVIDENCE_DIR}/github-release.txt" >&2
    exit 1
fi
echo "release-pipelinek: published asset set verified complete (${#STAGED_PATHS[@]} assets, manifest-driven)."

# Nothing is committed here. The four text artefacts were already delivered:
# they are release assets, and HANDOFF.md is the release notes body (see the
# --notes-file above). Committing a second copy would put run-generated output
# into the repository, which the testing policy forbids, and would duplicate the
# release page. The tag was pushed before this point and is not moved below.
echo "release-pipelinek: evidence published as release assets, not committed"
echo "release-pipelinek: evidence also retained at ${EVIDENCE_DIR}"

echo "release-pipelinek: PUBLISHED ${PIPELINEK_RELEASE_TAG} with candidate_id sha256:${LOCAL_SHA}"
