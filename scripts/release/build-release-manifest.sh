#!/usr/bin/env bash
# build-release-manifest.sh — produce the SHA256SUMS digest authority for a release.
#
# Why this is a separate script
# -----------------------------
# `resolve-release-digest.sh` is the CONSUMER half of the same contract: it
# resolves an asset's expected digest out of a published manifest. This is the
# PRODUCER half. Splitting them means the format has exactly one producer and
# exactly one consumer, and both are testable without a GitHub release.
#
# What the spec requires (and what this therefore emits)
# ------------------------------------------------------
# §4 lists what is "published alongside" the distribution ZIP:
#
#   - the ZIP
#   - SHA256SUMS      <- this script's output
#   - the SBOM
#   - release notes
#   - (optional) signature/provenance attestation
#
# SHA256SUMS is described there as "the single digest authority for the whole
# release". That word `whole` is the requirement this script exists to satisfy:
# every asset published on the release other than SHA256SUMS itself must carry
# an entry. A manifest that lists the ZIP and silently omits the SBOM is not a
# weaker digest authority, it is a wrong one — a consumer that trusts the
# manifest cannot detect a swapped SBOM.
#
# What is deliberately NOT invented
# ----------------------------------
# The output is the ordinary `sha256sum` text format (`<64 hex>  <name>`), which
# is what `resolve-release-digest.sh` and `scripts/install-pipelinek.sh` already
# parse. No header comment, no JSON wrapper, no extra columns. `sha256sum -c`
# reads it as-is, so a certifier can verify with the standard tool instead of a
# project-specific reader.
#
# SHA256SUMS cannot contain its own digest — that value is not known until the
# file is written. `sha256sum` has the same property, and every real release
# manifest in the wild excludes itself for the same reason. The script refuses
# the file rather than emitting a self-entry that would be a lie.
#
# Usage:
#   build-release-manifest.sh --out <path> <file> [<file> ...]
#
# <file> paths are recorded as GIVEN, so callers should pass paths relative to
# the directory the manifest will live in (or use --basenames). The consumer
# matches on basename anyway (see resolve-release-digest.sh §"Why basename
# matching"), but a manifest whose entries resolve on disk is easier to audit.
#
# Exit 0 on success (the manifest exists, was verified, and covers exactly the
# requested set). Non-zero on any missing file, duplicate, self-inclusion, or
# verification failure. It never emits a manifest it has not re-read.

set -euo pipefail

readonly MANIFEST_NAME="SHA256SUMS"

usage() {
  cat >&2 <<EOF
usage: $(basename "$0") --out <path> <file> [<file> ...]

Writes a sha256sum-format digest manifest covering exactly <file> ...
Refuses to run when any input is missing, duplicated, or is the manifest itself.
EOF
}

fail() { echo "build-release-manifest: $*" >&2; exit 1; }

main() {
  local out=""
  local files=()

  while [ "$#" -gt 0 ]; do
    case "$1" in
      --out)
        [ "$#" -ge 2 ] || fail "--out requires a path"
        out="$2"
        shift 2
        ;;
      -h|--help) usage; exit 0 ;;
      --) shift; files+=("$@"); break ;;
      -*) fail "unknown option: $1" ;;
      *) files+=("$1"); shift ;;
    esac
  done

  [ -n "$out" ] || fail "--out is required"
  [ "${#files[@]}" -gt 0 ] || fail "no input files given; a manifest with no entries would be a digest authority for nothing"

  # Absolute, so the entry names below are computed against a stable base even
  # when the caller mixed relative and absolute inputs.
  local abs_out
  abs_out="$(cd "$(dirname "$out")" 2>/dev/null && pwd)/$(basename "$out")" \
    || { mkdir -p "$(dirname "$out")"; abs_out="$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"; }
  local manifest_dir
  manifest_dir="$(dirname "$abs_out")"

  # Duplicate entries are not a formatting detail: `sha256sum -c` checks every
  # line, so a repeated name would let one file be verified twice and give the
  # reader a false impression of coverage.
  local seen=" " name base
  for name in "${files[@]}"; do
    [ -f "$name" ] || fail "input does not exist or is not a regular file: $name"
    [ -r "$name" ] || fail "input is not readable: $name"
    base="$(basename "$name")"
    [ "$base" = "$MANIFEST_NAME" ] && fail "refusing to put an entry for $MANIFEST_NAME in itself: a manifest cannot carry its own digest"
    case "$seen" in
      *" ${base} "*) fail "duplicate entry for $base" ;;
      *) seen="${seen}${base} " ;;
    esac
  done

  mkdir -p "$manifest_dir"

  # Entry names are recorded RELATIVE TO THE MANIFEST'S OWN DIRECTORY.
  #
  # The spec (§4) says entries carry "the build-time path, not the download
  # name", and the consumer resolves on basename, so any path would technically
  # work. But an ABSOLUTE path is a build-machine detail: it does not resolve
  # after a consumer downloads the assets and runs `sha256sum -c` beside them,
  # and it publishes the layout of the release machine to anyone who reads the
  # manifest. Staged assets all live beside the manifest, so the relative path
  # is both resolvable and non-disclosing.
  local -a entries=()
  for name in "${files[@]}"; do
    local abs
    abs="$(cd "$(dirname "$name")" && pwd)/$(basename "$name")"
    if [ "${abs#"${manifest_dir}/"}" != "$abs" ]; then
      entries+=("${abs#"${manifest_dir}/"}")
    else
      # Outside the manifest's tree: the basename is the only honest record,
      # and it is what the consumer matches on anyway.
      entries+=("$(basename "$name")")
    fi
  done

  # Deterministic order. `sha256sum` emits in argument order, so a manifest's
  # bytes would otherwise depend on how the caller listed the assets, and two
  # identical releases would produce two different manifest digests.
  local sorted=()
  while IFS= read -r line; do sorted+=("$line"); done < <(printf '%s\n' "${entries[@]}" | LC_ALL=C sort)

  ( cd "$manifest_dir" && sha256sum "${sorted[@]}" ) > "$abs_out"

  # Re-read what was actually written, from the directory the entries resolve
  # in. A manifest that does not verify against the files it names is worse
  # than no manifest, because it looks like an authority.
  if ! ( cd "$manifest_dir" && sha256sum -c "$(basename "$abs_out")" >/dev/null ); then
    rm -f "$abs_out"
    fail "the generated manifest does not verify against its own inputs; refusing to leave it on disk"
  fi

  local written
  written="$(grep -c . "$abs_out" || true)"
  [ "$written" -eq "${#sorted[@]}" ] || {
    rm -f "$abs_out"
    fail "manifest has $written entries for ${#sorted[@]} inputs; refusing to publish an incomplete digest authority"
  }

  echo "build-release-manifest: wrote $abs_out with $written entries (verified)"
}

main "$@"