#!/usr/bin/env bash
# install-pipelinek.sh — Autonomous installer for the PipelineK CLI
# Authority:
#   -: the canonical artifact is the ZIP.
#   - TRAIN: SHA256SUMS authority, transactional install,
#     exact runtime identity, doctor diagnostics.
# Verification status: the contract tests that used to cover this installer
# (scripts/test_install_pipelinek.py, exercised by the former /)
# were deleted by the testing reset, and the current policy forbids the
# subprocess-based unit tests that would replace them. These guarantees are
# therefore NOT covered by a permanent automated check; they are exercised,
# once per release cycle, by an ephemeral verification against a published
# release (real manifest, real ZIP, real install), and `doctor` is the manual
# diagnostic a user can run against a real installation.
# Usage:
#   install-pipelinek.sh install <release>          # download + verify + install
#   install-pipelinek.sh use <release>              # switch active symlink
#   install-pipelinek.sh list                       # installed releases + active
#   install-pipelinek.sh uninstall <release>        # remove a non-active release
#   install-pipelinek.sh doctor                     # run pipelinek doctor (active)
#   install-pipelinek.sh help
# <release> is a release SLOT: a stable version ("0.39.0") or a candidate
# ("0.39.0-rc1"). The slot names the RELEASE. The product version carried by
# the bytes and the name of the artifact are read from the release manifest,
# never guessed from the slot.
# Pre-conditions:
#   - bash 4+ (associative arrays, [[ ]])
#   - curl, sha256sum, unzip, python3 in PATH
#   - The user can write to $PIPELINEK_HOME (default ~/.local/share/pipelinek)
#   - The remote release publishes manifest.json, SHA256SUMS and the ZIP
# Environment overrides:
#   PIPELINEK_HOME             override install root (default ~/.local/share/pipelinek)
#   PIPELINEK_RELEASE_BASE_URL override base URL (MUST match allowlist; see URL_ALLOWLIST)
#   PIPELINEK_MIRROR_BASE_URL  install from a different base (e.g. a local mirror or
#                              an air-gapped copy). Loopback hosts are always allowed;
#                              any other host MUST still satisfy the allowlist.
#   PIPELINEK_SHA256_<SLOT>    override expected SHA-256 (else read from $BASE/SHA256SUMS).
#                              The slot is spelled with '_' wherever it has '.', '-' or any
#                              other character that is not a letter or a digit
#                              (0.88.0-rc1 -> PIPELINEK_SHA256_0_88_0_rc1).
# Idempotent: install is no-op if the release is already present; uninstall is
# no-op if absent.
# Never uses sudo. Never writes to /usr or /opt. Never spawns a daemon.
# Install is TRANSACTIONAL: download, digest verification, extraction
# and identity verification all happen in a private temporary directory. The
# final version directory is created by a single rename after everything has
# been verified, so a failed install can never leave a half-populated
# versions/<release> behind.

set -Eeuo pipefail

# ---------------------------------------------------------------------------
# Constants and configuration
# ---------------------------------------------------------------------------

readonly CANONICAL_REPO="Rubentxu/pipeline-kotlin"
readonly DEFAULT_BASE_URL="https://github.com/${CANONICAL_REPO}/releases/download"
readonly URL_ALLOWLIST_HOSTS=("github.com" "objects.githubusercontent.com")
readonly SCRIPT_NAME="${BASH_SOURCE[0]##*/}"
readonly SHA256SUMS_NAME="SHA256SUMS"
readonly RELEASE_MANIFEST_NAME="manifest.json"

# A release SLOT is what the operator types. It names the RELEASE, and the
# release carries a tag; the product version and the artifact name are not
# the slot, they are properties of the release that the manifest states.
# The manifest's own schema and version rules are enforced where they are
# read, in read_release_manifest, so there is one authority for each rule.
readonly STABLE_SLOT_REGEX='^[0-9]+\.[0-9]+\.[0-9]+$'
readonly CANDIDATE_SLOT_REGEX='^[0-9]+\.[0-9]+\.[0-9]+-[a-z]+[0-9]*$'

