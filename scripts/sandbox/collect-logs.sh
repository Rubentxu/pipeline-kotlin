#!/usr/bin/env bash
# collect-logs.sh — failure log collector for smoke runs
# D6: writes test-results + journal + stdout/stderr BEFORE cleanup
# skill §25: diagnostics retention is the contract

set -Eeuo pipefail

# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

SANDBOX_RUN_ID="${1:-}"

if [[ -z "$SANDBOX_RUN_ID" ]]; then
    die 1 "Usage: collect-logs.sh <SANDBOX_RUN_ID>"
fi

DEST="artifacts/smoke/$SANDBOX_RUN_ID"

log "Collecting logs to $DEST"

# test-results
if [[ -d v2/pipeline-application/build/test-results/test ]]; then
    mkdir -p "$DEST/test-results"
    cp -r v2/pipeline-application/build/test-results/test/* "$DEST/test-results/" 2>/dev/null || true
    log "Copied test-results"
fi

# journal database
if [[ -f v2/pipeline-application/build/tmp/test/databases/journals/*.db ]]; then
    mkdir -p "$DEST/journal"
    cp v2/pipeline-application/build/tmp/test/databases/journals/*.db "$DEST/journal/" 2>/dev/null || true
    log "Copied journal db"
fi

# Also handle journal.db in control roots from test runs
find . -name "journal.db" -newer /dev/null 2>/dev/null | while read -r jdb; do
    mkdir -p "$DEST/journal"
    cp "$jdb" "$DEST/journal/" 2>/dev/null || true
    log "Copied journal: $jdb"
done

# Collect jenkins-log.txt if present (from pipeline runs)
find . -name "jenkins-log.txt" 2>/dev/null | while read -r jlog; do
    mkdir -p "$DEST/logs"
    cp "$jlog" "$DEST/logs/" 2>/dev/null || true
done

log "Log collection complete: $DEST"
