!/usr/bin/env bash
 The ONE E2E launcher (AGENTS.md section 3).

 It runs real.pipeline.kts programs against the compiled distribution. It is
 deliberately not a framework: it discovers scenarios, gives each one a
 private control root, checks the exit code, and cleans up. It has no notion of
 assertions beyond the optional per-pipeline directives below, so a scenario
 cannot grow into a test suite with its own vocabulary.

#   //@ expect-exit 1                 default is 0
#   //@ expect-stdout <substring>     the pipeline must PRODUCE this, not merely
                                    # exit 0: without it a step that ran and did
                                    # # nothing reads exactly like a step that ran
                                    # # and did its work. Repeatable: every
                                    # # occurrence must be present.
#   //@ expect-stderr <substring>     the refusal must be this specific, not
                                     # merely a nonzero exit. Repeatable.
#   //@ expect-transcript <text>      the bytes the run COMMITTED must contain
                                     # this text. The transcript is read from
                                     # the Output Plane through the product's
                                     # own `console` verb, so it is the
                                     # durable surface rather than whatever the
                                     # live console happened to render: a
                                     # scenario that wants to assert on what a
                                     # step PRINTED asserts it where the print
                                     # is committed. Repeatable.
#   //@ durable                       run twice against one control root and
                                     require the second run not to repeat the
                                     effect; the pipeline itself asserts the
                                     count, this only re-runs it
#   //@ credentials <id> <kind>       seed <id> into this scenario's own store
                                     before the run, so a pipeline that binds a
                                     credential has one to resolve. Repeatable.
                                     The value is a fixture, generated here:
                                     the scenario asserts that the binding
                                     RESOLVED, never what it resolved to.

 The distribution must already exist:
     cd v2 &&./gradlew:pipeline-application:installDist

# Why `//@` and not `#!`. An earlier revision read directives from `#!` comment
 lines, on the assumption that the product supported them. It does not: no
 source under any `src/main` reads `#!`. The convention only ever worked by
 accident, because the Kotlin script compiler strips a shebang from the FIRST
 line of a file. That accident is a one-line budget, and it was spent wrongly:
 two `#!` lines make the second one an ordinary syntax error, so a scenario
 carrying both `expect-exit` and `expect-stderr` failed to COMPILE with
 "Syntax error: Expecting an element" instead of failing for its own reason.
 Measured, not assumed: two `#!` lines give VALIDATION FAILED; the same two as
# `//` comments give VALIDATION SUCCESSFUL.

# `//@` is an ordinary Kotlin comment, so it is position-independent, repeatable
 and always legal. The `@` is load-bearing rather than decorative: real
 scenarios already carry prose comments such as
# "// durable execution over the journal", so a bare `//` prefix would let a
 sentence in a comment silently switch a directive on.

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
seeded=0
durable_runs=0
failures=()

scenario_index=0

 Fixtures a seeded credential can be built from. Built once per invocation and
 shared: they are synthetic values nobody asserts on, so rebuilding them per
 scenario would spend a subprocess to produce the same bytes.
fixtures_dir="$cycle_root/fixtures"
seed_value() {
     $1 = credential kind. Writes the fixture bytes on standard output.
    case "$1" in
        secret-text)
            printf 'e2e-fixture-secret' ;;
        username-password | username-colon-password)
            printf 'e2e-fixture-user\ne2e-fixture-pass' ;;
        ssh-private-key)
            printf 'e2e-fixture-user\n-----BEGIN E2E KEY-----\ne2e-fixture-key\n-----END E2E KEY-----' ;;
        secret-file)
            printf 'e2e-fixture-file-contents' ;;
        certificate)
            cat "$fixtures_dir/keystore.p12" ;;
        zip)
            cat "$fixtures_dir/archive.zip" ;;
        *)
            printf 'run.sh: unknown credential kind %s\n' "$1" >&2
            return 1 ;;
    esac
}

 The two binary kinds need bytes that pass their own validation, which means
 something has to produce a real keystore and a real archive. A missing tool is
 a loud failure naming the tool, never a silently accepted fixture.
