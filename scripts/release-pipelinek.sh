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
# On success: the script exits 0, the ZIP is uploaded, the Prerelease is
# created on GitHub, and the receipt is committed under
# docs/v2/07-uat/evidence/<tag>/.
#
# On any refusal: non-zero exit, no upload, no Prerelease, no commit.

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
# 5. Tag + publish. The Prerelease URL is the receipt's permanent address.
# ----------------------------------------------------------------------------

echo "release-pipelinek: gate PASSED. candidate_id = sha256:$LOCAL_SHA"
echo "release-pipelinek: creating tag ${PIPELINEK_RELEASE_TAG} on HEAD $(git rev-parse HEAD)"

git tag -a "${PIPELINEK_RELEASE_TAG}" -m "PipelineK ${PIPELINEK_RELEASE_TAG} (candidate_id sha256:${LOCAL_SHA})"
git push origin "${PIPELINEK_RELEASE_TAG}"

mkdir -p "docs/v2/07-uat/evidence/${PIPELINEK_RELEASE_TAG}"
cp "$DIST_ZIP" "docs/v2/07-uat/evidence/${PIPELINEK_RELEASE_TAG}/${EXPECTED_ZIP_NAME}"
cp "$RECEIPT" "docs/v2/07-uat/evidence/${PIPELINEK_RELEASE_TAG}/distribution-manifest.json"
git add "docs/v2/07-uat/evidence/${PIPELINEK_RELEASE_TAG}/"
git commit -m "docs(uat): ${PIPELINEK_RELEASE_TAG} release-receipt + ZIP + gate manifest"
git push origin HEAD

gh release create "${PIPELINEK_RELEASE_TAG}" \
    --prerelease \
    --title "PipelineK ${PIPELINEK_RELEASE_TAG} (release-gate PASS sha256:${LOCAL_SHA})" \
    --notes "candidate_id: sha256:${LOCAL_SHA}
gate: gradle :pipeline-release:releaseGate PASS
distZip: ${DIST_ZIP}
receipt: ${RECEIPT}" \
    "docs/v2/07-uat/evidence/${PIPELINEK_RELEASE_TAG}/${EXPECTED_ZIP_NAME}"

echo "release-pipelinek: PUBLISHED ${PIPELINEK_RELEASE_TAG} with candidate_id sha256:${LOCAL_SHA}"
