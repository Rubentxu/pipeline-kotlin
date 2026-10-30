#!/usr/bin/env bash
# verify-all.sh — THE single local verification entrypoint (AGENTS.md section 5).
# It composes, in one process and on purpose no other:
#   0. a clean of the project's build outputs, so the run measures a real build;
#   1. the checks that read only the tree, cheapest first, and then the build:
#      the LOC budget census, the forbidden-source-set census, the comment
#      provenance guard, the process-exit guard, static analysis (detekt), the
#      public-API compatibility check, and the development distribution built
#      ONCE with installDist;
#   2. the permanent unit tests of every module that has any;
#   3. the tracked E2E scenarios in examples/, run by examples/run.sh;
#   4. the wall-clock gate.
# It is not a test framework and it has no registry, no service, no database
# and no tests-of-tests. It runs named commands in a fixed order, and it stops
# at the first failing stage so a red run names the stage that is red.
#
# THE ORDER IS PART OF THE GATE, not a detail of it. Four of the checks read
# only the tree -- the LOC census, the source-set census, the words the
# comments use, and the calls that end the process -- and they cost seconds
# between them. The checks that RUN the product cost minutes. A tree that
# breaks one of the cheap rules is therefore told so BEFORE the build, the
# unit tests and the scenarios have spent the time, instead of after the whole
# run has already been paid for: the same defect, found minutes later.
#
#   scripts/verify-all.sh              # the whole gate
#   scripts/verify-all.sh --no-budget  # skip only the LOC census, for a quick loop
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
# The gate has four stages, and a stage number that names a fifth is a typo: a
# typo that selected nothing would run nothing and print a clean verdict, which
# is the same silent green this script refuses everywhere else.
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

# The help is the header, and the header is the help: the range of lines the
# old text named by hand had drifted past the end of the comment block and
# printed a dozen lines of code with it. Reading the block until the first
# line that is not a comment keeps the two in step by construction.
usage() { awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        # `--no-budget` is a subset flag as well as a budget skip. It used to set
        # only skip_budget, so the run skipped the LOC census and then fell
        # through to the SAME `PASS sha=… budget=900s` line the full gate prints
        # -- a line naming a LOC budget this run never measured. The verdict
        # belongs to the run that observed everything, so dropping a stage drops
        # the claim with it.
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

# The gate has four stages. A number that names a fifth is a typo, and a typo
# that selected nothing would run nothing and print a clean verdict: the same
# silent green this script refuses everywhere else. The check is here, before
# any work, because a stage that does not exist must not be discovered after
# the build has already been paid for.
if [[ -n "$stage_spec" ]]; then
    for st in ${stage_spec//,/ }; do
        case "$st" in
            up-to-*) st="${st#up-to-}" ;;
        esac
        if [[ ! "$st" =~ ^[0-9]+$ ]]; then
            echo "verify-all: '$stage_spec' is not a stage selection." >&2
            echo "  the gate has four stages: 1 statics and compile, 2 unit tests," >&2
            echo "  3 E2E scenarios, 4 wall clock." >&2
            exit 2
        fi
        if (( st < 1 || st > 4 )); then
            echo "verify-all: stage $st does not exist; the gate has four stages." >&2
            echo "  1 statics and compile, 2 unit tests, 3 E2E scenarios, 4 wall clock." >&2
            exit 2
        fi
    done
fi

# The BUILD CACHE is a different thing from the dependency cache, and the full
# gate needs the first one switched off.
#
# The dependency cache is what "local dependency caches already populated"
# means in AGENTS.md section 4: the third-party jars are already on this host,
# so the measurement is of THIS project's work and not of the network. That
# cache is kept.
#
# The BUILD cache stores the OUTPUTS of the tasks, keyed by their inputs. It is
# what defeated this gate: `clean` removes the outputs, the build cache puts
# them straight back, and a run that executes ZERO tests ends with 196 report
# files on disk. The gate's own comment above the clean says the outputs go
# first "so the run is measurable every time" -- the build cache made the clean
# a no-op, so the second run of the day measured the cache instead of the work,
# and its wall clock was a cache hit dressed as a measurement.
#
# So the full gate runs Gradle with `--no-build-cache` (measured to override
# `org.gradle.caching=true` from pipelinek/gradle.properties: with the
# property alone the task is served from the cache and the report files it
# wrote come back unchanged; with the flag the task EXECUTES). The real cost
# of this gate is the cost of building and testing this repository, not the
# hundred-odd seconds a cache hit makes it look like, and only the former is a
# number AGENTS.md's 900 s budget can be compared against.
#
# A SUBSET run keeps the cache. The inner loop exists to be cheap, and a
# cache hit there is the cache doing its job; what the subset owes the reader
# is an HONEST count, which the census below gives it by reading each report's
# own stamp instead of the filesystem's.
gradle_cache_args=()
if [[ "$subset" == 0 ]]; then
    gradle_cache_args=(--no-build-cache)
fi

# Stage selection. `--stage up-to-N` is the progressive form: everything from 1
# to N. `--stage 1,3` is the explicit form. Both are a SUBSET by definition --
# stage 4 alone, or stage 3 without stage 1, measures nothing about the gate.
want_stage() {
    [[ -z "$stage_spec" ]] && return 0
    local n spec up_to=""
    if [[ "$stage_spec" == up-to-* ]]; then
        up_to="${stage_spec#up-to-}"; spec=""
        [[ "$up_to" =~ ^[0-9]+$ ]] || { echo "verify-all: 'up-to-$up_to' is not a stage number" >&2; exit 2; }
        for n in 1 2 3 4; do [[ "$n" -le "$up_to" ]] && spec="${spec:+$spec,}$n"; done
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
started_at_ms=$(date +%s%3N)
stage_started_at=$started_at

log()  { printf '\n=== %s\n' "$*"; }
fail() { printf 'verify-all: FAILED at %s\n' "$*" >&2; exit 1; }

# A failed run owes the reader a verdict line too. The verdict line is the
# artefact somebody quotes and somebody else greps for; a run that dies without
# one leaves its evidence in the middle of a log where nobody looks, and the
# numbers that describe the run travel with its outcome instead of with the
# runs that happened to succeed. The line is published only where the census
# was measured, so its fields are numbers somebody obtained and not zeros
# somebody assumed.
fail_with_verdict() {
    printf 'verify-all: FAILED at %s\n' "$1" >&2
    printf '\nFAIL  sha=%s  elapsed=%ss  budget=%ss%s\n' \
        "$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)" \
        "$(elapsed "$started_at")" "$BUDGET_SECONDS" "$(evidence_suffixes)"
    exit 1
}

elapsed() { printf '%s' "$(( $(date +%s) - $1 ))"; }

# --- The cost census ---------------------------------------------------------
# The gate answers two questions about time: how much did the run cost, and
# what did that time buy. Until now it answered the first with one number per
# stage and the second with nothing at all. A stage log that carries a single
# clock for four different works -- the compilation, the static analysis, the
# public-API check and the assembly of the distribution -- cannot say which of
# them the time went to, and a cost nobody can attribute is a cost nobody can
# cut.
#
# The census names PARTS of the run. Each part carries the time it took, the
# number of things behind that time, and whether the gate declared it
# mandatory. Three rules keep it honest:
#
#   1. A part is measured or it is absent. There is no invented zero.
#   2. A part the gate declared mandatory and did not measure is a FAILURE.
#      An instrument with a hole makes every other number look more trustworthy
#      than it is, and that is the direction the reader errs in.
#   3. The parts do NOT add up to the wall clock. The build runs its tasks in
#      parallel, so the sum of the tasks is the WORK, not the time. What does
#      have to hold is that no single item costs more than the clock of the
#      stage it belongs to, and that the stages which ran one after another
#      fit inside the total.
#
# The unit is the millisecond. Seconds cannot tell a task that cost nothing
# from a task that cost a tenth of a second, and a census that cannot tell
# those apart cannot answer the question it was built for.

declare -A component_ms=()
declare -A component_count=()
declare -A component_note=()
declare -A component_required=()
declare -A component_measured_flag=()
declare -a component_order=()
component_measured_total=0
component_required_total=0

# How the tasks of the build ended. Two runs of the same gate cost the reader
# the same wall clock on the page, and one of them may have done almost no
# work at all: the tasks were up to date, or they were served from the build
# cache. So the count of the tasks that REALLY ran travels with the verdict
# line, because "the gate passed in 240 s" is not the same statement when 210
# of the 319 tasks were free.
task_timing_lines=0
task_executed_total=0
task_no_work_total=0

now_ms() { date +%s%3N; }

component_touch() {
    local key="$1"
    if [[ -n "${component_ms[$key]+x}" ]]; then
        return 0
    fi
    component_ms[$key]=0
    component_count[$key]=0
    component_note[$key]=""
    component_order+=("$key")
}

component_add() {
    local key="$1" ms="$2" count="$3" note="$4"
    component_touch "$key"
    component_ms[$key]=$(( component_ms[$key] + ms ))
    component_count[$key]=$(( component_count[$key] + count ))
    if [[ -n "$note" ]]; then
        component_note[$key]="$note"
    fi
    if [[ -z "${component_measured_flag[$key]+x}" ]]; then
        component_measured_flag[$key]=1
        component_measured_total=$(( component_measured_total + 1 ))
    fi
}

component_set() {
    local key="$1" ms="$2" note="$3"
    component_touch "$key"
    component_ms[$key]="$ms"
    if [[ -n "$note" ]]; then
        component_note[$key]="$note"
    fi
    if [[ -z "${component_measured_flag[$key]+x}" ]]; then
        component_measured_flag[$key]=1
        component_measured_total=$(( component_measured_total + 1 ))
    fi
}

component_require() {
    local key="$1" why="$2"
    component_touch "$key"
    if [[ -z "${component_required[$key]+x}" ]]; then
        component_required[$key]="$why"
        component_required_total=$(( component_required_total + 1 ))
    fi
}

component_is_measured() {
    local key="$1"
    [[ -n "${component_measured_flag[$key]+x}" ]]
}

components_suffix() {
    # NOT_RUN means this run measured no part at all. A run that measured
    # something but declared nothing mandatory still says what it measured:
    # hiding the measurement behind a NOT_RUN would be the same silence the
    # census was built to end.
    if (( component_measured_total == 0 )); then
        printf '  components=NOT_RUN'
        return 0
    fi
    printf '  components=measured:%d required:%d' \
        "$component_measured_total" "$component_required_total"
}

print_cost_breakdown() {
    local key
    if [[ "${#component_order[@]}" -eq 0 ]]; then
        printf '    cost census: nothing was measured\n'
        return 0
    fi
    printf '    cost census (milliseconds; a part is the work it did -- a part that ran in parallel is the SUM of its pieces, which is more than the clock of the stage that ran them):\n'
    for key in "${component_order[@]}"; do
        printf '      %-24s %9d ms  %s\n' \
            "$key" "${component_ms[$key]}" "${component_note[$key]}"
    done
}

# The per-task clock of the build. It lives outside the project directory on
# purpose: Gradle resolves an initialization script against the project it is
# given, not against the directory the command was typed in, so a relative
# path here would be looked for inside the Gradle root and reported missing.
timing_probe="$repo_root/scripts/gradle-task-timing.init.gradle"

require_timing_probe() {
    if [[ ! -f "$timing_probe" ]]; then
        fail "the per-task clock of the build is missing: $timing_probe"
    fi
}

# The relocation of the temporaries of the test workers. It travels with the
# build as a second initialization script because the workers are launched by
# Gradle and do not inherit the property the client was given: without it the
# product's temporaries land in the shared directory of the machine, where
# the owner is unknown, and the census of the residue cannot attribute them.
relocation_probe="$repo_root/scripts/gate-tmpdir-relocation.init.gradle"

require_relocation_probe() {
    if [[ ! -f "$relocation_probe" ]]; then
        fail "the relocation of the temporaries of the test workers is missing: $relocation_probe"
    fi
}

# Which part of the build a task belongs to. The first match wins, and the
# order matters three times over: the static analysis of the test sources is
# part of the analysis and not part of the tests; the compilation of the test
# sources is reported apart from the tests themselves, because the two land in
# different stages and one bucket for both would hide which of them is the
# expensive half; and the test tasks are what "the cost of the tests" means.
task_category() {
    case "$1" in
        *detekt*)                                         printf 'static' ;;
        *apiCheck*|*apiBuild*|*apiCompatibility*)          printf 'api' ;;
        *installDist*|*distZip*|*distTar*|*startScripts*) printf 'distribution' ;;
        *compileTest*|*TestClasses*|*testClasses*)         printf 'test-compilation' ;;
        *Test*|*test*)                                   printf 'unit' ;;
        *compile*)                                       printf 'compile' ;;
        *)                                               printf 'other' ;;
    esac
}

