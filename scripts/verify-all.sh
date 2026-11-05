#!/usr/bin/env bash
# verify-all.sh — THE single local verification entrypoint (AGENTS.md section 4).
#
# It runs, in one process and on purpose no other:
#   1. the checks that read only the tree: the comment-provenance guard, the
#      process-exit guard, the durable-path cost guard, the product-boot-layer
#      guard, the census of Kotlin source sets, and the LOC census;
#   2. the build with its static analysis, its public-API compatibility check,
#      the development distribution, and the reference of the published
#      contracts with the review of the links it does not resolve;
#   3. the permanent unit tests of every module that has any;
#   4. the integration tests of every module that has any;
#   5. the mutants, over the seams the mutated modules declare;
#   6. the wall clock of each stage.
#
# It is not a test framework and it has no registry, no service, no database
# and no tests-of-tests. It runs named commands in a fixed order, and it stops
# at the first failing stage so a red run names the stage that is red.
#
# THE LAYER THIS GATE DOES NOT CALL. The layer that boots a product lives in
# a harness repository: it certifies the published artifact against the
# contract of the product and keeps the record of what it certified in its own
# board. This gate calls none of it, and the tree it certifies holds none of
# it: the first stage refuses a scenario suite, a launcher, a family map or an
# init script that belongs to that layer. The one executable program that does
# start the product is the compatibility gate of the publication, which
# compares the published artifact against the last published one; it is named
# in the declaration of that guard, and it does not run here.
#
# THE ORDER IS PART OF THE GATE, not a detail of it. The checks that read only
# the tree cost seconds between them, and the checks that RUN the product cost
# minutes. A tree that breaks one of the cheap rules is therefore told so
# BEFORE the build, the unit tests, the integration tests and the mutants have
# spent the time, instead of after the whole run has already been paid for:
# the same defect, found minutes later.
#
#   scripts/verify-all.sh                     # the whole gate
#   scripts/verify-all.sh --no-budget         # skip only the LOC census
#   scripts/verify-all.sh --list              # resolve the plan, run nothing
#
# SCOPED RUNS. The gate above is the unit of truth and always the default. For
# the inner loop there are filters, and they are deliberately subordinate: any
# filter switches the run to SUBSET mode, which prints its verdict in a
# different shape and says which parts it did NOT measure. That is not
# pedantry. A filtered run that ends with the same line as the full gate is a
# line that gets quoted as "the gate passed" while having measured, say, one
# module. A subset is a legitimate and cheap instrument; it is never evidence
# about the whole.
#
# A change that only touches the documentation is certified by the stage that
# carries the review of the documentation, and by no other: it does not pay
# the three legs.
#
#   scripts/verify-all.sh --stage 1           # the checks that read the tree
#   scripts/verify-all.sh --stage 2           # + the build and the reference
#   scripts/verify-all.sh --stage 3           # + the unit tests
#   scripts/verify-all.sh --stage 4           # + the integration tests
#   scripts/verify-all.sh --stage 5           # + the mutants
#   scripts/verify-all.sh --stage 3 --module ':pipeline-domain'
#   scripts/verify-all.sh --stage 3 --test '*ResolveTest' --test '*RejectTest'
#
# Filters compose; --list never invokes Gradle; a filter that selects nothing is
# an error rather than a green, for the same reason an empty census is not zero.

set -uo pipefail

# --- The identity of what THIS run verifies -----------------------------------
# A verdict is evidence about a commit, so the identifier it prints has to BE
# the content that was compiled. A commit is not the working tree: on a tree
# with changes, HEAD names a commit whose tree does not contain the code that
# ran, and a verdict that names it claims that commit passed when it was never
# built. A clean tree is named by its commit; a tree with changes is named by
# its content, which is a tree object and NOT a commit.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root" || exit 1

gate_head_id() {
    git -C "$1" rev-parse HEAD 2>/dev/null || printf 'unknown'
}

# Whether the working tree of $1 IS HEAD's tree, without hashing anything.
working_tree_is_head() {
    git -C "$1" diff --quiet HEAD -- >/dev/null 2>&1 || return 1
    [[ -z "$(git -C "$1" ls-files --others --exclude-standard 2>/dev/null)" ]] || return 1
    return 0
}

# The tree object of what is on disk, staged or not.
working_tree_id() {
    local repo="$1" index_file tree
    # The temporary index lives inside the repository's own directory: the
    # shared temporary directory of the machine is watched by the residue
    # census, and a file that appears there for a few milliseconds is a
    # question the census would have to answer.
    index_file="$(mktemp "${repo}/.git/pipelinek-gate-index.XXXXXX" 2>/dev/null || true)"
    if [[ -z "$index_file" ]]; then
        index_file="$(mktemp "${TMPDIR:-/tmp}/pipelinek-gate-index.XXXXXX")" || return 1
    fi
    rm -f "$index_file"
    if ! GIT_INDEX_FILE="$index_file" git -C "$repo" read-tree HEAD >/dev/null 2>&1; then
        rm -f "$index_file"
        return 1
    fi
    if ! GIT_INDEX_FILE="$index_file" git -C "$repo" add -A -- . >/dev/null 2>&1; then
        rm -f "$index_file"
        return 1
    fi
    tree="$(GIT_INDEX_FILE="$index_file" git -C "$repo" write-tree 2>/dev/null || true)"
    rm -f "$index_file"
    [[ -n "$tree" ]] || return 1
    printf '%s' "$tree"
}

