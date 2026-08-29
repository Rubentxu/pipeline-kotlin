#!/usr/bin/env bash
# common.sh — shared helpers for sandbox lifecycle scripts
# ML-R8 L7 smoke E2E sandbox

set -Eeuo pipefail

# log prints a timestamped info message to stderr
log() {
    echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*" >&2
}

# die prints an error and exits with the given code
die() {
    local rc="${1:-1}"
    shift
    log "ERROR: $*"
    exit "$rc"
}

# sandbox_run_id generates a unique per-run identifier
sandbox_run_id() {
    date -u +%Y%m%dT%H%M%S-$$-$RANDOM
}

# require_cmd aborts if a command is not available
require_cmd() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        die 1 "Required command not found: $cmd"
    fi
}