# Read the per-task clock the build wrote and turn it into parts. The log is
# the only witness: a build that wrote no timing lines measured nothing, and
# the gate has to say that rather than report a zero for it.
read_timings() {
    local log="$1" stage_ms="$2" label="$3"
    local lines n path ms state bucket slowest_ms=0 slowest=""
    local -A state_counts=()
    lines="$(grep -a '^TASK_TIMING ' "$log" || true)"
    if [[ -z "$lines" ]]; then
        fail "$label: the build wrote no timing lines, so its cost is unknown"
    fi
    n=0
    while read -r _ path ms state; do
        [[ "$ms" =~ ^[0-9]+$ ]] || continue
        n=$(( n + 1 ))
        if (( ms > slowest_ms )); then
            slowest_ms=$ms
            slowest="$path"
        fi
        bucket="$(task_category "$path")"
        component_add "build:$bucket" "$ms" 1 "tasks of the build"
        state_counts[$state]=$(( ${state_counts[$state]:-0} + 1 ))
        task_timing_lines=$(( task_timing_lines + 1 ))
        if [[ "$state" == "EXECUTED" ]]; then
            task_executed_total=$(( task_executed_total + 1 ))
        else
            task_no_work_total=$(( task_no_work_total + 1 ))
        fi
    done <<< "$lines"
    if (( n == 0 )); then
        fail "$label: the timing lines carry no readable duration"
    fi
    if (( slowest_ms > stage_ms )); then
        fail "$label: a task took ${slowest_ms}ms, more than the ${stage_ms}ms of the stage that ran it"
    fi
    printf '    %s: %d task(s) timed, the slowest %s at %dms\n' \
        "$label" "$n" "$slowest" "$slowest_ms"
    # The distribution by the state each task ended in. A count of tasks that
    # ran is only half the story: the reader also has to know how many were
    # free, because the free ones are the ones a cache produced.
    local state_name
    for state_name in $(printf '%s\n' "${!state_counts[@]}" | sort); do
        printf '      %-14s %d task(s)\n' "$state_name" "${state_counts[$state_name]}"
    done
}

# The host the measurement was taken on. A number without the machine it came
# from is a number nobody can compare against a number from another machine,
# and the budget of a fixed number of seconds only means something on a
# declared host.
print_host_description() {
    local cores ram_mb gradle_version
    cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)"
    [[ -n "$cores" ]] || cores=unknown
    ram_mb="$(awk '/^MemTotal:/ { printf "%d", $2 / 1024 }' /proc/meminfo 2>/dev/null || true)"
    [[ -n "$ram_mb" ]] || ram_mb=unknown
    gradle_version="$(sed -nE 's/^distributionUrl=.*gradle-([0-9.]+)-(bin|all)\.zip.*$/\1/p' \
        pipelinek/gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1)"
    [[ -n "$gradle_version" ]] || gradle_version=unknown
    printf '    reference host: %s cpu(s), %s MB of RAM, JDK %s, Gradle %s\n' \
        "$cores" "$ram_mb" "${java_version:-unknown}" "$gradle_version"
}

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
    for s in 1 2 3 4; do
        want_stage "$s" || continue
        case "$s" in
            1)
                printf '  stage 1  LOC budget census%s\n' \
                    "$([[ "$skip_budget" == 1 ]] && printf ' (SKIPPED: --no-budget)' || printf '')"
                printf '             forbidden Kotlin source sets\n'
                printf '             comment provenance\n'
                printf '             process exits\n'
                printf '             detektMain detektTest apiCheck :pipeline-runtime:compileKotlin :pipeline-application:installDist\n'
                ;;
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
            4) printf '  stage 4  wall clock%s\n' "$([[ "$subset" == 1 ]] && printf ' (reported, NOT compared to 900s: subset run)')" ;;
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
# The whole output is captured and then printed, rather than the first line
# being cut out of a pipe: a reader that closes the pipe early makes the
# producer fail on SIGPIPE, and a pipe failure under `set -o pipefail` takes
# the whole gate down with it.
java_version_output="$("$JAVA_HOME/bin/java" -version 2>&1)"
printf '%s\n' "$java_version_output" | sed 's/^/    /'
java_version="$(printf '%s\n' "$java_version_output" | awk 'NR==1 { print $3 }' | tr -d '"')"