# The identifier of the content of $1.
gate_content_id() {
    local repo="$1" head tree
    head="$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)"
    if [[ -z "$head" ]]; then
        printf 'unknown'
        return 0
    fi
    if working_tree_is_head "$repo"; then
        printf '%s' "$head"
        return 0
    fi
    tree="$(working_tree_id "$repo" || true)"
    if [[ -z "$tree" ]]; then
        # The content cannot be named. The commit is what the gate can still
        # say, and saying it is better than a silent substitution.
        printf '%s' "$head"
        return 0
    fi
    printf '%s' "$tree"
}

# The identity fields of a verdict line.
#
# The commit is printed beside the content only when they differ: the common
# case, a clean tree, has ONE identity, and two copies of it would be noise.
identity_fields() {
    if [[ "$gate_content_sha" == "$gate_head_sha" ]]; then
        printf 'sha=%s' "$gate_content_sha"
    else
        printf 'sha=%s  head=%s' "$gate_content_sha" "$gate_head_sha"
    fi
}

gate_head_sha="$(gate_head_id "$repo_root")"
gate_content_sha="$(gate_content_id "$repo_root")"

if [[ "$gate_head_sha" == "unknown" ]]; then
    gate_content_kind="unknown"
elif [[ "$gate_content_sha" == "$gate_head_sha" ]]; then
    gate_content_kind="commit"
else
    gate_content_kind="tree"
fi

# --- The options ---------------------------------------------------------------

skip_budget=0
want_stage_upto=5
module_filter=""
declare -a test_filters=()
list_only=0

usage() { awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-budget) skip_budget=1 ;;
        --list) list_only=1 ;;
        --stage)
            shift
            stage_value="${1:-}"
            case "$stage_value" in
                1|2|3|4|5) want_stage_upto="$stage_value" ;;
                up-to-*)
                    stage_value="${stage_value#up-to-}"
                    case "$stage_value" in
                        1|2|3|4|5) want_stage_upto="$stage_value" ;;
                        *) printf 'verify-all: --stage up-to-N takes 1..5\n' >&2; exit 2 ;;
                    esac
                    ;;
                *) printf 'verify-all: --stage takes 1..5 or up-to-N\n' >&2; exit 2 ;;
            esac
            ;;
        --module)
            shift
            module_filter="${1:-}"
            ;;
        --test)
            shift
            test_filters+=("${1:-}")
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'verify-all: unknown option %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

# --- The state of this run -------------------------------------------------------
#
# Every counter the verdict reports is declared HERE, before the first stage
# runs, and not next to the stage that fills it. A red run prints its verdict
# too, and a verdict that asks for a counter the run never reached reads an
# unset name as a missing value.

stage1_ran=0; stage2_ran=0; stage3_ran=0; stage4_ran=0; stage5_ran=0
stage1_wall_ms=0; stage2_wall_ms=0; stage3_wall_ms=0; stage4_wall_ms=0; stage5_wall_ms=0
stage1_started_ms=0; stage2_started_ms=0; stage3_started_ms=0
stage4_started_ms=0; stage5_started_ms=0

unit_tests_ran=0; unit_reports=0; unit_executed=0; unit_failed=0; unit_skipped=0
unit_rewritten=0; unit_read=0
integration_tests_ran=0; integration_reports=0; integration_executed=0
integration_failed=0; integration_skipped=0; integration_rewritten=0; integration_read=0
mutants_ran=0; mutants_killed=0; mutants_survived=0; mutants_no_coverage=0
mutants_equivalent=0; mutants_timeout=0; mutants_memory=0; mutants_run=0
mutants_rewritten=0; mutants_read=0

reference_ran=0
reference_summary=""

# The stage and the reason of the failure, when there is one.
gate_failed_stage=""
gate_failed_reason=""

