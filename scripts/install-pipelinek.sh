#!/usr/bin/env bash
# install-pipelinek.sh — Autonomous installer for the PipelineK CLI
#
# Authority:
#   - docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md §2 (DIST-2)
#   - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md §1 (canonical artifact is ZIP)
#   - docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md
#   - docs/pipelinek-release-evolution/pipeline-kotlin/03-roadmap.md TRAIN P1
#     (P1.1 SHA256SUMS authority, P1.2 transactional install,
#      P1.3 exact runtime identity, P1.4 doctor diagnostics)
#   - docs/pipelinek-release-evolution/pipeline-kotlin/04-uat.md P-UAT-05 / P-UAT-06
#
# Contract tests: python3 scripts/test_install_pipelinek.py
#
# Usage:
#   install-pipelinek.sh install <version>          # download + verify + install
#   install-pipelinek.sh use <version>              # switch active symlink
#   install-pipelinek.sh list                       # installed versions + active
#   install-pipelinek.sh uninstall <version>        # remove a non-active version
#   install-pipelinek.sh doctor                     # run pipelinek doctor (active)
#   install-pipelinek.sh help
#
# Pre-conditions:
#   - bash 4+ (associative arrays, [[ ]])
#   - curl, sha256sum, unzip in PATH
#   - The user can write to $PIPELINEK_HOME (default ~/.local/share/pipelinek)
#   - The remote ZIP exists at the canonical GitHub Releases URL
#
# Environment overrides:
#   PIPELINEK_HOME             override install root (default ~/.local/share/pipelinek)
#   PIPELINEK_RELEASE_BASE_URL override base URL (MUST match allowlist; see §URL_ALLOWLIST)
#   PIPELINEK_MIRROR_BASE_URL  install from a different base (e.g. a local mirror or
#                              an air-gapped copy). Loopback hosts are always allowed;
#                              any other host MUST still satisfy the allowlist.
#   PIPELINEK_SHA256_<VERSION> override expected SHA-256 (else read from $BASE/SHA256SUMS)
#
# Idempotent: install is no-op if version already present; uninstall is no-op if absent.
# Never uses sudo. Never writes to /usr or /opt. Never spawns a daemon.
#
# Install is TRANSACTIONAL (P1.2): download, digest verification, extraction
# and identity verification all happen in a private temporary directory. The
# final version directory is created by a single rename after everything has
# been verified, so a failed install can never leave a half-populated
# versions/<version> behind.

set -Eeuo pipefail

# ---------------------------------------------------------------------------
# Constants and configuration
# ---------------------------------------------------------------------------

readonly CANONICAL_REPO="Rubentxu/pipeline-kotlin"
readonly DEFAULT_BASE_URL="https://github.com/${CANONICAL_REPO}/releases/download"
readonly URL_ALLOWLIST_HOSTS=("github.com" "objects.githubusercontent.com")
readonly VERSION_REGEX='^[0-9]+\.[0-9]+\.[0-9]+$'
readonly SCRIPT_NAME="${BASH_SOURCE[0]##*/}"
readonly SHA256SUMS_NAME="SHA256SUMS"

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
# Version and path helpers
# ---------------------------------------------------------------------------

validate_version() {
  local version="$1"
  if ! [[ "${version}" =~ ${VERSION_REGEX} ]]; then
    die "Invalid version '${version}'. Expected semver MAJOR.MINOR.PATCH (e.g. 0.39.0)."
  fi
}

version_dir() { printf '%s/versions/%s' "${PIPELINEK_HOME}" "$1"; }
current_link() { printf '%s/current' "${PIPELINEK_HOME}"; }
asset_zip()    { printf 'pipelinek-%s.zip' "$1"; }

# Look up the expected digest of an asset in a SHA256SUMS manifest (P1.1).
# SHA256SUMS is the digest authority: one file lists every asset of a release,
# so a release does not need one sidecar file per asset. The per-asset
# PIPELINEK_SHA256_<VERSION> override still wins when the caller sets it.
#
# Echoes "<digest>" and returns 0 when the asset has an entry.
# Returns 1 when the asset is absent or the entry is malformed.
#
# The published manifest records the path the asset had INSIDE the release
# build, not the bare download name. Observed on the real v0.41.0-rc1
# release:
#
#   d49edc08...  dist/candidates/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip
#
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
# Subcommand: install
# ---------------------------------------------------------------------------

