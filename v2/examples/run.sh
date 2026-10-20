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
    local expect_exit expect_stderr durable durable_once resume needs_seeded_credentials
    local directive_error

    # A valid set of directives is understood, and the flags are not confused
    # with each other.
    directive_error=""
    parse_directives 3 <<'EOF' || true
//@ expect-exit 1
//@ expect-stderr algo
//@ durable-once
EOF
    self_check "expect-exit is read" "1" "$expect_exit" || failures=$((failures + 1))
    self_check "expect-stderr is read" "1" "${#expect_stderr[@]}" || failures=$((failures + 1))
    self_check "durable-once is read" "1" "$durable_once" || failures=$((failures + 1))
    self_check "durable is NOT confused with durable-once" "0" "$durable" || failures=$((failures + 1))
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

passed=0
failed=0
not_run=0
failures=()

scenario_index=0

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

# One scenario, fully isolated. Nothing is read from the developer's HOME and
# nothing is written outside $cycle_root.
run_scenario() {
    local script="$1"
    local relative="${script#"$here"/}"
    scenario_index=$((scenario_index + 1))
    local name="$relative"
    local slug
    slug="$(printf '%s' "$name" | tr '/.' '--')"

    local expect_exit=0
    local expect_stderr=()
    local durable=0
    local durable_once=0
    local resume=0
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
        failed=$((failed + 1))
        failures+=("$name: $directive_error")
        return
    fi

    if [[ "$needs_seeded_credentials" == 1 ]]; then
        # Declared up front, before any execution, so the verdict cannot depend
        # on a run whose outcome was never observed. NOT_RUN is not a pass:
        # it is the honest third state, and it keeps the scenario in the
        # inventory so it cannot be forgotten the way a deleted file can.
        printf 'NOT_RUN %-50s needs a credential store seeded on a TTY\n' "$name"
        not_run=$((not_run + 1))
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
    if [[ "$durable" == 1 || "$durable_once" == 1 || "$resume" == 1 ]]; then
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
    "$binary" run --isolated --control-root "$root/control" "${db_args[@]}" "$script" \
        >"$stdout" 2>"$stderr" || code=$?
    cd "$caller_pwd"

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
        "$binary" run --isolated --control-root "$root/control" "${db_args[@]}" "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"

        if [[ "$resume" == 1 ]]; then
            # "Died" means died: the process was killed by a signal, which a
            # shell reports as 128+signal (SIGKILL -> 137). A plain non-zero exit
            # is NOT a death -- a script that fails to COMPILE exits 1 -- and
            # accepting that as a crash would let a broken scenario pass as one
            # that crashed on purpose.
            if ! is_a_death "$code"; then
                printf 'FAIL %-52s the run was supposed to die and exited with %s (a normal exit is not a death)\n' \
                    "$name" "$code"
                failed=$((failed + 1))
                failures+=("$name: the run was supposed to die and exited with $code")
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

    local label="$name"
    if [[ "$durable" == 1 ]]; then
        label="$name (durable x2)"
    elif [[ "$durable_once" == 1 ]]; then
        label="$name (durable x1)"
    elif [[ "$resume" == 1 ]]; then
        label="$name (resume x2)"
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
            failed=$((failed + 1))
            failures+=("$name: exit matched but expected stderr substring absent")
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
                failed=$((failed + 1))
                failures+=("$name: the resumed run changed the workspace")
                return
            fi
        fi
        printf 'ok   %-52s exit %s\n' "$label" "$code"
        passed=$((passed + 1))
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
    failed=$((failed + 1))
    failures+=("$name: exit $code, expected $expect_exit")
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

for script in ${selected[@]+"${selected[@]}"}; do
    run_scenario "$script"
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