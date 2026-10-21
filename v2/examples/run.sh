#!/usr/bin/env bash
# The ONE E2E launcher (AGENTS.md section 3).
#
# It runs real .pipeline.kts programs against the compiled distribution. It is
# deliberately not a framework: it discovers scenarios, gives each one a
# private control root, checks the exit code, and cleans up. It has no notion of
# assertions beyond two optional per-pipeline directives, so a scenario cannot
# grow into a test suite with its own vocabulary.
#
#   //@ expect-exit 1                 default is 0
#   //@ expect-stderr <substring>     the refusal must be this specific, not
#                                     merely a nonzero exit. REPEATABLE: every
#                                     declared substring must be present, and
#                                     declaring it twice is not "the last one
#                                     wins" — see the note below.
#   //@ durable-once                  run ONCE against a real durable store
#   //@ durable                       run twice against one control root and
#                                     require the second run not to repeat the
#                                     effect; the pipeline itself asserts the
#                                     count, this only re-runs it
#   //@ resume                        the FIRST run is expected to DIE where the
#                                     scenario chooses, and the SECOND run resumes
#                                     the same run over the same store. The
#                                     verdict is the RESUMED run's: its exit code
#                                     is the declared one (not forced to 0), and
#                                     the declared stderr is looked for in the
#                                     RESUMED run, because the first one died
#                                     before it could say anything. The world
#                                     must not change between the two: a resumed
#                                     run that repeats the effect is the bug this
#                                     directive exists to catch.
#   //@ requires-seeded-credentials   NOT_RUN: the scenario needs a credential
#                                     store seeded in advance, and the product
#                                     offers no non-interactive way to seed one
#                                     (`credentials add` prompts on the TTY).
#                                     It is reported as not executed, which is
#                                     neither a pass nor a failure.
#
# A directive the launcher does not know is a FAILURE, not a silence. A typo
# that is ignored is the worst kind of defect here, because it does not look
# like one: a scenario that meant `//@ durable` and wrote `//@ durabel` used to
# run against a volatile store and PASS, having never tested what it claims.
# The same reasoning applies to the rest of the verdict:
#
#   - ZERO scenarios is a failure. Without a filter there used to be no check
#     at all, so an empty discovery printed "0 scenario(s), 0 passed, 0 failed"
#     and exited 0 — the exact shape of a green. A tree of examples that moved
#     left the gate green having executed NOTHING.
#   - ZERO scenarios EXECUTED is a failure. A run in which every selected
#     scenario was NOT_RUN has measured nothing, and "0 failed" about it would
#     be the same false green.
#   - `expect-exit` is the scenario's own claim, in every mode. It used to be
#     overwritten with 0 in `durable` mode, so a scenario could not say what it
#     expected of a second run.
#   - `resume` needs a real DEATH. A shell reports a process killed by a signal
#     as 128+signal (SIGKILL -> 137). A plain non-zero exit is not a death: a
#     script that fails to COMPILE exits 1, and accepting that as a crash let
#     a scenario that never crashed pass as one that did.
#
# The distribution must already exist:
#     cd v2 && ./gradlew :pipeline-application:installDist
#
# One flag, for the launcher itself:
#
#   run.sh --self-test    check the launcher's own contract and exit
#
# It is the cheapest instrument that can fail for the guarantee that matters
# most here: that a verdict means something. The gate runs it before it
# trusts this program with a single scenario, because every scenario below is
# judged by it.
#
# The scenarios run in PARALLEL, and they were built for it: each one gets its
# own root, its own working directory, its own credential store, its own
# durable database and its own control root, so two scenarios never share a
# resource. That isolation was not incidental -- a scenario that used a fixed
# `/tmp` path would race with every other run on the machine, which is
# exactly why the launcher hands each one a private directory.
#
#   PIPELINEK_E2E_JOBS=1    one scenario at a time (the old serial behaviour)
#   PIPELINEK_E2E_JOBS=8    eight at a time
#
# The default is `min(nproc, 8)`: the number of cores, capped, because a
# scenario starts a JVM and a machine that is already busy should not be made
# to thrash. The knob is the escape hatch: a scenario that looks flaky is
# re-run serially, and the report is identical either way -- the verdicts are
# written per scenario and printed in scenario order, not in completion order.
#
#   PIPELINEK_E2E_SCENARIO_TIMEOUT=120  the ceiling for ONE pipeline run of a
#                                       scenario, in seconds. A scenario that
#                                       hangs is KILLED and reports a red
#                                       verdict of its own, with its name and
#                                       its reason, and the other scenarios
#                                       keep running.
#
# This knob exists because of the worst way a gate can fail. The launcher
# prints its report at the END, so a scenario that hung left the stage log
# EMPTY and the verdict line never arrived: nobody could say what happened,
# and the run had to be killed from outside. Measured on this tree: a run of
# the gate hung for more than 1400 s in stage 3 with an empty stage log, and
# only an EXTERNAL `timeout` ended it; the next run, over the SAME tree,
# finished in 78 s. The defect was in the harness, not in the product.
#
# The ceiling is per RUN, not per scenario: a scenario may run the pipeline up
# to three times (durable, resume, storage-agnostic), and each of those runs
# is where a hang can happen. The default leaves the current scenarios alone
# -- it is above the slowest one by a wide margin -- and 0 disables the
# ceiling explicitly, which is the escape hatch for diagnosing a slow
# scenario.
#
# Why `//@` and not `#!`. An earlier revision read directives from `#!` comment
# lines, on the assumption that the product supported them. It does not: no
# source under any `src/main` reads `#!`. The convention only ever worked by
# accident, because the Kotlin script compiler strips a shebang from the FIRST
# line of a file. That accident is a one-line budget, and it was spent wrongly:
# two `#!` lines make the second one an ordinary syntax error, so a scenario
# carrying both `expect-exit` and `expect-stderr` failed to COMPILE with
# "Syntax error: Expecting an element" instead of failing for its own reason.
# Measured, not assumed: two `#!` lines give VALIDATION FAILED; the same two as
# `//` comments give VALIDATION SUCCESSFUL.
#
# `//@` is an ordinary Kotlin comment, so it is position-independent, repeatable
# and always legal. The `@` is load-bearing rather than decorative: real
# scenarios already carry prose comments such as
# "// durable execution over the journal", so a bare `//` prefix would let a
# sentence in a comment silently switch a directive on.
#
# Why `expect-stderr` is REPEATABLE, and every one of them must hold. It used to
# be "the last one wins": a scenario that declared two of them silently lost the
# first, and the second one was the only thing the verdict ever looked at. That is
# a directive that changes nothing, which is the exact defect the Semantic
# Constitution §2 forbids. Worse, it made a whole class of scenario unable to say
# what it meant: a run that is EXPECTED to fail carries two independent truths on
# stderr — why the run failed, and what the engine concluded — and a scenario
# that can only assert one of them has to choose which fact goes unchecked.
#
# With every one required, a scenario asserts both, and a scenario whose OWN check
# fails is still caught: the failing check replaces the run's cause on stderr, so
# the cause substring disappears and the verdict notices. A scenario that expects
# the run to fail therefore does not need a second exit code to tell "the domain
# failed" from "my check failed" — the cause says which.