# --- The owned cycle ----------------------------------------------------------
# Everything this run writes belongs to ONE directory, and that directory is
# under the state area rather than in the shared temporary directory of the
# machine.
#
# The first reason is ownership. AGENTS.md section 3 says the temporaries a
# verification cycle produces live under the state area and that the cycle
# removes them when it is over. A temporary in the shared temporary directory
# belongs to nobody: the process that made it forgets it, and the machine
# keeps it until something else happens to delete it.
#
# The second reason is measurement. The residue this run leaves in the shared
# temporary directory was invisible until now. The gate wrote its logs into it,
# the scenarios wrote their workspaces into it, and the product wrote its own
# temporaries into it because that is what java.io.tmpdir said. The gate then
# claimed to leave zero owned temporary artifacts while the directory filled up
# underneath it. A gate that cannot see its own residue cannot report it.
state_base="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/verification-cycles"
cycle_dir="$state_base/gate-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$cycle_dir/tmp" "$cycle_dir/logs"
export PIPELINEK_TMPDIR="$cycle_dir/tmp"
export TMPDIR="$cycle_dir/tmp"
# The workers that Gradle forks to run the tests do NOT inherit the property
# the client was given: they are launched with their own arguments, and
# -Djava.io.tmpdir is not among them. Measured, four `shoptions-shared*`
# directories appeared in the shared temporary directory of the machine during
# `:pipeline-step-sdk:runtime:test`, one per worker, while the client was
# writing into this run's own directory.
#
# That gap is what made the residue census blind: a temporary in the shared
# directory has no owner, so the census can report the names but cannot
# attribute them, and the leak produced by the very tests that guard the
# product was invisible. The destination travels in the environment, where the
# init script can read it, because the gate owns that directory: it creates it,
# it is the one that removes it with the cycle.
export PIPELINEK_GATE_TMPDIR="$cycle_dir/tmp"
# The JVM takes its temporaries from java.io.tmpdir, so the property is what
# moves them, and JAVA_OPTS is how a Gradle client is given one without
# editing the build. The scenarios read the same variable, so the gate and the
# scenarios point at the same place.
if [[ -n "${JAVA_OPTS:-}" ]]; then
    export JAVA_OPTS="$JAVA_OPTS -Djava.io.tmpdir=$cycle_dir/tmp"
else
    export JAVA_OPTS="-Djava.io.tmpdir=$cycle_dir/tmp"
fi

# Each invocation writes its stage output into its own directory, so two gates
# running concurrently on the same machine cannot write into the same file.
# A shared path let one run truncate the log while another was still appending
# to it, which silently destroyed part of that run's evidence.
log_dir="$cycle_dir/logs"

# --- The residue census -------------------------------------------------------
# The question is narrow and factual: did THIS run leave anything of its own
# behind?
#
# The census measures in the two places where the OWNER is known, because the
# owner is what makes a number attributable.
#
# 1. This run's own temporary directory, the relocated one. Everything the
#    product writes lands there, because the product's JVM is given
#    -Djava.io.tmpdir pointing at it -- and the gate PROVES that, per run, by
#    asking the product's own JVM which directory it uses (stage 1h). What
#    survives there when the run is over is this run's residue, with no
#    ambiguity at all: it FAILS the run.
#
# 2. The shared temporary directory of the machine. The own-prefixed entries
#    that appear there during the window are REPORTED with their names and do
#    NOT fail the run: by (1)'s proof, this run's own processes could not have
#    created them. They belong to another program -- a stale copy of this
#    product installed somewhere else, running another project's pipeline -- and
#    blaming them would be a defect of the measurement rather than of the run.
#    That is not a hypothesis: it is what a census of names measures, and the
#    census of names is exactly what produced a wrong verdict once.
#
# The Kotlin compiler daemon is a third thing and is reported apart: it is a
# persistent service that outlives the run and whose log rotates by size rather
# than per run, so its entries are neither this run's temporaries nor
# attributable to it.
#
# Two gates on the same machine would each see the other's entries and blame
# them, so the measurement of the shared directory is taken under a lock. A gate
# that cannot take the lock does not measure and says so, instead of reporting
# a number it cannot attribute.
residue_locked=0
residue_measured=0
own_residue_count=0
foreign_residue_count=0
service_residue_count=0
residue_reported=0
# Whether the census could actually be measured. A census whose instrument
# failed says NOT_MEASURED; it never says zero, and it never lets the run
# pass on a number nobody obtained.
census_failed=0
census_failure_reason=""
# Whether the product's JVM was PROVEN to use this run's own temporary
# directory. Without that proof the entries of the shared directory cannot be
# excluded, and the run fails closed if any of them shows up.
relocation_proved=0
# The prefixes of the temporaries this project owns. A leftover of any of them
# inside this run's own temporary directory is this run's residue.
own_temporary_pattern='^(shoptions-shared|captured-stdout-|pipeline-secret-dir-|pipelinek-sqlite-|pipelinek-e2e-|verify-all[.])'
# The output of a persistent service that this project starts. It is reported,
# never counted as residue: it is not a temporary of the run, and it does not
# disappear when the run ends.
service_output_pattern='^(kotlin-daemon[.])'
# The redirection of the lock file is NOT silenced with 2>/dev/null. On an
# `exec` with no command the redirections become the redirections of the
# SHELL itself, so a silenced stderr there silences the stderr of the whole
# run: every later failure message of the gate -- the refusal of the residue
# census, the LOC budget, the wall clock, the cost census -- disappears into
# /dev/null and the run exits 1 with no reason at all. A gate that dies
# without saying why is the worst way for a gate to fail.
#
# So the writability is probed first, in a command whose own failure is
# harmless and local: `:` writes nothing, and `>>` appends, so the probe
# neither truncates the lock nor changes any descriptor of the shell.
if command -v flock >/dev/null 2>&1 && { : >> "$state_base/gate.lock"; } 2>/dev/null; then
    exec 9>"$state_base/gate.lock"
    if flock -n 9; then
        residue_locked=1
    fi
fi
if [[ "$residue_locked" == 1 ]]; then
    LC_ALL=C ls -A /tmp 2>/dev/null | LC_ALL=C sort > "$cycle_dir/tmp-before.txt" || residue_locked=0
fi

