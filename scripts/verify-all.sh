#!/usr/bin/env bash
# verify-all.sh — THE single local verification entrypoint (AGENTS.md section 5).
# It composes, in one process and on purpose no other:
#   0. a clean of the project's build outputs, so the run measures a real build;
#   1. the development distribution, built ONCE with installDist;
#   2. static analysis (detekt) and the public-API compatibility check;
#   3. the permanent unit tests of every module that has any;
#   4. the tracked E2E scenarios in examples/, run by examples/run.sh;
#   5. the LOC budget gate (scripts/check-budgets.py);
#   6. the wall-clock gate.
# It is not a test framework and it has no registry, no service, no database
# and no tests-of-tests. It runs named commands in a fixed order, and it stops
# at the first failing stage so a red run names the stage that is red.
#   scripts/verify-all.sh              # the whole gate
#   scripts/verify-all.sh --no-budget  # skip only the LOC gate, for a quick loop
# SCOPED RUNS. The gate above is the unit of truth and always the default. For
# the inner loop there are filters, and they are deliberately subordinate: any
# filter switches the run to SUBSET mode, which prints its verdict in a
# different shape and stops comparing the wall clock against AGENTS.md's 900 s.
# That is not pedantry. A filtered run that ends with the same
# `PASS sha=... elapsed=... budget=900s` line as the full gate is a line that
# gets quoted as "the gate passed in N s" while having measured, say, one module
# and three scenarios. A subset is a legitimate and cheap instrument; it is
# never evidence about the whole.
#   scripts/verify-all.sh --list                      # resolve the plan, run nothing
#   scripts/verify-all.sh --stage 1                  # progressive: compile + statics
#   scripts/verify-all.sh --stage up-to-2            # through the unit tests
#   scripts/verify-all.sh --stage 2 --module ':pipeline-domain'
#   scripts/verify-all.sh --stage 2 --test '*ResolveTest' --test '*RejectTest'
#   scripts/verify-all.sh --stage 3 --scenario 'control-flow/*'
# Filters compose; --list never invokes Gradle; a filter that selects nothing is
# an error rather than a green, for the same reason an empty census is not zero.
# Exit 0 when every stage passed, 1 otherwise. The wall-clock gate prints its
# own verdict even on failure; callers who want a hard ceiling should wrap the
# whole command, as AGENTS.md section 5 describes:
#   timeout 900 scripts/verify-all.sh
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
list_only=0
stage_spec=""
module_filter=""
scenario_filter=""
declare -a test_filters=()
subset=0
stage_given=0
module_given=0
scenario_given=0

usage() { sed -n '2,57p' "$0"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        # `--no-budget` is a subset flag as well as a budget skip. It used to set
        # only skip_budget, so the run skipped stage 4 and then fell through to
        # the SAME `PASS sha=… budget=900s` line the full gate prints -- a line
        # naming a LOC budget this run never measured. The verdict belongs to the
        # run that observed everything, so dropping a stage drops the claim with it.
        --no-budget) skip_budget=1; subset=1 ;;
        --list)      list_only=1; subset=1 ;;
        --stage)     stage_spec="${2:-}"; stage_given=1; subset=1; shift ;;
        --module)    module_filter="${2:-}"; module_given=1; subset=1; shift ;;
        --scenario)  scenario_filter="${2:-}"; scenario_given=1; subset=1; shift ;;
        --test)      test_filters+=("${2:-}"); subset=1; shift ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "verify-all: unknown argument '$1'" >&2; exit 2 ;;
    esac
    shift
done

# An option that was GIVEN with an empty value is a mistake, and it is caught
# here rather than where the value is used because after the assignment an
# empty string is indistinguishable from "not given". `--test ''` is worse than
# a typo: it would expand to a bare `--tests` and Gradle would then run every
# class, i.e. a filter that silently runs everything.
if [[ "$stage_given" == 1 && -z "${stage_spec// /}" ]]; then
    echo "verify-all: --stage was given an empty value" >&2; exit 2
fi
if [[ "$module_given" == 1 && -z "${module_filter// /}" ]]; then
    echo "verify-all: --module was given an empty value" >&2; exit 2
fi
if [[ "$scenario_given" == 1 && -z "${scenario_filter// /}" ]]; then
    echo "verify-all: --scenario was given an empty value" >&2; exit 2
fi
for t in ${test_filters+"${test_filters[@]}"}; do
    [[ -n "$t" ]] || { echo "verify-all: --test was given an empty pattern" >&2; exit 2; }
done

