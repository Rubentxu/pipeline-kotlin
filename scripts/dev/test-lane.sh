#!/usr/bin/env bash
# BQ2 — Test lane runner. Selects the right Gradle invocation for a lane
# without dragging `installDist` or `buildExamplePlugin` into the fast path.
#
# Usage: scripts/dev/test-lane.sh <lane>
#   lane = fast | integration | candidate | restart | service-sandbox | online-smoke
#
# The fast lane is the only one that does not depend on installDist and
# does not build the example plugin. The integration lane adds the plugin
# build but not the install. The candidate lane depends on installDist.
# Restart / service-sandbox / online-smoke are heavy lanes; they exist as
# lane types but the runner falls back to `:pipeline-application:test`
# because the dedicated tasks are not yet defined (see BQ2 closure
# receipt for the gaps).
#
# This script is the operator-side counterpart to the LanePlanner in
# `v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/lanes/`.

set -euo pipefail

lane=${1:-}
[[ -z "${lane}" ]] && {
    echo "usage: $0 <fast|integration|candidate|restart|service-sandbox|online-smoke>" >&2
    exit 2
}

cd "$(dirname "$0")/../.."
cd v2

case "${lane}" in
    fast)
        # In-process only: no installDist, no plugin build.
        # The lane contains every test class that does not need a real
        # CLI / SDK / JAR. Until per-class @Tag annotations exist, this
        # runs the in-process :test-harness:test suite as the proof that
        # the deciders are stable.
        ./gradlew :test-harness:test
        ;;
    integration)
        # Cross-module, no installDist.
        ./gradlew :test-harness:test :pipeline-domain:test :pipeline-events:test
        ;;
    candidate)
        # Full :pipeline-application:test which depends on installDist.
        # This is the slow path; the UATs that survived migration live here.
        ./gradlew :pipeline-application:test
        ;;
    restart|service-sandbox|online-smoke)
        # Heavy lanes. The dedicated Gradle tasks are not yet wired
        # (BQ2 closure receipt tracks this as deferred). Fall back to the
        # candidate lane for now; the LanePlanner still records which
        # contract belongs here.
        echo "WARN: lane '${lane}' falls back to :pipeline-application:test" >&2
        ./gradlew :pipeline-application:test
        ;;
    *)
        echo "unknown lane: ${lane}" >&2
        exit 2
        ;;
esac