# Count what this run left behind, in the two places where the owner is known.
#
# The pattern travels in a VARIABLE and the program of the filter is fixed.
# That is not a style choice: gawk reads a bare `^(a|b)` as a PROGRAM and
# refuses it with a syntax error, so the filter that was supposed to name our
# own temporaries never ran. The `|| : > file` that used to swallow that error
# emptied the file, and the census then published zero residue with its own
# instrument broken -- a green that measured nothing. So the pattern goes in
# through -v, and the exit status is read: a filter that does not run says
# NOT_MEASURED, it does not say zero.
measure_residue() {
    if [[ "$residue_measured" != 0 ]]; then
        return 0
    fi
    # (1) This run's own temporary directory. Whatever of ours survives there
    # when the run is over is ours, and it fails the run.
    if ! LC_ALL=C ls -A "$cycle_dir/tmp" 2>/dev/null \
        | LC_ALL=C awk -v pat="$own_temporary_pattern" '$0 ~ pat' \
        > "$cycle_dir/tmp-residue.txt"; then
        census_failed=1
        census_failure_reason="the filter over this run's own temporary directory did not run"
    fi
    own_residue_count="$(wc -l < "$cycle_dir/tmp-residue.txt" | tr -d ' ')"
    if [[ "$residue_locked" != 1 || ! -f "$cycle_dir/tmp-before.txt" ]]; then
        residue_measured=2
        return 0
    fi
    # (2) The shared temporary directory of the machine. What appeared there
    # during the window, split by what it is.
    LC_ALL=C ls -A /tmp 2>/dev/null | LC_ALL=C sort > "$cycle_dir/tmp-after.txt" || return 0
    LC_ALL=C comm -13 "$cycle_dir/tmp-before.txt" "$cycle_dir/tmp-after.txt" > "$cycle_dir/tmp-new.txt" || return 0
    if ! LC_ALL=C awk -v pat="$own_temporary_pattern" '$0 ~ pat' \
        "$cycle_dir/tmp-new.txt" > "$cycle_dir/tmp-foreign.txt"; then
        census_failed=1
        census_failure_reason="the filter over the shared temporary directory did not run"
    fi
    if ! LC_ALL=C awk -v pat="$service_output_pattern" '$0 ~ pat' \
        "$cycle_dir/tmp-new.txt" > "$cycle_dir/tmp-service.txt"; then
        census_failed=1
        census_failure_reason="the filter over the output of the compiler daemon did not run"
    fi
    foreign_residue_count="$(wc -l < "$cycle_dir/tmp-foreign.txt" | tr -d ' ')"
    service_residue_count="$(wc -l < "$cycle_dir/tmp-service.txt" | tr -d ' ')"
    residue_measured=1
}

# Say it once, in a form that cannot be read as a zero.
report_residue() {
    if [[ "$residue_reported" == 1 ]]; then
        return 0
    fi
    residue_reported=1
    if [[ "$census_failed" == 1 ]]; then
        printf "    own temporaries left in this run's own temporary directory: NOT_MEASURED (%s)\n" \
            "$census_failure_reason"
    elif (( own_residue_count > 0 )); then
        printf "    own temporaries left in this run's own temporary directory: %s  <- FAILURE\n" "$own_residue_count"
    else
        printf "    own temporaries left in this run's own temporary directory: 0\n"
    fi
    if [[ "$relocation_proved" == 1 ]]; then
        printf "    the product's JVM was proven to use that directory, so what it writes there is this run's\n"
    else
        printf "    the product's JVM was NOT proven to use that directory: its temporaries cannot be attributed\n"
    fi
    if [[ "$residue_measured" == 1 ]]; then
        printf "    own-prefixed entries that appeared in the shared temporary directory: %s (not this run's: the product writes into its own directory)\n" "$foreign_residue_count"
        printf "    output of the persistent Kotlin compiler daemon seen in the shared temporary directory: %s (a service, not a temporary of the run)\n" "$service_residue_count"
    else
        printf "    the shared temporary directory: NOT_MEASURED (another gate holds the lock)\n"
    fi
}

# The verdict line carries the numbers, because a number nobody reads is a
# number nobody acts on. The two counts are separate on purpose: one is this
# run's debt and the other is the machine's, and a line that adds them hides
# which is which.
residue_suffix() {
    if [[ "$census_failed" == 1 ]]; then
        printf '  residuo_propio=NOT_MEASURED residuo_ajeno=NOT_MEASURED residuo_servicio=NOT_MEASURED'
        return 0
    fi
    printf '  residuo_propio=%s' "$own_residue_count"
    if [[ "$residue_measured" == 1 ]]; then
        printf ' residuo_ajeno=%s' "$foreign_residue_count"
        printf ' residuo_servicio=%s' "$service_residue_count"
    else
        printf ' residuo_ajeno=NOT_MEASURED residuo_servicio=NOT_MEASURED'
    fi
}

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
# The cycle is deleted when the run ends, whatever the verdict.
#
# It used to be kept on a failing run, because that is when somebody needs to
# read the logs. The logs are still read: every failing stage prints the tail
# of its own log as it fails, so the evidence reaches the reader without the
# directory having to survive. A cycle that outlives its run is exactly the
# residue this change is about.
#
# The trap returns the status the run already had. A trap that ends on a failed
# test OVERRIDES the exit code of the script that installed it: with
# `VERIFY_ALL_VERDICT` unset, the `[[ ]]` was false, the function returned 1, and
# every SUBSET run -- and every INCOMPLETE verdict, whose comment says its exit
# status stays 0 -- left with status 1 while the verdict line said PASS. The
# documented inner loop (`--stage 2 --module ... --test ...`) was therefore
# always a failure to the caller, and the line it printed and the code it
# returned said different things.
cleanup_logs() {
    local status=$?
    measure_residue
    report_residue
    rm -rf "$cycle_dir"
    return "$status"
}
trap cleanup_logs EXIT

prep_started_ms=$(now_ms)
if [[ "$subset" == 0 ]]; then
    log "preparation  clean build outputs"
    stage_started_at=$(date +%s)
    ./pipelinek/gradlew --no-daemon -p pipelinek "${gradle_cache_args[@]}" clean > "$log_dir/clean.log" 2>&1 \
        || { tail -40 "$log_dir/clean.log" >&2; fail "preparation (the tail of the clean log is above)"; }
    echo "    ok in $(elapsed "$stage_started_at")s"
else
    log "preparation  SKIPPED (a subset run does not quote a wall clock)"
fi
prep_wall_ms=$(( $(now_ms) - prep_started_ms ))

# --- Stage 1: the checks that read the tree, then the build ------------------
# The stage runs four checks that read ONLY the tree, and only then the Gradle
# invocation that builds the product and analyses it. The order is the point,
# and it is the reason this stage exists at all.
#
# The four checks cost seconds between them, measured on the reference host:
# the LOC census a fraction of a second, the source-set census a tenth of a
# second, the comment guard about five seconds and the process-exit guard about
# seven. The build, the unit tests and the scenarios cost minutes, because
# they RUN the product. A tree that breaks one of the cheap rules used to be
# told so only after all of that had been paid for -- the same defect, found
# minutes later, in a run that had already spent its budget. A guard that
# reports late is a guard nobody waits for.
#
# AGENTS.md section 5 calls this stage "compile + statics". The statics that
# are cheap now come first, and the word "compile" names what is left.