# Stage selection. `--stage up-to-N` is the progressive form: everything from 1
# to N. `--stage 1,3` is the explicit form. Both are a SUBSET by definition --
# stage 5 alone, or stage 3 without stage 1, measures nothing about the gate.
want_stage() {
    [[ -z "$stage_spec" ]] && return 0
    local n spec up_to=""
    if [[ "$stage_spec" == up-to-* ]]; then
        up_to="${stage_spec#up-to-}"; spec=""
        [[ "$up_to" =~ ^[0-9]+$ ]] || { echo "verify-all: 'up-to-$up_to' is not a stage number" >&2; exit 2; }
        for n in 1 2 3 4 5; do [[ "$n" -le "$up_to" ]] && spec="${spec:+$spec,}$n"; done
    else
        spec="$stage_spec"
    fi
    [[ ",$spec," == *",$1,"* ]]
}

# Module selection. The pattern is matched against the Gradle project path
# (`:pipeline-step-sdk:runtime`), which is what the operator sees in Gradle's own
# error messages -- not against the directory, which differs from it by a slash.
module_selected() {
    [[ -z "$module_filter" ]] && return 0
    case "$1" in $module_filter) return 0 ;; esac
    return 1
}

started_at=$(date +%s)
stage_started_at=$started_at

log()  { printf '\n=== %s\n' "$*"; }
fail() { printf 'verify-all: FAILED at %s\n' "$*" >&2; exit 1; }

elapsed() { printf '%s' "$(( $(date +%s) - $1 ))"; }

# --- Plan resolution ---------------------------------------------------------
# The plan is derived from the filesystem, not remembered. Stage 2 asks exactly
# the modules that hold a src/test.kt file, stage 3 exactly the scenarios that
# exist, and the filters cut those two lists. Deriving it twice -- once to print
# with --list and once to execute -- is deliberate: a plan that can only be
# printed by the same code that runs it is a claim about itself, not a census.

gradle_path_of() { local p="${1#pipelinek/}"; printf ':%s' "${p//\//:}"; }

