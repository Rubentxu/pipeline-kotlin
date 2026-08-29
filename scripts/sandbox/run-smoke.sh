#!/usr/bin/env bash
# run-smoke.sh — smoke E2E entry point for ML-R8 L7
# D5/D6: trap-clean lifecycle, SANDBOX_RUN_ID, timeout 600 wrapper

set -Eeuo pipefail

# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

# Cleanup function called on EXIT / INT / TERM
cleanup() {
    local rc=$?
    local lineno=${1:-?}
    log "cleanup called: rc=$rc lineno=$lineno"
    # Kill children of this process group before exit
    local self_pid=$$
    pgrep -P "$self_pid" 2>/dev/null | xargs -r kill -TERM
    # Remove per-run scratch directory if present
    if [[ -n "${SANDBOX_RUN_ID:-}" ]]; then
        log "Removing scratch dir artifacts/smoke/$SANDBOX_RUN_ID"
        rm -rf "artifacts/smoke/$SANDBOX_RUN_ID"
    fi
    exit "$rc"
}

trap 'cleanup $? $LINENO' EXIT INT TERM

# Unique per-run identifier
export SANDBOX_RUN_ID
SANDBOX_RUN_ID=$(sandbox_run_id)
export V2_SMOKE_E2E_OK=true

log "SANDBOX_RUN_ID=$SANDBOX_RUN_ID"
log "V2_SMOKE_E2E_OK=$V2_SMOKE_E2E_OK"

# Ensure per-run output directory exists
mkdir -p "artifacts/smoke/$SANDBOX_RUN_ID"

# Run the smoke test suite via devbox-pinned JDK 21
timeout 600 devbox run -- ./gradlew -p v2 :pipeline-application:test --tests 'UatLocal010*' || {
    local rc=$?
    log "Smoke tests failed with rc=$rc"
    log "Collecting logs before cleanup..."
    # Collect logs BEFORE cleanup (D6 / skill §25)
    ./scripts/sandbox/collect-logs.sh "$SANDBOX_RUN_ID"
    exit $rc
}

log "Smoke tests passed"
