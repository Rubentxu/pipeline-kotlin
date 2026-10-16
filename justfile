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
# Same UAT frontier as `just gate`: the local app gate excludes the UAT battery.
@gate-app budget="1500":
    timeout {{budget}} v2/gradlew -p v2 :pipeline-application:test -Pshard.excludes='{{uat_excludes}}'

# Legacy behaviour: application-module gate INCLUDING the UAT battery
@gate-app-full budget="1500":
    timeout {{budget}} v2/gradlew -p v2 :pipeline-application:test

# Round gate: incremental by default (1s when nothing changed since last green)
# UAT frontier (AGENTS.md — 'Frontera de responsabilidad'): the UAT battery belongs to
# the external pipelinek-release-harness; the LOCAL round gate verifies internal
# contracts and regressions and EXCLUDES the UAT classes. Tests are not deleted or
# skipped — they stay runnable via `just gate-full`.
uat_excludes := '*UatLocal*,*UatCompat*,*UatDsl*,*UatStep*,*UatEvt*,*UatDurable*,*Lpr011*,*PublishHtml*,*StashOps*'

# Round gate. Concurrency guard (B3.2): refuses while another gate runs on this
# host. Deliberate overlap: `just gate force=1` (recorded bypass).
@gate budget="1270" force="":
    #!/usr/bin/env bash
    set -Eeuo pipefail
    guard=()
    if [[ -n "{{force}}" ]]; then guard+=(--force); fi
    python3 scripts/gate-resource-guard.py --gate L-ROUND "${guard[@]}"
    timeout {{budget}} v2/gradlew -p v2 check -Pshard.excludes='{{uat_excludes}}'

# Legacy behaviour: round gate INCLUDING the UAT battery (belongs to the harness)
@gate-full budget="1270":
    timeout {{budget}} v2/gradlew -p v2 check

# Escalation only: after a killed run or suspected stale green (rule 2)
@gate-escalate budget="1270" force="":
    #!/usr/bin/env bash
    set -Eeuo pipefail
    guard=()
    if [[ -n "{{force}}" ]]; then guard+=(--force); fi
    python3 scripts/gate-resource-guard.py --gate L-ROUND-ESCALATED "${guard[@]}"
    timeout {{budget}} v2/gradlew -p v2 check --rerun-tasks -Pshard.excludes='{{uat_excludes}}'

# Escalation with the UAT battery included
@gate-escalate-full budget="1270":
    timeout {{budget}} v2/gradlew -p v2 check --rerun-tasks

# L-RC: the certification gate. releaseGate = distZip + SBOM + candidateAdmission +
# architecture/application/release suites, with the UAT battery excluded like the
# other local gates. Budget is DECLARED, not measured: releaseGate has never
# completed on this base (PK-58A F6), so re-derive it from the first complete run.
# The bytes it certifies are the bytes handed to the external harness.
@rc budget="2400" force="":
    #!/usr/bin/env bash
    set -Eeuo pipefail
    guard=()
    if [[ -n "{{force}}" ]]; then guard+=(--force); fi
    python3 scripts/gate-resource-guard.py --gate L-RC "${guard[@]}"
    timeout {{budget}} v2/gradlew -p v2 :pipeline-release:releaseGate -Pshard.excludes='{{uat_excludes}}'

# Single corpus fixture by number 1..13, e.g.: just corpus 11
@corpus n:
    #!/usr/bin/env bash
    set -Eeuo pipefail
    nn=$(printf '%02d' "{{n}}")
    timeout 600 v2/gradlew -p v2 :pipeline-application:test --tests "CompatibilityCorpusTest.fixture${nn}*"

# Change-driven selection: which tests does my diff touch? (rule 17)
# Mapping lives in scripts/plan-affected-tests.py (single source of truth).
@changed base="HEAD":
    python3 scripts/plan-affected-tests.py --base {{base}}

# B2 devGate: run the affected tests for this diff in ONE Gradle invocation
# (incremental, UAT battery excluded, no --rerun-tasks). Build-system diffs plan
# the full round gate; a tool without a suite is UNCLASSIFIED and fails closed.
@dev base="HEAD" budget="900":
    #!/usr/bin/env bash
    set -Eeuo pipefail
    plan_out=$(python3 scripts/plan-affected-tests.py --base {{base}} --format json) || exit $?
    py=$(python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)["python_suites"]))' <<<"$plan_out")
    g=$(python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)["gradle_tasks"]))' <<<"$plan_out")
    echo "dev plan: gradle=[${g}] python=[${py}]"
    if [[ -n "$py" ]]; then
        for suite in $py; do
            echo "dev> python3 $suite"
            timeout {{budget}} python3 "$suite"
        done
    fi
    if [[ -n "$g" ]]; then
        echo "dev> v2/gradlew -p v2 $g -Pshard.excludes='<uat>'"
        timeout {{budget}} v2/gradlew -p v2 $g -Pshard.excludes='{{uat_excludes}}'
    fi
    if [[ -z "$py" && -z "$g" ]]; then
        echo "dev: no test plan for this diff"
    fi

# ─── harness governance (B1) ─────────────────────────────────────

# Bidirectional tool <-> suite registry for scripts/ (OPEN_GAP reported) + the
# generated catalogue must be fresh (module impact, gate budgets, UAT frontier)
@fitness:
    python3 scripts/fitness-harness-coverage.py
    python3 scripts/gen-test-harness-catalogue.py --check

# Same registry, but a declared OPEN_GAP fails too (use when closing gaps)
@fitness-strict:
    python3 scripts/fitness-harness-coverage.py --strict
    python3 scripts/gen-test-harness-catalogue.py --check

# Regenerate the test-harness catalogue (v2/test-harness/: modules, gates, roles)
@catalogue:
    python3 scripts/gen-test-harness-catalogue.py

# Verify the committed catalogue matches the tree (this is the failing half of `just fitness`)
@catalogue-check:
    python3 scripts/gen-test-harness-catalogue.py --check

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

# B3 integrationGate: fitness + affected python suites + ONE Gradle graph (UAT
# battery excluded, incremental) + typed receipt PASS|FAIL|BLOCKED|STALE|NOT_RUN.
# Add --out <path> to persist the receipt JSON.
# L-MERGE: one Gradle graph. The integration tool holds the graph (it applies the
# same concurrency guard for its Gradle phase, so this recipe does not duplicate it).
@integration base="HEAD" budget="1800":
    python3 scripts/run-integration-gate.py --base {{base}} --budget {{budget}}

# B4 candidate receipt: bind a built candidate ZIP to its SHA, hashes and identity.
# Example: just candidate-receipt v2/pipeline-application/build/distributions/pipelinek-0.61.0.zip
@candidate-receipt zip manifest="v2/pipeline-release/build/candidate/distribution-manifest.json" handoff="v2/pipeline-release/build/candidate/candidate-handoff.json":
    python3 scripts/emit-candidate-receipt.py --zip {{zip}} --manifest {{manifest}} --handoff {{handoff}}
