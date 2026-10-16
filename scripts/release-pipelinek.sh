#!/usr/bin/env bash
# release-pipelinek.sh — the one and only release script.
#
# PK-RC-PLUGIN-001 corrective: a release MUST NOT be published unless the
# releaseGate produced a PASS receipt bound to the SHA of the ZIP the script
# then uploads. This script is the only path that calls `gh release create`;
# it refuses to advance without the four signals:
#
#   1. Gradle :pipeline-release:releaseGate exits 0 (admission passed)
#   2. The candidateAdmission receipt (build/candidate/distribution-manifest.json)
#      names a candidate_id whose sha256 matches the distZip artifact the
#      script then uploads.
#   3. The local sha256 of the distZip artifact equals the sha256 in the receipt.
#   4. The tag name is fresh (does not collide with an existing remote tag)
#      and points at HEAD on a clean working tree.
#
# Usage:
#   PIPELINEK_RELEASE_TAG=v0.56.0-rc5 PIPELINEK_RELEASE_TRAIN=0.56.0 \
#     ./scripts/release-pipelinek.sh
#
# On success: the script exits 0, the ZIP and the full §4 asset set are
# uploaded, the Prerelease is created on GitHub, and the receipt is committed
# under docs/v2/07-uat/evidence/<tag>/.
#
# On any refusal: non-zero exit, no upload, no Prerelease, no commit.
#
# Published asset set (DISTRIBUTION_RELEASE_SPEC.md §4)
# -----------------------------------------------------
# v0.61.0-rc1 shipped only the ZIP and the SBOM. §4 requires the digest
# manifest alongside them, and calls SHA256SUMS "the single digest authority for
# the whole release" — so a release without it has no digest authority a
# consumer can resolve, and `scripts/install-pipelinek.sh` fails closed rather
# than installing. The set this script publishes is therefore:
#
#   pipelinek-<train>.zip          the candidate bytes
#   pipelinek-<train>.sbom.json    the SBOM
#   SHA256SUMS                     the digest authority, covering ALL of the below
#   (HANDOFF.md is release notes, not an uploaded asset)
#   (git-head.txt stays internal; tag and manifest carry provenance)
#
# SHA256SUMS covers the ZIP and SBOM; it cannot list itself. Build provenance
# stays in the annotated release tag and internal distribution-manifest.json.
# SDK JARs and BOM are published separately using Maven metadata, never by
# uploading a source snapshot or a naked, dependency-less SDK JAR.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ----------------------------------------------------------------------------
# 1. Gate env.
# ----------------------------------------------------------------------------

: "${PIPELINEK_RELEASE_TAG:?PIPELINEK_RELEASE_TAG is required (e.g. v0.56.0-rc5)}"
: "${PIPELINEK_RELEASE_TRAIN:?PIPELINEK_RELEASE_TRAIN is required (e.g. 0.56.0)}"

EXPECTED_ZIP_NAME="pipelinek-${PIPELINEK_RELEASE_TRAIN}.zip"
DIST_ZIP="v2/pipeline-application/build/distributions/${EXPECTED_ZIP_NAME}"
RECEIPT="v2/pipeline-release/build/candidate/distribution-manifest.json"

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

if git ls-remote --tags origin "refs/tags/${PIPELINEK_RELEASE_TAG}" | grep -q .; then
    echo "release-pipelinek: tag ${PIPELINEK_RELEASE_TAG} already exists on origin." >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 3. Run the release gate. It runs distZip, cyclonedxBom, candidateAdmission,
#    and the mandatory-plugin test, all bound to the just-built ZIP. The
#    receipt is the JSON file the gate writes to build/candidate/.
# ----------------------------------------------------------------------------

echo "release-pipelinek: running releaseGate (distZip + cyclonedxBom + candidateAdmission + test)"
./v2/gradlew --no-daemon -p v2 :pipeline-release:releaseGate -Pcandidate.tag="${PIPELINEK_RELEASE_TAG}" -Pcandidate.sequence=1 "$@"

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
# 5. Assemble the §4 asset set and its digest authority.
#
#    The tag is created FIRST so the handoff can state a resolved tag commit
#    rather than a prediction. Note the ordering consequence, which is the
#    whole point of section 5a below: the tag lands on the commit the
#    artefacts were built from, and the evidence commit that carries the
#    handoff is a DESCENDANT of the tag, not the tag itself.
# ----------------------------------------------------------------------------