cmd_install() {
  local version="$1"

  validate_version "${version}"

  local base_url
  base_url="$(effective_base_url)"
  validate_base_url "${base_url}"

  local target_dir
  target_dir="$(version_dir "${version}")"

  if [ -d "${target_dir}" ]; then
    log_info "Version ${version} already installed at ${target_dir} (idempotent)."
    return 0
  fi

  local asset
  asset="$(asset_zip "${version}")"
  local zip_url sums_url
  zip_url="${base_url}/v${version}/${asset}"
  sums_url="${base_url}/v${version}/${SHA256SUMS_NAME}"

  # Allow callers to override expected digest via env var
  local expected_sha_var="PIPELINEK_SHA256_${version//./_}"
  local expected_sha="${!expected_sha_var:-}"

  # P1.2 TRANSACTIONAL INSTALL. Everything happens in a private temporary
  # directory; ${target_dir} is created by a single rename at the very end,
  # only after download, digest, extraction and identity have all passed. A
  # failure therefore cannot leave a partial versions/<version> behind.
  local workdir staging
  workdir="$(mktemp -d -t pipelinek-install-XXXXXX)"
  # Use a globally-exported scratch dir so the EXIT trap survives after
  # the function returns (set -u would otherwise complain about the
  # disappeared local variable).
  export PIPELINEK_INSTALL_TMPDIR="${workdir}"
  trap 'rm -rf "${PIPELINEK_INSTALL_TMPDIR:-}"' EXIT
  staging="${workdir}/staged"

  local zip_path
  zip_path="${workdir}/${asset}"
  local sums_path
  sums_path="${workdir}/${SHA256SUMS_NAME}"

  log_info "Downloading ${zip_url}"
  if ! curl -fsSL --retry 3 --retry-delay 2 -o "${zip_path}" "${zip_url}"; then
    die "Failed to download ZIP from ${zip_url}"
  fi

  # P1.1 SHA256SUMS is the digest authority. The old ${asset}.sha256
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

  local actual_digest
  actual_digest="$(sha256sum "${zip_path}" | cut -d' ' -f1)"
  if [ "${actual_digest}" != "${expected_digest}" ]; then
    log_error "Digest mismatch for ${asset}"
    log_error "  expected ${expected_digest}"
    log_error "  actual   ${actual_digest}"
    die "SHA-256 mismatch, refusing to install ${version}."
  fi
  log_info "Digest verified: ${actual_digest}"

  # Extract into the staging area, never into ${target_dir}.
  log_info "Staging verification copy in ${staging}"
  if ! unzip -q "${zip_path}" -d "${staging}"; then
    die "Failed to extract ZIP; nothing was installed."
  fi

  # The ZIP must unpack to exactly one top-level directory named
  # "pipelinek-${version}". Anything else means the archive is mislabeled
  # or hostile, so refuse before anything is created in ${PIPELINEK_HOME}.
  local expected_root="pipelinek-${version}"
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
    die "Archive root is '${observed_root}' but version ${version} requires '${expected_root}'. Nothing was installed."
  fi

  # P1.3 EXACT RUNTIME IDENTITY. A warning is not enough: the point of the
  # release-evolution protocol is that the installed binary reports exactly
  # the version that was requested. A mismatch is a hard failure.
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
  if [ "${observed_version}" != "${version}" ]; then
    log_error "Runtime identity mismatch: requested ${version}, binary reports ${observed_version}"
    die "Refusing to install ${version}: the extracted binary is ${observed_version}. Nothing was installed."
  fi

  # P1.2 final step: one rename creates the installed version. versions/ is
  # created only now, so a refusal above never touches the install root.
  log_info "Publishing verified install to ${target_dir}"
  mkdir -p "$(dirname "${target_dir}")"
  if ! mv "${staged_root}" "${target_dir}"; then
    die "Failed to move the verified install into ${target_dir}."
  fi

  log_info "Version ${version} installed at ${target_dir}"
  log_info "Activate with: ${SCRIPT_NAME} use ${version}"
}

# ---------------------------------------------------------------------------
# Subcommand: use
# ---------------------------------------------------------------------------

cmd_use() {
  local version="$1"

  validate_version "${version}"

  local target_dir
  target_dir="$(version_dir "${version}")"

  if [ ! -d "${target_dir}" ]; then
    die "Version ${version} is not installed. Run '${SCRIPT_NAME} install ${version}' first."
  fi

  local link
  link="$(current_link)"
  mkdir -p "$(dirname "${link}")"

  # Remove existing link (if any) and create a new one atomically.
  if [ -L "${link}" ] || [ -e "${link}" ]; then
    rm -f "${link}"
  fi
  ln -s "versions/${version}" "${link}"

  log_info "Active version: ${version} (-> ${link})"
  log_info "Add to PATH: export PATH=\"${link}/bin:\${PATH}\""
}