PIPELINEK_HOME="${PIPELINEK_HOME:-${HOME}/.local/share/pipelinek}"
PIPELINEK_RELEASE_BASE_URL="${PIPELINEK_RELEASE_BASE_URL:-${DEFAULT_BASE_URL}}"
PIPELINEK_MIRROR_BASE_URL="${PIPELINEK_MIRROR_BASE_URL:-}"

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

log_info()  { printf '[INFO]  %s\n' "$*" >&2; }
log_warn()  { printf '[WARN]  %s\n' "$*" >&2; }
log_error() { printf '[ERROR] %s\n' "$*" >&2; }

die() {
  log_error "$*"
  exit 1
}

# ---------------------------------------------------------------------------
# URL allowlist
# ---------------------------------------------------------------------------

# Validate a base URL. Fails closed if the URL host does not match an entry
# in URL_ALLOWLIST_HOSTS, or if the path does not point to a
# ${CANONICAL_REPO} release download. Loopback hosts are additionally
# allowed so that a local mirror or an air-gapped copy can be used as the
# install source without weakening the rule for public hosts.
is_loopback_host() {
  # The port is stripped by the caller, so a bare host is compared here.
  case "$1" in
    127.0.0.1|localhost|::1) return 0 ;;
    # Any address in 127.0.0.0/8 is loopback, not just 127.0.0.1.
    127.*) return 0 ;;
    *) return 1 ;;
  esac
}

validate_base_url() {
  local url="$1"

  # Parse host. Strip a port, credentials, and a trailing path, and unwrap
  # an IPv6 literal. A port left attached to the host would defeat the
  # allowlist comparison, so it is removed explicitly.
  local host
  host="$(printf '%s' "${url}" | sed -E 's|^[a-zA-Z]+://||; s|^[^@/]*@||; s|[:/?#].*$||')"
  if [ -z "${host}" ]; then
    die "Base URL has no host: '${url}'"
  fi

  if is_loopback_host "${host}"; then
    return 0
  fi

  local allowed=0
  local h
  for h in "${URL_ALLOWLIST_HOSTS[@]}"; do
    if [ "${h}" = "${host}" ]; then
      allowed=1
      break
    fi
  done

  if [ "${allowed}" -ne 1 ]; then
    die "Base URL host '${host}' is not in the allowlist (${URL_ALLOWLIST_HOSTS[*]})"
  fi

  # The path must point to the canonical repo releases/download
  if ! printf '%s' "${url}" | grep -q "${CANONICAL_REPO}/releases/download"; then
    die "Base URL must point to ${CANONICAL_REPO}/releases/download, got: '${url}'"
  fi
}

# The base URL the install actually downloads from. PIPELINEK_MIRROR_BASE_URL
# takes precedence when set; it is validated with the same fail-closed rule,
# so setting it can never open the installer to an arbitrary host.
effective_base_url() {
  if [ -n "${PIPELINEK_MIRROR_BASE_URL}" ]; then
    printf '%s' "${PIPELINEK_MIRROR_BASE_URL}"
  else
    printf '%s' "${PIPELINEK_RELEASE_BASE_URL}"
  fi
}

# ---------------------------------------------------------------------------
# Release identities
# ---------------------------------------------------------------------------
# A release carries FOUR names, and they are not interchangeable:
#
#   slot       what the operator types: "0.88.0" (stable) or "0.88.0-rc1"
#              (candidate). It names the RELEASE, and therefore the directory
#              the release is installed into.
#   tag        where the release lives: "v<slot>". This is the URL segment.
#   artifact   the file that carries the bytes: "pipelinek-<version>.zip".
#              It is DISCOVERED from the release manifest, never constructed.
#   version    the product version the binary reports: "<train>".
#
# Constructing all four from one string is what used to break this installer:
# a candidate could not be asked for at all, and asking for a stable version
# pointed at a release directory that does not exist when only the candidate
# is published. Asking for "0.88.0" must never silently yield the bytes of
# "v0.88.0-rc1": those are different releases.
#
# The manifest published with the release (schema pipelinek-release-manifest-1)
# is the authority that binds them: it names the tag, the product version and
# the artifact with its digest. Everything below is checked against it.

