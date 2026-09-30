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
#   - GitHub Release vX.Y.Z is PUBLIC with the canonical ZIP AND its
#     SHA256SUMS manifest as assets
#   - ZIP SHA-256 match between the manifest and the GitHub asset
#   - pipelinek-X.Y.Z/bin/pipelinek is the executable SDKMAN will expose
#   - scripts/release/resolve-release-digest.sh is present and executable
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
# Overridable only so the contract tests can point the whole script at a
# loopback server. Production always uses the canonical GitHub base.
RELEASE_BASE="${PIPELINEK_RELEASE_BASE_URL:-https://github.com/Rubentxu/pipeline-kotlin/releases/download}"
URL="${RELEASE_BASE}/${GIT_TAG}/${ASSET}"
SUMS_URL="${RELEASE_BASE}/${GIT_TAG}/SHA256SUMS"
RESOLVER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/resolve-release-digest.sh"

: "${SDKMAN_CONSUMER_KEY:?SDKMAN_CONSUMER_KEY required}"
: "${SDKMAN_CONSUMER_TOKEN:?SDKMAN_CONSUMER_TOKEN required}"

echo "=== SDKMAN publish ==="
echo "candidate: $CANDIDATE"
echo "version:   $VERSION"
echo "url:       $URL"

# Read the expected SHA-256 from the release's SHA256SUMS manifest. The old
# ${asset}.sha256 sidecar is gone: that URL returns 404 on real releases, so
# this script could never have published anything. SHA256SUMS is already
# published alongside the ZIP.
if ! SUMS_BODY="$(curl -fsSL --max-time 30 "${SUMS_URL}")"; then
  echo "❌ could not fetch ${SUMS_URL}" >&2
  exit 1
fi
if ! SHA256="$(printf '%s\n' "${SUMS_BODY}" | bash "${RESOLVER}" "${ASSET}")"; then
  echo "❌ could not resolve a SHA-256 for ${ASSET} from ${SUMS_URL}" >&2
  exit 1
fi
echo "sha256:    $SHA256"

# Defence-in-depth: verify the recorded SHA matches what we compute from the
# archive bytes themselves. Reading a manifest is not verification: if GitHub
# ever serves a manifest that disagrees with the asset, fail closed. (Cheap;
# one curl.)
EXPECTED_VIA_LOCAL_SHA=$(curl -fsSL "${URL}" | sha256sum | awk '{print $1}')
if [ "${EXPECTED_VIA_LOCAL_SHA}" != "${SHA256}" ]; then
  echo "❌ SHA mismatch: ${SUMS_URL} says ${SHA256} for ${ASSET}" >&2
  echo "   but recomputing from ${URL} gives ${EXPECTED_VIA_LOCAL_SHA}" >&2
  exit 1
fi
echo "sha256 verify: ✓ SHA256SUMS matches the recomputed archive digest"
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

SDKMAN_RELEASE_ENDPOINT="${PIPELINEK_SDKMAN_RELEASE_ENDPOINT:-https://vendors.sdkman.io/release}"
SDKMAN_CANDIDATE_ENDPOINT="${PIPELINEK_SDKMAN_CANDIDATE_ENDPOINT:-https://api.sdkman.io/2/${CANDIDATE}}"

echo
echo "=== POST ${SDKMAN_RELEASE_ENDPOINT} ==="
RESPONSE=$(curl -fsS -X POST \
  -H "Consumer-Key: ${SDKMAN_CONSUMER_KEY}" \
  -H "Consumer-Token: ${SDKMAN_CONSUMER_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "${PAYLOAD}" \
  "${SDKMAN_RELEASE_ENDPOINT}")
echo "${RESPONSE}"
echo
echo "=== verify ==="
curl -fsS --max-time 15 "${SDKMAN_CANDIDATE_ENDPOINT}" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); v=[x for x in d.get('versions',[]) if x.get('version')=='${VERSION}']; print('version visible:', bool(v), v[0] if v else 'NOT FOUND')"
echo
echo "✅ ${CANDIDATE} ${VERSION} published to SDKMAN (UNIVERSAL)."
echo "   Note: not yet DEFAULT. Promote with:"
echo "     curl -X PUT -H 'Consumer-Key: ...' -H 'Consumer-Token: ...' \\"
echo "          -H 'Content-Type: application/json' \\"
echo "          -d '{\"candidate\":\"${CANDIDATE}\",\"version\":\"${VERSION}\"}' \\"
echo "          https://vendors.sdkman.io/default"