build_binary_fixtures() {
    mkdir -p "$fixtures_dir"
    if [[ ! -f "$fixtures_dir/keystore.p12" ]]; then
        command -v keytool >/dev/null || {
            printf 'run.sh: keytool is required to build the certificate fixture\n' >&2
            return 1
        }
        keytool -genkeypair -alias e2e -keyalg RSA -keysize 2048 \
            -dname 'CN=pipelinek e2e fixture' -validity 1 \
            -keystore "$fixtures_dir/keystore.p12" -storetype PKCS12 \
            -storepass e2e-fixture -keypass e2e-fixture -noprompt >/dev/null 2>&1 || {
            printf 'run.sh: keytool could not build the certificate fixture\n' >&2
            return 1
        }
    fi
    if [[ ! -f "$fixtures_dir/archive.zip" ]]; then
        command -v zip >/dev/null || {
            printf 'run.sh: zip is required to build the archive fixture\n' >&2
            return 1
        }
        printf 'e2e-fixture-entry' >"$fixtures_dir/entry.txt"
        ( cd "$fixtures_dir" && zip -q archive.zip entry.txt ) || {
            printf 'run.sh: zip could not build the archive fixture\n' >&2
            return 1
        }
    fi
}

 One scenario, fully isolated. Nothing is read from the developer's HOME and
 nothing is written outside $cycle_root.