set -Eeuo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$here/../.." && pwd)"
binary="$repo_root/v2/pipeline-application/build/install/pipelinek/bin/pipelinek"

# --- The launcher's own contract ---------------------------------------------
# These three are here, and not inline in the scenario body, because the
# contract they express underwrites EVERY other scenario's verdict: if a
# mistyped directive is silently ignored, a scenario can pass without having
# tested what it claims to test. A guarantee that only lives in a mutation
# somebody ran once is a guarantee a refactor can lose without anybody
# noticing, so the launcher checks itself with `--self-test`.

# Parse the directive lines of a scenario. Reads them on stdin, sets the
# globals (which the caller may have declared `local`: bash scoping is
# dynamic, so the assignment lands on the caller's binding), and returns 1
# with the reason in `directive_error` when a directive is not one this
# launcher knows.
parse_directives() {
    local declared_total="${1:-0}"
    directive_error=""
    expect_exit=0
    expect_stderr=()
    durable=0
    durable_once=0
    resume=0
    storage_agnostic=0
    needs_seeded_credentials=0

    # More directives than the launcher reads is a SILENT TRUNCATION: the ones
    # past the limit would be ignored exactly like an unknown one, so the limit
    # is checked instead of assumed.
    if (( declared_total > 20 )); then
        directive_error="$declared_total directives declared; the launcher reads at most 20"
        return 1
    fi

    local directive
    while IFS= read -r directive; do
        [[ -n "$directive" ]] || continue
        case "$directive" in
            '//@ expect-exit '*)   expect_exit="${directive#//@ expect-exit }" ;;
            '//@ expect-stderr '*) expect_stderr+=("${directive#//@ expect-stderr }") ;;
            '//@ durable')         durable=1 ;;
            '//@ durable-once')    durable_once=1 ;;
            '//@ resume')          resume=1 ;;
            '//@ storage-agnostic') storage_agnostic=1 ;;
            '//@ requires-seeded-credentials') needs_seeded_credentials=1 ;;
            *) directive_error="unknown directive: $directive"; return 1 ;;
        esac
    done

    # An exit code that is not a number cannot be compared with one, and a
    # scenario whose expectation cannot be read has no verdict.
    if [[ ! "$expect_exit" =~ ^[0-9]+$ ]]; then
        directive_error="expect-exit needs a number, got: $expect_exit"
        return 1
    fi
    expect_exit=$((10#$expect_exit))
    return 0
}

# A death is a process killed by a signal: the shell reports it as
# 128+signal (SIGKILL -> 137). A plain non-zero exit is NOT a death -- a
# script that fails to COMPILE exits 1 -- and accepting that as a crash let a
# scenario that never crashed pass as one that did.
is_a_death() {
    local code="${1:-0}"
    (( code > 128 ))
}

# --- The ceiling --------------------------------------------------------------
#
# The exit code GNU timeout uses when it stopped the command itself. The
# product never returns it: the CLI exits 0 or 1 and nothing else, so 124
# means exactly one thing -- the ceiling was reached.
TIMEOUT_EXIT=124

# How long a killed run is given to die politely before it is shot. A run that
# ignores SIGTERM must not hold the stage hostage.
TIMEOUT_KILL_GRACE=10

# Now, in milliseconds. The ceiling is compared in milliseconds because a run
# that is stopped AT the ceiling has to be distinguishable from a run that
# finished just under it, and at second granularity those are the same number.
# %3N is a GNU extension; without it the comparison falls back to seconds,
# which is still correct -- only the resolution of the comparison is lost.
now_ms() {
    local now
    now="$(date +%s%3N 2>/dev/null || true)"
    if [[ "$now" =~ ^[0-9]+$ ]]; then
        printf '%s' "$now"
    else
        printf '%s' "$(( $(date +%s) * 1000 ))"
    fi
}

# Run one command under a ceiling expressed in milliseconds. 0 means no
# ceiling at all.
run_under_ceiling() {
    local ceiling_ms="$1"; shift
    if (( ceiling_ms <= 0 )); then
        "$@"
        return $?
    fi
    # GNU timeout does NOT know milliseconds: it takes s, m, h or d. The
    # ceiling is kept in milliseconds because a run stopped AT the ceiling
    # has to be distinguishable from one that finished just under it, so the
    # conversion to seconds carries the decimals instead of rounding them off.
    local seconds
    seconds="$(printf '%d.%03d' "$(( ceiling_ms / 1000 ))" "$(( ceiling_ms % 1000 ))")"
    timeout --kill-after="${TIMEOUT_KILL_GRACE}s" "${seconds}s" "$@"
}

# Whether a run was stopped BY THE CEILING. The exit code alone cannot say:
# GNU timeout returns 124 when the command dies on SIGTERM, but when the
# command IGNORES SIGTERM and --kill-after has to shoot it, the exit code is
# 137 -- the very same 137 a scenario's own SIGKILL produces, and the
# launcher already gives that one a meaning. The elapsed time tells the two
# apart: only a run that REACHED the ceiling was stopped by it.
timed_out() {
    local code="$1" elapsed_ms="$2" ceiling_ms="$3"
    if (( ceiling_ms <= 0 )); then
        return 1
    fi
    if [[ "$code" == "$TIMEOUT_EXIT" ]]; then
        return 0
    fi
    if (( elapsed_ms >= ceiling_ms )); then
        return 0
    fi
    return 1
}

# How long the last measured run took, in milliseconds.
last_run_ms=0

# Run one pipeline invocation under the ceiling, and MEASURE it. The elapsed
# time is what tells "the ceiling stopped this run" apart from "this run died
# on its own", so it is measured here, where the run happens.
run_measured() {
    local ceiling_ms="$1"; shift
    local started ended code=0
    started="$(now_ms)"
    run_under_ceiling "$ceiling_ms" "$@" || code=$?
    ended="$(now_ms)"
    last_run_ms=$(( ended - started ))
    return "$code"
}

# The ceiling for one pipeline run, in seconds. Read by a function and not
# inline, so the launcher can check its own reading of it: a knob that
# silently reads a wrong value is a ceiling that never fires.
timeout_knob_error=""
scenario_timeout_s=0
parse_timeout_knob() {
    timeout_knob_error=""
    local raw="${1:-}"
    if [[ ! "$raw" =~ ^[0-9]+$ ]]; then
        timeout_knob_error="PIPELINEK_E2E_SCENARIO_TIMEOUT needs a number of seconds, got: $raw"
        return 1
    fi
    scenario_timeout_s=$(( 10#$raw ))
    return 0
}

# A scenario that hit the ceiling is a FAILURE with a reason, not a silence.
# The reason names the scenario and the ceiling, because "the run stopped" is
# only actionable if you know WHICH run stopped and after how long.
timeout_failure() {
    local label="$1" name="$2" ceiling_ms="$3" stderr_file="$4"
    printf 'FAIL %-52s timed out after %ss: the run was killed, not finished\n' \
        "$label" "$(( ceiling_ms / 1000 ))"
    if [[ -s "$stderr_file" ]]; then
        sed 's/^/       | /' "$stderr_file" | head -20
    fi
    verdict_fail "$name: timed out after $(( ceiling_ms / 1000 ))s"
}

self_check() {
    local description="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then
        printf 'ok   self-test: %s\n' "$description"
        return 0
    fi
    printf 'FAIL self-test: %s (expected: %s, got: %s)\n' "$description" "$expected" "$actual" >&2
    return 1
}

# The launcher checks its own contract. Cheap (milliseconds), permanent, and
# in the same file as the code it protects: one authority, no second harness.
self_test() {
    local failures=0
    local expect_exit expect_stderr durable durable_once resume storage_agnostic needs_seeded_credentials
    local directive_error

    # A valid set of directives is understood, and the flags are not confused
    # with each other.
    directive_error=""
    parse_directives 4 <<'EOF' || true
//@ expect-exit 1
//@ expect-stderr algo
//@ durable-once
//@ storage-agnostic
EOF
    self_check "expect-exit is read" "1" "$expect_exit" || failures=$((failures + 1))
    self_check "expect-stderr is read" "1" "${#expect_stderr[@]}" || failures=$((failures + 1))
    self_check "durable-once is read" "1" "$durable_once" || failures=$((failures + 1))
    self_check "durable is NOT confused with durable-once" "0" "$durable" || failures=$((failures + 1))
    self_check "storage-agnostic is read" "1" "$storage_agnostic" || failures=$((failures + 1))
    self_check "storage-agnostic is NOT confused with resume" "0" "$resume" || failures=$((failures + 1))
    self_check "a valid set has no error" "" "$directive_error" || failures=$((failures + 1))

    # An unknown directive is REFUSED. This is the one that used to be a
    # silence, and a silence here is how a scenario passes without testing
    # anything.
    directive_error=""
    parse_directives 1 <<'EOF' || true
//@ durabel
EOF
    self_check "an unknown directive is refused" "refused" \
        "$([[ -n "$directive_error" ]] && printf refused || printf accepted)" || failures=$((failures + 1))

    # An exit code that is not a number is refused.
    directive_error=""
    parse_directives 1 <<'EOF' || true
//@ expect-exit abc
EOF
    self_check "a non-numeric expect-exit is refused" "refused" \
        "$([[ -n "$directive_error" ]] && printf refused || printf accepted)" || failures=$((failures + 1))

    # More directives than the launcher reads is refused, not truncated.
    directive_error=""
    parse_directives 21 <<'EOF' || true
//@ durable
EOF
    self_check "too many directives are refused" "refused" \
        "$([[ -n "$directive_error" ]] && printf refused || printf accepted)" || failures=$((failures + 1))

    # A death is a signal, not a normal exit.
    self_check "137 (SIGKILL) is a death" "yes" \
        "$(is_a_death 137 && printf yes || printf no)" || failures=$((failures + 1))
    self_check "143 (SIGTERM) is a death" "yes" \
        "$(is_a_death 143 && printf yes || printf no)" || failures=$((failures + 1))
    self_check "1 (a compile error) is not a death" "no" \
        "$(is_a_death 1 && printf yes || printf no)" || failures=$((failures + 1))
    self_check "0 is not a death" "no" \
        "$(is_a_death 0 && printf yes || printf no)" || failures=$((failures + 1))

    # The ceiling knob is read, and a knob that is not a number is refused.
    # A knob that silently reads a wrong value is a ceiling that never fires,
    # which is the defect this whole mechanism exists to remove.
    scenario_timeout_s=0
    parse_timeout_knob 120 || true
    self_check "the scenario timeout is read" "120" "$scenario_timeout_s" || failures=$((failures + 1))
    parse_timeout_knob abc || true
    self_check "a non-numeric scenario timeout is refused" "refused" \
        "$([[ -n "$timeout_knob_error" ]] && printf refused || printf accepted)" || failures=$((failures + 1))

    # A run that REACHES the ceiling is a timeout, and it is recognised as
    # one. The exit code alone cannot say it: a command that ignores SIGTERM
    # and has to be shot ends in 137, the same code a scenario's own SIGKILL
    # produces, and the launcher already gives that one a meaning. This check
    # exercises exactly that pair -- the ceiling fires, and the launcher calls
    # it a timeout rather than a death.
    local started ended elapsed_ms code=0
    started="$(now_ms)"
    run_under_ceiling 200 sleep 5 || code=$?
    ended="$(now_ms)"
    elapsed_ms=$(( ended - started ))
    self_check "a run that hits the ceiling is a timeout" "yes" \
        "$(timed_out "$code" "$elapsed_ms" 200 && printf yes || printf no)" || failures=$((failures + 1))

    # And a run that finishes INSIDE the ceiling is not a timeout, even when
    # it fails: a scenario that fails for its own reason must not be reported
    # as hung, or the launcher would replace one honest failure with another
    # honest failure that says less.
    code=0
    started="$(now_ms)"
    run_under_ceiling 5000 bash -c 'exit 1' || code=$?
    ended="$(now_ms)"
    elapsed_ms=$(( ended - started ))
    self_check "a run that fails inside the ceiling is not a timeout" "no" \
        "$(timed_out "$code" "$elapsed_ms" 5000 && printf yes || printf no)" || failures=$((failures + 1))

    # A ceiling of 0 means no ceiling, and a run under it is not a timeout
    # however long it took. This is the escape hatch for diagnosing a slow
    # scenario, so it has to mean what it says: not even the exit code 124
    # becomes a timeout when there is no ceiling to reach.
    self_check "with no ceiling, no run is a timeout" "no" \
        "$(timed_out 124 999999 0 && printf yes || printf no)" || failures=$((failures + 1))

    if (( failures > 0 )); then
        printf 'self-test: %s check(s) failed\n' "$failures" >&2
        return 1
    fi
    printf 'self-test: the launcher contract holds\n'
    return 0
}

if [[ "${1:-}" == "--self-test" ]]; then
    self_test
    exit $?
fi

if [[ ! -x "$binary" ]]; then
    echo "run.sh: no distribution at $binary" >&2
    echo "  build it first: cd v2 && ./gradlew :pipeline-application:installDist" >&2
    exit 2
fi

cycle_root="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-e2e-XXXXXXXX")"
cleanup() { rm -rf "$cycle_root"; }
trap cleanup EXIT

# The verdict of a scenario travels as TEXT, not as a counter. A parallel run
# executes each scenario in a SUBSHELL -- it has to, because a scenario
# exports its own environment (passphrase, credential store, private home, PID
# file) and an exported variable in a parallel run would leak into the next
# scenario -- and a counter incremented inside that subshell dies with it.
# The parent counts the markers below and prints the human lines in scenario
# order, so the report is the same shape as the sequential one.
verdict_ok() {
    printf '__VERDICT__ ok\n'
}

verdict_not_run() {
    printf '__VERDICT__ not_run\n'
}

verdict_fail() {
    # The reason travels with the verdict: the parent's array of failures is
    # not reachable from the subshell that produced it.
    printf '__VERDICT__ fail\n'
    printf '__FAILURE__ %s\n' "$1"
}

# The name a scenario is known by, and the private directory it gets. Both
# derive from the path relative to this directory, so two scenarios can never
# share a root.
scenario_slug() {
    printf '%s' "${1#"$here"/}" | tr '/.' '--'
}

passed=0
failed=0
not_run=0
failures=()

# A content-sensitive picture of a directory: its files, sorted, with their
# digests. Two pictures that differ mean the world changed in between, which
# for a resumed run means a completed effect happened twice.
workspace_snapshot() {
    local dir="$1"
    if [[ ! -d "$dir" ]]; then
        printf '(no directory)\n'
        return 0
    fi
    (
        cd "$dir" || exit 0
        find . -type f -print0 \
            | LC_ALL=C sort -z \
            | xargs -0 -r sha256sum
    ) 2>/dev/null || true
}

# Whether ONE run met the scenario's declared expectations: the exit code AND
# every declared stderr line, not only the last one.
#
# A helper because a scenario can be run in more than one world (P6), and the
# rule has to be the SAME rule in each of them: checking one world with the
# real code and the other with a copy of it is how two worlds drift apart.
expectations_met() {
    local run_code="$1" run_stderr="$2"
    local wanted
    [[ "$run_code" == "$expect_exit" ]] || return 1
    for wanted in ${expect_stderr[@]+"${expect_stderr[@]}"}; do
        grep -qF -- "$wanted" "$run_stderr" || return 1
    done
    return 0
}

# One scenario, fully isolated. Nothing is read from the developer's HOME and
# nothing is written outside $cycle_root.
run_scenario() {
    local script="$1"
    local relative="${script#"$here"/}"
    local name="$relative"
    local slug
    slug="$(scenario_slug "$script")"

    local expect_exit=0
    local expect_stderr=()
    local durable=0
    local durable_once=0
    local resume=0
    local storage_agnostic=0
    local memory_code=0
    local needs_seeded_credentials=0
    local directive_error=""

    # The contract lives in `parse_directives`, and so does the refusal: a
    # directive the launcher does not know is a typo, and a typo that is
    # IGNORED is the worst kind of defect here, because it does not look like
    # one. A scenario that meant `//@ durable` and wrote `//@ durabel` would
    # run against a volatile store and pass, having never tested what it
    # claims to test.
    local directive_total
    directive_total="$(grep -c '^//@ ' "$script" || true)"
    # Process substitution, NOT a pipe: a pipeline runs its last command in a
    # SUBSHELL, and every assignment `parse_directives` makes would die with
    # it -- the directives would be parsed and thrown away, and the scenario
    # would silently run with the defaults. That is the very failure this
    # function exists to prevent, wearing a different hat.
    if ! parse_directives "$directive_total" < <(grep -m 21 '^//@ ' "$script" || true); then
        printf 'FAIL %-52s %s\n' "$name" "$directive_error"
        if [[ "$directive_error" == "unknown directive: "* ]]; then
            printf '       known: expect-exit, expect-stderr, durable, durable-once, resume, requires-seeded-credentials\n'
        fi
        verdict_fail "$name: $directive_error"
        return
    fi

    # The label of this scenario, computed BEFORE anything runs: a scenario
    # that hits the ceiling has to name itself, and the name of a scenario
    # that ran twice is not the name of a scenario that ran once.
    local label="$name"
    if [[ "$durable" == 1 ]]; then
        label="$name (durable x2)"
    elif [[ "$durable_once" == 1 ]]; then
        label="$name (durable x1)"
    elif [[ "$resume" == 1 ]]; then
        label="$name (resume x2)"
    elif [[ "$storage_agnostic" == 1 ]]; then
        label="$name (both storages)"
    fi

    if [[ "$needs_seeded_credentials" == 1 ]]; then
        # Declared up front, before any execution, so the verdict cannot depend
        # on a run whose outcome was never observed. NOT_RUN is not a pass:
        # it is the honest third state, and it keeps the scenario in the
        # inventory so it cannot be forgotten the way a deleted file can.
        printf 'NOT_RUN %-50s needs a credential store seeded on a TTY\n' "$name"
        verdict_not_run
        return
    fi

    local root="$cycle_root/$slug"
    # A private working directory per scenario, entered with `cd`, so a step
    # that writes a relative path cannot land in the repository. AGENTS.md
    # section 3 asks for one temporary directory per example; --isolated and
    # --control-root already isolate the pipeline's own workspace, and this
    # isolates the process CWD the pipeline is launched from.
    mkdir -p "$root/cwd"

    # A per-scenario passphrase. The credentials gate refuses before any step
    # runs, even for a pipeline that never binds a credential, so a scenario
    # that did not set one would fail on a door rather than on its subject.
    # This value is generated for this scenario and never leaves the process.
    export PIPELINE_STORE_PASSPHRASE="e2e-${slug}-$$"

    # Pin the credentials store inside this scenario's own root. Without this,
    # `CredentialsStorePath.fromEnvironment` falls back to the real
    # ~/.pipeline/credentials.bin, so a scenario silently reads the
    # developer's own credential store: the run is then neither reproducible
    # nor hermetic, and its verdict depends on the machine. Pinned, the store
    # does not exist, the credential executor is not composed, and any scenario
    # that actually needs one says so instead of borrowing a stranger's.
    export PIPELINE_CREDENTIALS_STORE="$root/creds.bin"

    # The scenario's own private directory, absolute, and unique per scenario.
    # A scenario that needs a resource that SURVIVES between two runs cannot
    # use a relative path: `--isolated` gives every run a fresh managed
    # scratch workspace, and a fixed absolute path under /tmp is shared with
    # every other run on the machine (and with whatever else runs next).
    # This directory is removed with the scenario, so nothing leaks.
    export PIPELINEK_E2E_HOME="$root/cwd"

    local stdout="$root/stdout.txt" stderr="$root/stderr.txt" code=0

    # A durable scenario needs a REAL durable store. Without `--db` the CLI
    # runs in memory: there is no journal, nothing to resume, and the second
    # run simply repeats the first one's effects. The `//@ durable` directive
    # was therefore a promise the launcher could not keep — it re-ran the
    # script, but against a volatile store, so "the effect did not repeat"
    # was never actually on trial. One store, two runs, one control root: the
    # second run RESUMES the first one.
    # `durable-once` is the same promise as `durable` with ONE difference: the
    # scenario is not re-run over the same control root, so the workspace is
    # not compared between two runs.
    #
    # It exists because a Saga legitimately changes the world in its FIRST run
    # and must NOT change it in the second: the compensation happens once, and
    # a resumed run that compensated again would be the bug. The workspace
    # comparison of `durable` cannot express that, because the two runs
    # SHOULD leave different worlds. What such a scenario asserts is inside
    # the script, against the state the first run left.
    local db_args=()
    if [[ "$durable" == 1 || "$durable_once" == 1 || "$resume" == 1 || "$storage_agnostic" == 1 ]]; then
        db_args=(--db "$root/durable.sqlite")
    fi

    # A scenario that kills its own process needs to know WHICH process to kill,
    # and the launcher is the only one that can say where to put that answer.
    # The file lives beside the store, NOT in the scenario's working directory:
    # the working directory is compared between the two runs, and a PID file
    # would differ between them (two processes, two PIDs) and look like "the
    # effect repeated".
    if [[ "$resume" == 1 ]]; then
        export PIPELINEK_E2E_PID_FILE="$root/runner.pid"
    fi

    # `cd` in this shell, not a subshell: a subshell would discard the exit
    # code the verdict is built from, and every scenario would read as ok.
    local caller_pwd="$PWD"
    cd "$root/cwd"
    run_measured "$scenario_ceiling_ms" "$binary" run --isolated --control-root "$root/control" "${db_args[@]}" "$script" \
        >"$stdout" 2>"$stderr" || code=$?
    cd "$caller_pwd"

    # A run that reached the ceiling is a scenario of its own, in red, with
    # its reason. Without this check the launcher would say "exit 124,
    # expected 0" -- true, but it says nothing about WHY, and the reader
    # would have to guess between a scenario that failed and a scenario that
    # hung. Worse, a scenario that hung would be reported as a plain
    # failure, and the ONE thing the operator needs -- that it hung -- would
    # be missing.
    if timed_out "$code" "$last_run_ms" "$scenario_ceiling_ms"; then
        timeout_failure "$label" "$name" "$scenario_ceiling_ms" "$stderr"
        return
    fi

    # P6: the same script has to behave the same WITH and WITHOUT a durable
    # store. The run above was the DURABLE one; this is the VOLATILE one, with
    # its own control root AND its own working directory: the volatile run must
    # not see what the durable run left behind, or it would be testing the
    # leftovers instead of the behaviour.
    #
    # The second run is NOT a resume: it asks for a fresh run in a world with
    # no journal, which is the world `--db` decides.
    if [[ "$storage_agnostic" == 1 ]]; then
        mkdir -p "$root/cwd-memory"
        export PIPELINEK_E2E_HOME="$root/cwd-memory"
        cd "$root/cwd-memory"
        run_measured "$scenario_ceiling_ms" "$binary" run --isolated --control-root "$root/control-memory" "$script" \
            >"$root/stdout.memory.txt" 2>"$root/stderr.memory.txt" || memory_code=$?
        cd "$caller_pwd"
        export PIPELINEK_E2E_HOME="$root/cwd"

        # The volatile run has its own ceiling: it is a different run of the
        # pipeline, and it can hang on its own.
        if timed_out "$memory_code" "$last_run_ms" "$scenario_ceiling_ms"; then
            timeout_failure "$label" "$name" "$scenario_ceiling_ms" "$root/stderr.memory.txt"
            return
        fi
    fi

    # Which stderr carries the verdict. In `durable` it is the FIRST run's (the
    # second one replays the first one's checks); in `resume` it is the SECOND
    # run's, because the first one died before it could say anything.
    local verdict_stderr="$stderr"

    if [[ "$durable" == 1 || "$resume" == 1 ]]; then
        # The workspace picture BEFORE the resumed run. This is the observation
        # the pipeline cannot make for itself: a resumed run reuses the
        # journalled completions, so every check inside the script is REPLAYED
        # from the first run and observes nothing. The state after the second
        # run is only visible from out here.
        local before_snapshot after_snapshot
        before_snapshot="$(workspace_snapshot "$root/cwd")"

        # Second run over the same control root AND the same durable store.
        # The pipeline's own check decides whether the effect repeated; this
        # only makes the second run happen, and records its exit code
        # separately. The second run is the one whose exit code is the
        # verdict: the first run is the state the second one resumes.
        local second=0
        cd "$root/cwd"
        run_measured "$scenario_ceiling_ms" "$binary" run --isolated --control-root "$root/control" "${db_args[@]}" "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"

        # The second run has its own ceiling too. This one matters as much as
        # the first: a resumed run that hangs is exactly the failure the
        # resume scenarios exist to catch, and it would hang the same way.
        if timed_out "$second" "$last_run_ms" "$scenario_ceiling_ms"; then
            timeout_failure "$label" "$name" "$scenario_ceiling_ms" "$root/stderr.rerun.txt"
            return
        fi

        if [[ "$resume" == 1 ]]; then
            # "Died" means died: the process was killed by a signal, which a
            # shell reports as 128+signal (SIGKILL -> 137). A plain non-zero exit
            # is NOT a death -- a script that fails to COMPILE exits 1 -- and
            # accepting that as a crash would let a broken scenario pass as one
            # that crashed on purpose.
            if ! is_a_death "$code"; then
                printf 'FAIL %-52s the run was supposed to die and exited with %s (a normal exit is not a death)\n' \
                    "$name" "$code"
                verdict_fail "$name: the run was supposed to die and exited with $code"
                return
            fi
            code="$second"
            verdict_stderr="$root/stderr.rerun.txt"
        else
            # The verdict is the SECOND run's exit code, and the expectation is
            # the one the scenario DECLARED. Forcing it to 0 here discarded what
            # the scenario said about itself, silently.
            code="$second"
        fi
    fi

    # P6: a scenario that declares itself storage-agnostic has to meet its
    # expectations in BOTH worlds. The durable run happened above; the volatile
    # one right after it. One world meeting them is not evidence: the point is
    # that the storage choice does not change what the script does.
    if [[ "$storage_agnostic" == 1 ]]; then
        if ! expectations_met "$code" "$verdict_stderr"; then
            printf 'FAIL %-52s the DURABLE run did not meet the expectations\n' "$label"
            sed 's/^/       | /' "$verdict_stderr" | head -20
            verdict_fail "$name: the durable run did not meet the expectations"
            return
        fi
        if ! expectations_met "$memory_code" "$root/stderr.memory.txt"; then
            printf 'FAIL %-52s the IN-MEMORY run did not meet the expectations\n' "$label"
            sed 's/^/       | /' "$root/stderr.memory.txt" | head -20
            verdict_fail "$name: the in-memory run did not meet the expectations"
            return
        fi
        verdict_ok
        return
    fi

    if [[ "$code" == "$expect_exit" ]]; then
        # Every declared stderr expectation must hold, not only the last one.
        local missing="" wanted
        for wanted in ${expect_stderr[@]+"${expect_stderr[@]}"}; do
            if ! grep -qF -- "$wanted" "$verdict_stderr"; then
                missing="$wanted"
                break
            fi
        done
        if [[ -n "$missing" ]]; then
            printf 'FAIL %-52s exit %s as expected, but stderr lacked: %s\n' \
                "$label" "$code" "$missing"
            verdict_fail "$name: exit matched but expected stderr substring absent"
            return
        fi

        if [[ "$durable" == 1 || "$resume" == 1 ]]; then
            # The second half of the durable guarantee: the resumed run must
            # not have touched the workspace. A completed effect whose
            # completion was reused does not happen twice, and the only place
            # that shows up is the state of the world.
            #
            # For `resume` this is the whole point of the directive. The first
            # run died in the middle of an effect, and the resumed run has to
            # decide what to do about a call whose answer nobody has. If it
            # dispatches the call again, the world changes here — twice the
            # effect, and a row that looks exactly like a first attempt.
            after_snapshot="$(workspace_snapshot "$root/cwd")"
            if [[ "$before_snapshot" != "$after_snapshot" ]]; then
                printf 'FAIL %-52s the resumed run changed the workspace: the effect repeated\n' \
                    "$label"
                diff <(printf '%s\n' "$before_snapshot") <(printf '%s\n' "$after_snapshot") \
                    | sed 's/^/       | /' | head -10
                verdict_fail "$name: the resumed run changed the workspace"
                return
            fi
        fi
        printf 'ok   %-52s exit %s\n' "$label" "$code"
        verdict_ok
        return
    fi

    printf 'FAIL %-52s exit %s, expected %s\n' "$label" "$code" "$expect_exit"
    # In durable and resume modes the verdict is the SECOND run's exit code, so
    # the stderr that explains it is the second run's stderr. Showing the first
    # run's would point at a run that died before it could say anything.
    if [[ "$durable" == 1 || "$resume" == 1 ]]; then
        sed 's/^/       | /' "$root/stderr.rerun.txt" | head -20
    else
        sed 's/^/       | /' "$stderr" | head -20
    fi
    verdict_fail "$name: exit $code, expected $expect_exit"
}

# Optional glob filter, relative to this directory: `run.sh 'control-flow/*'`.
# Zero matches is an ERROR, not a run of nothing. A filter that quietly runs zero
# scenarios prints "0 scenario(s), 0 passed, 0 failed" and exits 0, which reads
# exactly like a green -- the shape this launcher spent a whole cycle learning to
# refuse elsewhere.
filter="${1:-}"
selected=()
while IFS= read -r script; do
    rel="${script#"$here"/}"
    if [[ -n "$filter" ]]; then
        # shellcheck disable=SC2254 # the pattern is a caller-supplied glob on purpose
        case "$rel" in $filter) selected+=("$script") ;; esac
    else
        selected+=("$script")
    fi
done < <(find "$here" -name '*.pipeline.kts' -type f -not -path '*/build/*' \
            -not -path '*/example-*-plugin/*' | LC_ALL=C sort)

if [[ ${#selected[@]} -eq 0 ]]; then
    if [[ -n "$filter" ]]; then
        printf 'run.sh: no scenario matches the pattern %s\n' "$filter" >&2
        printf '  patterns are globs against the path relative to this directory,\n' >&2
        printf '  e.g. '\''control-flow/*'\'' or '\''*/11-*'\''.\n' >&2
    else
        # Without a filter there used to be no check at all: an empty discovery
        # printed "0 scenario(s), 0 passed, 0 failed" and exited 0, which reads
        # exactly like a green. A tree of examples that moved, or a discovery
        # that broke, used to leave the gate green having executed NOTHING.
        printf 'run.sh: no scenario was found under %s\n' "$here" >&2
        printf '  A gate that runs NOTHING is not a gate that passed. Either the\n' >&2
        printf '  tree of examples moved or the discovery is broken; both fail.\n' >&2
    fi
    printf '  available:\n' >&2
    find "$here" -name '*.pipeline.kts' -type f -not -path '*/build/*' \
        -not -path '*/example-*-plugin/*' | LC_ALL=C sort \
        | sed "s|^$here/|    |" >&2
    exit 2
fi

# How many scenarios run at once. The scenarios are isolated BY DESIGN -- each
# one gets its own root, its own working directory, its own credential store,
# its own durable database and its own control root -- so they do not need to
# take turns. The limit is a knob, not a constant: a machine with fewer cores
# gets fewer workers, and `PIPELINEK_E2E_JOBS=1` restores the old serial
# behaviour exactly, which is what a flaky scenario needs to be diagnosed.
jobs_limit="${PIPELINEK_E2E_JOBS:-0}"
if [[ ! "$jobs_limit" =~ ^[0-9]+$ ]]; then
    printf 'run.sh: PIPELINEK_E2E_JOBS needs a number, got: %s\n' "$jobs_limit" >&2
    exit 2
fi
if [[ "$jobs_limit" -eq 0 ]]; then
    cores="$(nproc 2>/dev/null || echo 4)"
    (( cores > 8 )) && cores=8
    jobs_limit="$cores"
fi

# The ceiling for ONE pipeline run of a scenario. A scenario that hangs is
# killed here and reports a red verdict of its own, with its name and its
# reason, and the other scenarios keep running.
scenario_ceiling_ms=0
if ! parse_timeout_knob "${PIPELINEK_E2E_SCENARIO_TIMEOUT:-120}"; then
    printf 'run.sh: %s\n' "$timeout_knob_error" >&2
    exit 2
fi
scenario_ceiling_ms=$(( scenario_timeout_s * 1000 ))

# The ceiling needs `timeout`, and it is the same tool AGENTS.md section 5
# already wraps the whole gate with. Its absence is NOT a reason to run
# without a ceiling: the launcher would then hang exactly as it used to,
# and the absence of the mechanism would look exactly like the absence of a
# hang -- the one thing this knob exists to make visible.
if (( scenario_ceiling_ms > 0 )) && ! command -v timeout >/dev/null 2>&1; then
    printf 'run.sh: the scenario ceiling needs the `timeout` command, and it is not installed.\n' >&2
    printf '  Without it a hung scenario hangs the stage again, silently.\n' >&2
    printf '  Install coreutils, or set PIPELINEK_E2E_SCENARIO_TIMEOUT=0 to run without a ceiling.\n' >&2
    exit 2
fi

# Each scenario writes its verdict into its own file, and the parent prints
# those files in SCENARIO ORDER at the end. Two reasons: the report is then
# the same as the serial one, so a log can be compared between runs; and the
# summary line -- the only thing the gate parses -- keeps its shape.
verdict_dir="$cycle_root/verdicts"
mkdir -p "$verdict_dir"

running=0
for script in ${selected[@]+"${selected[@]}"}; do
    slug="$(scenario_slug "$script")"
    ( run_scenario "$script" ) > "$verdict_dir/$slug.txt" 2>&1 &
    running=$((running + 1))
    if (( running >= jobs_limit )); then
        # `wait -n` returns when the next job finishes. Its exit status is
        # whatever that scenario's subshell ended with, which is not the
        # runner's verdict -- the verdict travels in the file -- so it is
        # deliberately not treated as a failure here.
        wait -n || true
        running=$((running - 1))
    fi
done
# The exit status of the last background job is not the runner's verdict --
# the verdict travels in the files -- so it is not allowed to abort the run.
wait || true

scenario_index="${#selected[@]}"
for script in ${selected[@]+"${selected[@]}"}; do
    slug="$(scenario_slug "$script")"
    file="$verdict_dir/$slug.txt"
    if [[ ! -f "$file" ]]; then
        failed=$((failed + 1))
        failures+=("${script#"$here"/}: the scenario produced no verdict at all")
        continue
    fi
    # The human lines, in scenario order. The markers are the machine's
    # reading of the same file and do not belong in the report.
    grep -v -e '^__VERDICT__ ' -e '^__FAILURE__ ' "$file" || true
    verdict_line="$(grep -m 1 '^__VERDICT__ ' "$file" 2>/dev/null || true)"
    case "${verdict_line#__VERDICT__ }" in
        ok)       passed=$((passed + 1)) ;;
        not_run)  not_run=$((not_run + 1)) ;;
        fail)     failed=$((failed + 1)) ;;
        *)        failed=$((failed + 1))
                  failures+=("${script#"$here"/}: the scenario produced no readable verdict") ;;
    esac
    while IFS= read -r reason; do
        if [[ -n "$reason" ]]; then failures+=("$reason"); fi
    done < <(grep '^__FAILURE__ ' "$file" 2>/dev/null | sed 's/^__FAILURE__ //' || true)
done

printf '\n%d scenario(s), %d passed, %d failed, %d not run\n' \
    "$scenario_index" "$passed" "$failed" "$not_run"
if (( not_run > 0 )); then
    printf 'not run (NOT_RUN is not a pass):\n'
    printf '  - every scenario above marked NOT_RUN needs external state this\n'
    printf '    launcher does not and must not fabricate.\n'
fi
# Nothing RAN is not a pass. A run in which every selected scenario was
# NOT_RUN has measured nothing, and reporting "0 failed" about it would be the
# same false green as running zero scenarios.
if (( passed + failed == 0 )); then
    printf 'run.sh: no scenario was executed (%d selected, %d not run).\n' \
        "$scenario_index" "$not_run" >&2
    printf '  A gate that runs NOTHING is not a gate that passed.\n' >&2
    exit 2
fi
if (( failed > 0 )); then
    printf 'failures:\n'
    printf '  - %s\n' "${failures[@]}"
    exit 1
fi