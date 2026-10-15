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
    devbox run -- v2/gradlew -p v2 :pipeline-application:test --tests 'UatLocal010*'

# ─── test efficiency (AGENTS.md rules 2/4/11/17) ─────────────────

# Inner loop: single test by pattern, fixed 600s
@t pattern:
    timeout 600 v2/gradlew -p v2 :pipeline-application:test --tests '{{pattern}}'

# WU-RP-005: application inner loop, excludes the two most expensive CLI-fork
# classes (UatDsl003ParallelTest 65s, UatLocal010SmokeE2ESandboxTest 34s).
# Those run only in gate-app / round gate. Budget 900s.
@app-fast budget="900":
    timeout {{budget}} v2/gradlew -p v2 :pipeline-application:test \
      --tests 'dev.rubentxu.pipeline.v2.application.*' \
      --tests '!dev.rubentxu.pipeline.v2.application.UatDsl003ParallelTest*' \
      --tests '!dev.rubentxu.pipeline.v2.application.UatLocal010SmokeE2ESandboxTest*'

# WU-RP-005: full application-module gate (L3 round-gate equivalent) before
# pushing WUs that touch pipeline-application. Budget 1500s.
@gate-app budget="1500":
    timeout {{budget}} v2/gradlew -p v2 :pipeline-application:test

# Round gate: incremental by default (1s when nothing changed since last green)
@gate budget="1270":
    timeout {{budget}} v2/gradlew -p v2 check

# Escalation only: after a killed run or suspected stale green (rule 2)
@gate-escalate budget="1270":
    timeout {{budget}} v2/gradlew -p v2 check --rerun-tasks

# Single corpus fixture by number 1..13, e.g.: just corpus 11
@corpus n:
    #!/usr/bin/env bash
    set -Eeuo pipefail
    nn=$(printf '%02d' "{{n}}")
    timeout 600 v2/gradlew -p v2 :pipeline-application:test --tests "CompatibilityCorpusTest.fixture${nn}*"

# Change-driven selection: which tests does my diff touch? (rule 17)
@changed base="HEAD":
    #!/usr/bin/env bash
    set -Eeuo pipefail
    files=$(git diff --name-only "{{base}}")
    if [[ -z "$files" ]]; then
        echo "no diff vs {{base}}"
        exit 0
    fi
    echo "== changed files vs {{base}} =="
    echo "$files"
    echo ""
    echo "== suggested validation (rule 17 ladder) =="
    declare -A tasks=()
    corpus=0; docs_only=1; build_system=0
    while IFS= read -r f; do
        case "$f" in
            v2/compatibility/*) corpus=1; docs_only=0 ;;
            docs/*|*.md) : ;;
            v2/*.gradle.kts|v2/gradle/*|v2/gradlew*) build_system=1; docs_only=0 ;;
            v2/pipeline-application/*) tasks[":pipeline-application:test"]=1; docs_only=0 ;;
            v2/pipeline-events/*) tasks[":pipeline-events:test"]=1; tasks[":pipeline-architecture-tests:test"]=1; docs_only=0 ;;
            v2/pipeline-scripting-api/*) tasks[":pipeline-scripting-api:test"]=1; docs_only=0 ;;
            v2/pipeline-architecture-tests/*) tasks[":pipeline-architecture-tests:test"]=1; docs_only=0 ;;
            v2/pipeline-step-sdk/*/*) tasks[":pipeline-step-sdk:$(echo "$f" | cut -d/ -f3):test"]=1; tasks[":pipeline-application:test"]=1; docs_only=0 ;;
            v2/*) tasks[":$(echo "$f" | cut -d/ -f2):test"]=1; docs_only=0 ;;
        esac
    done <<< "$files"
    if [[ $build_system -eq 1 ]]; then
        echo "timeout 1270 v2/gradlew -p v2 check   # build-system change: full round gate (L5)"
    fi
    for t in "${!tasks[@]}"; do echo "timeout 600 v2/gradlew -p v2 $t"; done
    if [[ $corpus -eq 1 ]]; then
        echo "timeout 600 v2/gradlew -p v2 :pipeline-application:test --tests 'CompatibilityCorpusTest'  # or single fixture: just corpus <n>"
    fi
    if [[ $docs_only -eq 1 ]]; then
        echo "docs-only change: markdown/link validation suffices"
    fi

# ─── harness governance (B1) ─────────────────────────────────────

# Bidirectional tool <-> suite registry for scripts/ (OPEN_GAP reported)
@fitness:
    python3 scripts/fitness-harness-coverage.py

# Same registry, but a declared OPEN_GAP fails too (use when closing gaps)
@fitness-strict:
    python3 scripts/fitness-harness-coverage.py --strict

# Run every hermetic harness suite under scripts/ (recursive) and aggregate (B1 orchestration)
@harness budget="900":
    #!/usr/bin/env bash
    set -Eeuo pipefail
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    fail=0; total=0
    while IFS= read -r t; do
        total=$((total+1))
        printf '%-46s' "$t"
        if timeout {{budget}} python3 "$t" >"$tmp/out" 2>&1; then
            grep -E '^Ran ' "$tmp/out" | tr -d '\n' || true
            echo "  OK"
        else
            echo "  FAIL"
            tail -n 25 "$tmp/out"
            fail=1
        fi
    done < <(find scripts -name 'test_*.py' -not -path '*/__pycache__/*' | sort)
    echo "harness: $total suite(s), fail=$fail"
    exit "$fail"

# Reproducible Gradle baseline sample (B1): wall/Gradle time, task split, XML freshness
@baseline task=":pipeline-domain:test" runs="3":
    python3 scripts/measure-harness-baseline.py --task {{task}} --runs {{runs}} --profile