stage1_started_ms=$(now_ms)
if want_stage 1; then
    # --- Stage 1a: the LOC budget -------------------------------------------
    # AGENTS.md section 4's contract: VERIFY_LOC <= 2 * PRODUCT_LOC, globally
    # and per module. The census reads the Git index and the tracked files, so
    # it needs no build output and it measures the tree that is about to be
    # built.
    if [[ "$skip_budget" == 1 ]]; then
        log "stage 1a/4  LOC budget SKIPPED by --no-budget"
    else
        log "stage 1a/4  LOC budget"
        stage_started_at=$(date +%s)
        python3 scripts/check-budgets.py || fail "stage 1a: LOC budget"
        echo "    ok in $(elapsed "$stage_started_at")s"
    fi

    # --- Stage 1b: only src/main and src/test may hold Kotlin ----------------
    # AGENTS.md section 2 allows Kotlin unit tests and forbids the rest: "0
    # tests Kotlin UAT/E2E/harness interno". A bespoke lane named anything
    # other than `src/test` is exactly that rest, and an earlier census looked
    # only for `src/test`, so a seven-file `src/pluginContractTest` source set
    # sat in the tree for a whole cycle with no gate noticing and no task
    # compiling it. Enumerating only the permitted name would repeat the
    # blindness; so the forbidden names are searched for by name instead.
    log "stage 1b/4  forbidden Kotlin source sets"
    stage_started_at=$(date +%s)
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
        fail "stage 1b: forbidden Kotlin test source set"
    fi
    echo "    ok in $(elapsed "$stage_started_at")s"

    # --- Stage 1c: the words a comment uses ----------------------------------
    # A comment that names its ticket, its document section, or its commit is
    # the comment that anchors the code to a process, not to its behaviour. The
    # guard reads Kotlin, `.kts` and `.sh` files, and walks the comment stream
    # tokenised by hand so a `//` inside a string literal stays a string.
    #
    # It does NOT go with the LOC census, and `--no-budget` does not skip it: a
    # budget is a contract, and the guard is a check on the words the source
    # says. Skipping it because the operator did not want the census of lines
    # would be the wrong reason to stop saying what the code does.
    log "stage 1c/4  comment provenance"
    stage_started_at=$(date +%s)
    python3 scripts/check-comment-provenance.py >"$log_dir/comment.out" 2>&1 \
        || { cat "$log_dir/comment.out" >&2; fail "stage 1c: comment provenance"; }
    echo "    ok in $(elapsed "$stage_started_at")s"

    # --- Stage 1d: only the entry point may end the process ------------------
    # A helper that ends the process takes a decision that belongs to somebody
    # else, and the price is paid in the places that cannot be tested: the
    # helper cannot be called, so the code around it cannot be exercised, and
    # the exit code it invents is a literal rather than a case of the taxonomy
    # the CLI already declares. The guard reads the product sources, resolves
    # the function that holds each exit call, and fails when that function is
    # not the entry point.
    log "stage 1d/4  process exits"
    stage_started_at=$(date +%s)
    python3 scripts/check-process-exits.py >"$log_dir/exits.out" 2>&1 \
        || { cat "$log_dir/exits.out" >&2; fail "stage 1d: process exits"; }
    echo "    ok in $(elapsed "$stage_started_at")s"

    # --- Stage 1e: a scenario may not create a process ------------------------
    # A `.pipeline.kts` program reaches the outside world through the steps the
    # product offers. Creating a process from the program itself is a second
    # authority: the product does not know about it, does not account for the
    # temporaries it names, does not put a deadline on it and does not read back
    # its exit code. The `sh` step is the sanctioned door, and the guard refuses
    # a scenario that steps around it.
    #
    # It sits next to the exit guard and not inside the census because it is a
    # check on what the source SAYS, not a measurement of lines. The rule is
    # about CREATING a process; asking about the process already running is a
    # question, and several scenarios need it.
    log "stage 1e/4  process creation in scenarios"
    stage_started_at=$(date +%s)
    python3 scripts/check-scenario-process-creation.py >"$log_dir/scenario-process.out" 2>&1 \
        || { cat "$log_dir/scenario-process.out" >&2; fail "stage 1e: a scenario creates a process"; }
    echo "    ok in $(elapsed "$stage_started_at")s"

    # --- Stage 1f: the durable path spends no probe and no fixed wait --------
    # The durable shell used to establish the child's detachment with an
    # auxiliary process whose only object was to ask the platform whether
    # `setsid` was there, and then with a fixed hundred milliseconds whose
    # only object was to let the answer be inferred from a file's absence.
    # Both cost every execution a process and a wait, and the wait could
    # report a wrapper that was merely slow to start as a wrapper that never
    # started at all.
    #
    # The guard is a census, not a linter: every process the durable path
    # creates and every wait it makes is DECLARED, with the purpose it
    # serves. A cost that is not in the table fails, a row that the code no
    # longer has fails too, and a cost whose purpose is a probe or a fixed
    # wait is refused by name. The counts are taken over the code of the live
    # path, which is the only place where "there are none" is a fact.
    log "stage 1f/4  the durable path's costs"
    stage_started_at=$(date +%s)
    python3 scripts/check-durable-shell-cost.py >"$log_dir/durable-cost.out" 2>&1 \
        || { cat "$log_dir/durable-cost.out" >&2; fail "stage 1f: the durable path spends a probe or a fixed wait"; }
    echo "    ok in $(elapsed "$stage_started_at")s"

    # --- Stage 1f: compile, static analysis, API compatibility, distribution --
    # One Gradle invocation, so the distribution is assembled exactly once and
    # the tests below reuse the same compiled classes rather than recompiling.
    # `detektTest` sits beside `detektMain` because they are the same analysis
    # over two source sets, and only one of them used to be in this list. Every
    # module declares both; nothing ran the second. Thirteen test files across
    # seven modules were missing a trailing newline for as long as that held,
    # and no gate was in a position to say so -- the analysis was green in the
    # only sense that a task nobody invokes is green. It fans out to 26
    # subprojects and costs 3s, so running it adds no new gate: it runs one
    # that was already declared.
    #
    # `:pipeline-runtime:compileKotlin` is there because the analysis above
    # does not compile: detekt parses the sources, and a module whose only
    # defect is that it no longer matches the contract it implements parses
    # perfectly.
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
    # distribution". It travels, inside the application's `lib/`. What it is not
    # is a PUBLISHED SDK artifact — an external consumer compiles against the
    # ports in `:pipeline-runtime-api`, never against these adapters.
    log "stage 1g/4  compile + detekt + detektTest + apiCheck + installDist"
    stage_started_at=$(date +%s)
    # The per-task clock rides along with the build. Without it this stage has
    # ONE number for four different works, and the reader cannot tell which of
    # them the time went to. The clock is the build's own listener, so it adds
    # no work of its own to the stage it measures.
    require_timing_probe
    require_relocation_probe
    build_started_ms=$(now_ms)
    ./pipelinek/gradlew --no-daemon -p pipelinek --init-script "$timing_probe" \
        --init-script "$relocation_probe" "${gradle_cache_args[@]}" \
        detektMain \
        detektTest \
        apiCheck \
        :pipeline-runtime:compileKotlin \
        :pipeline-application:installDist \
        > "$log_dir/stage1.log" 2>&1 \
        || { tail -40 "$log_dir/stage1.log" >&2; fail "stage 1g (the tail of the stage log is above)"; }
    build_wall_ms=$(( $(now_ms) - build_started_ms ))
    read_timings "$log_dir/stage1.log" "$build_wall_ms" "stage 1g"
    # The four works this stage does are what the gate exists to certify, so
    # each one is a part the census declares mandatory: if the clock did not
    # measure it, the census is incomplete and the run is not a green gate.
    component_require "build:static" "the static analysis of the sources is part of the build"
    component_require "build:api" "the public-API check is part of the build"
    component_require "build:compile" "the compilation is part of the build"
    component_require "build:distribution" "the assembly of the distribution is part of the build"
    echo "    ok in $(elapsed "$stage_started_at")s"

    # --- Stage 1g: what the PRODUCT costs -----------------------------------
    # The gate's own time is only half the story. What a consumer waits for is
    # the product's own latency, and that has two very different shapes. The
    # first compilation of a script pays for the compiler; the second one pays
    # for the cache. The difference between them is what the script cache buys,
    # and it is invisible unless BOTH are measured. The third number is the
    # price of starting the product at all, which every invocation pays and
    # which no other number in this report contains.
    log "stage 1h/4  product calibration (cold script, warm script, startup)"
    stage_started_at=$(date +%s)
    calib_dir="$cycle_dir/calibration"
    mkdir -p "$calib_dir/cache"
    cat > "$calib_dir/calibration.pipeline.kts" <<'CALIBRATION_SCRIPT'
