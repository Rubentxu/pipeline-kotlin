#!/usr/bin/env bash
# build-oci-image.sh — build the OCI profile of the local service.
#
# WHAT THIS SCRIPT IS
#
#   A PACKAGING step. It takes the distribution ZIP that the release
#   publishes, unpacks it the way the installer unpacks it, and asks a
#   container runtime to build the image the profile declares.
#
#   It does NOT run the product. The image holds the distribution and the
#   profile's own configuration; building it starts no service, and a build
#   that started the product would be a layer that boots the product, which
#   is the one thing this repository does not hold.
#
# Usage:
#   build-oci-image.sh [distribution.zip] [tag]
#
#   distribution.zip  the ZIP to package. It defaults to the distribution the
#                     build of this tree produced, and it is the ZIP and not
#                     the installed directory, so the image holds the same
#                     bytes an operator downloads.
#   tag               the tag of the image. It defaults to
#                     localhost/pipelinek:<version>, with the version read
#                     from the NAME of the ZIP.
#
# Pre-conditions:
#   - bash 4+
#   - unzip, python3 and a container runtime (podman) in PATH
#   - the runtime works rootless: the profile runs as a non-root uid and
#     every volume it writes to belongs to that uid
#
# The runtime is NOT a dependency of this repository's build: nothing in the
# normal build calls this script, and the profile is optional by design.

set -Eeuo pipefail

readonly SCRIPT_NAME="${BASH_SOURCE[0]##*/}"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly CONTAINERFILE="${REPO_ROOT}/pipelinek/pipeline-application/oci/Containerfile"
readonly ENTRYPOINT="${REPO_ROOT}/pipelinek/pipeline-application/oci/entrypoint.sh"
readonly DISTRIBUTIONS="${REPO_ROOT}/pipelinek/pipeline-application/build/distributions"

# The runtime that builds the image. Podman is named because it is the one
# this profile was measured with, and the profile is a Containerfile, so any
# runtime that reads one can build it.
readonly RUNTIME="${PIPELINEK_OCI_RUNTIME:-podman}"

# The directory the build context lives in. It is a global because the trap
# that removes it outlives the function that created it.
CONTEXT=""

usage() {
    cat <<USAGE
${SCRIPT_NAME} [distribution.zip] [tag]

  distribution.zip  the ZIP to package (default: the distribution of this tree)
  tag               the tag of the image (default: localhost/pipelinek:<version>)
USAGE
}

die() {
    printf '%s: %s\n' "${SCRIPT_NAME}" "$1" >&2
    exit "${2:-1}"
}

# The distribution of this tree, when the caller named none.
#
# The SDK bundle is NOT a distribution: it carries the published coordinates
# and not the launcher, and an image of it would hold no service.
default_distribution() {
    local candidate
    candidate="$(find "${DISTRIBUTIONS}" -maxdepth 1 -type f -name 'pipelinek-*.zip' \
        ! -name '*.sdk.zip' 2>/dev/null | sort | tail -n 1 || true)"
    [ -n "${candidate}" ] || die "there is no distribution to package: build it with './gradlew :pipeline-application:distZip'"
    printf '%s\n' "${candidate}"
}

# The version the ZIP was built as, read from its name.
version_of() {
    local name
    name="$(basename "$1")"
    name="${name#pipelinek-}"
    name="${name%.zip}"
    [ -n "${name}" ] || die "the name of the distribution does not carry a version: $1"
    printf '%s\n' "${name}"
}

main() {
    case "${1:-}" in
        -h|--help)
            usage
            return 0
            ;;
    esac

    local distribution="${1:-$(default_distribution)}"
    local tag="${2:-localhost/pipelinek:$(version_of "${distribution}")}"

    [ -f "${distribution}" ] || die "the distribution is not there: ${distribution}"
    [ -f "${CONTAINERFILE}" ] || die "the profile is not there: ${CONTAINERFILE}"
    [ -f "${ENTRYPOINT}" ] || die "the profile has no entrypoint: ${ENTRYPOINT}"
    command -v "${RUNTIME}" >/dev/null 2>&1 || die "there is no container runtime to build the image: ${RUNTIME}"

    # The context is a global and not a local of this function: the trap that
    # removes it runs when the frame of this function is gone, and a local
    # would be gone with it.
    CONTEXT="$(mktemp -d)"
    trap 'rm -rf "${CONTEXT:-}"' EXIT

    # The distribution is unpacked the way the installer unpacks it: the
    # archive holds a directory named after the version, and the image wants
    # the CONTENT of that directory under /opt/pipelinek.
    local unpacked="${CONTEXT}/unpacked"
    mkdir -p "${unpacked}"
    unzip -q "${distribution}" -d "${unpacked}"
    local root
    root="$(find "${unpacked}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    [ -n "${root}" ] || die "the distribution holds no directory of its own: ${distribution}"
    mv "${root}" "${CONTEXT}/distribution"
    rm -rf "${unpacked}"

    # The launcher is the image's entrypoint, and an image without it would
    # start nothing. The check happens HERE, before the runtime is asked to
    # build, so the failure names the distribution and not a build step.
    [ -f "${CONTEXT}/distribution/bin/pipelinek" ] \
        || die "the distribution carries no launcher: ${distribution}"

    cp "${CONTAINERFILE}" "${CONTEXT}/Containerfile"
    cp "${ENTRYPOINT}" "${CONTEXT}/entrypoint.sh"

    printf '%s: building %s from %s\n' "${SCRIPT_NAME}" "${tag}" "${distribution}"
    "${RUNTIME}" build --tag "${tag}" "${CONTEXT}"

    printf '\n%s: built %s\n' "${SCRIPT_NAME}" "${tag}"
    cat <<RECIPE
What has to be there before the service starts, in this order:
  1. the three NAMED volumes, which are the three directories of the image:
     podman volume create pipelinek-socket
     podman volume create pipelinek-control
     podman volume create pipelinek-state
  2. the container, which PREPARES the substrate before it serves it, so
     there is no step to take by hand:
     podman run -d --name pipelinek-service \\
       -v pipelinek-socket:/var/lib/pipelinek/socket \\
       -v pipelinek-control:/var/lib/pipelinek/control \\
       -v pipelinek-state:/var/lib/pipelinek/state \\
       ${tag}
A client of the same environment runs as the same uid and reads the token
beside the socket:
  podman run --rm \\
    -v pipelinek-socket:/var/lib/pipelinek/socket \\
    ${tag} inspect --state --run <run-id> \\
    --endpoint /var/lib/pipelinek/socket/pipelinek.sock
Outside a container the substrate is prepared by hand, with the same verb:
  pipelinek substrate init --db /var/lib/pipelinek/state/store.db
RECIPE
}

main "$@"