# Whether this run measured less than the whole gate. A stage filter measures
# less than the whole just as a module filter does, and a run that measured
# less says so in the same words.
gate_is_subset=0
if [[ -n "$module_filter" || ${#test_filters[@]} -gt 0 || "$want_stage_upto" -lt 5 ]]; then
    gate_is_subset=1
fi

# --- The helpers ---------------------------------------------------------------

now_ms() { date +%s%3N; }

log()  { printf '\n=== %s\n' "$*"; }
elapsed() { printf '%s' "$(( $(date +%s) - $1 ))"; }

# The verdict of a run, on one line.
#
# One line, and every number on it is a number THIS run earned. A leg that did
# not run says NOT_RUN, because a silent field reads like a zero: a gate that
# skipped its tests and printed nothing about them is the same line as a gate
# that ran none and found none.
#
# A red run prints a verdict as well. The line that names the stage that is
# red, with the evidence of that stage, says more than a message that says
# something failed and leaves the reader to guess which: the stage that names
# the failure is the stage the reader has to open.
#
# A stage that did not run says NOT_RUN and not zero seconds. A stage that
# never started cost nothing, and a zero there reads as a stage that ran and
# was quick.

reference_field() {
    if [[ "$reference_ran" == 1 && -n "$reference_summary" ]]; then
        printf '%s' "$reference_summary"
    else
        printf 'reference=NOT_RUN'
    fi
}

tests_field() {
    if [[ "$gate_failed_stage" == "3" ]]; then
        printf 'tests=FAILED'
    elif [[ "$unit_tests_ran" == 1 ]]; then
        printf 'tests=executed:%d failed:%d skipped:%d rewritten:%d read:%d' \
            "$unit_executed" "$unit_failed" "$unit_skipped" "$unit_rewritten" "$unit_read"
    else
        printf 'tests=NOT_RUN'
    fi
}

integration_field() {
    if [[ "$gate_failed_stage" == "4" ]]; then
        printf 'integration=FAILED'
    elif [[ "$integration_tests_ran" == 1 ]]; then
        printf 'integration=executed:%d failed:%d skipped:%d rewritten:%d read:%d' \
            "$integration_executed" "$integration_failed" "$integration_skipped" \
            "$integration_rewritten" "$integration_read"
    else
        printf 'integration=NOT_RUN'
    fi
}

mutants_field() {
    if [[ "$gate_failed_stage" == "5" ]]; then
        printf 'mutants=FAILED'
    elif [[ "$mutants_ran" == 1 ]]; then
        printf 'mutants=run:%d killed:%d survived:%d rewritten:%d read:%d' \
            "$mutants_run" "$mutants_killed" "$mutants_survived" \
            "$mutants_rewritten" "$mutants_read"
    else
        printf 'mutants=NOT_RUN'
    fi
}

stages_field() {
    local parts="" n ran ms
    for n in 1 2 3 4 5; do
        case "$n" in
            1) ran=$stage1_ran; ms=$stage1_wall_ms ;;
            2) ran=$stage2_ran; ms=$stage2_wall_ms ;;
            3) ran=$stage3_ran; ms=$stage3_wall_ms ;;
            4) ran=$stage4_ran; ms=$stage4_wall_ms ;;
            5) ran=$stage5_ran; ms=$stage5_wall_ms ;;
        esac
        if [[ "$ran" == 1 ]]; then
            parts+=" $n:$(( ms / 1000 ))"
        else
            parts+=" $n:NOT_RUN"
        fi
    done
    printf 'stages=%s' "${parts# }"
}

print_verdict() {
    local status="$1"
    printf '\n'
    if [[ "$status" == "FAIL" ]]; then
        printf 'FAIL %s stage=%s reason="%s" %s %s %s %s %s\n' \
            "$(identity_fields)" "$gate_failed_stage" "$gate_failed_reason" \
            "$(reference_field)" "$(tests_field)" "$(integration_field)" \
            "$(mutants_field)" "$(stages_field)"
        return
    fi
    if [[ "$gate_is_subset" == 1 ]]; then
        printf 'PASS (subset) %s %s %s %s %s %s\n' \
            "$(identity_fields)" "$(reference_field)" "$(tests_field)" \
            "$(integration_field)" "$(mutants_field)" "$(stages_field)"
        printf 'filters in effect: module=%s test=%s stage=%s\n' \
            "${module_filter:-none}" "${test_filters[*]:-none}" "$want_stage_upto"
    else
        printf 'PASS %s %s %s %s %s %s\n' \
            "$(identity_fields)" "$(reference_field)" "$(tests_field)" \
            "$(integration_field)" "$(mutants_field)" "$(stages_field)"
    fi
}

# A red run names the stage that is red and prints the verdict of the run.
#
# The stage is the FIRST word of the message, so it can be read back from it:
# the verdict says which stage failed without the caller having to say it
# twice.
fail() {
    local rest="${1#stage }"
    gate_failed_stage="${rest%%[^0-9]*}"
    if [[ "$rest" == *": "* ]]; then
        gate_failed_reason="${rest#*: }"
    else
        gate_failed_reason="${rest#* }"
    fi
    case "$gate_failed_stage" in
        1) stage1_wall_ms=$(( $(now_ms) - stage1_started_ms )); stage1_ran=1 ;;
        2) stage2_wall_ms=$(( $(now_ms) - stage2_started_ms )); stage2_ran=1 ;;
        3) stage3_wall_ms=$(( $(now_ms) - stage3_started_ms )); stage3_ran=1 ;;
        4) stage4_wall_ms=$(( $(now_ms) - stage4_started_ms )); stage4_ran=1 ;;
        5) stage5_wall_ms=$(( $(now_ms) - stage5_started_ms )); stage5_ran=1 ;;
    esac
    printf 'verify-all: FAILED at %s\n' "$1" >&2
    print_verdict FAIL
    exit 1
}

# Whether the stage $1 is part of this run.
want_stage() {
    [[ "$1" -le "$want_stage_upto" ]]
}

# Whether the module $1 is asked for by the filter.
#
# An EMPTY filter asks for every module. A filter that names no module is a
# mistake in the invocation and not a green, and the stage says so.
module_selected() {
    local module="$1"
    if [[ -z "$module_filter" ]]; then
        return 0
    fi
    local pattern="${module_filter#:}"
    [[ "$module" == ":${pattern}" || "$module" == *":${pattern}" ]]
}

# The Gradle project path of a module directory.
gradle_path_of() { local p="${1#pipelinek/}"; printf ':%s' "${p//\//:}"; }

# The modules that have a test source, and the modules that have an
# integration-test source. The discovery is read from the filesystem, not
# assumed: a module is asked only if its source set holds a .kt file.
#
# A module with a test source that produces no JUnit XML fails the gate, so
# the enumeration here and the census below have to agree about which modules
# were asked for.
modules_with_unit_tests=()
modules_with_integration_tests=()
modules_with_mutants=()