ARTIFACT_BUILD_COMMIT="$(git rev-parse HEAD)"
SBOM="v2/pipeline-application/build/distributions/pipelinek-${PIPELINEK_RELEASE_TRAIN}.sbom.json"

echo "release-pipelinek: gate PASSED. candidate_id = sha256:$LOCAL_SHA"

if [ ! -f "$SBOM" ]; then
    echo "release-pipelinek: expected SBOM at $SBOM but it does not exist." >&2
    echo "    DISTRIBUTION_RELEASE_SPEC.md §4 requires the SBOM to be published alongside the ZIP." >&2
    exit 1
fi

echo "release-pipelinek: staging the §4 asset set for ${PIPELINEK_RELEASE_TAG} (build commit ${ARTIFACT_BUILD_COMMIT})"

# Evidence directory: small text artefacts only. The ZIP itself lives as
# the GitHub release asset, not in git.
EVIDENCE_DIR="docs/v2/07-uat/evidence/${PIPELINEK_RELEASE_TAG}"
mkdir -p "$EVIDENCE_DIR"
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
#
#     The audit of v0.61.0-rc1 recorded git-head.txt = eda7dd56 while the tag
#     pointed at fdcadcfd, with no statement of the relationship. That release
#     was published by hand, so nothing recorded WHICH commit produced the
#     bytes and nothing recorded that the two commits were parent and child. A
#     certifier reading it had three readings available and no way to choose.
#
#     The rule, stated so it is checkable rather than merely documented:
#
#       git-head.txt records the commit the ARTEFACT was built from. Never the
#       evidence commit, never the tag commit unless they coincide, never a
#       branch name.
#
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

# The handoff template. The identity block below is MANDATORY and generated,
# not authored: it is the statement the audit found missing, so it must not be
# something a release can forget to write.
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