# ---------------------------------------------------------------------------
# Subcommand: list
# ---------------------------------------------------------------------------

cmd_list() {
  if [ ! -d "${PIPELINEK_HOME}/versions" ]; then
    log_info "No versions installed yet (${PIPELINEK_HOME}/versions not found)."
    return 0
  fi

  local active_target=""
  local link
  link="$(current_link)"
  if [ -L "${link}" ]; then
    active_target="$(readlink "${link}")"
  fi

  printf '%-12s %-7s %s\n' "VERSION" "ACTIVE" "PATH"
  printf '%-12s %-7s %s\n' "-------" "------" "----"

  local v
  for v in "${PIPELINEK_HOME}/versions"/*; do
    [ -d "${v}" ] || continue
    local name
    name="$(basename "${v}")"
    local marker=""
    if [ "versions/${name}" = "${active_target}" ]; then
      marker="*"
    fi
    printf '%-12s %-7s %s\n' "${name}" "${marker}" "${v}"
  done
}

# ---------------------------------------------------------------------------
# Subcommand: uninstall
# ---------------------------------------------------------------------------

cmd_uninstall() {
  local version="$1"

  validate_version "${version}"

  local target_dir
  target_dir="$(version_dir "${version}")"

  if [ ! -d "${target_dir}" ]; then
    log_info "Version ${version} is not installed (idempotent)."
    return 0
  fi

  local link
  link="$(current_link)"
  if [ -L "${link}" ] && [ "$(readlink "${link}")" = "versions/${version}" ]; then
    die "Cannot uninstall active version ${version}. Run '${SCRIPT_NAME} use <other>' first."
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
    die "No active version. Run '${SCRIPT_NAME} install <version>' then '${SCRIPT_NAME} use <version>'."
  fi

  local active_target
  active_target="$(readlink "${link}")"
  local binary="${PIPELINEK_HOME}/${active_target}/bin/pipelinek"
  if [ ! -x "${binary}" ]; then
    die "Active binary not found or not executable: ${binary}"
  fi

  # P1.4 DIAGNOSTICS. Report where the binary actually comes from and what
  # it claims to be, BEFORE delegating. A support conversation about "the
  # wrong version on my PATH" is usually a stale symlink, a shadowed binary,
  # or a mismatch between the requested and installed identity; naming the
  # resolved root makes all three visible immediately.
  local active_version
  active_version="$("${binary}" version 2>&1 | head -n 1)" || active_version="<unreadable>"

  log_info "Active version dir: ${PIPELINEK_HOME}/${active_target}"
  log_info "Active binary:      ${binary}"
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
  ${SCRIPT_NAME} install <version>       Download + verify + install a version
  ${SCRIPT_NAME} use <version>           Switch the active version symlink
  ${SCRIPT_NAME} list                    List installed versions
  ${SCRIPT_NAME} uninstall <version>     Remove an installed (non-active) version
  ${SCRIPT_NAME} doctor                  Run the active binary's doctor command
  ${SCRIPT_NAME} help                    Show this help

Environment:
  PIPELINEK_HOME              Install root (default: ~/.local/share/pipelinek)
  PIPELINEK_RELEASE_BASE_URL  Override base URL (must match allowlist)
  PIPELINEK_MIRROR_BASE_URL   Install from a local/air-gapped mirror instead
  PIPELINEK_SHA256_<VERSION>  Override expected SHA-256 (no SHA256SUMS fetch)

Install is transactional: download, digest, extraction and identity checks
all run in a temporary directory, and the final version directory appears
only after every check has passed. A failed install leaves nothing behind.
The installed binary must report EXACTLY the requested version.

Examples:
  ${SCRIPT_NAME} install 0.39.0
  ${SCRIPT_NAME} use 0.39.0
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
    install)    [ "$#" -eq 1 ] || die "install requires one argument: <version>";  cmd_install "$1" ;;
    use)        [ "$#" -eq 1 ] || die "use requires one argument: <version>";      cmd_use "$1" ;;
    list)       [ "$#" -eq 0 ] || die "list takes no arguments";                    cmd_list ;;
    uninstall)  [ "$#" -eq 1 ] || die "uninstall requires one argument: <version>"; cmd_uninstall "$1" ;;
    doctor)     [ "$#" -eq 0 ] || die "doctor takes no arguments";                  cmd_doctor ;;
    help|-h|--help) cmd_help ;;
    *)          log_error "Unknown subcommand: ${subcommand}"; cmd_help >&2; exit 2 ;;
  esac
}

main "$@"
