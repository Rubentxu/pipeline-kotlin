#!/usr/bin/env bash
# SDKMAN publish for pipelinek vX.Y.Z
#
# Authority:
#   - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md §6 (SDKMAN publication)
#   - SDKMAN Vendor API doc: https://sdkman.io/vendors
#
# Usage:
#   SDKMAN_CONSUMER_KEY=xxx SDKMAN_CONSUMER_TOKEN=yyy \
#     ./scripts/release/sdkman-publish.sh 0.39.0
#
# Pre-conditions:
#   - GitHub Release vX.Y.Z is PUBLIC with the canonical ZIP as asset
#   - ZIP SHA-256 match between local build and GitHub asset
#   - pipelinek-X.Y.Z/bin/pipelinek is the executable SDKMAN will expose
#
# Idempotent: POST to /release is idempotent per SDKMAN doc.
# Does NOT make vX.Y.Z the default — that's a separate PUT to /default.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 VERSION" >&2
  exit 2
fi

VERSION="$1"
GIT_TAG="v${VERSION}"
CANDIDATE="pipelinek"
ASSET="pipelinek-${VERSION}.zip"
URL="https://github.com/Rubentxu/pipeline-kotlin/releases/download/${GIT_TAG}/${ASSET}"

: "${SDKMAN_CONSUMER_KEY:?SDKMAN_CONSUMER_KEY required}"
: "${SDKMAN_CONSUMER_TOKEN:?SDKMAN_CONSUMER_TOKEN required}"

echo "=== SDKMAN publish ==="
echo "candidate: $CANDIDATE"
echo "version:   $VERSION"
echo "url:       $URL"

# Read SHA-256 from the GitHub Release asset sidecar (already published there).
SHA256=$(curl -fsSL "${URL}.sha256" | awk '{print $1}')
if [ -z "$SHA256" ]; then
  echo "❌ could not fetch SHA-256 from ${URL}.sha256" >&2
  exit 1
fi
echo "sha256:    $SHA256"

# Defence-in-depth: verify the SHA matches what we'd compute locally from the
# same URL. If GitHub ever returns a different .sha256 sidecar than the
# archive, fail closed. (Cheap; one curl.)
EXPECTED_VIA_LOCAL_SHA=$(curl -fsSL "${URL}" | sha256sum | awk '{print $1}')
if [ "${EXPECTED_VIA_LOCAL_SHA}" != "${SHA256}" ]; then
  echo "❌ SHA mismatch: GitHub .sha256 sidecar says ${SHA256}" >&2
  echo "   but recomputing from ${URL} gives ${EXPECTED_VIA_LOCAL_SHA}" >&2
  exit 1
fi
echo "sha256 verify: ✓ GitHub sidecar matches recomputed archive digest"
echo

# SDKMAN distribution: UNIVERSAL — single binary works on all platforms
# because the archive contains BOTH bin/pipelinek (UNIX) and
# bin/pipelinek.bat (Windows). Per SDKMAN docs, UNIVERSAL and platform-
# specific binaries are mutually exclusive for the same version.
PLATFORM="UNIVERSAL"

PAYLOAD=$(cat <<JSON
{
  "candidate": "${CANDIDATE}",
  "version":   "${VERSION}",
  "url":       "${URL}",
  "platform":  "${PLATFORM}",
  "checksums": {
    "SHA-256": "${SHA256}"
  }
}
JSON
)

echo
echo "=== POST https://vendors.sdkman.io/release ==="
RESPONSE=$(curl -fsS -X POST \
  -H "Consumer-Key: ${SDKMAN_CONSUMER_KEY}" \
  -H "Consumer-Token: ${SDKMAN_CONSUMER_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "${PAYLOAD}" \
  https://vendors.sdkman.io/release)
echo "${RESPONSE}"
echo
echo "=== verify ==="
curl -fsS --max-time 15 "https://api.sdkman.io/2/${CANDIDATE}" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); v=[x for x in d.get('versions',[]) if x.get('version')=='${VERSION}']; print('version visible:', bool(v), v[0] if v else 'NOT FOUND')"
echo
echo "✅ ${CANDIDATE} ${VERSION} published to SDKMAN (UNIVERSAL)."
echo "   Note: not yet DEFAULT. Promote with:"
echo "     curl -X PUT -H 'Consumer-Key: ...' -H 'Consumer-Token: ...' \\"
echo "          -H 'Content-Type: application/json' \\"
echo "          -d '{\"candidate\":\"${CANDIDATE}\",\"version\":\"${VERSION}\"}' \\"
echo "          https://vendors.sdkman.io/default"