# normalize_slot drops an optional leading "v" so that the tag a user copied
# from the release page and the slot a user remembers are the same release.
normalize_slot() {
  local slot="$1"
  case "${slot}" in
    v[0-9]*.[0-9]*.[0-9]*) printf '%s' "${slot#v}" ;;
    *) printf '%s' "${slot}" ;;
  esac
}

# slot_kind echoes "stable" or "candidate"; returns 1 for anything else.
slot_kind() {
  local slot="$1"
  if [[ "${slot}" =~ ${STABLE_SLOT_REGEX} ]]; then
    printf 'stable'
    return 0
  fi
  if [[ "${slot}" =~ ${CANDIDATE_SLOT_REGEX} ]]; then
    printf 'candidate'
    return 0
  fi
  return 1
}

# slot_train echoes the product version the slot belongs to: "0.88.0" for
# both "0.88.0" and "0.88.0-rc1".
slot_train() { printf '%s' "${1%%-*}"; }

# release_tag echoes the URL segment of the release: "v0.88.0-rc1".
release_tag() { printf 'v%s' "$1"; }

validate_slot() {
  local slot="$1"
  if ! slot_kind "${slot}" >/dev/null; then
    die "Invalid release '${slot}'. Expected a stable version (0.39.0) or a candidate (0.39.0-rc1)."
  fi
}

# ---------------------------------------------------------------------------
# Path helpers
# ---------------------------------------------------------------------------

version_dir() { printf '%s/versions/%s' "${PIPELINEK_HOME}" "$1"; }
current_link() { printf '%s/current' "${PIPELINEK_HOME}"; }

# Look up the expected digest of an asset in a SHA256SUMS manifest.
# SHA256SUMS is the digest authority: one file lists every asset of a release,
# so a release does not need one sidecar file per asset. The per-asset
# PIPELINEK_SHA256_<SLOT> override still wins when the caller sets it.
# Echoes "<digest>" and returns 0 when the asset has an entry.
# Returns 1 when the asset is absent or the entry is malformed.
# The published manifest records the path the asset had INSIDE the release
# build, not the bare download name. Observed on the real v0.41.0-rc1
# release:
#   ... dist/candidates/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip
# so the asset is matched on its BASENAME. Matching the full field would make
# every real install fail closed, which is the safe direction but still wrong.
lookup_sha256sums_entry() {
  local sums_path="$1"
  local asset="$2"

  local line digest name
  while IFS= read -r line || [ -n "${line}" ]; do
    # sha256sum format: "<64 hex>  <name>"; tolerate one or many spaces,
    # and the binary marker '*' before the name.
    read -r digest name <<<"${line}"
    name="${name#\*}"
    [ -n "${digest}" ] || continue
    [ -n "${name}" ] || continue

    # Only this asset matters; other assets in the manifest are ignored.
    # Compare basenames because the manifest may carry build-time paths.
    if [ "$(basename "${name}")" = "${asset}" ]; then
      if ! [[ "${digest}" =~ ^[0-9a-f]{64}$ ]]; then
        log_error "SHA256SUMS entry for ${asset} is malformed: '${digest}'"
        return 1
      fi
      printf '%s\n' "${digest}"
      return 0
    fi
  done < "${sums_path}"

  return 1
}

# ---------------------------------------------------------------------------
# Release manifest
# ---------------------------------------------------------------------------

