#!/usr/bin/env bash
# CLI exit contract UAT — verifies the seven exit-code scenarios
# documented in `docs/v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md`
# (correction dated 2026-09-19) against the installed distribution.
#
# Pre-conditions:
#   - bash, unzip, java (JDK 21+) on PATH
#   - the project has been built at least once via
#     `./gradlew :pipeline-application:installDist` (so the installed
#     binary exists at v2/pipeline-application/build/install/pipelinek/)
#
# What this script proves:
#   - run success → exit 0
#   - run failure (fresh DB) → exit 1
#   - run failure (after success, same DB, different script) → exit 1
#   - validate OK → exit 0
#   - validate malformed → exit 2 (per WU-LPR-011 contract)
#   - pipelinek (no args) → exit 1
#   - pipelinek --unknown-flag → exit 1
#
# What this script does NOT prove:
#   - SDKMAN install (separate; WU-LPR-080 WAITING_EXTERNAL)
#   - The historical defect claims in the WU-LPR-080 table (those are
#     preserved untouched in that receipt; see its 2026-09-19 correction)

set -u

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALLED_BIN="$PROJECT_ROOT/v2/pipeline-application/build/install/pipelinek/bin/pipelinek"

WORK="${TMPDIR:-/tmp}/cli-exit-uat-$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

# ---- pre-flight ----
if [[ ! -x "$INSTALLED_BIN" ]]; then
    echo "FATAL: $INSTALLED_BIN not found or not executable."
    echo "Build it first: ./gradlew :pipeline-application:installDist"
    exit 2
fi

mkdir -p "$WORK/gradle"
cp -r "$PROJECT_ROOT/integration/gradle-demo/." "$WORK/gradle/"
[[ -f "$PROJECT_ROOT/.tool-versions" ]] && cp "$PROJECT_ROOT/.tool-versions" "$WORK/gradle/"
cd "$WORK/gradle"

FAIL=0

run_case() {
    local label="$1"
    local expected="$2"
    shift 2
    local actual
    "$@" >/dev/null 2>&1
    actual=$?
    if [[ "$actual" == "$expected" ]]; then
        echo "  ✓ $label: exit $actual (expected $expected)"
    else
        echo "  ✗ $label: exit $actual, expected $expected"
        FAIL=1
    fi
}

echo "=== CLI exit contract UAT (HEAD installDist) ==="
echo "bin: $INSTALLED_BIN"
echo

echo "--- 1. run success ---"
run_case "run success" 0 \
    "$INSTALLED_BIN" run --workspace . --db run.sqlite --control-root ctl pipeline.kts

echo
echo "--- 2. run failure (fresh DB) ---"
cat > "$WORK/gradle/fail.kts" <<'KOTLIN'
pipeline { stages { stage("fail") { sh("false") } } }
KOTLIN
rm -f "$WORK/gradle/run.sqlite"
rm -rf "$WORK/gradle/ctl"
run_case "run failure fresh DB" 1 \
    "$INSTALLED_BIN" run --workspace . --db run.sqlite --control-root ctl fail.kts

echo
echo "--- 3. run failure (after success, same DB, different script) ---"
"$INSTALLED_BIN" run --workspace . --db run.sqlite --control-root ctl pipeline.kts >/dev/null 2>&1
run_case "run failure after success in same DB" 1 \
    "$INSTALLED_BIN" run --workspace . --db run.sqlite --control-root ctl fail.kts

echo
echo "--- 4. validate OK ---"
run_case "validate OK" 0 \
    "$INSTALLED_BIN" validate pipeline.kts

echo
echo "--- 5. validate malformed ---"
cat > "$WORK/gradle/bad.kts" <<'KOTLIN'
pipeline { stages { stage("bad") { steps { echo("x") } } } }
KOTLIN
run_case "validate malformed" 2 \
    "$INSTALLED_BIN" validate bad.kts

echo
echo "--- 6. pipelinek (no args) ---"
run_case "no args" 1 "$INSTALLED_BIN"

echo
echo "--- 7. unknown flag ---"
run_case "unknown flag" 1 "$INSTALLED_BIN" --definitely-not-a-real-flag

echo
if [[ "$FAIL" == "0" ]]; then
    echo "=== CLI EXIT CONTRACT UAT PASS (7/7) ==="
    exit 0
else
    echo "=== CLI EXIT CONTRACT UAT FAIL ==="
    exit 1
fi
