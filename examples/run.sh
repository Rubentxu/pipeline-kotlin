#!/usr/bin/env bash
# The ONE E2E launcher (AGENTS.md section 3).
# It runs real.pipeline.kts programs against the compiled distribution. It is
# deliberately not a framework: it discovers scenarios, gives each one a
# private control root, checks the exit code, and cleans up. It has no notion of
# assertions beyond the optional per-pipeline directives below, so a scenario
# cannot grow into a test suite with its own vocabulary.
#   //@ expect-exit 1                 default is 0
#   //@ expect-stdout <substring>     the pipeline must PRODUCE this, not merely
#                                    # exit 0: without it a step that ran and did
#                                    # # nothing reads exactly like a step that ran
#                                    # # and did its work. Repeatable: every
#                                    # # occurrence must be present.
#   //@ expect-stderr <substring>     the refusal must be this specific, not
#                                     # merely a nonzero exit. Repeatable.
#   //@ expect-transcript <text>      the bytes the run COMMITTED must contain
#                                     # this text. The transcript is read from
#                                     # the Output Plane through the product's
#                                     # own `console` verb, so it is the
#                                     # durable surface rather than whatever the
#                                     # live console happened to render: a
#                                     # scenario that wants to assert on what a
#                                     # step PRINTED asserts it where the print
#                                     # is committed. Repeatable.
#   //@ durable                       run twice against one control root and
#                                     require the second run not to repeat the
#                                     effect; the pipeline itself asserts the
#                                     count, this only re-runs it
#   //@ durable-once                  run ONCE against a real durable store, and
#                                     assert nothing about a second run: the
#                                     scenario is about an effect that must
#                                     happen exactly once in a single run, and
#                                     re-running it would assert the opposite.
#   //@ resume                        the first run is expected to DIE and the
#                                     second run resumes it over the same store
#                                     and the same control root. The verdict is
#                                     the second run's, and the world must not
#                                     change across it: an effect whose answer
#                                     nobody has must not be dispatched twice.
#   //@ cancel                        the first run is expected to DIE, and then
#                                     the run is closed with `pipelinek runs
#                                     cancel`. The verdict is the cancel's: the
#                                     run that died could not say anything about
#                                     its own outcome. The launcher checks that
#                                     the first cancel closed the run, that the
#                                     second one was a no-op rather than a
#                                     refusal, and that the run's history ends
#                                     with a terminal bookend.
#   //@ credentials <id> <kind>       seed <id> into this scenario's own store
#                                     before the run, so a pipeline that binds a
#                                     credential has one to resolve. Repeatable.
#                                     The value is a fixture, generated here:
#                                     the scenario asserts that the binding
#                                     RESOLVED, never what it resolved to.
#   //@ plugin-jar <fixture>          the plugin artifact the run is pointed at, as
#                                     a fixture the launcher owns inside the
#                                     scenario's own root. `missing` names a plugin
#                                     JAR that is not there: the artifact whose bytes
#                                     cannot be read, and therefore cannot be
#                                     verified before anything is loaded from it.
# The distribution must already exist:
#     cd pipelinek &&./gradlew:pipeline-application:installDist
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
# `//@` is an ordinary Kotlin comment, so it is position-independent, repeatable
# and always legal. The `@` is load-bearing rather than decorative: real
# scenarios already carry prose comments such as
# "// durable execution over the journal", so a bare `//` prefix would let a
# sentence in a comment silently switch a directive on.

set -Eeuo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$here/.." && pwd)"
binary="$repo_root/pipelinek/pipeline-application/build/install/pipelinek/bin/pipelinek"

if [[ ! -x "$binary" ]]; then
    echo "run.sh: no distribution at $binary" >&2
    echo "  build it first: cd pipelinek && ./gradlew :pipeline-application:installDist" >&2
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

