#!/usr/bin/env bash
# verify-all.sh — THE single local verification entrypoint (AGENTS.md section 5).
#
# It composes, in one process and on purpose no other:
#
#   1. the development distribution, built ONCE with installDist;
#   2. static analysis (detekt) and the public-API compatibility check;
#   3. the permanent unit tests of every module that has any;
#   4. the tracked E2E scenarios in v2/examples/, run by examples/run.sh;
#   5. the LOC budget gate (scripts/check-budgets.py);
#   6. the wall-clock gate.
#
# It is not a test framework and it has no registry, no service, no database
# and no tests-of-tests. It runs named commands in a fixed order, and it stops
# at the first failing stage so a red run names the stage that is red.
#
#   scripts/verify-all.sh              # the whole gate
#   scripts/verify-all.sh --no-budget  # skip only the LOC gate, for a quick loop
#
# Exit 0 when every stage passed, 1 otherwise. The wall-clock gate prints its
# own verdict even on failure; callers who want a hard ceiling should wrap the
# whole command, as AGENTS.md section 5 describes:
#
#   timeout 900 scripts/verify-all.sh
#
# One environment note that is not optional. The repository declares
# jvmToolchain(21), and Kotlin's embedded build-script compiler cannot parse
# the Java version of a newer JDK: on Java 25 every Gradle invocation dies with
# "IllegalArgumentException: 25.0.4.1" raised from JavaVersion.parse inside
# KotlinScriptCompilationAndInstrumentation.compile, with a misleading
# "What went wrong: 25.0.4.1" headline. This script therefore resolves a JDK 21
# and refuses to run on anything else, rather than failing later with a message
# that does not name its cause.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

BUDGET_SECONDS=900
REQUIRED_JAVA_FEATURE=21

skip_budget=0
for arg in "$@"; do
    case "$arg" in
        --no-budget) skip_budget=1 ;;
        -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
        *) echo "verify-all: unknown argument '$arg'" >&2; exit 2 ;;
    esac
done

started_at=$(date +%s)
stage_started_at=$started_at

log()  { printf '\n=== %s\n' "$*"; }
fail() { printf 'verify-all: FAILED at %s\n' "$*" >&2; exit 1; }

elapsed() { printf '%s' "$(( $(date +%s) - $1 ))"; }

# --- JDK resolution ----------------------------------------------------------
# Prefer an explicit JAVA_HOME that already satisfies the requirement; then the
# asdf installations this repository has used; then whatever is on PATH.

java_feature() {
    # "21.0.8" -> 21
    local raw
    raw="$("$1/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')" || return 1
    [[ "$raw" =~ ^[0-9]+$ ]] || return 1
    printf '%s' "$raw"
}

