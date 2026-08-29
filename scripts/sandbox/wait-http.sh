#!/usr/bin/env bash
# wait-http.sh — no-op stub for readiness probe pattern parity
# D5 / skill pattern parity: reserved for L7.2 Podman overlay
# DROP-or-FILL decision re-opens at L7.2 (design §Open Questions)

set -Eeuo pipefail

# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

log "wait-http.sh: no-op stub — not used in L7 first cut"
exit 0