# Fixtures a seeded credential can be built from. Built once per invocation and
# shared: they are synthetic values nobody asserts on, so rebuilding them per
# scenario would spend a subprocess to produce the same bytes.
fixtures_dir="$cycle_root/fixtures"
seed_value() {
    # $1 = credential kind. Writes the fixture bytes on standard output.
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

# The two binary kinds need bytes that pass their own validation, which means
# something has to produce a real keystore and a real archive. A missing tool is
# a loud failure naming the tool, never a silently accepted fixture.
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

# The operator's trust material for a scenario: a publisher key pair, the detached signature over
# the plugin's bytes, and the row that pins those bytes and names the publisher and the key.
#
# The key is generated HERE, for THIS scenario, and dies with the scenario's directory: nothing
# private is written into the repository, and no key that ever existed elsewhere can have signed
# these bytes. The row pins the digest of the artifact's FILE, which is what the runtime measures,
# so the operator's pin and the runtime's measurement are the same quantity.
#
# `signed-then-tampered` changes the bytes AFTER the signature was made. That is the swap the
# policy exists to refuse: the signature still says "I signed those bytes", and the bytes on
# disk are not those bytes.
build_plugin_trust_fixture() {
    local root="$1" mode="$2" jar_name="$3"
    local jar="$root/cwd/plugins/$jar_name"
    local trust_dir="$root/trust"

    if [[ ! -f "$jar" ]]; then
        printf 'run.sh: the trust fixture needs the plugin artifact at %s\n' "$jar" >&2
        return 1
    fi
    command -v openssl >/dev/null || {
        printf 'run.sh: openssl is required to sign the plugin trust fixture\n' >&2
        return 1
    }
    command -v unzip >/dev/null || {
        printf 'run.sh: unzip is required to read the plugin manifest for the trust fixture\n' >&2
        return 1
    }

    mkdir -p "$trust_dir/keys"

    # The identity the operator's row is filed under: the plugin's own manifest says who it is
    # and which release it is. Reading it from the artifact is what keeps the row and the plugin
    # talking about the same thing.
    local manifest plugin_id release_version publisher
    manifest="$(unzip -p "$jar" META-INF/pipelinek/plugin-manifest.json 2>/dev/null || true)"
    plugin_id="$(printf '%s\n' "$manifest" | sed -n 's/.*"plugin"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
    release_version="$(printf '%s\n' "$manifest" | sed -n 's/.*"releaseVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
    publisher="$(printf '%s\n' "$manifest" | sed -n 's/.*"publisher"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
    if [[ -z "$plugin_id" || -z "$release_version" || -z "$publisher" ]]; then
        printf 'run.sh: the plugin manifest does not carry the identity the trust row needs\n' >&2
        return 1
    fi

    # The bytes the publisher signs, and the bytes the operator pins: one quantity, measured the
    # same way the runtime measures them (SHA-256 over the artifact's file).
    openssl dgst -sha256 -binary "$jar" >"$trust_dir/artifact.digest" || return 1
    local digest_hex
    digest_hex="$(od -An -tx1 -v "$trust_dir/artifact.digest" | tr -d ' \n')"

    openssl genpkey -algorithm ed25519 -out "$trust_dir/publisher-key.pem" 2>/dev/null || return 1
    openssl pkey -in "$trust_dir/publisher-key.pem" -pubout -outform DER 2>/dev/null \
        | base64 -w0 >"$trust_dir/keys/example-key.pub" || return 1
    openssl pkeyutl -sign -inkey "$trust_dir/publisher-key.pem" -rawin \
        -in "$trust_dir/artifact.digest" -out "$trust_dir/signature.bin" || return 1

    local signature
    signature="$(base64 -w0 <"$trust_dir/signature.bin")"

    cat >"$jar.sig" <<JSON
{
  "schemaVersion": 1,
  "keyId": "example-key",
  "algorithm": "Ed25519",
  "signature": "$signature",
  "digest": "sha256:$digest_hex"
}
JSON

    cat >"$trust_dir/plugin-trust.json" <<JSON
{
  "$plugin_id@$release_version": {
    "digest": "sha256:$digest_hex",
    "publisher": "$publisher",
    "signed": true,
    "signedBy": "example-key"
  }
}
JSON

    if [[ "$mode" == "signed-then-tampered" ]]; then
        command -v zip >/dev/null || {
            printf 'run.sh: zip is required to tamper with the plugin fixture\n' >&2
            return 1
        }
        printf 'tampered\n' >"$root/cwd/plugins/tampered.txt"
        ( cd "$root/cwd/plugins" && zip -q "$jar" tampered.txt ) || {
            printf 'run.sh: zip could not tamper with the plugin fixture\n' >&2
            return 1
        }
    fi
}

# What a scenario PRINTED, as opposed to what the product LOGGED. The product
# narrates every step on the same stream the scenario prints on, and that
# narration buries the text an assertion is about: a failure that shows the
# first twenty lines of a run can hide the very line that decides it, which
# sends the reader looking for a defect that is not there. The check itself
# always reads the whole stream; this only decides what a failure shows.
show_printed() {
    local stream="$1" printed
    printed="$(grep -av '^\[PipelineK\]' "$stream" 2>/dev/null | head -40 || true)"
    if [[ -z "$printed" ]]; then
        printf '       | the run printed no text of its own\n'
    else
        printf '%s\n' "$printed" | sed 's/^/       | /'
    fi
}

# The picture of a directory: the name and the digest of every file in it, in a
# stable order. This is the observation a pipeline cannot make for itself: a
# resumed run reuses the journalled completions, so every check inside the
# script is REPLAYED from the first run and observes nothing. Whether the world
# changed across the resume is only visible from out here.
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
    local durable=0
    local durable_once=0
    local resume=0
    local cancel=0
    local -a expect_stdout=()
    local -a expect_stderr=()
    local -a expect_transcript=()
    local -a seeds=()
    local plugin_jar=""
    local plugin_trust=""
    local events_refusal=""
    local events_resume=""
    local step_verdict=""
    local failure_stage=""
    local directive
    while IFS= read -r directive; do
        case "$directive" in
            '//@ expect-exit '*)   expect_exit="${directive#//@ expect-exit }" ;;
            '//@ expect-stdout '*) expect_stdout+=("${directive#//@ expect-stdout }") ;;
            '//@ expect-stderr '*) expect_stderr+=("${directive#//@ expect-stderr }") ;;
            '//@ expect-transcript '*) expect_transcript+=("${directive#//@ expect-transcript }") ;;
            '//@ durable')         durable=1 ;;
            '//@ durable-once')    durable_once=1 ;;
            '//@ resume')          resume=1 ;;
            '//@ cancel')          cancel=1 ;;
            '//@ credentials '*)   seeds+=("${directive#//@ credentials }") ;;
            '//@ plugin-jar '*)    plugin_jar="${directive#//@ plugin-jar }" ;;
            '//@ plugin-trust '*)  plugin_trust="${directive#//@ plugin-trust }" ;;
            '//@ events-refusal '*) events_refusal="${directive#//@ events-refusal }" ;;
            '//@ events-resume '*)  events_resume="${directive#//@ events-resume }" ;;
            '//@ step-verdict '*)   step_verdict="${directive#//@ step-verdict }" ;;
            '//@ failure-stage '*)  failure_stage="${directive#//@ failure-stage }" ;;
        esac
    done < <(grep -m 20 '^//@ ' "$script" || true)

    local root="$cycle_root/$slug"
    # A private working directory per scenario, entered with `cd`, so a step
    # that writes a relative path cannot land in the repository. AGENTS.md
    # section 3 asks for one temporary directory per example; --isolated and
    # --control-root already isolate the pipeline's own workspace, and this
    # isolates the process CWD the pipeline is launched from.
    mkdir -p "$root/cwd"

    # The plugin artifact this scenario points at, when it names one. The artifact belongs to the
    # launcher and lives inside the scenario's own root, so a scenario never reaches outside its
    # sandbox. `missing` is the artifact that is NOT there: the file the operator named and the
    # runtime cannot read, whose bytes therefore cannot be measured. That is the case the
    # admission path has to refuse, and the scenario is about the refusal.
    # `bundled-uppercase` is the distribution's own copy of the example plugin, copied here so
    # the scenario owns the file it points at: the operator brings a plugin, and what the operator
    # brings is what the trust policy is about.
    local -a plugin_jar_args=()
    if [[ -n "$plugin_jar" ]]; then
        case "$plugin_jar" in
            missing)
                plugin_jar_args=(--plugin-jar "$root/cwd/plugins/missing.jar")
                ;;
            bundled-uppercase)
                local bundled_plugin
                bundled_plugin="$(ls "$repo_root/pipelinek/pipeline-application/build/install/pipelinek/lib/"example-uppercase-plugin-*.jar 2>/dev/null | head -1 || true)"
                if [[ -z "$bundled_plugin" ]]; then
                    printf 'FAIL %-52s the distribution has no example plugin to point at\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the distribution has no example-uppercase-plugin JAR")
                    return
                fi
                mkdir -p "$root/cwd/plugins"
                cp "$bundled_plugin" "$root/cwd/plugins/uppercase.jar"
                plugin_jar_args=(--plugin-jar "$root/cwd/plugins/uppercase.jar")
                ;;
            *)
                printf 'FAIL %-52s unknown plugin-jar fixture: %s\n' "$name" "$plugin_jar"
                failed=$((failed + 1))
                failures+=("$name: unknown plugin-jar fixture '$plugin_jar'")
                return
                ;;
        esac
    fi

    # The operator's trust material, when the scenario asks for a trust policy. The launcher owns
    # it: a key pair generated for this scenario, the publisher's detached signature over the
    # plugin's bytes, and the row that pins those bytes and names the publisher and the key. The
    # private key never leaves the scenario's directory and dies with it, so nothing in the
    # repository can have signed these bytes and nothing outside the scenario can reuse the key.
    local -a plugin_trust_args=()
    if [[ -n "$plugin_trust" ]]; then
        case "$plugin_trust" in
            signed|signed-then-tampered)
                if ! build_plugin_trust_fixture "$root" "$plugin_trust" "uppercase.jar"; then
                    printf 'FAIL %-52s the plugin trust fixture could not be built\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the plugin trust fixture could not be built")
                    return
                fi
                plugin_trust_args=(--plugin-trust "$root/trust/plugin-trust.json")
                ;;
            *)
                printf 'FAIL %-52s unknown plugin-trust fixture: %s\n' "$name" "$plugin_trust"
                failed=$((failed + 1))
                failures+=("$name: unknown plugin-trust fixture '$plugin_trust'")
                return
                ;;
        esac
    fi

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

    # Seed the credentials this scenario binds, through the distribution's own
    # writer. The store is encrypted, so building it any other way would test
    # the hand-built bytes rather than the store; `credentials add --from-stdin`
    # is the same path a user takes, and the scenario runs against the same
    # binary it was seeded with.
    local seed
    for seed in ${seeds[@]+"${seeds[@]}"}; do
        local seed_id="${seed%% *}" seed_kind="${seed#* }"
        local seed_out="$root/seed-$seed_id.txt"
        case "$seed_kind" in
            certificate | zip)
                # Built on first use: paying for a keystore on the scenarios
                # that bind no certificate would tax every run for one file.
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
    local vstdout="$root/stdout.volatile.txt" vstderr="$root/stderr.volatile.txt" vcode=0
    local verdict_stdout="$stdout" verdict_stderr="$stderr"
    local before_snapshot="" after_snapshot=""

    # The scenario's own private directory, absolute, and unique per scenario.
    # A scenario that needs a resource that SURVIVES between two runs cannot
    # use a relative path: `--isolated` gives every run a fresh managed
    # scratch workspace, and a fixed absolute path under /tmp is shared with
    # every other run on the machine (and with whatever else runs next).
    # This directory is removed with the scenario, so nothing leaks.
    export PIPELINEK_E2E_HOME="$root/cwd"

    # A scenario that kills its own process needs to know WHICH process to kill,
    # and the launcher is the only one that can say where to put that answer.
    # The file lives beside the store, NOT in the scenario's working directory:
    # the working directory is compared between the two runs, and a PID file
    # would differ between them (two processes, two PIDs) and look like "the
    # effect repeated".
    if [[ "$resume" == 1 || "$cancel" == 1 ]]; then
        export PIPELINEK_E2E_PID_FILE="$root/runner.pid"
    fi

    # A scenario that says anything about the journal runs on the journal path
    # only. The volatile path has no journal at all: there is nothing to resume
    # and nothing to compensate, so a run there would exercise a different
    # product than the one the scenario is about, and its verdict would be about
    # that product. It would also be unfair to the scenario: the marker a
    # durable scenario writes lives in this directory, so the second execution
    # would find the first one's effect and read it as a repeat.
    local run_volatile=1
    if [[ "$durable" == 1 || "$durable_once" == 1 || "$resume" == 1 || "$cancel" == 1 || "$events_refusal" != "" || "$events_resume" != "" || "$step_verdict" != "" ]]; then
        run_volatile=0
    fi

    # `cd` in this shell, not a subshell: a subshell would discard the exit
    # code the verdict is built from, and every scenario would read as ok.
    #
    # TWO executions, and both are held to the same expectations:
    #   - with a private journal database, which is how a CI runs a pipeline;
    #   - without one, which is the default for a local run.
    # A divergence between the two execution paths is a defect, and only
    # running both can find one. It happened: the two paths reported a
    # failing pipeline differently, and a suite that ran only one of them
    # could not see that there was a choice to make.
    local caller_pwd="$PWD"
    cd "$root/cwd"
    "$binary" run --isolated --db "$root/events.db" --control-root "$root/control" \
        ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
        >"$stdout" 2>"$stderr" || code=$?
    cd "$caller_pwd"

    if [[ "$run_volatile" == 1 ]]; then
        cd "$root/cwd"
        "$binary" run --isolated --control-root "$root/control-volatile" \
            ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
            >"$vstdout" 2>"$vstderr" || vcode=$?
        cd "$caller_pwd"
    fi

    if [[ "$durable" == 1 ]]; then
        # Second run over the same control root and the same journal. The
        # pipeline's own check decides whether the effect repeated; this only
        # makes the second run happen, and records its exit code separately.
        # The second run has to SUCCEED: a resumed run that completed the work
        # cannot end in failure, and a failure there is the durable guarantee
        # broken.
        local second=0
        cd "$root/cwd"
        "$binary" run --isolated --db "$root/events.db" --control-root "$root/control" \
            ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"
        if [[ "$second" != 0 ]]; then
            printf 'FAIL %-52s the resumed run exited %s, expected 0\n' "$name" "$second"
            show_printed "$root/stderr.rerun.txt"
            failed=$((failed + 1))
            failures+=("$name: the resumed run exited $second")
            return
        fi
        durable_runs=$((durable_runs + 1))
    fi

    if [[ "$resume" == 1 ]]; then
        # The FIRST run is the crash, and a scenario that says it will die and
        # finishes cleanly has not exercised what it claims. That is a failure
        # of the scenario, not a pass: a launcher that cannot tell "the crash
        # happened" from "the crash did not happen" proves nothing about
        # crashes.
        if [[ "$code" == 0 ]]; then
            printf 'FAIL %-52s the run was supposed to crash and finished cleanly\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run was supposed to crash and did not")
            return
        fi

        # The workspace picture BEFORE the resumed run. This is the observation
        # the pipeline cannot make for itself: a resumed run reuses the
        # journalled completions, so every check inside the script is REPLAYED
        # from the first run and observes nothing. The state after the second
        # run is only visible from out here.
        before_snapshot="$(workspace_snapshot "$root/cwd")"

        local second=0
        cd "$root/cwd"
        "$binary" run --isolated --db "$root/events.db" --control-root "$root/control" \
            ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"
        code="$second"
        # The verdict is the SECOND run's: the first one died before it could
        # say anything about the outcome of the run.
        verdict_stdout="$root/stdout.rerun.txt"
        verdict_stderr="$root/stderr.rerun.txt"
    fi

    if [[ "$cancel" == 1 ]]; then
        # The FIRST run is the crash, for the same reason a resumed scenario's
        # first run is: a scenario that says it will die and finishes cleanly has
        # not exercised what it claims.
        if [[ "$code" == 0 ]]; then
            printf 'FAIL %-52s the run was supposed to crash and finished cleanly\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run was supposed to crash and did not")
            return
        fi

        # The id of the run that died. The launcher reads it from the run's own
        # record of its last run, because the id is a UUID the launcher cannot
        # know in advance and the operator would have to copy it by hand.
        local run_id=""
        local record
        for record in "$root"/control/last-run/*; do
            [[ -f "$record" ]] || continue
            run_id="$(head -1 "$record")"
            break
        done
        if [[ -z "$run_id" ]]; then
            printf 'FAIL %-52s the crashed run left no run id, so it cannot be closed\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the crashed run left no run id")
            return
        fi

        # The first cancel closes the run.
        local cancel_code=0
        "$binary" runs cancel "$run_id" --db "$root/events.db" --control-root "$root/control" \
            --reason "the writer was killed" \
            >"$root/stdout.cancel.txt" 2>"$root/stderr.cancel.txt" || cancel_code=$?
        if [[ "$cancel_code" != 0 ]]; then
            printf 'FAIL %-52s the cancel of the dead run exited %s, expected 0\n' \
                "$name" "$cancel_code"
            show_printed "$root/stderr.cancel.txt"
            failed=$((failed + 1))
            failures+=("$name: the cancel of the dead run exited $cancel_code")
            return
        fi
        if ! grep -aqF -- "cancelled run $run_id" "$root/stdout.cancel.txt"; then
            printf 'FAIL %-52s the cancel did not report the run as closed\n' "$name"
            show_printed "$root/stdout.cancel.txt"
            failed=$((failed + 1))
            failures+=("$name: the cancel did not report the run as closed")
            return
        fi

        # The second cancel is a no-op: it closes nothing twice and it refuses
        # nothing. A refusal here would mean the contract is broken, because
        # the run IS closed.
        local again=0
        "$binary" runs cancel "$run_id" --db "$root/events.db" --control-root "$root/control" \
            >"$root/stdout.cancel2.txt" 2>"$root/stderr.cancel2.txt" || again=$?
        if [[ "$again" != 0 ]]; then
            printf 'FAIL %-52s the second cancel exited %s, expected 0 (it is a no-op)\n' \
                "$name" "$again"
            show_printed "$root/stderr.cancel2.txt"
            failed=$((failed + 1))
            failures+=("$name: the second cancel exited $again")
            return
        fi
        if ! grep -aqF -- "was already cancelled" "$root/stdout.cancel2.txt"; then
            printf 'FAIL %-52s the second cancel did not report the run as already closed\n' "$name"
            show_printed "$root/stdout.cancel2.txt"
            failed=$((failed + 1))
            failures+=("$name: the second cancel did not report the run as already closed")
            return
        fi

        # The run's history ends with a terminal bookend. Without it the run
        # would carry a cancellation receipt and no terminal fact, and a reader
        # would have two answers about whether the run is over.
        "$binary" events "$run_id" --db "$root/events.db" \
            >"$root/events.after-cancel.txt" 2>&1 || true
        if ! grep -aqF -- '"kind":"RunFinished"' "$root/events.after-cancel.txt"; then
            printf 'FAIL %-52s the closed run has no terminal bookend\n' "$name"
            head -20 "$root/events.after-cancel.txt" | sed 's/^/       | /'
            failed=$((failed + 1))
            failures+=("$name: the closed run has no terminal bookend")
            return
        fi

        # The verdict is the cancel's: the run that died could not say anything
        # about its own outcome.
        verdict_stdout="$root/stdout.cancel.txt"
        verdict_stderr="$root/stderr.cancel.txt"
        code=0
    fi

    if [[ "$events_refusal" != "" ]]; then
        # The scenario's own run is the FIXTURE: it writes a history that the
        # read verb is then asked about. So the run has to succeed — a scenario
        # whose pipeline crashed would be asserting about a history nobody
        # wrote.
        if [[ "$code" != 0 ]]; then
            printf 'FAIL %-52s the scenario run exited %s, expected 0 (it is the fixture the refusal is asked about)\n' \
                "$name" "$code"
            show_printed "$root/stderr.txt"
            failed=$((failed + 1))
            failures+=("$name: the scenario run exited $code")
            return
        fi

        # The id of the run that was written. The launcher reads it from the
        # run's own record of its last run, because the id is a UUID the
        # launcher cannot know in advance.
        local run_id=""
        local record
        for record in "$root"/control/last-run/*; do
            [[ -f "$record" ]] || continue
            run_id="$(head -1 "$record")"
            break
        done
        if [[ -z "$run_id" ]]; then
            printf 'FAIL %-52s the run left no run id, so there is no history to read\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run left no run id")
            return
        fi

        # The read verb is asked the question the scenario is about. Its answer
        # is the verdict: the run itself succeeded, and the REFUSAL is what
        # the scenario is testing.
        local refusal_code=0
        case "$events_refusal" in
            unknown-run)
                "$binary" events "no-such-run-$run_id" --db "$root/events.db" \
                    >"$root/stdout.events-refusal.txt" 2>"$root/stderr.events-refusal.txt" || refusal_code=$?
                ;;
            cursor-beyond-tail)
                "$binary" events "$run_id" --db "$root/events.db" \
                    --after-cursor "evt-cursor-v1:$run_id:999999999" \
                    >"$root/stdout.events-refusal.txt" 2>"$root/stderr.events-refusal.txt" || refusal_code=$?
                ;;
            *)
                printf 'FAIL %-52s unknown events-refusal fixture: %s\n' "$name" "$events_refusal"
                failed=$((failed + 1))
                failures+=("$name: unknown events-refusal fixture '$events_refusal'")
                return
                ;;
        esac

        verdict_stdout="$root/stdout.events-refusal.txt"
        verdict_stderr="$root/stderr.events-refusal.txt"
        code="$refusal_code"
    fi

    if [[ "$events_resume" != "" ]]; then
        # The scenario writes a history and the launcher acts as an EXTERNAL
        # client of it: it reads by cursor, cuts between pages — every page is
        # a new process, which is what a client that disconnects does — and
        # comes back with the cursor. What is asserted is that between one
        # read and the next NO fact is skipped and NO fact is repeated.
        #
        # The comparison is the whole verdict, and it is made against the FULL
        # read of the same history: the client that pages must end up having
        # seen exactly what a single read sees.
        if [[ "$code" != 0 ]]; then
            printf 'FAIL %-52s the scenario run exited %s, expected 0 (it is the fixture the reader reads)\n' \
                "$name" "$code"
            show_printed "$root/stderr.txt"
            failed=$((failed + 1))
            failures+=("$name: the scenario run exited $code")
            return
        fi

        local run_id=""
        local record
        for record in "$root"/control/last-run/*; do
            [[ -f "$record" ]] || continue
            run_id="$(head -1 "$record")"
            break
        done
        if [[ -z "$run_id" ]]; then
            printf 'FAIL %-52s the run left no run id, so there is no history to read\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run left no run id")
            return
        fi

        local resume_kind="${events_resume%% *}"
        local resume_limit="${events_resume##* }"
        if [[ "$resume_kind" == "$resume_limit" ]]; then
            printf 'FAIL %-52s events-resume needs <kind> <limit>, got: %s\n' "$name" "$events_resume"
            failed=$((failed + 1))
            failures+=("$name: events-resume needs <kind> <limit>")
            return
        fi

        # The truth: the whole history of that kind, read in one go.
        local expected read_code=0
        expected="$("$binary" events "$run_id" --db "$root/events.db" --limit 1000 \
            --kind "$resume_kind" --fields SEQ 2>/dev/null | tail -n +2 | tr '\n' ' ')" || read_code=$?
        if [[ "$read_code" != 0 ]]; then
            printf 'FAIL %-52s the full read exited %s\n' "$name" "$read_code"
            failed=$((failed + 1))
            failures+=("$name: the full read exited $read_code")
            return
        fi
        expected="$(printf '%s' "$expected" | tr -s ' ' | sed 's/^ //;s/ $//')"
        if [[ -z "$expected" ]]; then
            printf 'FAIL %-52s the history holds no %s facts, so there is nothing to resume\n' \
                "$name" "$resume_kind"
            failed=$((failed + 1))
            failures+=("$name: the history holds no $resume_kind facts")
            return
        fi

        # The external client. Each page is a separate invocation: the
        # disconnection is real, and the cursor is the only thing that travels.
        local cursor="" delivered="" page next facts
        local rounds=0
        while (( rounds < 200 )); do
            rounds=$((rounds + 1))
            read_code=0
            if [[ -z "$cursor" ]]; then
                page="$("$binary" events "$run_id" --db "$root/events.db" --limit "$resume_limit" \
                    --kind "$resume_kind" --fields SEQ 2>"$root/stderr.events-resume.txt")" || read_code=$?
            else
                page="$("$binary" events "$run_id" --db "$root/events.db" --limit "$resume_limit" \
                    --kind "$resume_kind" --fields SEQ --after-cursor "$cursor" \
                    2>"$root/stderr.events-resume.txt")" || read_code=$?
            fi
            if [[ "$read_code" != 0 ]]; then
                printf 'FAIL %-52s a page read exited %s\n' "$name" "$read_code"
                show_printed "$root/stderr.events-resume.txt"
                failed=$((failed + 1))
                failures+=("$name: a page read exited $read_code")
                return
            fi
            next="$(grep -aoE 'evt-cursor-v1:[^ ]+' "$root/stderr.events-resume.txt" 2>/dev/null | tail -1 || true)"
            facts="$(printf '%s\n' "$page" | tail -n +2 | tr '\n' ' ')"
            delivered="$delivered $facts"
            if [[ -z "$next" || "$next" == "$cursor" ]]; then
                break
            fi
            cursor="$next"
        done
        delivered="$(printf '%s' "$delivered" | tr -s ' ' | sed 's/^ //;s/ $//')"

        if [[ "$delivered" != "$expected" ]]; then
            # The diff says WHAT was lost and WHAT was repeated: a bare
            # "they differ" does not say whether the reader dropped a fact or
            # handed one out twice.
            local missing="" extra="" seq
            for seq in $expected; do
                case " $delivered " in
                    *" $seq "*) ;;
                    *) missing="$missing $seq" ;;
                esac
            done
            for seq in $delivered; do
                case " $expected " in
                    *" $seq "*) ;;
                    *) extra="$extra $seq" ;;
                esac
            done
            printf 'FAIL %-52s the external reader did not resume cleanly (kind=%s limit=%s)\n' \
                "$name" "$resume_kind" "$resume_limit"
            printf '       | expected:  %s\n' "$expected"
            printf '       | delivered: %s\n' "$delivered"
            if [[ -n "$missing" ]]; then
                printf '       | lost:      %s\n' "$missing"
            fi
            if [[ -n "$extra" ]]; then
                printf '       | repeated:  %s\n' "$extra"
            fi
            failed=$((failed + 1))
            failures+=("$name: the external reader lost or repeated facts while resuming")
            return
        fi
    fi

    if [[ -n "$step_verdict" ]]; then
        # The scenario declares what each step's verdict must be, and the launcher
        # is an EXTERNAL consumer of the event lane: it asks for the step terminal
        # events ONLY — never the failure event — and reads each one's verdict.
        #
        # A terminal event with no verdict leaves the consumer unable to tell a
        # success from a failure: it would have to join the terminal event with the
        # failure event, by a step index that does not even say which stage the
        # failure belongs to. That is the defect this scenario exists to catch, and
        # it is invisible to a reader that only looks at successful runs.
        local sv_run_id=""
        local record
        for record in "$root"/control/last-run/*; do
            [[ -f "$record" ]] || continue
            sv_run_id="$(head -1 "$record")"
            break
        done
        if [[ -z "$sv_run_id" ]]; then
            printf 'FAIL %-52s the run left no run id, so there is no history to read\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run left no run id")
            return
        fi

        local sv_code=0
        local sv_terminals
        sv_terminals="$("$binary" events "$sv_run_id" --db "$root/events.db" \
            --kind StepFinished --typed 2>"$root/stderr.step-verdict.txt")" || sv_code=$?
        if [[ "$sv_code" != 0 ]]; then
            printf 'FAIL %-52s reading the step terminals exited %s\n' "$name" "$sv_code"
            show_printed "$root/stderr.step-verdict.txt"
            failed=$((failed + 1))
            failures+=("$name: reading the step terminals exited $sv_code")
            return
        fi

        # Each terminal event, read as "<step>=<verdict>". A terminal event that
        # carries no verdict reads as "<step>=", and that empty half is the whole
        # difference the scenario is about.
        local sv_delivered="" sv_line sv_step sv_verdict
        while IFS= read -r sv_line; do
            [[ -z "$sv_line" ]] && continue
            sv_step="$(printf '%s' "$sv_line" \
                | sed -n 's/.*"stepName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
            sv_verdict="$(printf '%s' "$sv_line" \
                | sed -n 's/.*"outcome"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
            sv_delivered="$sv_delivered $sv_step=$sv_verdict"
        done <<< "$sv_terminals"
        sv_delivered="$(printf '%s' "$sv_delivered" | tr -s ' ' | sed 's/^ //;s/ $//')"

        # Which verdict belongs to which step is the point; the order is not.
        local sv_expected sv_delivered_sorted sv_expected_sorted
        sv_expected="$(printf '%s' "$step_verdict" | tr -s ' ' | sed 's/^ //;s/ $//')"
        sv_delivered_sorted="$(printf '%s\n' $sv_delivered | sort | tr '\n' ' ')"
        sv_expected_sorted="$(printf '%s\n' $sv_expected | sort | tr '\n' ' ')"
        if [[ "$sv_delivered_sorted" != "$sv_expected_sorted" ]]; then
            printf 'FAIL %-52s the external reader could not read the step verdicts\n' "$name"
            printf '       | expected:  %s\n' "$sv_expected_sorted"
            printf '       | delivered: %s\n' "$sv_delivered_sorted"
            failed=$((failed + 1))
            failures+=("$name: the external reader could not read the step verdicts")
            return
        fi
    fi

    if [[ -n "$failure_stage" ]]; then
        # The scenario declares which stage each failure belongs to, and the launcher
        # is an EXTERNAL consumer of the event lane: it asks for the failure events
        # ONLY — never the step's terminal event, never the stage's own events — and
        # reads the stage each one names.
        #
        # The failure event used to name the step and not the stage, and the step
        # index is the index INSIDE a stage, so the same index means a different step
        # in each stage of the run: the only way to learn the stage was to join the
        # failure with another event. A failure that names no stage is not a failure
        # in the first stage, and a consumer that reads the absent stage as stage 0
        # invents a stage. That is the defect this scenario exists to catch, and it
        # is invisible to a reader that only looks at runs that succeeded.
        local fs_run_id=""
        local record
        for record in "$root"/control/last-run/*; do
            [[ -f "$record" ]] || continue
            fs_run_id="$(head -1 "$record")"
            break
        done
        if [[ -z "$fs_run_id" ]]; then
            printf 'FAIL %-52s the run left no run id, so there is no history to read\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run left no run id")
            return
        fi

        local fs_code=0
        local fs_failures
        fs_failures="$("$binary" events "$fs_run_id" --db "$root/events.db" \
            --kind StepFailed --typed 2>"$root/stderr.failure-stage.txt")" || fs_code=$?
        if [[ "$fs_code" != 0 ]]; then
            printf 'FAIL %-52s reading the failure events exited %s\n' "$name" "$fs_code"
            show_printed "$root/stderr.failure-stage.txt"
            failed=$((failed + 1))
            failures+=("$name: reading the failure events exited $fs_code")
            return
        fi

        # Each failure event, read as "<step>=<stage>". A failure event that carries
        # no stage reads as "<step>=-1", the wire's own encoding of an absent stage,
        # and that half is the whole difference the scenario is about.
        local fs_delivered="" fs_line fs_step fs_stage
        while IFS= read -r fs_line; do
            [[ -z "$fs_line" ]] && continue
            fs_step="$(printf '%s' "$fs_line" \
                | sed -n 's/.*"stepName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
            fs_stage="$(printf '%s' "$fs_line" \
                | sed -n 's/.*"stageIndex"[[:space:]]*:[[:space:]]*\(-\?[0-9]*\).*/\1/p')"
            fs_delivered="$fs_delivered $fs_step=$fs_stage"
        done <<< "$fs_failures"
        fs_delivered="$(printf '%s' "$fs_delivered" | tr -s ' ' | sed 's/^ //;s/ $//')"

        # Which stage belongs to which failure is the point; the order is not.
        local fs_expected fs_delivered_sorted fs_expected_sorted
        fs_expected="$(printf '%s' "$failure_stage" | tr -s ' ' | sed 's/^ //;s/ $//')"
        fs_delivered_sorted="$(printf '%s\n' $fs_delivered | sort | tr '\n' ' ')"
        fs_expected_sorted="$(printf '%s\n' $fs_expected | sort | tr '\n' ' ')"
        if [[ "$fs_delivered_sorted" != "$fs_expected_sorted" ]]; then
            printf 'FAIL %-52s the external reader could not attribute the failure to its stage\n' "$name"
            printf '       | expected:  %s\n' "$fs_expected_sorted"
            printf '       | delivered: %s\n' "$fs_delivered_sorted"
            failed=$((failed + 1))
            failures+=("$name: the external reader could not attribute the failure to its stage")
            return
        fi
    fi

    local label="$name"
    if [[ "$cancel" == 1 ]]; then
        label="$name (cancel)"
    elif [[ "$events_refusal" != "" ]]; then
        label="$name (events-refusal)"
    elif [[ "$events_resume" != "" ]]; then
        label="$name (events-resume)"
    elif [[ "$step_verdict" != "" ]]; then
        label="$name (step-verdict)"
    elif [[ "$failure_stage" != "" ]]; then
        label="$name (failure-stage)"
    elif [[ "$durable" == 1 ]]; then
        label="$name (durable x2)"
    elif [[ "$durable_once" == 1 ]]; then
        label="$name (durable x1)"
    elif [[ "$resume" == 1 ]]; then
        label="$name (resume x2)"
    fi

    # Checks ONE execution against every expectation the scenario declares.
    # Declared inside the scenario so it reads the scenario's own expectations
    # without inventing a second place to keep them.
    #
    # $1 label, $2 stdout file, $3 stderr file, $4 exit code, $5 control root.
    # Returns non-zero when an expectation did not hold, and leaves the reason
    # in $check_detail for the caller to report.
    check_execution() {
        local ex_label="$1" ex_stdout="$2" ex_stderr="$3" ex_code="$4" ex_control="$5"
        local wanted

        if [[ "$ex_code" != "$expect_exit" ]]; then
            printf 'FAIL %-52s exit %s, expected %s\n' "$ex_label" "$ex_code" "$expect_exit"
            show_printed "$ex_stdout"
            show_printed "$ex_stderr"
            check_detail="exit $ex_code, expected $expect_exit"
            return 1
        fi

        # -a porque la salida puede traer bytes NUL, y sin el fallo se reporta
        # como "fichero binario" sin la coincidencia, indistinguible de su ausencia.
        for wanted in ${expect_stdout[@]+"${expect_stdout[@]}"}; do
            if ! grep -aqF -- "$wanted" "$ex_stdout"; then
                printf 'FAIL %-52s exit %s as expected, but stdout lacked: %s\n' \
                    "$ex_label" "$ex_code" "$wanted"
                show_printed "$ex_stdout"
                check_detail="stdout lacked: $wanted"
                return 1
            fi
        done
        for wanted in ${expect_stderr[@]+"${expect_stderr[@]}"}; do
            if ! grep -aqF -- "$wanted" "$ex_stderr"; then
                printf 'FAIL %-52s exit %s as expected, but stderr lacked: %s\n' \
                    "$ex_label" "$ex_code" "$wanted"
                show_printed "$ex_stderr"
                check_detail="stderr lacked: $wanted"
                return 1
            fi
        done

        # The committed transcript. The Output Plane holds every byte a step
        # printed, and the product's own `console` verb is the reader for it.
        # The op ids are the stream directories the plane created, so the
        # launcher asks for each of them rather than composing one: a name it
        # invented would be refused, and a refusal here would be a launcher
        # bug wearing a scenario's clothes.
        if (( ${#expect_transcript[@]} > 0 )); then
            local transcript="$root/transcript.$(basename "$ex_control").txt"
            local run_id
            run_id="$(grep -ao '"runId":"[^"]*"' "$ex_stderr" | head -1 | cut -d'"' -f4)"
            if [[ -z "$run_id" ]]; then
                printf 'FAIL %-52s the run published no run id, so its transcript cannot be read\n' \
                    "$ex_label"
                check_detail="no run id in the launch receipt"
                return 1
            fi
            : >"$transcript"
            local stream_dir op_id
            for stream_dir in "$ex_control/output-plane/streams"/*_stdout; do
                [[ -d "$stream_dir" ]] || continue
                op_id="$(basename "$stream_dir")"
                # The directory is <runId>_<opId>_stdout.
                op_id="${op_id#${run_id}_}"
                op_id="${op_id%_stdout}"
                "$binary" console --control-dir "$ex_control" "$run_id" "$op_id" \
                    >>"$transcript" 2>/dev/null || true
            done

            for wanted in ${expect_transcript[@]+"${expect_transcript[@]}"}; do
                if ! grep -aqF -- "$wanted" "$transcript"; then
                    printf 'FAIL %-52s the committed transcript lacked: %s\n' \
                        "$ex_label" "$wanted"
                    head -40 "$transcript" | sed 's/^/       | /'
                    check_detail="transcript lacked: $wanted"
                    return 1
                fi
            done
        fi
        return 0
    }

    # The execution that decides the verdict, against every expectation the
    # scenario declares. For a resumed scenario it is the SECOND run: the
    # first one died before it could say anything.
    local check_detail=""
    if ! check_execution "$label" "$verdict_stdout" "$verdict_stderr" "$code" "$root/control"; then
        failed=$((failed + 1))
        failures+=("$label: $check_detail")
        return
    fi

    # The volatile path, when the scenario ran it. Both paths are held to the
    # same expectations: a scenario is ok when BOTH met them.
    if [[ "$run_volatile" == 1 ]]; then
        if ! check_execution "$label (volatile)" "$vstdout" "$vstderr" "$vcode" \
            "$root/control-volatile"; then
            failed=$((failed + 1))
            failures+=("$label (volatile): $check_detail")
            return
        fi
    fi

    # The second half of the durable guarantee: the resumed run must not have
    # touched the workspace. A completed effect whose completion was reused
    # does not happen twice, and the only place that shows up is the state of
    # the world.
    #
    # For `resume` this is the whole point of the directive. The first run
    # died in the middle of an effect, and the resumed run has to decide what
    # to do about a call whose answer nobody has. If it dispatches the call
    # again, the world changes here — twice the effect, and a row that looks
    # exactly like a first attempt.
    if [[ "$resume" == 1 ]]; then
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

if (( ${#selected[@]} == 0 )); then
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

# The three counters must reconcile before the summary is published: every
# scenario the launcher selected either reached a verdict or failed, and one
# that reached neither was skipped. A skip is a gap, not a pass, so it is
# declared here rather than left for whoever reads the counts to notice. The
# gate derives the same gap from the same three numbers, and the two cannot
# disagree because neither invents a fourth.
if (( passed + failed != scenario_index )); then
    printf 'run.sh: %d scenario(s) selected, %d passed, %d failed -- %d skipped.\n' \
        "$scenario_index" "$passed" "$failed" "$(( scenario_index - passed - failed ))" >&2
    printf '  A skipped scenario is a declared gap, not a pass.\n' >&2
    exit 1
fi

printf '\n%d scenario(s), %d passed, %d failed, %d credential(s) seeded, %d durable rerun(s)\n' \
    "$scenario_index" "$passed" "$failed" "$seeded" "$durable_runs"

# A capability with no scenario exercising it is not covered, it is merely
# unobserved -- and unobserved is indistinguishable from absent from the outside.
# The durable double-run branch below sat behind `//@ durable` while not one
# scenario declared it, so the whole replay path reported green for as long as
# anybody cared to look. A count is therefore published in the summary above,
# and a FULL inventory run refuses to be green without at least one rerun. The
# check is scoped to the unfiltered run on purpose: a `--scenario` filter is a
# caller choosing which slice to run, and forcing durable coverage there would
# punish the inner loop for using the filter at all.
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