discover_modules() {
    [[ "$modules_discovered" == 1 ]] && return 0
    modules_discovered=1
    modules_with_unit_tests=()
    modules_with_integration_tests=()
    modules_with_mutants=()

    local kt mod
    while IFS= read -r kt; do
        mod="${kt%%/src/test/*}"
        modules_with_unit_tests+=("$mod")
    done < <(find pipelinek -path '*/src/test/*' -name '*.kt' -not -path '*/build/*' \
        | LC_ALL=C sort | awk -F'/src/test/' '!seen[$1]++')

    while IFS= read -r kt; do
        mod="${kt%%/src/integrationTest/*}"
        modules_with_integration_tests+=("$mod")
    done < <(find pipelinek -path '*/src/integrationTest/*' -name '*.kt' -not -path '*/build/*' \
        | LC_ALL=C sort | awk -F'/src/integrationTest/' '!seen[$1]++')

    # The modules that DECLARE the mutation tool. The declaration is the
    # module's own build script, and it is the only authority: a module that
    # names no target mutates nothing, and a module that names one is asked
    # for it.
    while IFS= read -r build_file; do
        mod="${build_file%/build.gradle.kts}"
        if grep -q 'id("info.solidsoft.pitest")' "$build_file"; then
            modules_with_mutants+=("$mod")
        fi
    done < <(find pipelinek -name 'build.gradle.kts' -not -path '*/build/*' \
        | LC_ALL=C sort)
}
modules_discovered=0

# `--list` answers what the gate WOULD run and runs nothing. It is asked
# before the first stage so the answer costs no build and no test run.
if [[ "$list_only" == 1 ]]; then
    discover_modules
    printf 'the gate would run, in this order:\n'
    printf '  1. the checks that read only the tree\n'
    printf '  2. the build, the static analysis, the API check and the published reference\n'
    printf '  3. the unit tests of %d module(s)\n' "${#modules_with_unit_tests[@]}"
    printf '  4. the integration tests of %d module(s)\n' "${#modules_with_integration_tests[@]}"
    printf '  5. the mutants of %d module(s)\n' "${#modules_with_mutants[@]}"
    exit 0
fi

# The log directory of this run. It lives under the state's own directory so
# two runs on the same machine cannot collide, and it is deleted when the run
# ends.
run_dir="$(mktemp -d "${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/gate.XXXXXX" 2>/dev/null || true)"
if [[ -z "$run_dir" ]]; then
    run_dir="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-gate.XXXXXX")" || exit 1
fi
log_dir="$run_dir/logs"
mkdir -p "$log_dir"

cleanup_run_dir() {
    [[ -n "${run_dir:-}" && -d "$run_dir" ]] && rm -rf "$run_dir"
}
trap cleanup_run_dir EXIT

# --- Stage 1: the checks that read only the tree --------------------------------
#
# The stage runs seven checks that read ONLY the tree, and only then the Gradle
# invocation that builds the product and analyses it. The order is the point,
# and it is the reason this stage exists at all.
#
# The checks cost seconds between them and the work that RUNS the product
# costs minutes. A tree that breaks one of the cheap rules used to be told so
# only after all of that had been paid for -- the same defect, found minutes
# later, in a run that had already spent its time. A guard that reports late is
# a guard nobody waits for.

stage1_started_ms="$(now_ms)"