# read_release_manifest <manifest> <expected-tag> <expected-train>
#
# Echoes three lines: the artifact name, its SHA-256 and the product version.
# It fails closed on anything it does not understand: an unknown schema, a
# tag that is not the one requested, a product version outside the train, a
# manifest that does not name exactly one distribution, or a digest that is
# not a SHA-256. A manifest this script cannot read is a manifest it must not
# guess at. The reason is printed on stderr; the caller decides what to do.
read_release_manifest() {
  local manifest_path="$1"
  local expected_tag="$2"
  local expected_train="$3"

  local err_file
  err_file="$(mktemp -t pipelinek-manifest-XXXXXX)"

  local parsed
  if ! parsed="$(python3 - "${manifest_path}" "${expected_tag}" "${expected_train}" <<'PY' 2>"${err_file}"
import json
import re
import sys

path, expected_tag, expected_train = sys.argv[1:4]
SCHEMA = "pipelinek-release-manifest-1"
PRODUCT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
DISTRIBUTION = re.compile(r"^pipelinek-[0-9]+\.[0-9]+\.[0-9]+\.zip$")

try:
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
except Exception as exc:
    sys.exit(f"the release manifest could not be read: {exc}")

if not isinstance(doc, dict):
    sys.exit("the release manifest is not a JSON object")

if doc.get("schema_version") != SCHEMA:
    sys.exit(f"unknown release manifest schema: {doc.get('schema_version')!r}")

tag = doc.get("release_tag")
if tag != expected_tag:
    sys.exit(f"the manifest names release {tag!r}, but this release is {expected_tag!r}")

version = doc.get("version")
if not isinstance(version, str) or not PRODUCT_VERSION.match(version):
    sys.exit(f"the manifest names a product version that is not MAJOR.MINOR.PATCH: {version!r}")
if version != expected_train:
    sys.exit(f"the manifest carries product version {version}, but this release belongs to train {expected_train}")

contents = doc.get("contents")
if not isinstance(contents, dict) or not contents:
    sys.exit("the manifest carries no contents")
if len(contents) != 1:
    sys.exit(f"the manifest names {len(contents)} contents, expected exactly one distribution")

name, entry = next(iter(contents.items()))
if not isinstance(entry, dict):
    sys.exit(f"the manifest entry for {name!r} is not an object")

digest = entry.get("sha256")
if not isinstance(digest, str) or not SHA256.match(digest):
    sys.exit(f"the manifest entry for {name!r} has no valid SHA-256 digest")

if not DISTRIBUTION.match(name):
    sys.exit(f"the manifest does not name the CLI distribution: {name!r}")
if name != f"pipelinek-{version}.zip":
    sys.exit(f"the manifest names {name!r}, but product version {version} publishes pipelinek-{version}.zip")

print(name)
print(digest)
print(version)
PY
  )"; then
    cat "${err_file}" >&2
    rm -f "${err_file}"
    return 1
  fi
  rm -f "${err_file}"
  printf '%s\n' "${parsed}"
}

# ---------------------------------------------------------------------------
# Subcommand: install
# ---------------------------------------------------------------------------