pipeline {
    stages {
        stage("noop") {
            echo("cost baseline")
        }
    }
}
CALIBRATION_SCRIPT
    dist_bin="$repo_root/pipelinek/pipeline-application/build/install/pipelinek/bin/pipelinek"
    if [[ ! -x "$dist_bin" ]]; then
        fail "stage 1h: the distribution this gate just assembled is not there: $dist_bin"
    fi
    # The script cache is the product's own and it is off by default. Turning
    # it on HERE is what makes the two numbers comparable: the first pass has
    # to compile, and the second one has to find the compiled bytes the first
    # pass left behind.
    had_cache_dir=0
    saved_cache_dir="${PIPELINEK_SCRIPT_CACHE_DIR-}"
    if [[ -n "${PIPELINEK_SCRIPT_CACHE_DIR+x}" ]]; then
        had_cache_dir=1
    fi
    export PIPELINEK_SCRIPT_CACHE_DIR="$calib_dir/cache"

    cold_started_ms=$(now_ms)
    "$dist_bin" validate "$calib_dir/calibration.pipeline.kts" > "$calib_dir/cold.log" 2>&1 \
        || { tail -20 "$calib_dir/cold.log" >&2; fail "stage 1h: the first compilation of a script did not succeed"; }
    cold_ms=$(( $(now_ms) - cold_started_ms ))

    # The second pass is only warm if it did NOT compile the script again. The
    # product names the compiled bytes after the CONTENT of the script, so the
    # file that was there before the pass has to be the file that is there
    # after it: same name, same size, same moment. A second pass that rewrote
    # it would be a second cold pass wearing a warm name.
    if [[ -z "$(find "$calib_dir/cache" -type f -print -quit 2>/dev/null)" ]]; then
        fail "stage 1h: the first pass left no compiled bytes, so there is nothing for a second pass to reuse"
    fi
    cache_before="$(find "$calib_dir/cache" -type f -printf '%p %s %T@\n' 2>/dev/null | LC_ALL=C sort | md5sum)"
    warm_started_ms=$(now_ms)
    "$dist_bin" validate "$calib_dir/calibration.pipeline.kts" > "$calib_dir/warm.log" 2>&1 \
        || { tail -20 "$calib_dir/warm.log" >&2; fail "stage 1h: the second compilation of a script did not succeed"; }
    warm_ms=$(( $(now_ms) - warm_started_ms ))
    cache_after="$(find "$calib_dir/cache" -type f -printf '%p %s %T@\n' 2>/dev/null | LC_ALL=C sort | md5sum)"
    if [[ "$cache_before" != "$cache_after" ]]; then
        fail "stage 1h: the second pass rewrote the compiled bytes, so it was not a warm pass"
    fi

    # The price of starting the product. Three runs, and the MEDIAN, because
    # the first one pays for the page cache and the last one does not: the
    # MEAN of three would charge the product for a warm file system it does
    # not always meet. The median is the number a consumer actually meets.
    startup_runs=()
    for startup_index in 1 2 3; do
        startup_started_ms=$(now_ms)
        "$dist_bin" version > "$calib_dir/version-$startup_index.log" 2>&1 \
            || fail "stage 1h: the product did not report its version"
        startup_runs+=("$(( $(now_ms) - startup_started_ms ))")
    done
    startup_median="$(printf '%s\n' "${startup_runs[@]}" | sort -n | sed -n '2p')"

    # The proof that the product's temporaries land in THIS run's own temporary
    # directory. The JVM prints its effective system properties when it is
    # asked, and java.io.tmpdir is the property that decides where the product
    # creates its temporaries. Asking the product's OWN JVM is what turns "the
    # launcher passes JAVA_OPTS" from an assumption into a measurement of THIS
    # run.
    #
    # Without this proof the residue census cannot tell this run's temporaries
    # from a neighbour's: both carry the same prefixes, and a census of names
    # once blamed the gate for a stale copy of the product running another
    # project's pipeline, which turned a green run into a red one for something
    # the run never did. The proof is what makes the attribution a fact of this
    # run instead of an assumption about the machine.
    proof_log="$calib_dir/tmpdir-proof.log"
    JAVA_OPTS="${JAVA_OPTS:-} -XshowSettings:properties" "$dist_bin" version \
        > "$proof_log" 2>&1 \
        || { tail -20 "$proof_log" >&2; fail "stage 1h: the product did not report its version while proving its temporary directory"; }
    product_tmpdir="$(LC_ALL=C awk -F' = ' '/^[[:space:]]*java\.io\.tmpdir = /{ print $2; exit }' "$proof_log")"
    if [[ "$product_tmpdir" == "$cycle_dir/tmp" ]]; then
        relocation_proved=1
    else
        printf "    the product's JVM reports java.io.tmpdir=%s, not %s\n" \
            "${product_tmpdir:-UNKNOWN}" "$cycle_dir/tmp" >&2
    fi

    if (( had_cache_dir == 1 )); then
        export PIPELINEK_SCRIPT_CACHE_DIR="$saved_cache_dir"
    else
        unset PIPELINEK_SCRIPT_CACHE_DIR
    fi

    component_set "product:script_cold" "$cold_ms" "first compilation of a script"
    component_set "product:script_warm" "$warm_ms" "second compilation, served from the script cache"
    component_set "product:startup" "$startup_median" "median of three starts of the product"
    component_require "product:script_cold" "the price of the first compilation is part of the baseline"
    component_require "product:script_warm" "the price of a cached compilation is part of the baseline"
    component_require "product:startup" "the price of starting the product is part of the baseline"
    printf '    cold %dms, warm %dms, startup %dms (the median of three)\n' \
        "$cold_ms" "$warm_ms" "$startup_median"
    echo "    ok in $(elapsed "$stage_started_at")s"
else
  log "stage 1/4  SKIPPED by --stage"
fi
stage1_wall_ms=$(( $(now_ms) - stage1_started_ms ))

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

