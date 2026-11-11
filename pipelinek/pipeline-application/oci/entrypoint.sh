#!/bin/sh
# The entrypoint of the OCI profile: it prepares the substrate and then
# serves it.
#
# WHY THE ENTRYPOINT PREPARES THE SUBSTRATE
#
# The service SERVES a substrate and never creates one: it reads the store it
# is given and refuses to start when the store is not there. That is the right
# shape for a service — it must not invent the facts it answers about — and it
# leaves a step to take before the first start. In a container that step is
# this script, and it is taken BEFORE the service is asked to serve.
#
# WHY ONLY THE `serve` FORM PREPARES
#
# Because a preparation is a write, and a form that only reads must not write:
# `inspect` and `observe` open a substrate they were given, and a container
# that prepared one on their behalf would create a store nobody named. The
# `run` form has a lane of its own, and that lane creates the store it writes
# into.
#
# WHY THE PREPARATION IS SAFE TO REPEAT
#
# Because the container starts again on every restart, and because a
# preparation changes nothing that is already there: the second start finds the
# substrate and says that it found it.
#
# WHY THE PATH IS READ FROM THE ARGUMENTS
#
# Because the container is started with the same arguments an installation is
# started with, and the substrate the service serves is the one those
# arguments name. A path written here would be a second authority about where
# the state lives.

set -eu

# The launcher of the distribution, which is NEXT TO this script: the image
# unpacks the distribution in /opt/pipelinek and puts this script beside it.
#
# The directory is resolved to an ABSOLUTE one, because the container may
# start this script from any directory it likes, and a launcher named by a
# relative path is a launcher that depends on where the process happens to be.
LAUNCHER_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
LAUNCHER="${LAUNCHER_DIR}/bin/pipelinek"

# The path of the substrate, read from the arguments of the form.
#
# Both spellings are read: `--db <path>` and `--db=<path>`. An argument
# written in one spelling and not the other is the same request, and a
# preparation that understood only one of them would leave a container that
# refuses to start.
db_path() {
    previous=""
    for argument in "$@"; do
        case "${previous}" in
            --db)
                printf '%s\n' "${argument}"
                return 0
                ;;
        esac
        case "${argument}" in
            --db=*)
                printf '%s\n' "${argument#--db=}"
                return 0
                ;;
        esac
        previous="${argument}"
    done
    return 1
}

main() {
    if [ "$#" -eq 0 ]; then
        exec "${LAUNCHER}"
    fi

    form="$1"
    shift

    if [ "${form}" = "serve" ]; then
        if substrate="$(db_path "$@")"; then
            # The result is printed, not swallowed: an operator who starts the
            # container reads what the substrate is and which tables it
            # carries.
            "${LAUNCHER}" substrate init --db "${substrate}" || exit $?
        fi
    fi

    exec "${LAUNCHER}" "${form}" "$@"
}

main "$@"
