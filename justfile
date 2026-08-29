# justfile — ML-R8 L7 smoke E2E sandbox interface
# D2: 6 recipes in [group('sandbox')] + [group('test')]
# Env from devbox.json env block + script exports; NO set dotenv-load

set dotenv-load := false

# ─── sandbox group ───────────────────────────────────────────────

@doctor:
    #!/usr/bin/env bash
    set -Eeuo pipefail
    missing=""
    for cmd in podman just git curl jq java; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            echo "sandbox OK: missing tool: $cmd" >&2
            missing="$missing $cmd"
        fi
    done
    if [[ -n "$missing" ]]; then
        echo "sandbox OK: FAIL — missing:$missing" >&2
        exit 1
    fi
    if [[ ! -f devbox.lock ]]; then
        echo "sandbox OK: missing devbox.lock — run 'just bootstrap' first" >&2
        exit 1
    fi
    echo "sandbox OK"
    exit 0

@bootstrap:
    devbox install

@smoke:
    ./scripts/sandbox/run-smoke.sh

@smoke-clean:
    #!/usr/bin/env bash
    set -Eeuo pipefail
    echo "Cleaning up smoke run artefacts..."
    rm -rf artifacts/smoke/* 2>/dev/null || true
    echo "smoke-clean complete"

# ─── test group ──────────────────────────────────────────────────

@uat:
    ./scripts/sandbox/run-uat.sh

@uat-local-010:
    devbox run -- ./gradlew -p v2 :pipeline-application:test --tests 'UatLocal010*'