stage2_started_ms=$(now_ms)
if want_stage 2; then
log "stage 2/4  permanent unit tests"
stage_started_at=$(date +%s)

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
    #
    # The instant is kept in MILLISECONDS because the attribution below is made
    # against a report's own timestamp, which carries milliseconds too. Whole
    # seconds would leave a one-second window in which a report written by the
    # PREVIOUS invocation is indistinguishable from one written by this one.
    # The whole-second form is kept too, because the mtime is read in seconds
    # and the two witnesses are compared with each other below.
    test_run_started_ms=$(date +%s%3N)
    test_run_started_s=$(date +%s)
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
    # The per-task clock rides along here too. The unit tests are the part of
    # the build a reader most wants to weigh against the scenarios, and the
    # stage clock alone cannot say how much of it was the tests and how much
    # was the compilation of the sources the tests exercise.
    require_timing_probe
    # The workers that run the tests are the ones that inherit the temporary
    # directory of the run: they are the ones that create the product's own
    # temporaries, and without the relocation their residue lands in the
    # shared directory of the machine, where nobody owns it.
    require_relocation_probe
    unit_started_ms=$(now_ms)
    ./pipelinek/gradlew --no-daemon -p pipelinek --init-script "$timing_probe" \
        --init-script "$relocation_probe" "${gradle_cache_args[@]}" "${gradle_args[@]}" \
        > "$log_dir/stage2.log" 2>&1 \
        || { tail -40 "$log_dir/stage2.log" >&2; fail "stage 2 (the tail of the stage log is above)"; }
    unit_wall_ms=$(( $(now_ms) - unit_started_ms ))
    read_timings "$log_dir/stage2.log" "$unit_wall_ms" "stage 2"
    component_require "build:unit" "the permanent unit tests are part of the build"
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
    # The third state: a report that this run PLACED on disk without a test
    # having written it, because the build cache restored its bytes. It is not
    # "read from disk" and it is not "executed": the file is new, the content
    # is old. The count is what lets the gate say which of the two happened
    # instead of guessing.
    xml_cached=0; xml_cached_tests=0
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
            #
            # WHICH run wrote a report is decided by the report's OWN content,
            # not by the filesystem's mtime. The mtime is the wrong witness: the
            # Gradle build cache restores a report's BYTES, and the restore
            # stamps the restored file with the RESTORE moment. So a report
            # that no test wrote since October carries yesterday's tests and
            # today's mtime, and an mtime census calls it "rewritten by this
            # run". That is exactly how a run that executed ZERO tests came to
            # print `tests=rewritten:1465 read:0`, and how the gate's own guard
            # ("no report was rewritten") could never fire.
            #
            # The `<testsuite timestamp="...">` attribute is written by the test
            # framework at the moment the suite STARTS, and it lives INSIDE the
            # restored bytes: a cache restore cannot renew it, because
            # renewing it would mean rewriting the content the cache entry
            # claims to reproduce. So the stamp is a witness the cache cannot
            # forge, and reading it is the difference between a count and a
            # measurement.
            #
            # A report whose stamp is missing or unparsable is counted as READ.
            # The conservative direction is the one that does not claim credit:
            # a report the census cannot attribute is not evidence that this run
            # wrote it.
            #
            # The two witnesses answer two different questions, and the gate
            # needs both. The stamp says WHEN THE CONTENT was produced; the
            # mtime says WHEN THE FILE ARRIVED. Together they name the three
            # states there are, instead of two:
            #
            #   EXECUTED NOW      the content is from this run. The stamp is
            #                     this run's. The tests really ran.
            #   SERVED FROM CACHE  the file arrived during this run but the
            #                     content is older: the build cache restored the
            #                     bytes, and the restore is what refreshed the
            #                     mtime. The tests did NOT run.
            #   READ FROM DISK    neither: the file was already there, untouched.
            #
            # The mtime is a bad witness for the CONTENT and a good one for the
            # ARRIVAL, which is why it is used here for the second and not for
            # the first. A build cache cannot forge a stamp, and it cannot
            # avoid refreshing an mtime; between the two, the state is known.
            suite=$(grep -m1 '<testsuite ' "$xml" 2>/dev/null || true)
            stamp=$(sed -nE 's/.*timestamp="([^"]+)".*/\1/p' <<<"$suite")
            stamp_ms=""
            if [[ -n "$stamp" ]]; then
                stamp_ms=$(date -d "$stamp" +%s%3N 2>/dev/null || true)
            fi
            if [[ -n "$stamp_ms" && "$stamp_ms" -ge "$test_run_started_ms" ]]; then
                xml_fresh=$((xml_fresh + 1))
                xml_fresh_tests=$((xml_fresh_tests + t))
                xml_fresh_fail=$((xml_fresh_fail + f + e))
                xml_fresh_skip=$((xml_fresh_skip + s))
            elif [[ "$(stat -c %Y "$xml")" -ge "$test_run_started_s" ]]; then
                xml_cached=$((xml_cached + 1))
                xml_cached_tests=$((xml_cached_tests + t))
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
    # by an earlier invocation, or restored from the cache by this one. The
    # verdict line carries both, because a reader cannot otherwise tell a
    # measurement from a cache hit.
    xml_read_tests=$(( xml_total - xml_fresh_tests ))
    xml_disk=$(( xml_files - xml_fresh - xml_cached ))
    stage2_ran=1
    if [[ "$subset" == 1 ]]; then
        echo "    JUnit XML (this run only): $xml_fresh_tests test(s) in $xml_fresh report(s)," \
             "failed=$xml_fresh_fail skipped=$xml_fresh_skip"
        echo "    ($xml_files report(s) on disk: $xml_cached served from the cache, $xml_disk already there," \
             "$(( xml_files - xml_fresh )) not written by this run and excluded)"
        xml_fail="$xml_fresh_fail"; xml_skip="$xml_fresh_skip"; xml_total="$xml_fresh_tests"
    else
        # The full gate counts what it EXECUTED, not what it found. A report
        # that was already on disk carries the failures of an earlier
        # invocation, and attributing them to this run would be a claim about
        # work that did not happen here.
        echo "    JUnit XML: $xml_files report(s), $xml_fresh rewritten by this run," \
             "executed=$xml_fresh_tests failed=$xml_fresh_fail skipped=$xml_fresh_skip"
        if [[ "$xml_cached" -gt 0 ]]; then
            echo "    ($xml_cached report(s) were served from the build cache by this run: $xml_cached_tests test(s) not executed)"
        fi
    fi
    # The full gate quotes a wall clock, and a wall clock is a measurement of
    # the work only if the work was DONE. A report served from the build cache
    # is a test that did not run, so a run with any of them measured the cache
    # for that part of the graph. Measured: a run with the cache on executed
    # 45 of the 196 reports and had the other 151 served, and its clock was
    # 103 s for a suite that costs 185 s. The wall clock of a partially cached
    # run is not a smaller measurement of the same thing; it is a measurement
    # of something else.
    #
    # `--no-build-cache` on the full gate is what makes this refusal
    # unreachable in practice. The guard is here so that a gate whose cache
    # switch is removed, or whose flag stops working, cannot quote a number it
    # did not earn. A SUBSET run is not measured against the budget, so a
    # cached subset is not a breach: it is the inner loop doing its job, and
    # the note below says so.
    if [[ "$subset" == 0 && "$xml_cached" -gt 0 ]]; then
        echo "verify-all: $xml_cached report(s) were served from the build cache," >&2
        echo "so this run did NOT execute the whole test graph ($xml_cached_tests" >&2
        echo "test(s) never ran). Its wall clock would measure the cache and not" >&2
        echo "the work; it must not be quoted as the cost of the gate." >&2
        fail "stage 2: part of the test graph was served from the build cache"
    fi
    if [[ "$xml_fresh" -eq 0 ]]; then
        if [[ "$subset" == 1 ]]; then
            # For a scoped run this is the normal second invocation with no
            # source change, and failing would punish the fast loop for the
            # cache doing its job. It still says what happened, because a silent
            # cache hit reads exactly like an executed test.
            #
            # It says WHICH happened, because the two are different facts with
            # different consequences: a task that was up to date did not run
            # because nothing changed, and a task that was served from the
            # cache did not run because the build cache had its output. The
            # first is a no-op; the second is a measurement of the cache. The
            # census knows which one it is, so the note does not guess.
            if [[ "$xml_cached" -gt 0 ]]; then
                echo "    note: $xml_cached report(s) were served from the build cache;" \
                     "nothing was executed by this run."
            else
                echo "    note: every task was up to date; nothing was executed by this run."
            fi
        else
            # The refusal names the state it found, because the census knows it:
            # the old wording asserted a cause ("up to date") that the gate had
            # no way to observe. A report restored from the cache is a different
            # fact with the same consequence, and only one of the two is true.
            echo "verify-all: no report was rewritten, so this run executed no test:" >&2
            echo "the test graph was served without running ($xml_cached report(s) restored" >&2
            echo "from the build cache, $xml_disk already on disk). That is NOT a" >&2
            echo "measurement of the gate's cost; remove the test outputs and rerun" >&2
            echo "before quoting a wall clock." >&2
            fail "stage 2: no test task was executed by this run"
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
  log "stage 2/4  SKIPPED by --stage"
fi
stage2_wall_ms=$(( $(now_ms) - stage2_started_ms ))

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

# How much of the build REALLY ran. It travels with the verdict line for the
# same reason the test census does: the seconds on the line are the same
# whether the tasks executed or the cache answered for them, and a reader who
# compares two runs needs to know which one paid for the work.
#
#   executed   the tasks that ran their work.
#   cached     the tasks that did none: up to date, served from the build
#              cache, or without source. They are the ones the cache paid for.
#
# A run that never read a timing line says NOT_RUN instead of staying silent,
# because a silent field reads like a zero.
tasks_suffix() {
    if (( task_timing_lines == 0 )); then
        printf '  tasks=NOT_RUN'
        return 0
    fi
    printf '  tasks=executed:%s cached:%s' "$task_executed_total" "$task_no_work_total"
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
    printf '%s%s%s%s%s%s' "$(tests_suffix)" "$(gaps_suffix)" "$(scenarios_suffix)" \
        "$(residue_suffix)" "$(components_suffix)" "$(tasks_suffix)"
}

stage3_started_ms=$(now_ms)
if want_stage 3; then
log "stage 3/4  E2E scenarios in examples/"
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
        || { tail -40 "$log_dir/stage3.log" >&2; fail "stage 3 (the tail of the stage log is above)"; }
else
    ./examples/run.sh > "$log_dir/stage3.log" 2>&1 \
        || { tail -40 "$log_dir/stage3.log" >&2; fail "stage 3 (the tail of the stage log is above)"; }
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
# What the E2E stage COMPILED and what it REUSED. The launcher measures it and
# prints it; the gate is the report a person reads. A reuse that stops working
# has to be visible here, or the gate would keep reporting the same number of
# seconds and nobody would know the saving had gone.
stage3_cache="$(grep -aE '^script cache: ' "$log_dir/stage3.log" || true)"
if [[ -n "$stage3_cache" ]]; then
    printf '    %s\n' "$stage3_cache"
fi