resolve_java_home() {
    local candidates=()
    [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
    local d
    for d in "$HOME"/.asdf/installs/java/*/; do
        [[ -x "$d/bin/java" ]] && candidates+=("${d%/}")
    done
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        local mac
        mac=$(/usr/libexec/java_home -v "$REQUIRED_JAVA_FEATURE" 2>/dev/null || true)
        [[ -n "$mac" ]] && candidates+=("$mac")
    fi
    local c
    for c in "${candidates[@]:-}"; do
        [[ -x "$c/bin/java" ]] || continue
        if [[ "$(java_feature "$c" || true)" == "$REQUIRED_JAVA_FEATURE" ]]; then
            printf '%s' "$c"; return 0
        fi
    done
    return 1
}

log "resolving a JDK ${REQUIRED_JAVA_FEATURE} (jvmToolchain(${REQUIRED_JAVA_FEATURE}))"
if ! java_home="$(resolve_java_home)"; then
    echo "verify-all: no JDK ${REQUIRED_JAVA_FEATURE} found." >&2
    echo "  v2/build.gradle.kts declares jvmToolchain(${REQUIRED_JAVA_FEATURE}), and" >&2
    echo "  Kotlin's build-script compiler cannot parse a newer JDK, so running" >&2
    echo "  on a later one fails with an unrelated-looking 'IllegalArgumentException:" >&2
    echo "  <version>'. Install a JDK ${REQUIRED_JAVA_FEATURE} or point JAVA_HOME at one." >&2
    fail "JDK resolution"
fi
export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"
echo "    JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | sed 's/^/    /'

# --- Stage 1: compile, static analysis, API compatibility, distribution -------
# One Gradle invocation, so the distribution is assembled exactly once and the
# tests below reuse the same compiled classes rather than recompiling.

log "stage 1/5  compile + detekt + apiCheck + installDist"
stage_started_at=$(date +%s)
./v2/gradlew --no-daemon -p v2 \
    detektMain \
    apiCheck \
    :pipeline-application:installDist \
    > "${TMPDIR:-/tmp}/verify-all-stage1.log" 2>&1 \
    || { tail -40 "${TMPDIR:-/tmp}/verify-all-stage1.log" >&2; fail "stage 1 (see ${TMPDIR:-/tmp}/verify-all-stage1.log)"; }
echo "    ok in $(elapsed "$stage_started_at")s"

# --- Stage 2: permanent unit tests ------------------------------------------
# Every module runs its own :test. A module with no test source has nothing to
# prove, and Gradle reports a Test task over an empty source set as GREEN, so
# the modules that are actually asked are enumerated from the filesystem
# instead of assumed: a module is asked only if src/test holds a .kt file.

log "stage 2/5  permanent unit tests"
stage_started_at=$(date +%s)

# Gate first, then run. AGENTS.md section 2 allows Kotlin unit tests and
# forbids the rest: "0 tests Kotlin UAT/E2E/harness interno". A bespoke lane
# named anything other than `src/test` is exactly that rest, and an earlier
# census looked only for `src/test`, so a seven-file `src/pluginContractTest`
# source set sat in the tree for a whole cycle with no gate noticing and no
# task compiling it. Enumerating only the permitted name would repeat the
# blindness; so the forbidden names are searched for by name instead.
stray_lanes=()
while IFS= read -r stray; do
    stray_lanes+=("$stray")
done < <(find v2 -path '*/src/*' -name '*.kt' -not -path '*/build/*' \
             -not -path 'v2/buildSrc/*' -not -path 'v2/examples/*' \
             -not -path '*/src/main/*' -not -path '*/src/test/*' \
         | LC_ALL=C sort)
if [[ ${#stray_lanes[@]} -gt 0 ]]; then
    printf '    a Kotlin source set exists outside src/test, which AGENTS.md section 2\n' >&2
    printf '    forbids: it is either dead code or a lane no gate enumerates.\n' >&2
    printf '  %s\n' "${stray_lanes[@]}" >&2
    fail "stage 2: forbidden Kotlin test source set"
fi

modules_with_tests=()
while IFS= read -r kt; do
    mod="${kt%%/src/test/*}"
    modules_with_tests+=("$mod")
done < <(find v2 -path '*/src/test/*' -name '*.kt' -not -path '*/build/*' | LC_ALL=C sort | awk -F'/src/test/' '!seen[$1]++')
if [[ ${#modules_with_tests[@]} -eq 0 ]]; then
    echo "    no module has a unit test yet; nothing to run (NOT_RUN, not PASS)"
else
    tasks=()
    # `find v2 ...` yields paths relative to the repository root, so a module
    # reads `v2/pipeline-domain`. Gradle is invoked with `-p v2`, which makes
    # `v2` the Gradle ROOT rather than a project segment, so its project path is
    # `:pipeline-domain` -- not `:v2/pipeline-domain`. Prefixing the find output
    # verbatim made every task path unresolvable, and the stage failed with
    # "Cannot locate tasks that match ':v2/pipeline-domain:test'" before running
    # a single test. The unit tests had therefore never executed through this gate.
    #
    # A NESTED module needs the other half of the translation: settings.gradle.kts
    # declares `:pipeline-step-sdk:runtime`, with a colon, while the directory is
    # `pipeline-step-sdk/runtime`, with a slash. Stripping only the `v2/` prefix
    # therefore resolved the flat modules and failed on the first nested one with
    # "Cannot locate tasks that match ':pipeline-step-sdk/runtime:test' as project
    # 'pipeline-step-sdk/runtime' not found". Gradle aborted the whole stage, so
    # the flat modules ran and the nested ones never did -- and the census that
    # follows could not have reported it, because the stage had already exited.
    for m in "${modules_with_tests[@]}"; do
        gradle_path="${m#v2/}"
        gradle_path="${gradle_path//\//:}"
        tasks+=(":${gradle_path}:test")
    done
    printf '    %s\n' "${modules_with_tests[@]}" | sed 's/^/    module: /'
    ./v2/gradlew --no-daemon -p v2 "${tasks[@]}" \
        > "${TMPDIR:-/tmp}/verify-all-stage2.log" 2>&1 \
        || { tail -40 "${TMPDIR:-/tmp}/verify-all-stage2.log" >&2; fail "stage 2 (see ${TMPDIR:-/tmp}/verify-all-stage2.log)"; }
    # Gradle's exit status is not the result. A Test task can report success
    # with its classes skipped or absent, so the executed/failed/skipped counts
    # are read from the JUnit XML that the task wrote.
    #
    # The census is scoped to the modules just asked for, deliberately. A global
    # `find v2 -path '*/build/test-results/*'` also collects the XML that the
    # suites deleted by the RESET left behind: it reported "283 reports,
    # executed=1880" for a run that asked one module and executed 56 tests in
    # 13 reports, the other 230 reports coming from code that no longer exists.
    # A count whose set cannot be attributed to a requested task is not a
    # measurement, so the set is taken from the same list the tasks came from.
    xml_total=0; xml_fail=0; xml_skip=0; xml_files=0
    silent_modules=()
    for m in "${modules_with_tests[@]}"; do
        mod_reports=0
        while IFS= read -r xml; do
            [[ -n "$xml" ]] || continue
            mod_reports=$((mod_reports + 1))
            xml_files=$((xml_files + 1))
            line=$(head -3 "$xml" | tr '\n' ' ')
            t=$(sed -nE 's/.*tests="([0-9]+)".*/\1/p' <<<"$line"); t=${t:-0}
            f=$(sed -nE 's/.*failures="([0-9]+)".*/\1/p' <<<"$line"); f=${f:-0}
            e=$(sed -nE 's/.*errors="([0-9]+)".*/\1/p' <<<"$line"); e=${e:-0}
            s=$(sed -nE 's/.*skipped="([0-9]+)".*/\1/p' <<<"$line"); s=${s:-0}
            xml_total=$((xml_total + t)); xml_fail=$((xml_fail + f + e)); xml_skip=$((xml_skip + s))
        done < <(find "$m/build/test-results" -name 'TEST-*.xml' 2>/dev/null | LC_ALL=C sort)
        # A module with a test source but no discovered test is the same silent
        # green Gradle 8 reports for an empty source set, one level deeper.
        if [[ "$mod_reports" -eq 0 ]]; then
            silent_modules+=("$m")
        fi
    done
    echo "    JUnit XML: $xml_files report(s), executed=$xml_total failed=$xml_fail skipped=$xml_skip"
    [[ "$xml_fail" -eq 0 ]] || fail "stage 2: $xml_fail failing test(s) in the JUnit XML"
    if [[ "$xml_files" -eq 0 ]]; then
        echo "verify-all: no JUnit XML was produced, so the test result is UNKNOWN." >&2
        fail "stage 2: green with no evidence"
    fi
    if [[ ${#silent_modules[@]} -gt 0 ]]; then
        echo "verify-all: these modules have a test source but produced no JUnit XML," >&2
        echo "so their test task discovered nothing while reporting success:" >&2
        printf '  %s\n' "${silent_modules[@]}" >&2
        fail "stage 2: green with no discovered test"
    fi
fi
echo "    ok in $(elapsed "$stage_started_at")s"

# --- Stage 3: E2E scenarios --------------------------------------------------

log "stage 3/5  E2E scenarios in v2/examples/"
stage_started_at=$(date +%s)
./v2/examples/run.sh > "${TMPDIR:-/tmp}/verify-all-stage3.log" 2>&1 \
    || { tail -40 "${TMPDIR:-/tmp}/verify-all-stage3.log" >&2; fail "stage 3 (see ${TMPDIR:-/tmp}/verify-all-stage3.log)"; }
grep -E '^[0-9]+ scenario' "${TMPDIR:-/tmp}/verify-all-stage3.log" | sed 's/^/    /'
echo "    ok in $(elapsed "$stage_started_at")s"

# --- Stage 4: LOC budget -----------------------------------------------------

if [[ "$skip_budget" == 1 ]]; then
    log "stage 4/5  LOC budget SKIPPED by --no-budget"
else
    log "stage 4/5  LOC budget"
    stage_started_at=$(date +%s)
    python3 scripts/check-budgets.py || fail "stage 4: LOC budget"
    echo "    ok in $(elapsed "$stage_started_at")s"
fi

# --- Stage 5: wall-clock -----------------------------------------------------

total=$(elapsed "$started_at")
log "stage 5/5  wall clock"
echo "    elapsed ${total}s against a budget of ${BUDGET_SECONDS}s"
if [[ "$total" -gt "$BUDGET_SECONDS" ]]; then
    echo "verify-all: BLOCKED_BUDGET. ${total}s > ${BUDGET_SECONDS}s." >&2
    echo "  Per AGENTS.md section 4 this is not something to raise or split:" >&2
    echo "  shrink the scenario set, drop duplicate verification, or cut the" >&2
    echo "  slowest stage, then rerun the whole gate." >&2
    fail "stage 5: wall clock ${total}s"
fi

printf '\nPASS  sha=%s  elapsed=%ss  budget=%ss\n' \
    "$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)" "$total" "$BUDGET_SECONDS"