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
#                                     merely a nonzero exit
#   //@ durable-once                  run ONCE against a real durable store
#   //@ durable                       run twice against one control root and
#                                     require the second run not to repeat the
#                                     effect; the pipeline itself asserts the
#                                     count, this only re-runs it
#   //@ requires-seeded-credentials   NOT_RUN: the scenario needs a credential
#                                     store seeded in advance, and the product
#                                     offers no non-interactive way to seed one
#                                     (`credentials add` prompts on the TTY).
#                                     It is reported as not executed, which is
#                                     neither a pass nor a failure.
#
# The distribution must already exist:
#     cd v2 && ./gradlew :pipeline-application:installDist
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

set -Eeuo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$here/../.." && pwd)"
binary="$repo_root/v2/pipeline-application/build/install/pipelinek/bin/pipelinek"

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
    local expect_stderr=""
    local durable=0
    local durable_once=0
    local needs_seeded_credentials=0
    local directive
    while IFS= read -r directive; do
        case "$directive" in
            '//@ expect-exit '*)   expect_exit="${directive#//@ expect-exit }" ;;
            '//@ expect-stderr '*) expect_stderr="${directive#//@ expect-stderr }" ;;
            '//@ durable')         durable=1 ;;
            '//@ durable-once')    durable_once=1 ;;
            '//@ requires-seeded-credentials') needs_seeded_credentials=1 ;;
        esac
    done < <(grep -m 20 '^//@ ' "$script" || true)

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
    if [[ "$durable" == 1 || "$durable_once" == 1 ]]; then
        db_args=(--db "$root/durable.sqlite")
    fi

    # `cd` in this shell, not a subshell: a subshell would discard the exit
    # code the verdict is built from, and every scenario would read as ok.
    local caller_pwd="$PWD"
    cd "$root/cwd"
    "$binary" run --isolated --control-root "$root/control" "${db_args[@]}" "$script" \
        >"$stdout" 2>"$stderr" || code=$?
    cd "$caller_pwd"

    if [[ "$durable" == 1 ]]; then
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
        code="$second"
        expect_exit=0
    fi

    local label="$name"
    if [[ "$durable" == 1 ]]; then
        label="$name (durable x2)"
    elif [[ "$durable_once" == 1 ]]; then
        label="$name (durable x1)"
    fi

    if [[ "$code" == "$expect_exit" ]]; then
        if [[ -n "$expect_stderr" ]] && ! grep -qF -- "$expect_stderr" "$stderr"; then
            printf 'FAIL %-52s exit %s as expected, but stderr lacked: %s\n' \
                "$label" "$code" "$expect_stderr"
            failed=$((failed + 1))
            failures+=("$name: exit matched but expected stderr substring absent")
            return
        fi

        if [[ "$durable" == 1 ]]; then
            # The second half of the durable guarantee: the resumed run must
            # not have touched the workspace. A completed effect whose
            # completion was reused does not happen twice, and the only place
            # that shows up is the state of the world.
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
    # In durable mode the verdict is the SECOND run's exit code, so the stderr
    # that explains it is the second run's stderr. Showing the first run's
    # would point at a run that succeeded.
    if [[ "$durable" == 1 ]]; then
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

if [[ -n "$filter" && ${#selected[@]} -eq 0 ]]; then
    printf 'run.sh: no scenario matches the pattern %s\n' "$filter" >&2
    printf '  patterns are globs against the path relative to this directory,\n' >&2
    printf '  e.g. '\''control-flow/*'\'' or '\''*/11-*'\''.\n' >&2
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
if (( failed > 0 )); then
    printf 'failures:\n'
    printf '  - %s\n' "${failures[@]}"
    exit 1
fi