plan_modules_all() {
    local kt mod out=()
    while IFS= read -r kt; do
        mod="${kt%%/src/test/*}"
        out+=("$(gradle_path_of "$mod")")
    done < <(find pipelinek -path '*/src/test/*' -name '*.kt' -not -path '*/build/*' \
             | LC_ALL=C sort | awk -F'/src/test/' '!seen[$1]++')
    [[ ${#out[@]} -eq 0 ]] || printf '%s\n' "${out[@]}"
}

plan_modules() {
    local m keep=()
    while IFS= read -r m; do
        [[ -n "$m" ]] || continue
        module_selected "$m" && keep+=("$m")
    done < <(plan_modules_all)
    # `printf '%s\n' a b c` with no operands still prints one empty line, which
    # the caller reads back as one empty path. An empty result must print
    # NOTHING, so the empty case is spelled out rather than left to printf.
    [[ ${#keep[@]} -eq 0 ]] || printf '%s\n' "${keep[@]}"
}

plan_scenarios() {
    local rel keep=()
    while IFS= read -r rel; do
        if [[ -z "$scenario_filter" ]]; then
            keep+=("$rel")
        else
            # shellcheck disable=SC2254 # the pattern is a user-supplied glob on purpose
            case "$rel" in $scenario_filter) keep+=("$rel") ;; esac
        fi
    done < <(find examples -name '*.pipeline.kts' -not -path '*/build/*' \
             | LC_ALL=C sort | sed 's|^examples/||')
    # `printf '%s\n' a b c` with no operands still prints one empty line, which
    # the caller then reads back as one empty path. An empty result must print
    # NOTHING, so the empty case is spelled out rather than left to printf.
    [[ ${#keep[@]} -eq 0 ]] || printf '%s\n' "${keep[@]}"
}

print_plan() {
    local s m sc
    printf 'plan for sha %s\n' "$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    if [[ "$subset" == 0 ]]; then
        printf '  prep      clean build outputs\n'
    fi
    for s in 1 2 3 4 5; do
        want_stage "$s" || continue
        case "$s" in
            1) printf '  stage 1  detektMain detektTest apiCheck :pipeline-runtime:compileKotlin :pipeline-application:installDist\n' ;;
            2)
                printf '  stage 2  unit tests\n'
                planned_modules=0
                while IFS= read -r m; do
                    [[ -n "$m" ]] || continue
                    planned_modules=$((planned_modules + 1))
                    printf '             %s:test%s\n' "$m" \
                        "$([[ ${#test_filters[@]} -gt 0 ]] && printf ' --tests %s' "$(IFS='|'; printf '%s' "${test_filters[*]}")")"
                done < <(plan_modules)
                if [[ "$planned_modules" -eq 0 ]]; then
                    printf 'verify-all: --module %s matched no module with a unit test.\n' "'$module_filter'" >&2
                    printf '  have one:\n' >&2
                    plan_modules_all | sed 's/^/    /' >&2
                    exit 2
                fi
                ;;
            3)
                printf '  stage 3  E2E scenarios\n'
                planned_scenarios=0
                while IFS= read -r sc; do
                    [[ -n "$sc" ]] || continue
                    planned_scenarios=$((planned_scenarios + 1))
                    printf '             %s\n' "$sc"
                done < <(plan_scenarios)
                if [[ "$planned_scenarios" -eq 0 ]]; then
                    printf 'verify-all: --scenario %s matched no scenario.\n' "'$scenario_filter'" >&2
                    exit 2
                fi
                ;;
            4) [[ "$skip_budget" == 1 ]] && printf '  stage 4  SKIPPED (--no-budget)\n' \
                 || printf '  stage 4  LOC budget\n' ;;
            5) printf '  stage 5  wall clock%s\n' "$([[ "$subset" == 1 ]] && printf ' (reported, NOT compared to 900s: subset run)')" ;;
        esac
    done
    [[ "$subset" == 1 ]] && printf 'SUBSET run: these stages are not the gate.\n'
    return 0
}

if [[ "$list_only" == 1 ]]; then
    print_plan
    exit 0
fi

if [[ "$subset" == 1 ]]; then
    printf 'SUBSET RUN -- filters in effect: stage=%s module=%s test=%s scenario=%s\n' \
        "${stage_spec:-all}" "${module_filter:-all}" \
        "$([[ ${#test_filters[@]} -gt 0 ]] && printf '%s ' "${test_filters[@]}" || printf 'all ')" \
        "${scenario_filter:-all}"
    printf 'This is NOT the AGENTS.md section 4 gate and its wall clock is not a gate measurement.\n\n'
fi

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
    echo "  pipelinek/build.gradle.kts declares jvmToolchain(${REQUIRED_JAVA_FEATURE}), and" >&2
    echo "  Kotlin's build-script compiler cannot parse a newer JDK, so running" >&2
    echo "  on a later one fails with an unrelated-looking 'IllegalArgumentException:" >&2
    echo "  <version>'. Install a JDK ${REQUIRED_JAVA_FEATURE} or point JAVA_HOME at one." >&2
    fail "JDK resolution"
fi
export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"
echo "    JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | sed 's/^/    /'

# Each invocation writes its stage output into its own directory, so two gates
# running concurrently on the same machine cannot write into the same file.
# A shared path let one run truncate the log while another was still appending
# to it, which silently destroyed part of that run's evidence.
log_dir="${TMPDIR:-/tmp}/verify-all.$$"
mkdir -p "$log_dir"

# --- Preparation: clean build outputs -----------------------------------------
# The full gate measures a real build, so it starts from one. AGENTS.md section 4
# asks for the measurement to be taken "from clean build OUTPUTS with local
# dependency caches already populated", and the difference is not pedantry: a run
# whose test tasks are all UP-TO-DATE executes no test at all, and a wall clock
# taken from it measures the cache rather than the work. Stage 2 refuses that run
# outright, which is the right refusal -- but a gate that can only be measured
# once is a gate nobody measures twice, so the outputs go first and the run is
# measurable every time.
# The clean is Gradle's own task rather than a hand-rolled deletion. The build
# system knows which directories are its outputs, and a `find -name build` would
# also match a package directory named `build` that holds tracked sources.
# A subset run does not clean: it is the inner loop, it never quotes a wall
# clock, and paying a full rebuild for it would defeat its purpose.
if [[ "$subset" == 0 ]]; then
    log "preparation  clean build outputs"
    stage_started_at=$(date +%s)
    ./pipelinek/gradlew --no-daemon -p pipelinek clean > "$log_dir/clean.log" 2>&1 \
        || { tail -40 "$log_dir/clean.log" >&2; fail "preparation (see "$log_dir/clean.log")"; }
    echo "    ok in $(elapsed "$stage_started_at")s"
else
    log "preparation  SKIPPED (a subset run does not quote a wall clock)"
fi

# The logs are removed only when the run reaches a full pass. Every other
# outcome leaves them in place, because that is when someone needs to read them.
#
# The trap returns the status the run already had. A trap that ends on a failed
# test OVERRIDES the exit code of the script that installed it: with
# `VERIFY_ALL_VERDICT` unset, the `[[ ]]` is false, the function returns 1, and
# every SUBSET run -- and every INCOMPLETE verdict, whose comment says its exit
# status stays 0 -- left with status 1 while the verdict line said PASS. The
# documented inner loop (`--stage 2 --module ... --test ...`) was therefore
# always a failure to the caller, and the line it printed and the code it
# returned said different things.
cleanup_logs() {
    local status=$?
    if [[ "${VERIFY_ALL_VERDICT:-FAIL}" == PASS* ]]; then
        rm -rf "$log_dir"
    fi
    return "$status"
}
trap cleanup_logs EXIT

# --- Stage 1: compile, static analysis, API compatibility, distribution -------
# One Gradle invocation, so the distribution is assembled exactly once and the
# tests below reuse the same compiled classes rather than recompiling.
# `detektTest` sits beside `detektMain` because they are the same analysis over
# two source sets, and only one of them used to be in this list. Every module
# declares both; nothing ran the second. Thirteen test files across seven
# modules were missing a trailing newline for as long as that held, and no
# gate was in a position to say so -- the analysis was green in the only sense
# that a task nobody invokes is green. It fans out to 26 subprojects and costs
# 3s, so running it adds no new gate: it runs one that was already declared.
#
# `:pipeline-runtime:compileKotlin` is there because the analysis above does not
# compile: detekt parses the sources, and a module whose only defect is that it
# no longer matches the contract it implements parses perfectly.
#
# It is NOT the only thing that compiles it. `:pipeline-application:installDist`
# already builds `pipeline-runtime-<version>.jar` for the distribution's `lib/`
# — measured in the installed tree, not inferred — so the module is compiled
# transitively either way. What the explicit task buys is that the gate NAMES
# it: the dependency that puts the module in the distribution is invisible in a
# stage plan, and the day it is dropped the module would fall out of the
# compiled set without anything turning red. Asking for it by name keeps it
# inside the gate whatever the dependency graph does, and it costs nothing: the
# same compilation was already going to happen.
#
# A word that is NOT true of this module: it does not "not travel in the
# distribution". It travels, inside the application's `lib/`. What it is not is
# a PUBLISHED SDK artifact — an external consumer compiles against the ports in
# `:pipeline-runtime-api`, never against these adapters.

if want_stage 1; then
log "stage 1/5  compile + detekt + detektTest + apiCheck + installDist"
stage_started_at=$(date +%s)
./pipelinek/gradlew --no-daemon -p pipelinek \
    detektMain \
    detektTest \
    apiCheck \
    :pipeline-runtime:compileKotlin \
    :pipeline-application:installDist \
    > "$log_dir/stage1.log" 2>&1 \
    || { tail -40 "$log_dir/stage1.log" >&2; fail "stage 1 (see "$log_dir/stage1.log")"; }
echo "    ok in $(elapsed "$stage_started_at")s"
else
  log "stage 1/5  SKIPPED by --stage"
fi

# --- Stage 2: permanent unit tests ------------------------------------------
# Every module runs its own:test. A module with no test source has nothing to
# prove, and Gradle reports a Test task over an empty source set as GREEN, so
# the modules that are actually asked are enumerated from the filesystem
# instead of assumed: a module is asked only if src/test holds.kt file.

# The test evidence, declared BEFORE the stage that produces it, because the
# verdict line at the end of the run has to be able to say `NOT_RUN` about a
# stage this invocation never asked for. A silent field reads like a zero: a
# gate that skipped its tests and printed nothing about them is the same line
# as a gate that ran none and found none.
stage2_ran=0
xml_fresh_tests=0
xml_read_tests=0

if want_stage 2; then
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
done < <(find pipelinek -path '*/src/*' -name '*.kt' -not -path '*/build/*' \
             -not -path 'pipelinek/buildSrc/*' -not -path 'examples/*' \
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
done < <(find pipelinek -path '*/src/test/*' -name '*.kt' -not -path '*/build/*' | LC_ALL=C sort | awk -F'/src/test/' '!seen[$1]++')
if [[ ${#modules_with_tests[@]} -eq 0 ]]; then
    echo "    no module has a unit test yet; nothing to run (NOT_RUN, not PASS)"
else
    tasks=()
    selected=()
    # `find pipelinek ...` yields paths relative to the repository root, so a module
    # reads `pipelinek/pipeline-domain`. Gradle is invoked with `-p pipelinek`, which makes
    # `pipelinek` the Gradle ROOT rather than a project segment, so its project path is
    # `:pipeline-domain` -- not `:pipelinek/pipeline-domain`. Prefixing the find output
    # verbatim made every task path unresolvable, and the stage failed with
    # "Cannot locate tasks that match ':pipelinek/pipeline-domain:test'" before running
    # a single test. The unit tests had therefore never executed through this gate.
    # A NESTED module needs the other half of the translation: settings.gradle.kts
    # declares `:pipeline-step-sdk:runtime`, with a colon, while the directory is
    # `pipeline-step-sdk/runtime`, with a slash. Stripping only the `pipelinek/` prefix
    # therefore resolved the flat modules and failed on the first nested one with
    # "Cannot locate tasks that match ':pipeline-step-sdk/runtime:test' as project
    # 'pipeline-step-sdk/runtime' not found". Gradle aborted the whole stage, so
    # the flat modules ran and the nested ones never did -- and the census that
    # follows could not have reported it, because the stage had already exited.
    for m in "${modules_with_tests[@]}"; do
        gradle_path="${m#pipelinek/}"
        gradle_path="${gradle_path//\//:}"
        module_selected ":${gradle_path}" || continue
        tasks+=(":${gradle_path}:test")
        selected+=("$m")
    done
    # A module filter that excluded every module would otherwise run an empty
    # task list and report SUCCESS, which is the same silent green stage 2 exists
    # to prevent -- reached now through a filter instead of through an empty
    # source set. Selecting nothing is a mistake in the invocation, so it says so.
    if [[ ${#tasks[@]} -eq 0 ]]; then
        echo "verify-all: --module '$module_filter' matched none of the ${#modules_with_tests[@]}" >&2
        echo "module(s) that have a unit test:" >&2
        for m in "${modules_with_tests[@]}"; do
            echo "  $(gradle_path_of "$m")" >&2
        done
        fail "stage 2: the module filter selected nothing"
    fi
    modules_with_tests=("${selected[@]}")
    printf '    %s\n' "${modules_with_tests[@]}" | sed 's/^/    module: /'
    # The instant the tests are asked for, so each report can be attributed to
    # THIS run or to an up-to-date task Gradle legitimately reused. Cached test
    # results are correct evidence -- the inputs did not change -- but calling
    # them "executed" reports a number this invocation did not earn, and it made
    # 505 tests read as a 17s stage when 36 of them came from the cache. The
    # count is therefore split: how many reports this run rewrote, and how many
    # it read.
    test_run_started=$(date +%s)
    # `--tests` narrows which classes the Test tasks execute. It is appended per
    # task, because Gradle binds `--tests` to the task named before it; a single
    # trailing flag applies to the last task only, which is the same
    # "the filter applied to one module" bug as a census scoped to a guess.
    gradle_args=()
    if [[ ${#test_filters[@]} -gt 0 ]]; then
        for t in "${tasks[@]}"; do
            gradle_args+=("$t")
            for p in "${test_filters[@]}"; do gradle_args+=(--tests "$p"); done
        done
    else
        gradle_args=("${tasks[@]}")
    fi
    ./pipelinek/gradlew --no-daemon -p pipelinek "${gradle_args[@]}" \
        > "$log_dir/stage2.log" 2>&1 \
        || { tail -40 "$log_dir/stage2.log" >&2; fail "stage 2 (see "$log_dir/stage2.log")"; }
    # Gradle's exit status is not the result. A Test task can report success
    # with its classes skipped or absent, so the executed/failed/skipped counts
    # are read from the JUnit XML that the task wrote.
    # The census is scoped to the modules just asked for, deliberately. A global
    # `find pipelinek -path '*/build/test-results/*'` also collects the XML that the
    # suites deleted by the RESET left behind: it reported "283 reports,
    # executed=1880" for a run that asked one module and executed 56 tests in
    # 13 reports, the other 230 reports coming from code that no longer exists.
    # A count whose set cannot be attributed to a requested task is not a
    # measurement, so the set is taken from the same list the tasks came from.
    xml_total=0; xml_fail=0; xml_skip=0; xml_files=0; xml_fresh=0
    xml_fresh_tests=0; xml_fresh_fail=0; xml_fresh_skip=0
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
            # A report written BEFORE this run started belongs to a previous
            # invocation. With `--test` in play that is the common case, not the
            # exception: the directory still holds the classes the filter just
            # excluded, and counting them would report tests this run never
            # executed. So the fresh-only totals are accumulated separately and
            # a scoped run quotes those.
            if [[ "$(stat -c %Y "$xml")" -ge "$test_run_started" ]]; then
                xml_fresh=$((xml_fresh + 1))
                xml_fresh_tests=$((xml_fresh_tests + t))
                xml_fresh_fail=$((xml_fresh_fail + f + e))
                xml_fresh_skip=$((xml_fresh_skip + s))
            fi
        done < <(find "$m/build/test-results" -name 'TEST-*.xml' 2>/dev/null | LC_ALL=C sort)
        # A module with a test source but no discovered test is the same silent
        # green Gradle 8 reports for an empty source set, one level deeper.
        if [[ "$mod_reports" -eq 0 ]]; then
            silent_modules+=("$m")
        fi
    done
    # The two halves of the test census, taken BEFORE the subset branch below
    # narrows `xml_total` to this run. `rewritten` is what this run really
    # executed; `read` is what it found on disk and did not ask for -- written
    # by an earlier invocation. The verdict line carries both, because a reader
    # cannot otherwise tell a measurement from a cache hit.
    xml_read_tests=$(( xml_total - xml_fresh_tests ))
    stage2_ran=1
    if [[ "$subset" == 1 ]]; then
        echo "    JUnit XML (this run only): $xml_fresh_tests test(s) in $xml_fresh report(s)," \
             "failed=$xml_fresh_fail skipped=$xml_fresh_skip"
        echo "    ($xml_files report(s) on disk, $(( xml_files - xml_fresh )) from an earlier invocation and excluded)"
        xml_fail="$xml_fresh_fail"; xml_skip="$xml_fresh_skip"; xml_total="$xml_fresh_tests"
    else
        echo "    JUnit XML: $xml_files report(s), $xml_fresh rewritten by this run," \
             "tests=$xml_total failed=$xml_fail skipped=$xml_skip"
    fi
    if [[ "$xml_fresh" -eq 0 ]]; then
        if [[ "$subset" == 1 ]]; then
            # For a scoped run this is the normal second invocation with no
            # source change, and failing would punish the fast loop for the
            # cache doing its job. It still says what happened, because a silent
            # cache hit reads exactly like an executed test.
            echo "    note: every task was up to date; nothing was executed by this run."
        else
            echo "verify-all: no report was rewritten, so this run measured a fully" >&2
            echo "up-to-date test graph. That is a valid cache hit but NOT a" >&2
            echo "measurement of the gate's cost; remove the test outputs and rerun" >&2
            echo "before quoting a wall clock." >&2
            fail "stage 2: every test task was up to date"
        fi
    fi
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
else
  log "stage 2/5  SKIPPED by --stage"
fi

# --- Stage 3: E2E scenarios --------------------------------------------------

# How many scenarios the launcher selected but never reached a verdict on.
# Zero here means "the launcher's own numbers reconcile", NOT "stage 3 did
# not happen": e2e_stage3_ran separates those two, because a count that cannot
# be observed must fail rather than read as a clean zero.
e2e_not_run=0
e2e_stage3_ran=0

# The suffix travels WITH the verdict line. A gap that only exists in the
# stage log disappears the moment somebody quotes the one line that says the
# gate passed, which is the only line anybody actually reads. Same argument as
# the subset marker below: the difference between this run and the full gate
# has to be inside the line, not beside it.
gaps_suffix() {
    if [[ "$e2e_stage3_ran" == 1 && "$e2e_not_run" -gt 0 ]]; then
        printf '  gaps=%s not_run' "$e2e_not_run"
    fi
}

# The test evidence travels WITH the verdict line, for the same reason the gap
# count does. The stage log is read by whoever ran the gate; the verdict line is
# read by everyone else, and it is the one that gets pasted into a report. A line
# that says "the gate passed" without saying how many tests really executed is
# half a verdict: the reader cannot tell a measurement from a cache hit.
#
#   rewritten   tests in the reports THIS run wrote. They really ran.
#   read        tests in the reports this run did not write: executed by an
#               earlier invocation and read from disk.
#
# A run that never asked for the tests says NOT_RUN instead of staying silent,
# because a silent field reads like a zero.
tests_suffix() {
    if [[ "$stage2_ran" == 1 ]]; then
        printf '  tests=rewritten:%s read:%s' "$xml_fresh_tests" "$xml_read_tests"
    else
        printf '  tests=NOT_RUN'
    fi
}

# How much of the E2E stage was OBSERVED rather than merely executed. It travels
# with the verdict line for the same reason the test census does.
scenarios_suffix() {
    if [[ "$e2e_stage3_ran" == 1 && -n "$e2e_observed" ]]; then
        printf '  scenarios=observed:%s executed:%s' "$e2e_observed" "$e2e_executed"
    else
        printf '  scenarios=NOT_RUN'
    fi
}

# Everything the run observed, as ONE argument.
#
# These used to travel as separate `%s` in the verdict format, and that is how a
# count goes missing without anybody noticing: the format carried five
# conversions and the call passed six arguments, so the shell read the surplus
# as a second pass over the same format. Composing them first makes that
# unrepresentable -- one string, one `%s` -- and each field keeps saying NOT_RUN
# rather than going quiet when its stage was skipped.
evidence_suffixes() {
    printf '%s%s%s' "$(tests_suffix)" "$(gaps_suffix)" "$(scenarios_suffix)"
}

if want_stage 3; then
log "stage 3/5  E2E scenarios in examples/"
stage_started_at=$(date +%s)
# The launcher gets the time this gate has LEFT, minus a reserve for the
# stages that come after it. The launcher's own clock then stops the stage
# BEFORE the gate's ceiling, and it names the scenarios it did not get to --
# which is the difference between "the run was stopped" and "scenario X was
# stuck". Without this, a hung scenario takes the whole gate down with it and
# the only thing the operator learns is that time ran out.
stage_budget=$(( BUDGET_SECONDS - (stage_started_at - started_at) - 30 ))
if (( stage_budget <= 0 )); then
    fail "stage 3: the gate has no budget left for the E2E stage"
fi
export PIPELINEK_E2E_BUDGET_SECONDS="$stage_budget"
if [[ -n "$scenario_filter" ]]; then
    ./examples/run.sh "$scenario_filter" > "$log_dir/stage3.log" 2>&1 \
        || { tail -40 "$log_dir/stage3.log" >&2; fail "stage 3 (see "$log_dir/stage3.log")"; }
else
    ./examples/run.sh > "$log_dir/stage3.log" 2>&1 \
        || { tail -40 "$log_dir/stage3.log" >&2; fail "stage 3 (see "$log_dir/stage3.log")"; }
fi
# Reading the log with -a: the scenario output can carry NUL bytes, and without
# it the match is reported as a binary file with no text, which is
# indistinguishable from the summary being absent.
stage3_summary="$(grep -aE '^[0-9]+ scenario' "$log_dir/stage3.log" || true)"
printf '%s\n' "$stage3_summary" | sed 's/^/    /'
if [[ -z "$stage3_summary" ]]; then
    fail "stage 3 printed no scenario summary; a missing count is not a zero count"
fi
e2e_stage3_ran=1
# What the E2E stage OBSERVED, next to what it executed. The launcher publishes
# the census as its own line; the gate reads it and carries it to the verdict,
# because a gate that cannot say which of its checks were real is a gate whose
# cost nobody can weigh against what it bought. A census line the launcher did
# not print leaves the field empty rather than zero: an absent count is not a
# count of nothing, it is a count nobody took.
e2e_observed=""
e2e_executed=""
e2e_census="$(grep -aE '^[0-9]+ of [0-9]+ executed scenario' "$log_dir/stage3.log" || true)"
if [[ -n "$e2e_census" ]]; then
    e2e_observed="$(sed -nE 's/^([0-9]+) of [0-9]+ executed scenario.*/\1/p' <<<"$e2e_census")"
    e2e_executed="$(sed -nE 's/^[0-9]+ of ([0-9]+) executed scenario.*/\1/p' <<<"$e2e_census")"
    printf '    %s\n' "$e2e_census"
fi
# The gap is derived, not read from a field. The launcher publishes three
# independently produced counters -- selected, passed, failed -- and a scenario
# that was selected but reached neither verdict was skipped. Deriving the gap
# from all three cannot be fooled by a counter that is structurally zero, and it
# cannot drift away from the launcher's own arithmetic the way a literal match
# can: a summary that stops carrying a field is a broken contract, and a broken
# contract fails here rather than reading as a clean zero.
e2e_counts="$(printf '%s\n' "$stage3_summary" | tr ',' '\n')"
e2e_selected="$(printf '%s\n' "$e2e_counts" | sed -n 's/^ *\([0-9][0-9]*\) scenario.*/\1/p' | head -1)"
e2e_passed="$(printf '%s\n' "$e2e_counts" | sed -n 's/^ *\([0-9][0-9]*\) passed.*/\1/p' | head -1)"
e2e_failed="$(printf '%s\n' "$e2e_counts" | sed -n 's/^ *\([0-9][0-9]*\) failed.*/\1/p' | head -1)"
if [[ -z "$e2e_selected" || -z "$e2e_passed" || -z "$e2e_failed" ]]; then
    fail "stage 3 summary carries no scenario counts: $stage3_summary"
fi
e2e_not_run=$(( e2e_selected - e2e_passed - e2e_failed ))
if (( e2e_not_run < 0 )); then
    fail "stage 3 summary does not reconcile: $stage3_summary"
fi
if [[ "$e2e_not_run" -gt 0 ]]; then
    # A scenario that was selected and never reached a verdict was skipped, and
    # a skipped scenario is a declared third state rather than a pass. The
    # count is reported here so it reaches the verdict line, not only the
    # stage log.
    echo "    ${e2e_not_run} scenario(s) NOT_RUN — declared unrunnable, NOT a pass"
fi
echo "    ok in $(elapsed "$stage_started_at")s"
else
  log "stage 3/5  SKIPPED by --stage"
fi

# --- Stage 4: LOC budget -----------------------------------------------------

if ! want_stage 4; then
    log "stage 4/5  SKIPPED by --stage"
elif [[ "$skip_budget" == 1 ]]; then
    log "stage 4/5  LOC budget SKIPPED by --no-budget"
    # The provenance guard does NOT go with the LOC budget. `--no-budget` skips a
    # CONTRACT, and the guard is not that contract: it is a check on the words
    # the source says, and skipping it because the operator did not want the
    # census of lines would be the wrong reason to stop saying what the code
    # does.
    log "stage 4b/5 comment provenance"
    stage_started_at=$(date +%s)
    python3 scripts/check-comment-provenance.py >/tmp/.verify-comment.out 2>&1 \
        || { cat /tmp/.verify-comment.out >&2; fail "stage 4b: comment provenance"; }
    echo "    ok in $(elapsed "$stage_started_at")s"
else
    log "stage 4/5  LOC budget"
    stage_started_at=$(date +%s)
    python3 scripts/check-budgets.py || fail "stage 4: LOC budget"
    echo "    ok in $(elapsed "$stage_started_at")s"

    # --- Stage 4b: comment provenance ---------------------------------------
    #
    # A comment that names its ticket, its document section, or its commit is
    # the comment that anchors the code to a process, not to its behaviour. The
    # guard reads Kotlin, `.kts` and `.sh` files, and walks the comment stream
    # tokenised by hand so a `//` inside a string literal stays a string.
    #
    # It runs here, between the LOC gate and the wall clock, because the LOC
    # contract already names the file as a whole -- a guard that runs later
    # cannot qualify the verdict the way the 2:1 contract qualifies the budget.
    log "stage 4b/5 comment provenance"
    stage_started_at=$(date +%s)
    python3 scripts/check-comment-provenance.py >/tmp/.verify-comment.out 2>&1 \
        || { cat /tmp/.verify-comment.out >&2; fail "stage 4b: comment provenance"; }
    echo "    ok in $(elapsed "$stage_started_at")s"
fi

# --- Stage 5: wall-clock -----------------------------------------------------

total=$(elapsed "$started_at")
log "stage 5/5  wall clock"
if [[ "$subset" == 1 ]]; then
    # A subset that spends 3 minutes instead of 240 is not a budget breach, and a
    # subset that spends 900 is not evidence the gate would. Comparing either
    # against 900 s would be a number about nothing, so none is printed.
    echo "    elapsed ${total}s (SUBSET: not compared against the ${BUDGET_SECONDS}s gate)"
    printf '\nPASS (subset)  sha=%s  elapsed=%ss%s\n' \
        "$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)" "$total" "$(evidence_suffixes)"
    printf 'This is not the gate. Quote scripts/verify-all.sh with no filters for that.\n'
    exit 0
fi
echo "    elapsed ${total}s against a budget of ${BUDGET_SECONDS}s"
if [[ "$total" -gt "$BUDGET_SECONDS" ]]; then
    echo "verify-all: BLOCKED_BUDGET. ${total}s > ${BUDGET_SECONDS}s." >&2
    echo "  Per AGENTS.md section 4 this is not something to raise or split:" >&2
    echo "  shrink the scenario set, drop duplicate verification, or cut the" >&2
    echo "  slowest stage, then rerun the whole gate." >&2
    fail "stage 5: wall clock ${total}s"
fi

# A scenario that did not run is not a scenario that passed. AGENTS.md section 4
# says an absent test or a skipped task is never PASS, and the only artefact
# anybody reads is the verdict line -- so a `gaps=1 not_run` suffix welded onto
# `PASS` produced a sentence that contradicted itself, and the contradiction was
# the part most likely to be quoted. The verdict word now follows the evidence.
# The exit status stays 0 because every stage that DID run is still real
# evidence, and a scenario the product cannot launch without a TTY is a gap to
# name, not a defect to redden the whole gate over.
if (( e2e_not_run > 0 )); then
    printf '\nINCOMPLETE  sha=%s  elapsed=%ss  budget=%ss%s\n' \
        "$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)" "$total" \
        "$BUDGET_SECONDS" "$(evidence_suffixes)"
    printf 'Every stage above ran and passed, but %s scenario(s) never ran at all.\n' "$e2e_not_run"
    printf 'This is not the gate: a scenario that was selected and skipped is a gap\n'
    printf 'to name, not a pass. AGENTS.md section 4 does not allow an unrun test to be a PASS.\n'
else
    # The only verdict that earns a clean exit is a full PASS. INCOMPLETE, a
    # subset, and every `fail` keep their logs: those are exactly the runs an
    # operator has to come back and read.
    VERIFY_ALL_VERDICT=PASS
    printf '\nPASS  sha=%s  elapsed=%ss  budget=%ss%s\n' \
        "$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)" "$total" \
        "$BUDGET_SECONDS" "$(evidence_suffixes)"
fi