run_scenario() {
    local script="$1"
    local relative="${script"$here"/}"
    scenario_index=$((scenario_index + 1))
    local name="$relative"
    local slug
    slug="$(printf '%s' "$name" | tr '/.' '--')"

    local expect_exit=0
    local durable=0
    local -a expect_stdout=()
    local -a expect_stderr=()
    local -a expect_transcript=()
    local -a seeds=()
    local directive
    while IFS= read -r directive; do
        case "$directive" in
            '//@ expect-exit '*)   expect_exit="${directive//@ expect-exit }";;
            '//@ expect-stdout '*) expect_stdout+=("${directive//@ expect-stdout }");;
            '//@ expect-stderr '*) expect_stderr+=("${directive//@ expect-stderr }");;
            '//@ expect-transcript '*) expect_transcript+=("${directive//@ expect-transcript }");;
            '//@ durable')         durable=1 ;;
            '//@ credentials '*)   seeds+=("${directive//@ credentials }");;
        esac
    done < <(grep -m 20 '^//@ ' "$script" || true)

    local root="$cycle_root/$slug"
     A private working directory per scenario, entered with `cd`, so a step
     that writes a relative path cannot land in the repository. AGENTS.md
     section 3 asks for one temporary directory per example; --isolated and
     --control-root already isolate the pipeline's own workspace, and this
     isolates the process CWD the pipeline is launched from.
    mkdir -p "$root/cwd"

     A per-scenario passphrase. The credentials gate refuses before any step
     runs, even for a pipeline that never binds a credential, so a scenario
     that did not set one would fail on a door rather than on its subject.
     This value is generated for this scenario and never leaves the process.
    export PIPELINE_STORE_PASSPHRASE="e2e-${slug}-$$"

     Pin the credentials store inside this scenario's own root. Without this,
     `CredentialsStorePath.fromEnvironment` falls back to the real
     ~/.pipeline/credentials.bin, so a scenario silently reads the
     developer's own credential store: the run is then neither reproducible
     nor hermetic, and its verdict depends on the machine. Pinned, the store
     does not exist, the credential executor is not composed, and any scenario
     that actually needs one says so instead of borrowing a stranger's.
    export PIPELINE_CREDENTIALS_STORE="$root/creds.bin"

     Seed the credentials this scenario binds, through the distribution's own
     writer. The store is encrypted, so building it any other way would test
     the hand-built bytes rather than the store; `credentials add --from-stdin`
     is the same path a user takes, and the scenario runs against the same
     binary it was seeded with.
    local seed
    for seed in ${seeds[@]+"${seeds[@]}"}; do
        local seed_id="${seed%% *}" seed_kind="${seed* }"
        local seed_out="$root/seed-$seed_id.txt"
        case "$seed_kind" in
            certificate | zip)
                 Built on first use: paying for a keystore on the scenarios
                 that bind no certificate would tax every run for one file.
                if [[ ! -f "$fixtures_dir/keystore.p12" || ! -f "$fixtures_dir/archive.zip" ]]; then
                    if ! build_binary_fixtures; then
                        printf 'FAIL %-52s could not build the fixtures for %s\n' \
                            "$name" "$seed_id"
                        failed=$((failed + 1))
                        failures+=("$name: binary fixtures unavailable")
                        return
                    fi
                fi
                ;;
        esac
        if ! seed_value "$seed_kind" >"$root/fixture.bin" \
            || ! "$binary" credentials add --kind "$seed_kind" --from-stdin \
                   "$seed_id" <"$root/fixture.bin" >"$seed_out" 2>&1; then
            printf 'FAIL %-52s could not seed credential %s (%s)\n' \
                "$name" "$seed_id" "$seed_kind"
            sed 's/^/       | /' "$seed_out" 2>/dev/null | head -5
            failed=$((failed + 1))
            failures+=("$name: credential $seed_id could not be seeded")
            return
        fi
        seeded=$((seeded + 1))
    done

    local stdout="$root/stdout.txt" stderr="$root/stderr.txt" code=0

     `cd` in this shell, not a subshell: a subshell would discard the exit
     code the verdict is built from, and every scenario would read as ok.
    local caller_pwd="$PWD"
    cd "$root/cwd"
    "$binary" run --isolated --control-root "$root/control" "$script" \
        >"$stdout" 2>"$stderr" || code=$?
    cd "$caller_pwd"

    if [[ "$durable" == 1 ]]; then
         Second run over the same control root. The pipeline's own check
         decides whether the effect repeated; this only makes the second run
         happen, and records its exit code separately.
        local second=0
        cd "$root/cwd"
        "$binary" run --isolated --control-root "$root/control" "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"
        code="$second"
        expect_exit=0
        durable_runs=$((durable_runs + 1))
    fi

    local label="$name"
    if [[ "$durable" == 1 ]]; then
        label="$name (durable x2)"
    fi

    if [[ "$code" == "$expect_exit" ]]; then
         -a porque la salida puede traer bytes NUL, y sin el fallo se reporta
         como "fichero binario" sin la coincidencia, indistinguible de su ausencia.
        local wanted
        for wanted in ${expect_stdout[@]+"${expect_stdout[@]}"}; do
            if ! grep -aqF -- "$wanted" "$stdout"; then
                printf 'FAIL %-52s exit %s as expected, but stdout lacked: %s\n' \
                    "$label" "$code" "$wanted"
                sed -n '1,20p' "$stdout" | sed 's/^/       | /'
                failed=$((failed + 1))
                failures+=("$name: exit matched but expected stdout substring absent")
                return
            fi
        done
        for wanted in ${expect_stderr[@]+"${expect_stderr[@]}"}; do
            if ! grep -aqF -- "$wanted" "$stderr"; then
                printf 'FAIL %-52s exit %s as expected, but stderr lacked: %s\n' \
                    "$label" "$code" "$wanted"
                sed -n '1,20p' "$stderr" | sed 's/^/       | /'
                failed=$((failed + 1))
                failures+=("$name: exit matched but expected stderr substring absent")
                return
            fi
        done

         The committed transcript. The Output Plane holds every byte a step
         printed, and the product's own `console` verb is the reader for it.
         The op ids are the stream directories the plane created, so the
         launcher asks for each of them rather than composing one: a name it
         invented would be refused, and a refusal here would be a launcher
         bug wearing a scenario's clothes.
        if (( ${expect_transcript[@]} > 0 )); then
            local transcript="$root/transcript.txt"
            local run_id
            run_id="$(grep -ao '"runId":"[^"]*"' "$stderr" | head -1 | cut -d'"' -f4)"
            if [[ -z "$run_id" ]]; then
                printf 'FAIL %-52s the run published no run id, so its transcript cannot be read\n' \
                    "$label"
                failed=$((failed + 1))
                failures+=("$name: no run id in the launch receipt")
                return
            fi
            : >"$transcript"
            local stream_dir op_id
            for stream_dir in "$root/control/output-plane/streams"/*_stdout; do
                [[ -d "$stream_dir" ]] || continue
                op_id="$(basename "$stream_dir")"
                # The directory is <runId>_<opId>_stdout.
                op_id="${op_id#${run_id}_}"
                op_id="${op_id%_stdout}"
                "$binary" console --control-dir "$root/control" "$run_id" "$op_id" \
                    >>"$transcript" 2>/dev/null || true
            done

            for wanted in ${expect_transcript[@]+"${expect_transcript[@]}"}; do
                if ! grep -aqF -- "$wanted" "$transcript"; then
                    printf 'FAIL %-52s the committed transcript lacked: %s\n' \
                        "$label" "$wanted"
                    sed -n '1,20p' "$transcript" | sed 's/^/       | /'
                    failed=$((failed + 1))
                    failures+=("$name: the committed transcript lacked '$wanted'")
                    return
                fi
            done
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

# Optional glob filter, relative to this directory: `run.sh 'control-flow/*'`.
# Zero matches is an ERROR, not a run of nothing. A filter that quietly runs zero
# scenarios prints "0 scenario(s), 0 passed, 0 failed" and exits 0, which reads
# exactly like a green -- the shape this launcher spent a whole cycle learning to
# refuse elsewhere.
filter="${1:-}"
selected=
while IFS= read -r script; do
    rel="${script#"$here"/}"
    if [[ -n "$filter" ]]; then
        # shellcheck disable=SC2254 # the pattern is a caller-supplied glob on purpose
        case "$rel" in $filter) selected+=("$script");; esac
    else
        selected+=("$script")
    fi
done < <(find "$here" -name '*.pipeline.kts' -type f -not -path '*/build/*' \
            -not -path '*/example-*-plugin/*' | LC_ALL=C sort)

if (( ${selected[@]} == 0 )); then
    if [[ -n "$filter" ]]; then
        printf 'run.sh: no scenario matches the pattern %s\n' "$filter" >&2
        printf '  patterns are globs against the path relative to this directory,\n' >&2
        printf '  e.g. '\''control-flow/*'\'' or '\''*/11-*'\''.\n' >&2
        printf '  available:\n' >&2
        find "$here" -name '*.pipeline.kts' -type f -not -path '*/build/*' \
            -not -path '*/example-*-plugin/*' | LC_ALL=C sort \
            | sed "s|^$here/|    |" >&2
    else
        printf 'run.sh: no scenario was found under %s\n' "$here" >&2
        printf '  A run of zero scenarios observes nothing, and nothing observed\n' >&2
        printf '  is not a green.\n' >&2
    fi
    exit 2
fi

for script in ${selected[@]+"${selected[@]}"}; do
    run_scenario "$script"
done

 The three counters must reconcile before the summary is published: every
 scenario the launcher selected either reached a verdict or failed, and one
 that reached neither was skipped. A skip is a gap, not a pass, so it is
 declared here rather than left for whoever reads the counts to notice. The
 gate derives the same gap from the same three numbers, and the two cannot
 disagree because neither invents a fourth.
if (( passed + failed != scenario_index )); then
    printf 'run.sh: %d scenario(s) selected, %d passed, %d failed -- %d skipped.\n' \
        "$scenario_index" "$passed" "$failed" "$(( scenario_index - passed - failed ))" >&2
    printf '  A skipped scenario is a declared gap, not a pass.\n' >&2
    exit 1
fi

printf '\n%d scenario(s), %d passed, %d failed, %d credential(s) seeded, %d durable rerun(s)\n' \
    "$scenario_index" "$passed" "$failed" "$seeded" "$durable_runs"

 A capability with no scenario exercising it is not covered, it is merely
 unobserved -- and unobserved is indistinguishable from absent from the outside.
# The durable double-run branch below sat behind `//@ durable` while not one
 scenario declared it, so the whole replay path reported green for as long as
 anybody cared to look. A count is therefore published in the summary above,
 and a FULL inventory run refuses to be green without at least one rerun. The
 check is scoped to the unfiltered run on purpose: a `--scenario` filter is a
 caller choosing which slice to run, and forcing durable coverage there would
 punish the inner loop for using the filter at all.
if [[ -z "$filter" ]] && (( durable_runs == 0 )); then
    printf 'run.sh: no scenario declared "//@ durable", so the replay path was never executed.\n' >&2
    printf '  A green here would be the absence of the subject, not a passing check.\n' >&2
    exit 1
fi
if (( failed > 0 )); then
    printf 'failures:\n'
    printf '  - %s\n' "${failures[@]}"
    exit 1
fi