# What the E2E stage COST, per scenario. The launcher measures every scenario
# and prints the number; the gate is the report a person actually reads, and a
# cost that is measured and then dropped on the floor is a cost nobody can
# act on. The slowest are named because a suite's cost is not spread evenly:
# a handful of scenarios carry most of it, and that is where a change to the
# harness pays.
stage3_cost="$(grep -aE '^ {5}[^ ]+ +[0-9]+\.[0-9]s$' "$log_dir/stage3.log" || true)"
if [[ -n "$stage3_cost" ]]; then
    stage3_cost_n="$(printf '%s\n' "$stage3_cost" | wc -l | tr -d ' ')"
    stage3_cost_total="$(printf '%s\n' "$stage3_cost" \
        | awk '{ t=$NF; gsub(/s$/,"",t); s+=t } END { printf "%.1f", s }')"
    printf '    %s scenario(s) measured, %s s in total (the stage runs them in parallel); the slowest:\n' \
        "$stage3_cost_n" "$stage3_cost_total"
    printf '%s\n' "$stage3_cost" \
        | sed 's/^ *//' \
        | awk '{ t=$NF; gsub(/s$/,"",t); printf "%8.1fs  %s\n", t, $1 }' \
        | sort -rn \
        | head -5 \
        | sed 's/^/      /'
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
    # The cost of the scenarios, per family. The launcher already prices every
    # scenario it ran; the gate turns those prices into parts a reader can act
    # on, because a suite whose cost is only a total is a suite nobody can
    # shrink. The family is the first segment of the path relative to the
    # examples directory, so the parts line up with the directories a reader
    # can see in the tree.
    #
    # The resolution is the one the launcher publishes: a tenth of a second.
    # Finer numbers are not invented here.
    e2e_cost_total_ms=0
    e2e_cost_n=0
    while read -r cost_path cost_label; do
        [[ -n "$cost_path" ]] || continue
        e2e_cost_n=$(( e2e_cost_n + 1 ))
        cost_family="${cost_path%%/*}"
        if [[ "$cost_family" == "$cost_path" ]]; then
            cost_family="(root)"
        fi
        cost_ms="$(printf '%s' "$cost_label" | sed 's/s$//' | awk '{ printf "%d", $1 * 1000 }')"
        e2e_cost_total_ms=$(( e2e_cost_total_ms + cost_ms ))
        component_add "e2e_family:$cost_family" "$cost_ms" 1 \
            "sum of the scenarios of this family; they run in parallel"
    done < <(printf '%s\n' "$stage3_cost" | sed 's/^ *//' | awk '{ print $1, $2 }')
    # The number of prices has to agree with the launcher's own counters. A
    # scenario that reached a verdict carries a price, and one that never
    # reached one carries none. If the two disagree, the reader is looking at a
    # cost report that does not describe the run it claims to describe.
    e2e_blocked="$(printf '%s\n' "$e2e_counts" | sed -n 's/^ *\([0-9][0-9]*\) blocked.*/\1/p' | head -1)"
    [[ -n "$e2e_blocked" ]] || e2e_blocked=0
    e2e_cost_expected=$(( e2e_passed + e2e_failed + e2e_blocked ))
    if (( e2e_cost_n != e2e_cost_expected )); then
        fail "stage 3: the cost report prices ${e2e_cost_n} scenario(s) and the launcher says ${e2e_cost_expected} reached a verdict"
    fi
    component_set "e2e:scenarios" "$e2e_cost_total_ms" \
        "sum of every scenario the launcher priced; they run in parallel"
    component_require "e2e:scenarios" "the cost of the scenarios is part of the baseline"
    echo "    ok in $(elapsed "$stage_started_at")s"
else
  log "stage 3/4  SKIPPED by --stage"
fi
stage3_wall_ms=$(( $(now_ms) - stage3_started_ms ))

# --- Stage 4: wall clock -------------------------------------------------------

log "stage 4/4  wall clock"
# What this run left behind. The gate promises to leave zero owned temporary
# artifacts, and a promise nobody measures is a promise nobody checks.
#
# What FAILS the run is what survives in this run's OWN temporary directory:
# the product writes there and the run has proved it, so anything left there
# is this run's debt. What appeared in the SHARED temporary directory of the
# machine is reported but not fatal: the proof says this run's processes could
# not have created it, so blaming it would be a defect of the measurement
# rather than of the run. If the proof is missing, the shared entries cannot
# be excluded and the run fails closed instead of assuming them away.
measure_residue
report_residue
if [[ "$census_failed" == 1 ]]; then
    # A census that could not run is not a census that found nothing. The run
    # that publishes a zero it never measured is the exact defect this stage
    # exists to catch, so it fails closed instead.
    echo "verify-all: the residue census could not be measured: ${census_failure_reason}" >&2
    fail_with_verdict "stage 4: the residue census could not be measured"
fi
if [[ "$subset" == 0 ]]; then
    if (( own_residue_count > 0 )); then
        echo "    the entries this run left in its own temporary directory:" >&2
        sed 's/^/      /' "$cycle_dir/tmp-residue.txt" >&2
        fail_with_verdict "stage 4: this run left ${own_residue_count} own temporaries in its own temporary directory"
    fi
    if [[ "$relocation_proved" != 1 && "$residue_measured" == 1 ]] && (( foreign_residue_count > 0 )); then
        echo "    the entries with our prefixes that appeared in the shared temporary directory:" >&2
        sed 's/^/      /' "$cycle_dir/tmp-foreign.txt" >&2
        fail_with_verdict "stage 4: ${foreign_residue_count} own-prefixed entries appeared in the shared temporary directory and the product's temporary directory could not be proven, so they cannot be excluded"
    fi
fi

# The removal of the cycle is part of what the run costs, so it is measured
# and it happens BEFORE the clock is read. A number that excluded the cleanup
# would be the cost of a run nobody had to pay for.
cleanup_started_ms=$(now_ms)
rm -rf "$cycle_dir"
component_set "cleanup" "$(( $(now_ms) - cleanup_started_ms ))" \
    "removal of the cycle this run owned"

total=$(elapsed "$started_at")
total_ms=$(( $(now_ms) - started_at_ms ))
print_host_description
print_cost_breakdown

# The stages run one after another, so their clocks have to fit inside the
# clock of the run. This checks the ACCOUNTING, not the product: the stages do
# not overlap, so a sum larger than the run that held them means one of the
# clocks was taken from the wrong place. It is NOT the same check as "the
# categories of the build add up to the clock of their stage": the build runs
# its tasks in parallel, so those categories MAY exceed their stage.
printf '    the stages, one after another:\n'
printf '      %-24s %9d ms\n' "preparation" "$prep_wall_ms"
printf '      %-24s %9d ms\n' "stage 1" "$stage1_wall_ms"
printf '      %-24s %9d ms\n' "stage 2" "$stage2_wall_ms"
printf '      %-24s %9d ms\n' "stage 3" "$stage3_wall_ms"
sequential_ms=$(( prep_wall_ms + stage1_wall_ms + stage2_wall_ms + stage3_wall_ms ))
printf '      %-24s %9d ms\n' "sum of the stages" "$sequential_ms"
printf '      %-24s %9d ms\n' "the whole run" "$total_ms"
if (( sequential_ms > total_ms )); then
    echo "verify-all: the stages took ${sequential_ms}ms together, more than the ${total_ms}ms of the run that held them." >&2
    fail "stage 4: the sequential clocks do not fit inside the wall clock"
fi

# A part the gate declared mandatory and did not measure is a hole in the
# baseline, not a zero. The census would otherwise publish a number for a
# cost it never observed, which is the same defect as the gate that reported
# a scenario it never ran.
missing_parts=()
for part_key in "${!component_required[@]}"; do
    if ! component_is_measured "$part_key"; then
        missing_parts+=("$part_key")
    fi
done
if (( ${#missing_parts[@]} > 0 )); then
    echo "verify-all: the cost census declared parts it did not measure:" >&2
    for part_key in "${missing_parts[@]}"; do
        printf '  %s: %s\n' "$part_key" "${component_required[$part_key]}" >&2
    done
    fail "stage 4: the cost census is incomplete"
fi

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
    fail "stage 4: wall clock ${total}s"
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