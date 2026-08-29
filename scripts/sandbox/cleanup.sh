#!/usr/bin/env bash
# cleanup.sh — sandbox teardown: scoped kill + scratch dir removal
# D5 / skill §71 DoD item 12: never destructive system-wide

set -Eeuo pipefail

# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

SANDBOX_RUN_ID="${1:-}"

log "cleanup.sh: starting teardown for SANDBOX_RUN_ID=$SANDBOX_RUN_ID"

# Kill child processes of this shell (Gradle daemons, etc.)
# Uses pgrep -P <self> — scoped to this process tree only
# Never: pkill -9 java, kill -9 1, rm -rf /, podman system reset
local self_pid=$$
pgrep -P "$self_pid" 2>/dev/null | xargs -r kill -TERM 2>/dev/null || true

# Remove per-run scratch directory
if [[ -n "$SANDBOX_RUN_ID" && -d "artifacts/smoke/$SANDBOX_RUN_ID" ]]; then
    rm -rf "artifacts/smoke/$SANDBOX_RUN_ID"
    log "Removed artifacts/smoke/$SANDBOX_RUN_ID"
fi

log "cleanup.sh: teardown complete"