The ZIP and SBOM have checksum entries in \`SHA256SUMS\`. This HANDOFF is
release notes only (not a GitHub Release asset). The build commit is bound to
the release tag and recorded in the internal distribution manifest. Resolve
digests with \`scripts/release/resolve-release-digest.sh\`.

| Asset | Role |
|---|---|
| \`${EXPECTED_ZIP_NAME}\` | the candidate bytes |
| \`pipelinek-${PIPELINEK_RELEASE_TRAIN}.sbom.json\` | the SBOM |
| Release notes (not a separate asset) | this handoff |
| Git tag / internal distribution manifest | artefact provenance |
| \`SHA256SUMS\` | the digest authority for all of the above |

## What the certifier must do

1. \`sha256sum -c SHA256SUMS\` against the downloaded assets.
2. Confirm the release tag points to the build commit named above and inspect
   the internally retained \`git-head.txt\` / \`distribution-manifest.json\`.
   A \`DIVERGENT\` identity is a hard stop.
3. Re-run \`./v2/gradlew -p v2 :pipeline-release:releaseGate\` at
   \`${ARTIFACT_BUILD_COMMIT}\` and confirm it admits these same bytes.
HANDOFF_EOF

SBOM_NAME="pipelinek-${PIPELINEK_RELEASE_TRAIN}.sbom.json"
SUMS="${EVIDENCE_DIR}/SHA256SUMS"

if [ "$IDENTITY_VERDICT" = "DIVERGENT" ]; then
    echo "release-pipelinek: refusing to publish — the tag would not be on the artefact build commit." >&2
    echo "    artefact=${ARTIFACT_BUILD_COMMIT} prospective_tag=${PROSPECTIVE_TAG_COMMIT}" >&2
    rm -rf "$EVIDENCE_DIR"
    exit 1
fi

# The ZIP is ~90 MB; the release asset store must hold a copy, so stage it
# beside the evidence rather than uploading from build/ and hoping Gradle has
# not cleaned it between the gate and the upload.
cp "$DIST_ZIP" "${EVIDENCE_DIR}/${EXPECTED_ZIP_NAME}"
cp "$SBOM" "${EVIDENCE_DIR}/${SBOM_NAME}"

# Every published asset except the manifest itself. This list IS the coverage
# claim, and it is the same list the upload below consumes — so an asset added
# to the release but forgotten here is a compile-time-shaped mistake rather
# than a silent hole. The digests describe the STAGED bytes, which are the
# bytes about to be uploaded.
"${REPO_ROOT}/scripts/release/build-release-manifest.sh" \
    --out "$SUMS" \
    "${EVIDENCE_DIR}/${EXPECTED_ZIP_NAME}" \
    "${EVIDENCE_DIR}/${SBOM_NAME}"

# ----------------------------------------------------------------------------
# 6. Tag, then confirm the tag landed where the handoff says it would.
#
#    Everything above ran without touching the tag, so a failure here is still
#    recoverable: delete the tag and re-run. The reverse was not true.
# ----------------------------------------------------------------------------

git tag -a "${PIPELINEK_RELEASE_TAG}" -m "PipelineK ${PIPELINEK_RELEASE_TAG} (candidate_id sha256:${LOCAL_SHA})"
git push origin "${PIPELINEK_RELEASE_TAG}"

TAG_COMMIT="$(git rev-parse "${PIPELINEK_RELEASE_TAG}^{commit}")"

if [ "$TAG_COMMIT" != "$PROSPECTIVE_TAG_COMMIT" ]; then
    echo "release-pipelinek: tag ${PIPELINEK_RELEASE_TAG} resolved to ${TAG_COMMIT}," >&2
    echo "    but the handoff asserts ${PROSPECTIVE_TAG_COMMIT}. The published handoff would state a" >&2
    echo "    false relationship. Delete the tag and investigate before retrying." >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 7. Publish only consumer assets: ZIP (compiled runtime), SBOM, SHA256SUMS.
#    SDK modules are a distinct Maven publication, not raw GitHub release assets.
#    HANDOFF becomes the release description; internal provenance stays in Git.
# ----------------------------------------------------------------------------

gh release create "${PIPELINEK_RELEASE_TAG}" \
    --prerelease \
    --title "PipelineK ${PIPELINEK_RELEASE_TAG} (release-gate PASS sha256:${LOCAL_SHA})" \
    --notes-file "${EVIDENCE_DIR}/HANDOFF.md" \
    "${EVIDENCE_DIR}/${EXPECTED_ZIP_NAME}" \
    "${EVIDENCE_DIR}/${SBOM_NAME}" \
    "$SUMS"

# Fail closed if the release did not end up carrying the whole set. `gh` exits
# 0 on a partial upload in some failure modes, and a release missing its digest
# authority is the defect this script was changed to close.
PUBLISHED=$(gh release view "${PIPELINEK_RELEASE_TAG}" --json assets --jq '.assets[].name')
# Exact set comparison: no unanticipated source archives, test evidence or stale SDK JARs.
EXPECTED_ASSETS=$(printf '%s\n' "${EXPECTED_ZIP_NAME}" "${SBOM_NAME}" SHA256SUMS | LC_ALL=C sort)
ACTUAL_ASSETS=$(printf '%s\n' "$PUBLISHED" | sed '/^$/d' | LC_ALL=C sort)
if [ "$ACTUAL_ASSETS" != "$EXPECTED_ASSETS" ]; then
    echo "release-pipelinek: remote release assets are missing or unexpected:" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "$EXPECTED_ASSETS" "$ACTUAL_ASSETS" >&2
    exit 1
fi
echo "release-pipelinek: consumer asset set verified complete (3 assets)."

# Commit the SMALL TEXT evidence only. This commit is a *post-publish*
# documentation commit; it does not move the tag, because the tag was
# already pushed before this commit. That is exactly the DESCENDANT relationship
# recorded in HANDOFF.md — reverting this commit does not unpublish the release.
#
# The ZIP and the SBOM are explicitly NOT committed. They exist here only to
# upload the exact admitted bytes and remain excluded by .gitignore. No broad
# `git add "$EVIDENCE_DIR"` may bring generated binaries into version control.
git add "${EVIDENCE_DIR}/distribution-manifest.json" \
        "${EVIDENCE_DIR}/HANDOFF.md" \
        "${EVIDENCE_DIR}/git-head.txt" \
        "${EVIDENCE_DIR}/SHA256SUMS"
if ! git diff --cached --quiet; then
    git commit -m "docs(uat): ${PIPELINEK_RELEASE_TAG} release-receipt"
    git push origin HEAD
else
    echo "release-pipelinek: no evidence changes to commit (manifest already present)"
fi

echo "release-pipelinek: PUBLISHED ${PIPELINEK_RELEASE_TAG} with candidate_id sha256:${LOCAL_SHA}"