cmd_install() {
  local slot
  slot="$(normalize_slot "${1}")"

  validate_slot "${slot}"

  if ! command -v python3 >/dev/null 2>&1; then
    die "python3 is required to read the release manifest of ${slot}."
  fi

  local base_url
  base_url="$(effective_base_url)"
  validate_base_url "${base_url}"

  local kind train tag
  kind="$(slot_kind "${slot}")"
  train="$(slot_train "${slot}")"
  tag="$(release_tag "${slot}")"

  local target_dir
  target_dir="$(version_dir "${slot}")"

  if [ -d "${target_dir}" ]; then
    log_info "Release ${slot} already installed at ${target_dir} (idempotent)."
    return 0
  fi

  local manifest_url="${base_url}/${tag}/${RELEASE_MANIFEST_NAME}"
  local sums_url="${base_url}/${tag}/${SHA256SUMS_NAME}"

  # Allow callers to override expected digest via env var
  local expected_sha_var="PIPELINEK_SHA256_${slot//[^a-zA-Z0-9]/_}"
  local expected_sha="${!expected_sha_var:-}"

  # TRANSACTIONAL INSTALL. Everything happens in a private temporary
  # directory; ${target_dir} is created by a single rename at the very end,
  # only after download, digest, extraction and identity have all passed. A
  # failure therefore cannot leave a partial versions/<slot> behind.
  local workdir staging
  workdir="$(mktemp -d -t pipelinek-install-XXXXXX)"
  # Use a globally-exported scratch dir so the EXIT trap survives after
  # the function returns (set -u would otherwise complain about the
  # disappeared local variable).
  export PIPELINEK_INSTALL_TMPDIR="${workdir}"
  trap 'rm -rf "${PIPELINEK_INSTALL_TMPDIR:-}"' EXIT
  staging="${workdir}/staged"

  # IDENTITY FIRST. The manifest states which release this is, which artifact
  # carries its bytes and which product version those bytes report. The
  # artifact name is READ from it, never constructed from the slot: the two
  # names differ (a candidate release publishes pipelinek-<train>.zip), and
  # constructing them is what used to make candidates uninstallable.
  local manifest_path="${workdir}/${RELEASE_MANIFEST_NAME}"
  log_info "Installing ${kind} release ${slot} (tag ${tag})"
  log_info "Fetching the release manifest ${manifest_url}"
  if ! curl -fsSL --retry 3 --retry-delay 2 -o "${manifest_path}" "${manifest_url}"; then
    log_error "No release found at tag ${tag}."
    log_error "  ${manifest_url}"
    log_error "A stable version never resolves to a candidate: this release line publishes"
    log_error "candidates under their own -rcN tag, so name the candidate explicitly"
    log_error "(for example ${train}-rc1) if that is the release you want."
    die "Nothing was installed."
  fi

  local manifest_info asset manifest_digest product_version
  if ! manifest_info="$(read_release_manifest "${manifest_path}" "${tag}" "${train}")"; then
    die "Refusing to install ${slot}: the release manifest does not describe this release."
  fi
  asset="$(printf '%s\n' "${manifest_info}" | sed -n '1p')"
  manifest_digest="$(printf '%s\n' "${manifest_info}" | sed -n '2p')"
  product_version="$(printf '%s\n' "${manifest_info}" | sed -n '3p')"
  log_info "Release ${tag} carries product version ${product_version} in ${asset}"

  local zip_url="${base_url}/${tag}/${asset}"
  local zip_path="${workdir}/${asset}"
  local sums_path="${workdir}/${SHA256SUMS_NAME}"

  log_info "Downloading ${zip_url}"
  if ! curl -fsSL --retry 3 --retry-delay 2 -o "${zip_path}" "${zip_url}"; then
    die "Failed to download ZIP from ${zip_url}"
  fi

  # SHA256SUMS is the digest authority. The old ${asset}.sha256
  # sidecar is gone: a release ships one manifest listing every asset.
  local expected_digest=""
  if [ -n "${expected_sha}" ]; then
    log_info "Using expected digest from env var ${expected_sha_var}"
    if ! [[ "${expected_sha}" =~ ^[0-9a-fA-F]{64}$ ]]; then
      die "${expected_sha_var} is not a valid SHA-256 (expected 64 hex chars), got: '${expected_sha}'"
    fi
    expected_digest="$(printf '%s' "${expected_sha}" | tr '[:upper:]' '[:lower:]')"
  else
    log_info "Fetching ${SHA256SUMS_NAME} from ${sums_url}"
    if ! curl -fsSL --retry 3 --retry-delay 2 -o "${sums_path}" "${sums_url}"; then
      die "Failed to fetch ${SHA256SUMS_NAME} from ${sums_url}. Refusing to install without a digest authority."
    fi
    local entry
    if ! entry="$(lookup_sha256sums_entry "${sums_path}" "${asset}")"; then
      die "${SHA256SUMS_NAME} has no valid entry for ${asset}. Refusing to install without a digest authority."
    fi
    expected_digest="${entry}"
  fi

  # The receipt and the digest authority must agree about the same bytes. A
  # manifest that names a different digest than SHA256SUMS is a release whose
  # two statements about its own artifact contradict each other, and this
  # installer does not pick a winner.
  if [ "${expected_digest}" != "${manifest_digest}" ]; then
    log_error "The release manifest and the digest authority disagree about ${asset}"
    log_error "  manifest  ${manifest_digest}"
    log_error "  authority ${expected_digest}"
    die "Refusing to install ${slot}."
  fi

  local actual_digest
  actual_digest="$(sha256sum "${zip_path}" | cut -d' ' -f1)"
  if [ "${actual_digest}" != "${expected_digest}" ]; then
    log_error "Digest mismatch for ${asset}"
    log_error "  expected ${expected_digest}"
    log_error "  actual   ${actual_digest}"
    die "SHA-256 mismatch, refusing to install ${slot}."
  fi
  log_info "Digest verified: ${actual_digest}"

  # Extract into the staging area, never into ${target_dir}.
  log_info "Staging verification copy in ${staging}"
  if ! unzip -q "${zip_path}" -d "${staging}"; then
    die "Failed to extract ZIP; nothing was installed."
  fi

  # The ZIP must unpack to exactly one top-level directory, and that directory
  # is named after the ARTIFACT (pipelinek-<version>.zip -> pipelinek-<version>).
  # The artifact name comes from the manifest, so the root is checked against
  # the release that was asked for, not against a name this script invented.
  local expected_root="${asset%.zip}"
  shopt -s dotglob nullglob
  local top
  top=("${staging}"/*)
  shopt -u dotglob nullglob

  if [ "${#top[@]}" -ne 1 ] || [ ! -d "${top[0]}" ]; then
    die "Archive must contain exactly one top-level directory '${expected_root}/', found ${#top[@]} entries. Nothing was installed."
  fi

  local observed_root
  observed_root="$(basename "${top[0]}")"
  if [ "${observed_root}" != "${expected_root}" ]; then
    die "Archive root is '${observed_root}' but release ${tag} requires '${expected_root}'. Nothing was installed."
  fi

  # EXACT RUNTIME IDENTITY. A warning is not enough: the point of the
  # release-evolution protocol is that the installed binary reports exactly
  # the product version the release carries. A mismatch is a hard failure.
  # The comparison is against the MANIFEST's product version, not against the
  # slot: a candidate release legitimately contains a binary that reports the
  # train it belongs to ("0.88.0"), because candidate state is not expressed
  # inside the product version.
  local staged_root="${top[0]}"
  local binary="${staged_root}/bin/pipelinek"
  if [ ! -f "${binary}" ]; then
    die "Expected binary bin/pipelinek not found in extracted archive. Nothing was installed."
  fi
  chmod +x "${binary}"

  local reported_version
  if ! reported_version="$("${binary}" version 2>&1 | head -n 1)"; then
    die "Failed to execute the extracted 'pipelinek version'. Nothing was installed."
  fi
  log_info "Installed binary reports: ${reported_version}"

  # The CLI contract is exactly "pipeline <Implementation-Version>" (see
  # Main.kt). Compare the extracted token, not a substring: a substring test
  # would accept "0.44.0" when asked for "0.4", and would accept a
  # 0.44.0-rc1 body underneath a 0.44.0 filename.
  local observed_version="${reported_version#pipeline }"
  observed_version="$(printf '%s' "${observed_version}" | tr -d '[:space:]')"
  if [ "${observed_version}" != "${product_version}" ]; then
    log_error "Runtime identity mismatch: release ${tag} carries ${product_version}, binary reports ${observed_version}"
    die "Refusing to install ${slot}: the extracted binary is ${observed_version}. Nothing was installed."
  fi

  # final step: one rename creates the installed release. versions/ is
  # created only now, so a refusal above never touches the install root.
  log_info "Publishing verified install to ${target_dir}"
  mkdir -p "$(dirname "${target_dir}")"
  if ! mv "${staged_root}" "${target_dir}"; then
    die "Failed to move the verified install into ${target_dir}."
  fi

  log_info "Release ${slot} (tag ${tag}, product version ${product_version}) installed at ${target_dir}"
  log_info "Activate with: ${SCRIPT_NAME} use ${slot}"
}

# ---------------------------------------------------------------------------
# Subcommand: use
# ---------------------------------------------------------------------------

cmd_use() {
  local slot
  slot="$(normalize_slot "${1}")"

  validate_slot "${slot}"

  local target_dir
  target_dir="$(version_dir "${slot}")"

  if [ ! -d "${target_dir}" ]; then
    die "Release ${slot} is not installed. Run '${SCRIPT_NAME} install ${slot}' first."
  fi

  local link
  link="$(current_link)"
  mkdir -p "$(dirname "${link}")"

  # Remove existing link (if any) and create a new one atomically.
  if [ -L "${link}" ] || [ -e "${link}" ]; then
    rm -f "${link}"
  fi
  ln -s "versions/${slot}" "${link}"

  log_info "Active release: ${slot} (-> ${link})"
  log_info "Add to PATH: export PATH=\"${link}/bin:\${PATH}\""
}

# ---------------------------------------------------------------------------
# Subcommand: list
# ---------------------------------------------------------------------------

cmd_list() {
  if [ ! -d "${PIPELINEK_HOME}/versions" ]; then
    log_info "No releases installed yet (${PIPELINEK_HOME}/versions not found)."
    return 0
  fi

  local active_target=""
  local link
  link="$(current_link)"
  if [ -L "${link}" ]; then
    active_target="$(readlink "${link}")"
  fi

  printf '%-14s %-7s %s\n' "RELEASE" "ACTIVE" "PATH"
  printf '%-14s %-7s %s\n' "-------" "------" "----"

  local v
  for v in "${PIPELINEK_HOME}/versions"/*; do
    [ -d "${v}" ] || continue
    local name
    name="$(basename "${v}")"
    local marker=""
    if [ "versions/${name}" = "${active_target}" ]; then
      marker="*"
    fi
    printf '%-14s %-7s %s\n' "${name}" "${marker}" "${v}"
  done
}

# ---------------------------------------------------------------------------
# Subcommand: uninstall
# ---------------------------------------------------------------------------

cmd_uninstall() {
  local slot
  slot="$(normalize_slot "${1}")"

  validate_slot "${slot}"

  local target_dir
  target_dir="$(version_dir "${slot}")"

  if [ ! -d "${target_dir}" ]; then
    log_info "Release ${slot} is not installed (idempotent)."
    return 0
  fi

  local link
  link="$(current_link)"
  if [ -L "${link}" ] && [ "$(readlink "${link}")" = "versions/${slot}" ]; then
    die "Cannot uninstall active release ${slot}. Run '${SCRIPT_NAME} use <other>' first."
  fi

  rm -rf "${target_dir}"
  log_info "Removed ${target_dir}"
}

# ---------------------------------------------------------------------------
# Subcommand: doctor
# ---------------------------------------------------------------------------

cmd_doctor() {
  local link
  link="$(current_link)"
  if [ ! -L "${link}" ]; then
    die "No active version. Run '${SCRIPT_NAME} install <release>' then '${SCRIPT_NAME} use <release>'."
  fi

  local active_target
  active_target="$(readlink "${link}")"
  local binary="${PIPELINEK_HOME}/${active_target}/bin/pipelinek"
  if [ ! -x "${binary}" ]; then
    die "Active binary not found or not executable: ${binary}"
  fi

  # DIAGNOSTICS. Report where the binary actually comes from and what
  # it claims to be, BEFORE delegating. A support conversation about "the
  # wrong version on my PATH" is usually a stale symlink, a shadowed binary,
  # or a mismatch between the requested and installed identity; naming the
  # resolved root makes all three visible immediately.
  local active_version
  active_version="$("${binary}" version 2>&1 | head -n 1)" || active_version="<unreadable>"

  # The slot and the tag are the identity of the RELEASE; the binary reports
  # the PRODUCT version, which for a candidate is the train it belongs to.
  # Both are printed because "the wrong version" has two possible causes.
  local active_slot
  active_slot="$(basename "${active_target}")"

  log_info "Active release dir: ${PIPELINEK_HOME}/${active_target}"
  log_info "Active binary:      ${binary}"
  log_info "Active release:     ${active_slot} (tag v${active_slot})"
  log_info "Active version:     ${active_version}"
  log_info "Install root:       ${PIPELINEK_HOME}"
  log_info "PATH entry to add:  ${link}/bin"

  # Report shadowing rather than guessing about it: a different pipelinek
  # earlier on PATH is the usual cause of a confusing version report.
  local resolved
  resolved="$(command -v pipelinek 2>/dev/null || true)"
  if [ -n "${resolved}" ] && [ "${resolved}" != "${binary}" ]; then
    log_warn "PATH resolves 'pipelinek' to ${resolved}, which is not the active install."
    log_warn "Put ${link}/bin ahead of it in PATH to use the active version."
  fi

  "${binary}" doctor
}

# ---------------------------------------------------------------------------
# Subcommand: help
# ---------------------------------------------------------------------------

cmd_help() {
  cat <<EOF
${SCRIPT_NAME} — autonomous installer for PipelineK

Usage:
  ${SCRIPT_NAME} install <release>       Download + verify + install a release
  ${SCRIPT_NAME} use <release>           Switch the active release symlink
  ${SCRIPT_NAME} list                    List installed releases
  ${SCRIPT_NAME} uninstall <release>     Remove an installed (non-active) release
  ${SCRIPT_NAME} doctor                  Run the active binary's doctor command
  ${SCRIPT_NAME} help                    Show this help

A <release> is a stable version (0.39.0) or a candidate (0.39.0-rc1). The
release publishes a manifest that names its artifact and its product
version; both are read from that manifest, so a candidate installs from its
own tag and never under the name of a stable version.

Environment:
  PIPELINEK_HOME              Install root (default: ~/.local/share/pipelinek)
  PIPELINEK_RELEASE_BASE_URL  Override base URL (must match allowlist)
  PIPELINEK_MIRROR_BASE_URL   Install from a local/air-gapped mirror instead
  PIPELINEK_SHA256_<SLOT>     Override expected SHA-256 (no SHA256SUMS fetch)

Install is transactional: download, digest, extraction and identity checks
all run in a temporary directory, and the final release directory appears
only after every check has passed. A failed install leaves nothing behind.
The installed binary must report EXACTLY the product version the release
manifest names.

Examples:
  ${SCRIPT_NAME} install 0.39.0
  ${SCRIPT_NAME} install 0.39.0-rc1
  ${SCRIPT_NAME} use 0.39.0-rc1
  export PATH="\$HOME/.local/share/pipelinek/current/bin:\${PATH}"
  pipelinek version
EOF
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

main() {
  if [ "$#" -lt 1 ]; then
    cmd_help >&2
    exit 2
  fi

  local subcommand="$1"
  shift

  case "${subcommand}" in
    install)    [ "$#" -eq 1 ] || die "install requires one argument: <release>"; cmd_install "$1" ;;
    use)        [ "$#" -eq 1 ] || die "use requires one argument: <release>";      cmd_use "$1" ;;
    list)       [ "$#" -eq 0 ] || die "list takes no arguments";                    cmd_list ;;
    uninstall)  [ "$#" -eq 1 ] || die "uninstall requires one argument: <release>"; cmd_uninstall "$1" ;;
    doctor)     [ "$#" -eq 0 ] || die "doctor takes no arguments";                  cmd_doctor ;;
    help|-h|--help) cmd_help ;;
    *)          log_error "Unknown subcommand: ${subcommand}"; cmd_help >&2; exit 2 ;;
  esac
}

main "$@"
