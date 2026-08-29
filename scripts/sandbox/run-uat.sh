#!/usr/bin/env bash
# run-uat.sh — regression runner for UAT-LOCAL-001..005 + 007..009
# D5: trap-clean Bash lifecycle

set -Eeuo pipefail

# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

cleanup() {
    local rc=$?
    local lineno=${1:-?}
    log "cleanup: rc=$rc lineno=$lineno"
    pgrep -P "$$" 2>/dev/null | xargs -r kill -TERM
    exit "$rc"
}

trap 'cleanup $? $LINENO' EXIT INT TERM

export V2_SMOKE_E2E_OK=true

log "Running UAT regression suite: UAT-LOCAL-001..005 + 007..009"

timeout 600 devbox run -- ./gradlew -p v2 :pipeline-application:test \
    --tests 'UatLocal001*' \
    --tests 'UatLocal002*' \
    --tests 'UatLocal003*' \
    --tests 'UatLocal004*' \
    --tests 'UatLocal005*' \
    --tests 'UatLocal007*' \
    --tests 'UatLocal008*' \
    --tests 'UatLocal009*' \
    || {
    local rc=$?
    log "UAT suite failed with rc=$rc"
    exit $rc
}

log "UAT regression suite passed"
