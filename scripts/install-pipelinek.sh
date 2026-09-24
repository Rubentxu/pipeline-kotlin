#!/usr/bin/env bash
# install-pipelinek.sh — Autonomous installer for the PipelineK CLI
#
# Authority:
#   - docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md §2 (DIST-2)
#   - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md §1 (canonical artifact is ZIP)
#   - docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md
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
#   PIPELINEK_SHA256_<VERSION> override expected SHA-256 (else fetched from $BASE/<asset>.sha256)
#
# Idempotent: install is no-op if version already present; uninstall is no-op if absent.
# Never uses sudo. Never writes to /usr or /opt. Never spawns a daemon.

set -Eeuo pipefail

# ---------------------------------------------------------------------------
# Constants and configuration
# ---------------------------------------------------------------------------

readonly CANONICAL_REPO="Rubentxu/pipeline-kotlin"
readonly DEFAULT_BASE_URL="https://github.com/${CANONICAL_REPO}/releases/download"
readonly URL_ALLOWLIST_HOSTS=("github.com" "objects.githubusercontent.com")
readonly VERSION_REGEX='^[0-9]+\.[0-9]+\.[0-9]+$'
readonly SCRIPT_NAME="${BASH_SOURCE[0]##*/}"

PIPELINEK_HOME="${PIPELINEK_HOME:-${HOME}/.local/share/pipelinek}"
PIPELINEK_RELEASE_BASE_URL="${PIPELINEK_RELEASE_BASE_URL:-${DEFAULT_BASE_URL}}"

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

# Validate the base URL against the allowlist. Fails closed if the URL host
# does not match an entry in URL_ALLOWLIST_HOSTS, or if the path does not
# point to a ${CANONICAL_REPO} release download.
validate_base_url() {
  local url="$1"

  # Parse host
  local host
  host="$(printf '%s' "${url}" | sed -E 's|^https?://([^/]+).*|\1|')"
  if [ -z "${host}" ]; then
    die "PIPELINEK_RELEASE_BASE_URL has no host: '${url}'"
  fi

  local allowed=0
  for h in "${URL_ALLOWLIST_HOSTS[@]}"; do
    if [ "${h}" = "${host}" ]; then
      allowed=1
      break
    fi
  done

  if [ "${allowed}" -ne 1 ]; then
    die "PIPELINEK_RELEASE_BASE_URL host '${host}' is not in the allowlist (${URL_ALLOWLIST_HOSTS[*]})"
  fi

  # The path must point to the canonical repo releases/download
  if ! printf '%s' "${url}" | grep -q "${CANONICAL_REPO}/releases/download"; then
    die "PIPELINEK_RELEASE_BASE_URL must point to ${CANONICAL_REPO}/releases/download, got: '${url}'"
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

# ---------------------------------------------------------------------------
# Subcommand: install
# ---------------------------------------------------------------------------

cmd_install() {
  local version="$1"

  validate_version "${version}"
  validate_base_url "${PIPELINEK_RELEASE_BASE_URL}"

  local target_dir
  target_dir="$(version_dir "${version}")"

  if [ -d "${target_dir}" ]; then
    log_info "Version ${version} already installed at ${target_dir} (idempotent)."
    return 0
  fi

  local zip_url sha_url
  zip_url="${PIPELINEK_RELEASE_BASE_URL}/v${version}/$(asset_zip "${version}")"
  sha_url="${zip_url}.sha256"

  # Allow callers to override expected digest via env var
  local expected_sha_var="PIPELINEK_SHA256_${version//./_}"
  local expected_sha="${!expected_sha_var:-}"

  local workdir
  workdir="$(mktemp -d -t pipelinek-install-XXXXXX)"
  # Use a globally-exported scratch dir so the EXIT trap survives after
  # the function returns (set -u would otherwise complain about the
  # disappeared local variable).
  export PIPELINEK_INSTALL_TMPDIR="${workdir}"
  trap 'rm -rf "${PIPELINEK_INSTALL_TMPDIR:-}"' EXIT

  local zip_path
  zip_path="${workdir}/$(asset_zip "${version}")"
  local sha_path
  sha_path="${workdir}/$(asset_zip "${version}").sha256"

  log_info "Downloading ${zip_url}"
  if ! curl -fL --retry 3 --retry-delay 2 -o "${zip_path}" "${zip_url}"; then
    die "Failed to download ZIP from ${zip_url}"
  fi

  if [ -n "${expected_sha}" ]; then
    log_info "Verifying SHA-256 against env var ${expected_sha_var}"
    printf '%s  %s\n' "${expected_sha}" "$(basename "${zip_path}")" > "${sha_path}"
  else
    log_info "Fetching SHA-256 from ${sha_url}"
    if ! curl -fL --retry 3 --retry-delay 2 -o "${sha_path}" "${sha_url}"; then
      die "Failed to fetch SHA-256 manifest from ${sha_url}. Refusing to install without digest."
    fi
  fi

  log_info "Verifying digest with sha256sum -c"
  (
    cd "${workdir}"
    # Rewrite the .sha256 manifest so its filename matches the basename of
    # the downloaded ZIP. The published .sha256 may reference an internal
    # build path (e.g. v2/pipeline-application/build/distributions/...),
    # which sha256sum -c cannot resolve.
    local zip_basename
    zip_basename="$(basename "${zip_path}")"
    local parsed_digest
    parsed_digest="$(awk '{print $1}' "${sha_path}" | head -n 1)"
    if [ -z "${parsed_digest}" ]; then
      die "SHA-256 manifest is empty or malformed: ${sha_url}"
    fi
    printf '%s  %s\n' "${parsed_digest}" "${zip_basename}" > "${sha_path}.tmp"
    mv "${sha_path}.tmp" "${sha_path}"

    if ! sha256sum -c "$(basename "${sha_path}")"; then
      die "SHA-256 mismatch — refusing to install ${version}."
    fi
  )

  log_info "Extracting to ${target_dir}"
  mkdir -p "${target_dir}"
  if ! unzip -q "${zip_path}" -d "${target_dir}"; then
    rm -rf "${target_dir}"
    die "Failed to extract ZIP; cleaned up partial directory."
  fi

  # The ZIP unpacks to a top-level dir named "pipelinek-${version}". Flatten it.
  local inner_dir="${target_dir}/pipelinek-${version}"
  if [ -d "${inner_dir}" ]; then
    shopt -s dotglob nullglob
    local f
    for f in "${inner_dir}"/*; do
      mv "${f}" "${target_dir}/"
    done
    rmdir "${inner_dir}"
    shopt -u dotglob nullglob
  fi

  # Sanity: the active binary must report its version
  local binary="${target_dir}/bin/pipelinek"
  if [ ! -x "${binary}" ]; then
    rm -rf "${target_dir}"
    die "Expected binary ${binary} not found in extracted archive; cleaned up."
  fi

  local reported_version
  if ! reported_version="$("${binary}" version 2>&1 | head -n 1)"; then
    rm -rf "${target_dir}"
    die "Failed to execute '${binary} version'; cleaned up."
  fi
  log_info "Installed binary reports: ${reported_version}"

  if ! printf '%s' "${reported_version}" | grep -q "${version}"; then
    log_warn "Reported version does not contain '${version}'. The ZIP may be mislabeled."
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
    die "Active binary not found: ${binary}"
  fi

  exec "${binary}" doctor
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
  PIPELINEK_SHA256_<VERSION>  Override expected SHA-256 (no fetch needed)

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