if want_stage 1; then
    stage1_ran=1
    log "stage 1/5  the checks that read only the tree"

    # --- The LOC census -------------------------------------------------------
    # AGENTS.md section 4's contract: VERIFY_LOC <= 2 * PRODUCT_LOC. The census
    # reads the Git index and the tracked files, so it needs no build output
    # and it measures the tree that is about to be built.
    if [[ "$skip_budget" == 1 ]]; then
        log "stage 1/5  LOC census SKIPPED by --no-budget"
    else
        if python3 scripts/check-budgets.py > "$log_dir/budget.out" 2>&1; then
            cat "$log_dir/budget.out"
        else
            cat "$log_dir/budget.out" >&2
            fail "stage 1: the LOC census"
        fi
    fi

    # --- The words a comment uses ---------------------------------------------
    # A comment that names its ticket, its document section, or its commit is
    # the comment that anchors the code to a process, not to its behaviour. The
    # guard reads Kotlin, `.kts` and `.sh` files, and walks the comment stream
    # tokenised by hand so a `//` inside a string literal stays a string.
    #
    # It does NOT go with the LOC census, and `--no-budget` does not skip it: a
    # budget is a contract, and the guard is a check on the words the source
    # says. Skipping it because the operator did not want the census of lines
    # would be the wrong reason to stop saying what the code does.
    if python3 scripts/check-comment-provenance.py > "$log_dir/comments.out" 2>&1; then
        log "stage 1/5  comment provenance"
        cat "$log_dir/comments.out"
    else
        cat "$log_dir/comments.out" >&2
        fail "stage 1: the comment provenance guard"
    fi

    # --- The published contracts carry their contract -------------------------
    # The `.api` files are the authority of the public surface, and a type
    # that a consumer can program against is a type whose contract a consumer
    # has to be able to read. The guard locates every type the seven
    # published contracts name in the sources of the module that publishes it
    # and asks whether a KDoc block states it; the types the compiler writes
    # (the file facades, the `DefaultImpls` holders, the companion the
    # serialization plugin adds) are listed and measured by nobody.
    #
    # The number is the point: a coverage that nobody re-reads is a
    # sentiment, and a receipt that only the author can reproduce is a
    # receipt that cannot be checked. The guard prints it per contract, and
    # the floor is the whole surface.
    if python3 scripts/check-kdoc-coverage.py > "$log_dir/kdoc.out" 2>&1; then
        log "stage 1/5  contract KDoc coverage"
        cat "$log_dir/kdoc.out"
    else
        cat "$log_dir/kdoc.out" >&2
        fail "stage 1: the contract KDoc coverage guard"
    fi

    # --- The KDoc names the exception the code throws --------------------------
    # A `@throws` is part of the contract of a function: it is what a consumer
    # reads to know which exception to expect and to catch. A `@throws` that
    # names an exception the code never produces is a promise the product does
    # not keep — the catcher waits for a class that never arrives, and the
    # exception that does arrive goes uncaught.
    #
    # The guard reads the tree and resolves the calls BY RECEIVER, so it can
    # tell a promise the code keeps from one it does not: a receiver it cannot
    # type resolves to nothing, because `connection.close()` is not the
    # project's `close()`. A promise it cannot check is COUNTED and not called
    # broken: a guard that accused the exceptions of the JDK of being false
    # would be a guard nobody reads.
    if python3 scripts/check-kdoc-throws.py > "$log_dir/throws.out" 2>&1; then
        log "stage 1/5  the exceptions the KDoc promises"
        cat "$log_dir/throws.out"
    else
        cat "$log_dir/throws.out" >&2
        fail "stage 1: the exception-promise guard"
    fi

    # --- Only the entry point may end the process ------------------------------
    # A helper that ends the process takes a decision that belongs to somebody
    # else, and the price is paid in the places that cannot be tested: the
    # helper cannot be called, so the code around it cannot be exercised, and
    # the exit code it invents is a literal rather than a case of the taxonomy
    # the CLI already declares. The guard reads the product sources, resolves
    # the function that holds each exit call, and fails when that function is
    # not the entry point.
    if python3 scripts/check-process-exits.py > "$log_dir/exits.out" 2>&1; then
        log "stage 1/5  process exits"
        cat "$log_dir/exits.out"
    else
        cat "$log_dir/exits.out" >&2
        fail "stage 1: the process-exit guard"
    fi

    # --- The durable path spends no probe and no fixed wait --------------------
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
    # wait is refused by name.
    if python3 scripts/check-durable-shell-cost.py > "$log_dir/durable.out" 2>&1; then
        log "stage 1/5  the durable path's costs"
        cat "$log_dir/durable.out"
    else
        cat "$log_dir/durable.out" >&2
        fail "stage 1: the durable-path cost guard"
    fi

    # --- The tree holds no layer that boots a product -------------------------
    # A layer that starts the product to certify it lives in a harness
    # repository, which certifies the published artifact and keeps the record
    # of that certification in its own board. This tree certifies itself with
    # three legs -- unit tests, integration tests with mocks and mutants -- so
    # it holds no scenario suite, no acceptance harness, no launcher of the
    # product under test, no receipt of a booted run and no executable
    # end-to-end program.
    #
    # The guard reads the tree and compares it with a closed declaration: an
    # executable program that the declaration does not name is a failure, and a
    # declared program that is no longer there is a failure too, so the list
    # cannot rot into a set of names that would allow whatever arrives next.
    # The one executable program that starts the product is the compatibility
    # gate of the publication, and the declaration says so with its reason.
    if python3 scripts/check-product-boot-layer.py > "$log_dir/boot-layer.out" 2>&1; then
        log "stage 1/5  the layer that boots a product"
        cat "$log_dir/boot-layer.out"
    else
        cat "$log_dir/boot-layer.out" >&2
        fail "stage 1: the product-boot layer guard"
    fi

    # --- Only src/main, src/test and src/integrationTest may hold Kotlin -------
    # AGENTS.md section 2 allows the unit lane and the integration lane and
    # forbids the rest. A bespoke lane named anything else is exactly that
    # rest: it is either dead code or a lane no gate enumerates, and an
    # earlier census looked only for `src/test`, so a seven-file
    # `src/pluginContractTest` source set sat in the tree for a whole cycle
    # with no gate noticing and no task compiling it. Enumerating only the
    # permitted names would repeat the blindness; so the forbidden names are
    # searched for by name instead.
    stray_lanes=()
    while IFS= read -r stray; do
        stray_lanes+=("$stray")
    done < <(find pipelinek -path '*/src/*' -name '*.kt' -not -path '*/build/*' \
             -not -path 'pipelinek/buildSrc/*' -not -path 'examples/*' \
             -not -path '*/src/main/*' -not -path '*/src/test/*' \
             -not -path '*/src/integrationTest/*' \
         | LC_ALL=C sort)
    if [[ ${#stray_lanes[@]} -gt 0 ]]; then
        printf '    a Kotlin source set exists outside src/test and src/integrationTest,\n' >&2
        printf '    which AGENTS.md section 2 forbids: it is either dead code or a\n' >&2
        printf '    lane no gate enumerates.\n' >&2
        printf '  %s\n' "${stray_lanes[@]}" >&2
        fail "stage 1: forbidden Kotlin source set"
    fi
    log "stage 1/5  forbidden Kotlin source sets"
fi

stage1_wall_ms=$(( $(now_ms) - stage1_started_ms ))

# --- Stage 2: the build, its static analysis, its API check and the reference ---
#
# ONE Gradle invocation, so the distribution is assembled exactly once and the
# static analysis and the API compatibility check see the same compiled bytes
# the tests will exercise.
#
# `detektTest` sits beside `detektMain` because they are the same analysis
# over two source sets, and it has to NAME them: detekt 2.x does not attach
# itself to `check`, and the bare `detekt` task carries no default source set,
# so it analyses zero files and reports success. The same reasoning applies to
# `detektIntegrationTest` for the integration lane.
#
# `apiCheck` is the public-API compatibility check, and it is wired into each
# published module's own `check`, so it is named here to run in the same
# invocation as the rest of the build.
#
# The review of the published reference comes AFTER that invocation, in a
# second one, and the reason is the measurement: the reference has to be
# generated again for this tree, and the generation has to be forced, because
# a generation the build holds emits no warning at all. Sharing the
# invocation would put the review's warnings in the build's log, where the
# count of them is one more number among many, and the cost of the review
# would be a difference between two runs instead of a number this run
# measured.

stage2_started_ms="$(now_ms)"

if want_stage 2; then
    stage2_ran=1
    log "stage 2/5  the build, the static analysis and the API check"

    gradle_tasks=(
        detektMain
        detektTest
        detektIntegrationTest
        apiCheck
        :pipeline-application:installDist
    )

    if ! ./pipelinek/gradlew -p pipelinek "${gradle_tasks[@]}" \
        > "$log_dir/stage2.log" 2>&1; then
        tail -40 "$log_dir/stage2.log" >&2
        fail "stage 2 (the tail of the stage log is above)"
    fi
    log "stage 2/5  the build, the static analysis and the API check"

    # --- The published reference: does every link resolve? --------------------
    # A published contract is read through its reference, and a link in that
    # reference which names a symbol the reader cannot open is a promise the
    # artifact does not keep. The generator emits a warning for each of those
    # links, and this check counts them and fails when there is one.
    #
    # The reference is the ONLY document a third party reads to learn the API,
    # so a broken link in it is a broken link in the product's own words. It
    # is here, in the gate that already reads the tree, and not in a review
    # somebody has to remember to run.
    if python3 scripts/check-published-reference.py > "$log_dir/reference.out" 2>&1; then
        reference_ran=1
        reference_summary="$(grep -m1 '^reference=' "$log_dir/reference.out" || true)"
        log "stage 2/5  the published reference"
        cat "$log_dir/reference.out"
    else
        reference_ran=1
        reference_summary="$(grep -m1 '^reference=' "$log_dir/reference.out" || true)"
        cat "$log_dir/reference.out" >&2
        fail "stage 2: the published reference"
    fi
fi

stage2_wall_ms=$(( $(now_ms) - stage2_started_ms ))

# --- The census of the JUnit XML reports ----------------------------------------
#
# Gradle's exit status is not the result. A Test task can report success with
# its classes skipped or absent, so the executed/failed/skipped counts are
# read from the JUnit XML that the task wrote.
#
# The census is scoped to the modules just asked for, deliberately. A global
# `find pipelinek -path '*/build/test-results/*'` also collects the XML that a
# suite deleted left behind, and a count whose set cannot be attributed to a
# requested task is not a measurement.
#
# The XML is written where the lane says it goes, so the unit reports and the
# integration reports cannot be counted as one another.
#
# The census does NOT force the lane to re-run. A Test task whose inputs have
# not changed is UP TO DATE, and the report it leaves behind is the report of
# that same run over those same inputs: re-running it would spend the
# delivery's time and would measure nothing new. What the census does instead
# is SAY which reports this run wrote and which it read from disk, so a reader
# knows what was measured now and what was inherited.
#
# A report whose test class no longer exists cannot be counted: any change to
# the sources changes the task's inputs, the task re-runs, and the task clears
# its own results directory before it writes.
census_reports() {
    local module="$1" lane="$2" results_dir="$3" since="$4"
    local reports=0 total=0 failed=0 skipped=0 rewritten=0 xml
    while IFS= read -r xml; do
        [[ -n "$xml" ]] || continue
        reports=$((reports + 1))
        if [[ "$(stat -c %Y "$xml")" -ge "$since" ]]; then
            rewritten=$((rewritten + 1))
        fi
        local line
        line="$(head -3 "$xml" | tr '\n' ' ')"
        local t f e s
        t=$(sed -nE 's/.*tests="([0-9]+)".*/\1/p' <<<"$line"); t=${t:-0}
        f=$(sed -nE 's/.*failures="([0-9]+)".*/\1/p' <<<"$line"); f=${f:-0}
        e=$(sed -nE 's/.*errors="([0-9]+)".*/\1/p' <<<"$line"); e=${e:-0}
        s=$(sed -nE 's/.*skipped="([0-9]+)".*/\1/p' <<<"$line"); s=${s:-0}
        total=$((total + t))
        failed=$((failed + f + e))
        skipped=$((skipped + s))
    done < <(find "$results_dir" -name 'TEST-*.xml' 2>/dev/null | LC_ALL=C sort)

    printf '%s %s %s %s %s %s' \
        "$reports" "$total" "$failed" "$skipped" "$rewritten" "$module"
}

# --- Stage 3: the permanent unit tests ------------------------------------------
#
# Every module runs its own :test. A module with no test source has nothing to
# prove, and Gradle reports a Test task over an empty source set as GREEN, so
# the modules that are actually asked are enumerated from the filesystem
# instead of assumed.

stage3_started_ms="$(now_ms)"
stage3_started_s=$(( stage3_started_ms / 1000 ))
silent_modules=()

if want_stage 3; then
    stage3_ran=1
    log "stage 3/5  the permanent unit tests"

    discover_modules
    unit_tasks=()
    for m in "${modules_with_unit_tests[@]}"; do
        module_selected "$(gradle_path_of "$m")" || continue
        unit_tasks+=("$(gradle_path_of "$m"):test")
    done

    if [[ ${#unit_tasks[@]} -eq 0 ]]; then
        if [[ ${#modules_with_unit_tests[@]} -eq 0 ]]; then
            echo "    no module has a unit test yet; nothing to run (NOT_RUN, not PASS)"
        else
            echo "    the module filter selected no module with a unit test" >&2
            fail "stage 3: the module filter selected nothing"
        fi
    else
        unit_tests_ran=1
        # The lane is NOT forced to re-run. Gradle already knows which test
        # tasks have to run because their inputs changed, and forcing the rest
        # would spend the delivery's time to measure what it already knows.
        # The census says which reports this run wrote and which it read.
        if [[ ${#test_filters[@]} -gt 0 ]]; then
            gradle_args=()
            for t in "${unit_tasks[@]}"; do
                gradle_args+=("$t")
                for p in "${test_filters[@]}"; do gradle_args+=(--tests "$p"); done
            done
        else
            gradle_args=("${unit_tasks[@]}")
        fi

        if ! ./pipelinek/gradlew -p pipelinek "${gradle_args[@]}" \
            > "$log_dir/stage3.log" 2>&1; then
            tail -40 "$log_dir/stage3.log" >&2
            fail "stage 3 (the tail of the stage log is above)"
        fi

        for m in "${modules_with_unit_tests[@]}"; do
            module_selected "$(gradle_path_of "$m")" || continue
            read -r reports total failed skipped rewritten _ \
                < <(census_reports "$m" unit "$m/build/test-results/test" "$stage3_started_s")
            unit_reports=$((unit_reports + reports))
            unit_executed=$((unit_executed + total))
            unit_failed=$((unit_failed + failed))
            unit_skipped=$((unit_skipped + skipped))
            unit_rewritten=$((unit_rewritten + rewritten))
            unit_read=$((unit_read + reports - rewritten))
            if [[ "$reports" -eq 0 ]]; then
                silent_modules+=("$m")
            fi
        done

        if [[ ${#silent_modules[@]} -gt 0 ]]; then
            printf '    a module with a unit test source produced no JUnit XML, so the\n' >&2
            printf '    tests in it did NOT run. Gradle reports SUCCESS over an empty\n' >&2
            printf '    result set, and a green over nothing is not a green.\n' >&2
            printf '  %s\n' "${silent_modules[@]}" >&2
            fail "stage 3: a module with tests executed none of them"
        fi

        log "stage 3/5  the permanent unit tests"
        printf '    %d reports, %d executed, %d failed, %d skipped, %d rewritten, %d read\n' \
            "$unit_reports" "$unit_executed" "$unit_failed" "$unit_skipped" \
            "$unit_rewritten" "$unit_read"
    fi
fi

stage3_wall_ms=$(( $(now_ms) - stage3_started_ms ))

# --- Stage 4: the integration tests ----------------------------------------------
#
# The integration lane composes two or more real modules with the world
# outside them faked. It is a lane of its own and it is reported as one: a
# number that merges the two would hide which leg produced it.

stage4_started_ms="$(now_ms)"
stage4_started_s=$(( stage4_started_ms / 1000 ))
silent_integration_modules=()

if want_stage 4; then
    stage4_ran=1
    log "stage 4/5  the integration tests"

    discover_modules
    integration_tasks=()
    for m in "${modules_with_integration_tests[@]}"; do
        module_selected "$(gradle_path_of "$m")" || continue
        integration_tasks+=("$(gradle_path_of "$m"):integrationTest")
    done

    if [[ ${#integration_tasks[@]} -eq 0 ]]; then
        if [[ ${#modules_with_integration_tests[@]} -eq 0 ]]; then
            echo "    no module has an integration test yet; nothing to run (NOT_RUN, not PASS)"
        else
            echo "    the module filter selected no module with an integration test" >&2
            fail "stage 4: the module filter selected nothing"
        fi
    else
        integration_tests_ran=1
        if ! ./pipelinek/gradlew -p pipelinek "${integration_tasks[@]}" \
            > "$log_dir/stage4.log" 2>&1; then
            tail -40 "$log_dir/stage4.log" >&2
            fail "stage 4 (the tail of the stage log is above)"
        fi

        for m in "${modules_with_integration_tests[@]}"; do
            module_selected "$(gradle_path_of "$m")" || continue
            read -r reports total failed skipped rewritten _ \
                < <(census_reports "$m" integration "$m/build/test-results/integrationTest" "$stage4_started_s")
            integration_reports=$((integration_reports + reports))
            integration_executed=$((integration_executed + total))
            integration_failed=$((integration_failed + failed))
            integration_skipped=$((integration_skipped + skipped))
            integration_rewritten=$((integration_rewritten + rewritten))
            integration_read=$((integration_read + reports - rewritten))
            if [[ "$reports" -eq 0 ]]; then
                silent_integration_modules+=("$m")
            fi
        done

        if [[ ${#silent_integration_modules[@]} -gt 0 ]]; then
            printf '    a module with an integration-test source produced no JUnit XML,\n' >&2
            printf '    so the tests in it did NOT run.\n' >&2
            printf '  %s\n' "${silent_integration_modules[@]}" >&2
            fail "stage 4: a module with integration tests executed none of them"
        fi

        log "stage 4/5  the integration tests"
        printf '    %d reports, %d executed, %d failed, %d skipped, %d rewritten, %d read\n' \
            "$integration_reports" "$integration_executed" "$integration_failed" \
            "$integration_skipped" "$integration_rewritten" "$integration_read"
    fi
fi

stage4_wall_ms=$(( $(now_ms) - stage4_started_ms ))

# --- Stage 5: the mutants ---------------------------------------------------------
#
# The mutation leg is SELECTIVE: it runs over the seams the mutated modules
# declare, and it is not a global sweep. A surviving mutant is a hole in the
# test, not a defect in the product, so the gate REPORTS them rather than
# failing over them: a mutation score is not a guarantee, and a gate that
# failed over a percentage would be a gate measuring the wrong thing.
#
# What the gate does refuse is a mutation leg that did not run while the
# modules that declare it were in scope. A mutant leg that reports nothing
# because the tool was missing is not a clean leg.

stage5_started_ms="$(now_ms)"
stage5_started_s=$(( stage5_started_ms / 1000 ))

if want_stage 5; then
    stage5_ran=1
    log "stage 5/5  the mutants"

    discover_modules
    mutant_tasks=()
    for m in "${modules_with_mutants[@]}"; do
        module_selected "$(gradle_path_of "$m")" || continue
        mutant_tasks+=("$(gradle_path_of "$m"):pitest")
    done

    if [[ ${#mutant_tasks[@]} -eq 0 ]]; then
        if [[ ${#modules_with_mutants[@]} -eq 0 ]]; then
            echo "    no module declares the mutation tool yet; nothing to run (NOT_RUN, not PASS)"
        else
            echo "    the module filter selected no module with mutants" >&2
            fail "stage 5: the module filter selected nothing"
        fi
    else
        mutants_ran=1
        # The mutation leg is NOT forced to re-run either. Gradle re-runs
        # pitest when the sources it mutates or the tests it runs change, and
        # the report it leaves behind is the measurement of this tree.
        if ! ./pipelinek/gradlew -p pipelinek "${mutant_tasks[@]}" \
            > "$log_dir/stage5.log" 2>&1; then
            tail -40 "$log_dir/stage5.log" >&2
            fail "stage 5 (the tail of the stage log is above)"
        fi

        for m in "${modules_with_mutants[@]}"; do
            module_selected "$(gradle_path_of "$m")" || continue
            report="$m/build/reports/pitest/mutations.xml"
            if [[ ! -f "$report" ]]; then
                printf '    the mutation report of %s is missing: the leg did not run.\n' "$m" >&2
                fail "stage 5: a module with mutants produced no mutation report"
            fi
            # A report this run did not write was written by an earlier
            # invocation over the same inputs, and it is said so rather than
            # passed off as a measurement that happened now.
            if [[ "$(stat -c %Y "$report")" -ge "$stage5_started_s" ]]; then
                mutants_rewritten=$((mutants_rewritten + 1))
            else
                mutants_read=$((mutants_read + 1))
            fi
            # The report is read once and counted by STATUS. A mutant is
            # KILLED when a relevant assertion went red, SURVIVED when the
            # tests passed over the change, and the three states that are
            # neither a kill nor a survival are counted apart: a mutant that
            # no test reached says nothing about the guard, and a mutant that
            # timed out says the test was too slow, not that the code was
            # right.
            counts="$(python3 - "$report" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
killed = len(re.findall(r"status='KILLED'", text))
survived = len(re.findall(r"status='SURVIVED'", text))
no_cov = len(re.findall(r"status='NO_COVERAGE'", text))
equiv = len(re.findall(r"status='(EQUIVALENT|TIMED_OUT|MEMORY_ERROR|NON_VIABLE)'", text))
timeout = len(re.findall(r"status='TIMED_OUT'", text))
memory = len(re.findall(r"status='MEMORY_ERROR'", text))
print(killed, survived, no_cov, equiv, timeout, memory)
PY
)"
            read -r k s nc eq tmo mem <<< "$counts"
            mutants_killed=$((mutants_killed + k))
            mutants_survived=$((mutants_survived + s))
            mutants_no_coverage=$((mutants_no_coverage + nc))
            mutants_equivalent=$((mutants_equivalent + eq))
            mutants_timeout=$((mutants_timeout + tmo))
            mutants_memory=$((mutants_memory + mem))
            mutants_run=$((mutants_run + k + s + nc + eq))
        done

        log "stage 5/5  the mutants"
        printf '    %d mutants: %d killed, %d survived, %d no coverage, %d equivalent, %d timed out\n' \
            "$mutants_run" "$mutants_killed" "$mutants_survived" "$mutants_no_coverage" \
            "$mutants_equivalent" "$mutants_timeout"
        printf '    %d mutation reports: %d rewritten by this run, %d read from disk\n' \
            "$((mutants_rewritten + mutants_read))" "$mutants_rewritten" "$mutants_read"
        if [[ "$mutants_survived" -gt 0 ]]; then
            printf '    a surviving mutant is a hole in the test that was supposed to\n'
            printf '    kill it. Strengthen the cheapest assertion that failed to, or\n'
            printf '    record why the mutant is equivalent. Do not exclude it to make\n'
            printf '    the number look better.\n'
        fi
    fi
fi

stage5_wall_ms=$(( $(now_ms) - stage5_started_ms ))

print_verdict PASS

