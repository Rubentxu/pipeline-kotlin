#!/usr/bin/env bash
# The ONE E2E launcher (AGENTS.md section 3).
#
# It runs real .pipeline.kts programs against the compiled distribution. It is
# deliberately not a framework: it discovers scenarios, gives each one a
# private control root, checks the exit code, and cleans up. It has no notion of
# assertions beyond two optional per-pipeline directives, so a scenario cannot
# grow into a test suite with its own vocabulary.
#
#   #! expect-exit 1                  default is 0
#   #! expect-stderr <substring>      the refusal must be this specific, not
#                                     merely a nonzero exit
#   #! durable                        run twice against one control root and
#                                     require the second run not to repeat the
#                                     effect; the pipeline itself asserts the
#                                     count, this only re-runs it
#
# The distribution must already exist:
#     cd v2 && ./gradlew :pipeline-application:installDist

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
failures=()

scenario_index=0

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
    local directive
    while IFS= read -r directive; do
        case "$directive" in
            '#! expect-exit '*)    expect_exit="${directive#\#! expect-exit }" ;;
            '#! expect-stderr '*)  expect_stderr="${directive#\#! expect-stderr }" ;;
            '#! durable')          durable=1 ;;
        esac
    done < <(grep -m 20 '^#!' "$script" || true)

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

    local stdout="$root/stdout.txt" stderr="$root/stderr.txt" code=0

    # `cd` in this shell, not a subshell: a subshell would discard the exit
    # code the verdict is built from, and every scenario would read as ok.
    local caller_pwd="$PWD"
    cd "$root/cwd"
    "$binary" run --isolated --control-root "$root/control" "$script" \
        >"$stdout" 2>"$stderr" || code=$?
    cd "$caller_pwd"

    if [[ "$durable" == 1 ]]; then
        # Second run over the same control root. The pipeline's own check
        # decides whether the effect repeated; this only makes the second run
        # happen, and records its exit code separately.
        local second=0
        cd "$root/cwd"
        "$binary" run --isolated --control-root "$root/control" "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"
        code="$second"
        expect_exit=0
    fi

    local label="$name"
    if [[ "$durable" == 1 ]]; then
        label="$name (durable x2)"
    fi

    if [[ "$code" == "$expect_exit" ]]; then
        if [[ -n "$expect_stderr" ]] && ! grep -qF -- "$expect_stderr" "$stderr"; then
            printf 'FAIL %-52s exit %s as expected, but stderr lacked: %s\n' \
                "$label" "$code" "$expect_stderr"
            failed=$((failed + 1))
            failures+=("$name: exit matched but expected stderr substring absent")
            return
        fi
        printf 'ok   %-52s exit %s\n' "$label" "$code"
        passed=$((passed + 1))
        return
    fi

    printf 'FAIL %-52s exit %s, expected %s\n' "$label" "$code" "$expect_exit"
    sed 's/^/       | /' "$stderr" | head -20
    failed=$((failed + 1))
    failures+=("$name: exit $code, expected $expect_exit")
}

while IFS= read -r script; do
    run_scenario "$script"
done < <(find "$here" -name '*.pipeline.kts' -type f -not -path '*/build/*' \
            -not -path '*/example-*-plugin/*' | LC_ALL=C sort)

printf '\n%d scenario(s), %d passed, %d failed\n' "$scenario_index" "$passed" "$failed"
if (( failed > 0 )); then
    printf 'failures:\n'
    printf '  - %s\n' "${failures[@]}"
    exit 1
fi