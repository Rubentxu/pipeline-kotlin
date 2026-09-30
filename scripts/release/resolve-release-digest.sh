#!/usr/bin/env bash
# resolve-release-digest.sh — read an asset's expected SHA-256 from a
# SHA256SUMS manifest on stdin.
#
# Authority:
#   - docs/pipelinek-release-evolution/pipeline-kotlin/03-roadmap.md TRAIN P1.1
#   - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md
#
# Usage:
#   curl -fsSL "$RELEASE_BASE/v$VERSION/SHA256SUMS" | resolve-release-digest.sh pipelinek-$VERSION.zip
#
# Prints the 64-hex digest on stdout and exits 0. On any problem it names the
# reason on stderr and exits non-zero. This script NEVER prints a digest it
# did not read from the manifest: an absent or malformed entry is a hard
# failure, not an empty value a caller might accidentally accept.
#
# Why basename matching
# ---------------------
# The published SHA256SUMS records the path the asset had inside the release
# BUILD, not its download name. Observed on the real v0.41.0-rc1 release:
#
#   d49edc08...  dist/candidates/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip
#
# Comparing the whole field would fail every real release closed. Both the
# prefixed and the flat form are accepted; only the basename is significant.
#
# The old `${asset}.sha256` sidecar is NOT consulted. That URL returns 404 on
# real releases: a release publishes one manifest, not one sidecar per asset.

set -euo pipefail

readonly SHA256SUMS_NAME="SHA256SUMS"

usage() {
  cat >&2 <<EOF
usage: $(basename "$0") ASSET_NAME

Reads a SHA256SUMS manifest on stdin and prints the SHA-256 recorded for
ASSET_NAME. Fails closed when the asset is absent or its digest is malformed.
EOF
}

main() {
  if [ "$#" -ne 1 ]; then
    usage
    exit 2
  fi

  local asset="$1"
  if [ -z "${asset}" ]; then
    echo "error: ASSET_NAME is empty" >&2
    exit 2
  fi

  local line digest name
  while IFS= read -r line || [ -n "${line}" ]; do
    # sha256sum format: "<64 hex>  <name>" (text mode) or "<64 hex> *<name>"
    # (binary mode). Both split on whitespace into the same two fields. Any
    # '*' marker is absorbed by the basename comparison below, so it needs
    # no separate stripping.
    read -r digest name <<<"${line}"
    [ -n "${digest}" ] || continue
    [ -n "${name}" ] || continue

    # Only this asset matters; other entries in the manifest are ignored.
    # Compare basenames because the manifest may carry build-time paths.
    if [ "$(basename "${name}")" != "${asset}" ]; then
      continue
    fi

    if ! [[ "${digest}" =~ ^[0-9a-f]{64}$ ]]; then
      echo "error: ${SHA256SUMS_NAME} entry for ${asset} has a malformed digest: '${digest}'" >&2
      exit 1
    fi

    printf '%s\n' "${digest}"
    return 0
  done

  echo "error: ${SHA256SUMS_NAME} has no entry for ${asset}" >&2
  exit 1
}

main "$@"
