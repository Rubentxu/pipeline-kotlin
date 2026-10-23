#!/usr/bin/env bash
# The ONE E2E launcher (AGENTS.md section 3).
# It runs real.pipeline.kts programs against the compiled distribution. It is
# deliberately not a framework: it discovers scenarios, gives each one a
# private control root, checks the exit code, and cleans up. It has no notion of
# assertions beyond the optional per-pipeline directives below, so a scenario
# cannot grow into a test suite with its own vocabulary.
#   //@ expect-exit 1                 default is 0
#   //@ expect-outcome <token>       the result the RUN must conclude, in the
#                                    # product's own vocabulary: success,
#                                    # unstable or failure. The default follows
#                                    # the expected exit: a run that must end
#                                    # well is expected to conclude success, and
#                                    # a run that must fail is expected to
#                                    # conclude failure. The token is needed
#                                    # for the case the exit code cannot
#                                    # express: a run that caught a failure and
#                                    # continued ends well AND is not a success,
#                                    # so say so and the launcher checks the
#                                    # conclusion the run published.
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
#                                     `bundled-uppercase` is the distribution's own
#                                     copy of the example plugin. `repacked-uppercase`
#                                     is that same plugin packaged a second time: the
#                                     same manifest, the same code, different bytes.
#                                     Two artifacts claiming one identity.
#   //@ distribution <fixture>        the scenario runs against its OWN copy of the
#                                     installed distribution, with one thing changed
#                                     inside it. `lying-uppercase` replaces the
#                                     distribution's own plugin with one whose
#                                     manifest declares a Step it does not implement:
#                                     the installation that ADMITS and then lies, which
#                                     only a second gate can catch. `lying-capabilities`
#                                     declares a capability the plugin's own contract
#                                     never requires, `lying-directives` declares a
#                                     Directive nobody contributes, and `lying-events`
#                                     declares an Event nobody contributes.
#                                     `lying-capabilities-omitted` is the OTHER
#                                     direction: the plugin's contract requires a
#                                     capability and the manifest omits it.
#   //@ timeout <seconds>            the clock this scenario gets. The default is
#                                    an order of magnitude above what any scenario
#                                    takes today, so the clock only fires on a
#                                    scenario that is stuck -- and then that
#                                    scenario is STOPPED and named, instead of the
#                                    whole run being paralyzed by it.
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

# The product's path, as a function and NOT as a variable. A variable would be
# a door: a later edit could invoke the product through it and escape the
# clock, and a hung scenario that escapes the clock is the one failure this
# whole mechanism exists to prevent. As a function, the only ways to reach
# the product are the two below, and BOTH of them apply a clock.
product_path() {
    # A scenario that runs against its OWN distribution says so here, and only
    # here. The clock is applied to whatever this function returns, so an
    # override cannot become a way to run the product without a ceiling. The
    # variable it reads is a LOCAL of the scenario body, so it dies with the
    # scenario and no scenario inherits another's distribution.
    if [[ -n "${scenario_product:-}" ]]; then
        printf '%s' "$scenario_product"
        return
    fi
    printf '%s' "$repo_root/pipelinek/pipeline-application/build/install/pipelinek/bin/pipelinek"
}

if [[ ! -x "$(product_path)" ]]; then
    echo "run.sh: no distribution at $(product_path)" >&2
    echo "  build it first: cd pipelinek && ./gradlew :pipeline-application:installDist" >&2
    exit 2
fi

cycle_root="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-e2e-XXXXXXXX")"
cleanup() { rm -rf "$cycle_root"; }
trap cleanup EXIT

# The product compiles every script it runs, and that compilation is the
# dominant cost of a scenario: a pipeline that takes a second to execute
# spends several compiling. Measured on one scenario of this suite, a run
# costs about 27 seconds of CPU without the compiled-script cache and about
# 17 with it, because a run compiles the SAME source twice and the cache
# serves the second one.
#
# The product keeps that cache off by default, and for a good reason: there
# is no user-home directory that is the right place for it in a read-only
# HOME, in a sandboxed CI or in an ephemeral container, and guessing one is a
# product decision rather than a harness one. This launcher has no such
# problem. The cycle directory above is its own, and the trap above deletes
# it with everything else, so the cache is a directory the cycle OWNS: it
# never outlives the run, it never accumulates in someone's home, and two
# invocations of this script never share an artifact.
#
# The cache changes nothing about what is checked. The key is the source text
# together with the classpath and the versions, so an edited scenario is a
# different key and is compiled again; and a stored artifact that cannot be
# used degrades to a miss inside the product, never to a wrong answer.
export PIPELINEK_SCRIPT_CACHE_DIR="$cycle_root/script-cache"

passed=0
failed=0
blocked=0
seeded=0
durable_runs=0
failures=()

scenario_index=0

# --- The clocks ---------------------------------------------------------------
#
# Two clocks, and both of them are the launcher's to keep. A hung test must
# name itself instead of paralyzing the delivery, and that takes two limits:
# one that says how long a SCENARIO may take, and one that says how long the
# whole STAGE may take.
#
# The scenario clock defaults to 120 s. Measured over the whole suite, the
# slowest scenario takes 21 s and the median 11 s, so the default is roughly
# six times the worst case observed: it never fires on a healthy run, and it
# fires long before the gate's own ceiling. A scenario that needs longer says
# so with `//@ timeout <seconds>`.
#
# The stage clock defaults to 780 s, and the gate tells the launcher how much
# of ITS budget is left (PIPELINEK_E2E_BUDGET_SECONDS) so the two clocks can
# never disagree: the launcher stops first, and it says which scenarios it did
# not get to, instead of the gate dying without a name.
SCENARIO_TIMEOUT_SECONDS="${PIPELINEK_E2E_SCENARIO_TIMEOUT_SECONDS:-120}"
STAGE_TIMEOUT_SECONDS="${PIPELINEK_E2E_BUDGET_SECONDS:-780}"
stage_deadline=$(( $(date +%s) + STAGE_TIMEOUT_SECONDS ))

# The scenario's own clock, set by run_scenario before its body runs: the
# deadline in epoch seconds, the budget it was given, and the file that
# records that the clock fired.
scenario_deadline=0
scenario_budget="$SCENARIO_TIMEOUT_SECONDS"
clock_marker=""

# The clock has to WORK, not merely exist: a launcher whose clock never fires
# lets a hung scenario hang the run, which is the very thing the clock is
# for. So the instrument is checked once, at startup, against a command that
# cannot finish on time. It costs one second and it is the difference
# between "the launcher has a clock" and "the launcher's clock works".
clock_works() {
    local marker="$cycle_root/clock-selfcheck.pid"
    local rc=0
    timeout --kill-after=1s 2s bash -c "sleep 30 & echo \$! >'$marker'; sleep 30" \
        >/dev/null 2>&1 || rc=$?
    (( rc == 124 )) || return 1
    [[ -s "$marker" ]] || return 1
    local grandchild
    grandchild="$(cat "$marker")"
    # The signal goes to the group; give it a moment to land before calling
    # the tree alive.
    sleep 1
    if kill -0 "$grandchild" 2>/dev/null; then
        kill -KILL "$grandchild" 2>/dev/null || true
        return 1
    fi
    return 0
}

if ! clock_works; then
    echo "run.sh: the timeout instrument does not work on this machine" >&2
    echo "  a launcher whose clock cannot fire, or cannot take the tree with it," >&2
    echo "  would let a hung scenario hang the run." >&2
    exit 2
fi

# The product, under the scenario's clock.
#
# The exit code 124 is GNU timeout's "I had to stop it", and it is the ONE
# code the launcher reads as a clock event rather than as a product verdict.
# `timeout` puts the command in its own process group and signals the GROUP,
# so a pipeline that leaves a shell behind does not leave the run hanging:
# the tree goes down with the clock.
binary() {
    local left
    left=$(( scenario_deadline - $(date +%s) ))
    if (( left <= 0 )); then
        touch "$clock_marker"
        return 124
    fi
    timeout --kill-after=5s "$left" "$(product_path)" "$@"
    local rc=$?
    if (( rc == 124 )); then
        touch "$clock_marker"
    fi
    return "$rc"
}

# The product, under an EXPLICIT budget, for the one case that has one: the
# follow, whose scenario says how long it is allowed to wait. The scenario's
# own clock still binds -- the explicit budget can only shorten what is left,
# never lengthen it -- and when it is the scenario's clock that stops the
# command, the clock marker says so, because a follow that ran out of the
# SCENARIO's time is a different fact from a follow that ran out of its own.
binary_within() {
    local budget="$1"; shift
    local left
    left=$(( scenario_deadline - $(date +%s) ))
    if (( left <= 0 )); then
        touch "$clock_marker"
        return 124
    fi
    local capped="$budget"
    if (( capped > left )); then capped="$left"; fi
    timeout --kill-after=5s "$capped" "$(product_path)" "$@"
    local rc=$?
    if (( rc == 124 && capped == left )); then
        touch "$clock_marker"
    fi
    return "$rc"
}

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

# A plugin whose manifest says more than it provides.
#
# The copy is the distribution's own plugin, byte for byte, with ONE thing changed: its manifest
# declares a Step the plugin does not implement. Nothing else is touched, so the artifact still
# passes admission -- the manifest is well formed, the identity is the publisher's, the version is
# the publisher's -- and the run reaches the second gate, the one that compares what the manifest
# DECLARED against what the contributor ACTUALLY provided.
#
# The change is legal to make because the manifest is excluded from the digest the plugin build
# measures: that digest is provenance, it says which release the manifest describes, and the runtime
# never compares it with the digest of the file that carries it. A manifest that lies about its
# Steps is a valid document. Only loading the plugin's code and comparing the two sides can catch
# it, which is exactly what the scenario needs to observe.
build_lying_plugin_fixture() {
    local jar="$1"
    local kind="$2"
    local ghost_key="$3"

    command -v unzip >/dev/null || {
        printf 'run.sh: unzip is required to read the plugin manifest for the lying fixture\n' >&2
        return 1
    }
    command -v zip >/dev/null || {
        printf 'run.sh: zip is required to write the plugin manifest for the lying fixture\n' >&2
        return 1
    }

    local workdir
    workdir="$(mktemp -d)" || {
        printf 'run.sh: could not create a work directory for the lying fixture\n' >&2
        return 1
    }

    local manifest_entry="META-INF/pipelinek/plugin-manifest.json"
    ( cd "$workdir" && unzip -qo "$jar" "$manifest_entry" ) || {
        printf 'run.sh: could not read the plugin manifest from %s\n' "$jar" >&2
        rm -rf "$workdir"
        return 1
    }

    local manifest="$workdir/$manifest_entry"
    local rewritten="$workdir/plugin-manifest.json"
    # The doctoring, one family at a time. In every case ONE thing changes and NOTHING else does:
    # the identity, the version, the publisher, the api range and every other declared
    # contribution stay the publisher's own.
    #
    #   steps        a Step the plugin does not implement is declared.
    #   capabilities a capability the plugin's contract does not require is declared for one of
    #                its Steps: the plugin says it needs something its own code never asks for.
    #   capabilities-omitted
    #                the OTHER direction: the plugin's contract requires a capability and the
    #                manifest does not declare it for that Step. Here the drift is a MISSING
    #                capability, so the check below looks for the ABSENCE of the drift, not for
    #                its presence.
    #   directives   a Directive nobody contributes is declared.
    #   events       an Event nobody contributes is declared.
    #
    # Each substitution is anchored on a literal that the manifest really contains, and the result
    # is checked twice: the document must have CHANGED, and the drift must be IN it -- or, for the
    # one kind whose drift is an absence, it must be GONE from it. A manifest whose shape moved
    # would leave the document untouched, the fixture would fail loudly, and the scenario would
    # go RED on the missing refusal -- never green on a fixture that stopped lying.
    #
    # The drift is checked by the SENSE of the change, not by the key alone: the omitted
    # capability is still in the manifest's global capability list, so looking for it would find
    # it there and prove nothing about the per-Step declaration, which is the thing under test.
    drift_check="$ghost_key"
    drift_present=1
    case "$kind" in
        steps)
            sed "s|\"steps\": \[|\"steps\": [{\"stepKey\": \"$ghost_key\", \"declaredCapabilities\": []}, |" \
                "$manifest" >"$rewritten"
            ;;
        capabilities)
            sed "0,/\"declaredCapabilities\": \[\]/s||\"declaredCapabilities\": [\"$ghost_key\"]|" \
                "$manifest" >"$rewritten"
            ;;
        capabilities-omitted)
            sed "s|\"declaredCapabilities\": \[\"example.uppercase.case-table\", \"$ghost_key\"\]|\"declaredCapabilities\": [\"example.uppercase.case-table\"]|" \
                "$manifest" >"$rewritten"
            drift_check="\"declaredCapabilities\": [\"example.uppercase.case-table\", \"$ghost_key\"]"
            drift_present=0
            ;;
        directives)
            sed "s|\"directives\": \[|\"directives\": [\"$ghost_key\", |" \
                "$manifest" >"$rewritten"
            ;;
        events)
            sed "s|\"events\": \[|\"events\": [\"$ghost_key\", |" \
                "$manifest" >"$rewritten"
            ;;
        *)
            printf 'run.sh: unknown lying-plugin kind: %s\n' "$kind" >&2
            rm -rf "$workdir"
            return 1
            ;;
    esac
    if [[ ! -s "$rewritten" ]]; then
        printf 'run.sh: could not write the lying plugin manifest\n' >&2
        rm -rf "$workdir"
        return 1
    fi
    if cmp -s "$manifest" "$rewritten"; then
        printf 'run.sh: the lying fixture could not apply a %s drift to the plugin manifest\n' "$kind" >&2
        rm -rf "$workdir"
        return 1
    fi
    if (( drift_present == 1 )); then
        if ! grep -aqF -- "$drift_check" "$rewritten"; then
            printf 'run.sh: the lying fixture lost its %s drift on the way into the manifest\n' "$kind" >&2
            rm -rf "$workdir"
            return 1
        fi
    else
        # The drift is an ABSENCE: the fixture proves it by the literal that used to be there
        # no longer being there. Finding it would mean the fixture stopped lying.
        if grep -aqF -- "$drift_check" "$rewritten"; then
            printf 'run.sh: the lying fixture lost its %s drift on the way into the manifest\n' "$kind" >&2
            rm -rf "$workdir"
            return 1
        fi
    fi

    mv "$rewritten" "$manifest" || {
        printf 'run.sh: could not replace the plugin manifest\n' >&2
        rm -rf "$workdir"
        return 1
    }
    ( cd "$workdir" && zip -q "$jar" "$manifest_entry" ) || {
        printf 'run.sh: could not write the lying manifest back into %s\n' "$jar" >&2
        rm -rf "$workdir"
        return 1
    }
    rm -rf "$workdir"
}

# The same plugin, packaged twice.
#
# Two artifacts that claim one identity only make a question when they are two ARTIFACTS: the
# same bytes in two files are one artifact twice, and the runtime is right to read them that way.
# This fixture produces the pair without inventing a second defect. The manifest is the
# publisher's, byte for byte, so the identity is the publisher's and the artifact is honest about
# what it declares; the code is the publisher's, so it is a valid plugin; only the PACKAGING is
# different, which is what a rebuild is.
#
# The fixture checks the two claims it makes, because a fixture that quietly stopped being what
# it says would leave the scenario green for the wrong reason: the two files must differ, and the
# manifest must not. A repack that came out byte for byte identical leaves nothing to refuse, and
# that is a loud failure here, not a green scenario.
build_repacked_plugin_fixture() {
    local source_jar="$1"
    local target_jar="$2"

    command -v unzip >/dev/null || {
        printf 'run.sh: unzip is required to repack a plugin fixture\n' >&2
        return 1
    }
    command -v zip >/dev/null || {
        printf 'run.sh: zip is required to repack a plugin fixture\n' >&2
        return 1
    }

    local workdir
    workdir="$(mktemp -d)" || {
        printf 'run.sh: could not create a work directory for the repacked fixture\n' >&2
        return 1
    }

    local contents="$workdir/contents"
    if ! ( cd "$workdir" && unzip -qo "$source_jar" -d contents ); then
        printf 'run.sh: could not unpack the plugin fixture %s\n' "$source_jar" >&2
        rm -rf "$workdir"
        return 1
    fi

    # A fixed timestamp for every entry: the packaging changes, the contents do not.
    find "$contents" -exec touch -t 202001010000 {} + 2>/dev/null || true

    if ! ( cd "$contents" && zip -q -r -X "$target_jar" . ); then
        printf 'run.sh: could not repack the plugin fixture\n' >&2
        rm -rf "$workdir"
        return 1
    fi
    rm -rf "$workdir"

    if cmp -s "$source_jar" "$target_jar"; then
        printf 'run.sh: the repacked plugin fixture is byte for byte the original\n' >&2
        return 1
    fi

    local manifest_entry="META-INF/pipelinek/plugin-manifest.json"
    local source_manifest target_manifest
    source_manifest="$(unzip -p "$source_jar" "$manifest_entry" 2>/dev/null || true)"
    target_manifest="$(unzip -p "$target_jar" "$manifest_entry" 2>/dev/null || true)"
    if [[ -z "$source_manifest" || "$source_manifest" != "$target_manifest" ]]; then
        printf 'run.sh: the repacked plugin fixture does not carry the publisher manifest\n' >&2
        return 1
    fi
}
# the plugin's bytes, and the row that pins those bytes and names the publisher and the key.
#
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

# The identity of a run, normalized away.
#
# Two runs of the same pipeline are two different runs: they have different ids,
# different owners, different starting times and different temporary names. A
# comparison that cannot tell an identifier from a difference reports a
# divergence on every scenario, and a check that is always red is a check nobody
# reads.
#
# The long numbers are the ones that make a temporary name unique: the product
# materializes a credential into a file whose name carries a number no other
# run would use, so two runs cannot produce the same bytes there and the
# difference says nothing about the run.
normalize_identity() {
    LC_ALL=C sed -E \
        -e 's/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/<id>/g' \
        -e 's/"ownerId":"[^"]*"/"ownerId":"<owner>"/g' \
        -e 's/"startedAtMs":[0-9]+/"startedAtMs":<clock>/g' \
        -e 's/"controlDir":"[^"]*"/"controlDir":"<control>"/g' \
        -e 's/[0-9]{6,}/<n>/g'
}

# The id of the run that wrote a launch receipt.
run_id_of() {
    grep -ao '"schema":"pipelinek.launch/1","runId":"[^"]*"' "$1" 2>/dev/null \
        | head -1 | sed 's/.*"runId":"//; s/"$//' || true
}

# The narration of a run, from the moment the run starts.
#
# A process that prints without a trailing newline leaves the console mid-line,
# and the next narration line lands on top of it. The narration is read from the
# marker, not from the beginning of the line, so a line that was glued to the
# output of a process is still the narration it is.
#
# The story starts where the run starts. What came before is the PREPARATION --
# the correlation and the compilation -- and the two worlds are deliberately
# different there: the launcher compiles the script once and lets the second
# world reuse that compilation, and a run that is refused before it starts
# never prints the preamble at all in one of them. What the run DID is what has
# to be the same in both worlds.
narration_of() {
    LC_ALL=C sed 's/\[PipelineK\] /\n[PipelineK] /g' "$1" 2>/dev/null \
        | grep -a '^\[PipelineK\]' \
        | awk '/^\[PipelineK\] Run started: / { story = 1 } story' || true
}

# What a run SAYS: the story it told and the diagnostics it wrote.
#
# The two worlds live in different directories, and the place a run lives is not
# part of what it says, so the two roots are normalized away before the
# comparison.
#
# The process output is NOT part of it. The observer that delivers those bytes
# polls, so the place where they land between two console lines is a projection
# and not a promise; the account is the run's own narration, in the order the
# run produced it.
world_account() {
    local root="$3"
    {
        narration_of "$1"
        grep -av '^[[:space:]]*$' "$2" 2>/dev/null || true
    } | sed -e "s#${root}/cwd-volatile#<cwd>#g" \
            -e "s#${root}/cwd#<cwd>#g" \
            -e "s#${root}/control-volatile#<control>#g" \
            -e "s#${root}/control#<control>#g" \
          | normalize_identity
}

# What a run COMMITTED: the bytes of every output stream, read back with the
# product's own reader.
#
# The reader is the product's, so this asks the run what a consumer would see
# instead of guessing at the layout of the plane.
world_committed() {
    local control="$1" run_id="$2"
    local stream_dir op_id
    for stream_dir in "$control/output-plane/streams"/*_stdout; do
        [[ -d "$stream_dir" ]] || continue
        op_id="$(basename "$stream_dir")"
        op_id="${op_id#${run_id}_}"
        op_id="${op_id%_stdout}"
        binary console --control-dir "$control" "$run_id" "$op_id" 2>/dev/null || true
    done
}

# One scenario, under its clock.
#
# The body does the work and reaches a verdict of its own; this wrapper owns
# the clock and owns the LAST word. A scenario whose clock fired did NOT check
# its guarantee -- the stop is not a product verdict -- so it is declared
# BLOCKED, its verdict is withdrawn, and the run is not green.
#
# The wrapper is also where the scenario is counted, so the count and the
# clock cannot disagree: a scenario is counted exactly once, and it is counted
# as exactly one of passed, failed or blocked.
run_scenario() {
    local script="$1"
    local name="${script#"$here"/}"

    scenario_index=$((scenario_index + 1))

    # What this scenario's checks will PROVE, and how much machinery they ran
    # through. They are reset here, before anything can fail, so a scenario
    # that never reaches its body reports no guarantees rather than the
    # previous scenario's. The marker writer reads them once the body is done.
    scenario_guarantees=0
    scenario_executions=0
    scenario_steps=0
    scenario_outcome="none"

    local started_ms
    started_ms="$(now_ms)"

    # The budget this scenario asked for, or the default.
    local budget="$SCENARIO_TIMEOUT_SECONDS"
    local directive
    while IFS= read -r directive; do
        case "$directive" in
            '//@ timeout '*) budget="${directive#//@ timeout }" ;;
        esac
    done < <(grep -m 20 '^//@ ' "$script" || true)
    if [[ ! "$budget" =~ ^[0-9]+$ ]] || (( budget <= 0 )); then
        printf 'FAIL %-52s the timeout must be a positive number of seconds, got: %s\n' \
            "$name" "$budget"
        failed=$((failed + 1))
        failures+=("$name: the timeout directive is not a positive number of seconds")
        emit_scenario_markers "$name" 0 0 0 0 "$started_ms"
        return
    fi

    local slug
    slug="$(printf '%s' "$name" | tr '/.' '--')"
    local root="$cycle_root/$slug"
    mkdir -p "$root"

    scenario_budget="$budget"
    scenario_deadline=$(( $(date +%s) + budget ))
    clock_marker="$root/clock-exceeded"
    rm -f "$clock_marker"

    local before_passed="$passed" before_failed="$failed" before_failures="${#failures[@]}"
    local before_blocked="$blocked" before_seeded="$seeded" before_durable="$durable_runs"

    run_scenario_body "$script"

    if [[ -f "$clock_marker" ]]; then
        # The clock fired. The body's verdict was a consequence of the stop, so
        # it is withdrawn: the guarantee was not checked, and a guarantee that
        # was not checked is a declared gap, not a pass and not a product
        # failure.
        if (( passed > before_passed )); then passed=$((passed - 1)); fi
        if (( failed > before_failed )); then failed=$((failed - 1)); fi
        blocked=$((blocked + 1))
        printf 'BLOCKED %-52s the clock of %ss expired: the guarantee was NOT checked\n' \
            "$name" "$scenario_budget"
        # The body's own entry says what the stop looked like from inside
        # ("exit 124, expected 0"); the clock says what it WAS. The clock wins,
        # because it is the cause, and one scenario must appear once in this
        # list.
        if (( ${#failures[@]} > before_failures )); then
            failures[${#failures[@]}-1]="$name: the clock of ${scenario_budget}s expired; the guarantee was NOT checked"
        else
            failures+=("$name: the clock of ${scenario_budget}s expired; the guarantee was NOT checked")
        fi
    fi

    emit_scenario_markers "$name" "$before_failed" "$before_failures" "$before_blocked" \
        "$started_ms" "$before_seeded" "$before_durable"
}

# The verdict of a scenario travels as TEXT, not as a counter.
#
# The scenarios run in parallel, each in its own subshell, so a counter the
# parent incremented is a counter that died with the subshell: the parent would
# read zero and print a green over a stage where nothing was counted. The
# scenario therefore writes what it decided into its own output, and the parent
# reads it back once every scenario has finished. The markers are stripped
# before the report is printed -- they are the machine's reading of the file,
# not a line anybody asked for.
emit_scenario_markers() {
    local name="$1" before_failed="$2" before_failures="$3" before_blocked="$4"
    local started_ms="$5" before_seeded="${6:-0}" before_durable="${7:-0}"

    local verdict="ok"
    if (( blocked > before_blocked )); then
        verdict="blocked"
    elif (( failed > before_failed )); then
        verdict="fail"
    fi

    printf '__VERDICT__ %s\n' "$verdict"
    printf '__COUNTS__ observed=%s executions=%s steps=%s outcome=%s durable=%s seeded=%s\n' \
        "$scenario_guarantees" "$scenario_executions" "$scenario_steps" "$scenario_outcome" \
        "$(( durable_runs - before_durable ))" "$(( seeded - before_seeded ))"
    printf '__ELAPSED__ %s\n' "$(( $(now_ms) - started_ms ))"
    local reason
    for (( i = before_failures; i < ${#failures[@]}; i++ )); do
        printf '__FAILURE__ %s\n' "${failures[$i]}"
    done
}

# What the harness COMPARED for one scenario, as opposed to what it merely ran.
#
# The census used to count what a scenario DECLARED, and that was the wrong
# number to count. A scenario whose guarantee lives in the pipeline's own steps
# -- a step that fails the run when the product misbehaves -- declares
# nothing, and the count said it asserted nothing while the exit code it
# watched was carrying exactly that guarantee. A metric that misreports what
# the suite buys does not only mislead: it points the work at declarations
# instead of guarantees, and a declaration is the cheapest thing to add.
#
# What is counted here is what the harness COMPARED: text the run had to
# produce, text the refusal had to contain, the bytes the run had to commit,
# and a non-zero exit that is a guarantee rather than an accident. The default
# exit of zero is what a process returns when it did not crash, so checking it
# is checking that nothing happened.
count_content_expectations() {
    local name="$1"
    local script="$here/$name"
    local count=0 directive
    while IFS= read -r directive; do
        case "$directive" in
            '//@ expect-stdout '*|'//@ expect-stderr '*|'//@ expect-transcript '*)
                count=$((count + 1)) ;;
            '//@ expect-exit '*)
                [[ "${directive#//@ expect-exit }" != "0" ]] && count=$((count + 1)) ;;
        esac
    done < <(grep -m 40 '^//@ expect-' "$script" || true)
    printf '%s' "$count"
}

# The run's own conclusion, read from the product.
#
# The exit code says whether the process ended well. It does not say what the
# RUN concluded, nor that the run concluded anything at all: a process that
# dies between its last step and its terminal bookend leaves an exit code and
# no verdict. The product publishes that conclusion as the terminal bookend of
# its event lane, and the launcher reads it from there, so the verdict and the
# exit code are two independent facts about the same run and can be compared.
#
# Prints `outcome=<word> steps=<n>`, or nothing when the run left no verdict.
# The absence is a fact the caller has to handle: it is the difference
# between "the run failed" and "the run said nothing".
read_run_verdict() {
    local db="$1" run_id="$2"
    local events=""
    events="$(binary events "$run_id" --db "$db" --typed 2>/dev/null)" || return 1
    local bookend=""
    bookend="$(grep -m1 '"kind":"RunFinished"' <<<"$events" || true)"
    [[ -n "$bookend" ]] || return 1
    local outcome=""
    outcome="$(sed -n 's/.*"outcome":"\([a-z]*\)".*/\1/p' <<<"$bookend")"
    [[ -n "$outcome" ]] || return 1
    local steps=0
    steps="$(grep -c '"kind":"StepFinished"' <<<"$events" || true)"
    printf 'outcome=%s steps=%s\n' "$outcome" "$steps"
}

# One scenario, fully isolated. Nothing is read from the developer's HOME and
# nothing is written outside $cycle_root.
run_scenario_body() {
    local script="$1"
    local relative="${script#"$here"/}"
    local name="$relative"
    local slug
    slug="$(printf '%s' "$name" | tr '/.' '--')"

    local expect_exit=0
    local expect_outcome=""
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
    local distribution=""
    # The product this scenario runs, when it is not the repository's
    # installation. It is a LOCAL of this function: `product_path` reads it
    # and nothing else does, so it cannot outlive the scenario and it cannot
    # be a way to reach the product without the clock.
    local scenario_product=""
    local events_refusal=""
    local events_resume=""
    local step_verdict=""
    local failure_stage=""
    local events_follow=""
    local run_state=""
    local recover=0
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
            '//@ recover')         recover=1 ;;
            '//@ credentials '*)   seeds+=("${directive#//@ credentials }") ;;
            '//@ plugin-jar '*)    plugin_jar="${directive#//@ plugin-jar }" ;;
            '//@ distribution '*)  distribution="${directive#//@ distribution }" ;;
            '//@ plugin-trust '*)  plugin_trust="${directive#//@ plugin-trust }" ;;
            '//@ events-refusal '*) events_refusal="${directive#//@ events-refusal }" ;;
            '//@ events-resume '*)  events_resume="${directive#//@ events-resume }" ;;
            '//@ step-verdict '*)   step_verdict="${directive#//@ step-verdict }" ;;
            '//@ failure-stage '*)  failure_stage="${directive#//@ failure-stage }" ;;
            '//@ events-follow '*)  events_follow="${directive#//@ events-follow }" ;;
            '//@ run-state '*)      run_state="${directive#//@ run-state }" ;;
            '//@ expect-outcome '*) expect_outcome="${directive#//@ expect-outcome }" ;;
        esac
    done < <(grep -m 20 '^//@ ' "$script" || true)

    # The guarantees this scenario PROMISED, before any of them is checked:
    # the text it must produce, the refusal it must contain, the bytes it must
    # commit, and a non-zero exit that is a guarantee rather than an accident.
    # They are counted as promises, because a scenario that promised one and
    # failed to meet it still promised it, and a census that hid the failure
    # would report a smaller suite than the one that ran.
    #
    # The count is kept aside and NOT added here. A scenario that runs on both
    # execution paths has its expectations compared against EACH path's own
    # output, so the same promise is compared twice, and a census that added it
    # once per scenario reported half the comparisons the harness made. The
    # promises are added once per execution instead, where the comparison
    # happens.
    local content_promises
    content_promises="$(count_content_expectations "$name")"

    # One run of the product. The census counts them because a scenario that
    # runs on both execution paths is TWO runs of the product, and a report
    # that says "one scenario" without saying how many times the product ran
    # describes a suite that costs half of what it costs.
    count_run() {
        scenario_executions=$((scenario_executions + 1))
        return 0
    }

    # The result the scenario expects its RUN to conclude, in the product's own
    # vocabulary. The exit code cannot say it: a run that caught a failure and
    # continued exits well, exactly like a run that had nothing to catch, and
    # the two are different worlds for whoever reads the result. A scenario
    # that says nothing expects the clean one; a scenario that expects the run
    # to have caught something says so, and the harness checks the conclusion
    # the run published instead of the process that carried it.
    if [[ -z "$expect_outcome" ]]; then
        if [[ "$expect_exit" == 0 ]]; then
            expect_outcome="success"
        else
            expect_outcome="failure"
        fi
    fi

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
            repacked-uppercase)
                # The same plugin, packaged twice. The distribution keeps its own copy and this
                # one is the operator's, so the composition sees two artifacts with one identity.
                local bundled_plugin
                bundled_plugin="$(ls "$repo_root/pipelinek/pipeline-application/build/install/pipelinek/lib/"example-uppercase-plugin-*.jar 2>/dev/null | head -1 || true)"
                if [[ -z "$bundled_plugin" ]]; then
                    printf 'FAIL %-52s the distribution has no example plugin to repack\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the distribution has no example-uppercase-plugin JAR")
                    return
                fi
                mkdir -p "$root/cwd/plugins"
                if ! build_repacked_plugin_fixture "$bundled_plugin" "$root/cwd/plugins/uppercase.jar"; then
                    printf 'FAIL %-52s the repacked plugin fixture could not be built\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the repacked plugin fixture could not be built")
                    return
                fi
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

    # The distribution this scenario runs against, when it is not the repository's installation.
    # The copy is byte for byte the installed one, with ONE JAR inside it replaced, and the run
    # happens against that copy. The alternative -- offering the lying plugin IN ADDITION to the
    # bundled one -- is a different artifact with the same identity, and it is refused for that
    # before anything else happens, which is a different refusal from the one this scenario is
    # about. An installation whose plugin lies looks like this: the same distribution, one JAR
    # different.
    if [[ -n "$distribution" ]]; then
        case "$distribution" in
            lying-uppercase|lying-capabilities|lying-capabilities-omitted|lying-directives|lying-events)
                # Five kinds of lie, one instrument. The copy is byte for byte the installed
                # distribution, with ONE JAR inside it replaced, and the run happens against that
                # copy. The alternative -- offering the lying plugin IN ADDITION to the bundled
                # one -- is a different artifact with the same identity, and it is refused for
                # that before anything else happens, which is a different refusal from the one
                # these scenarios are about. An installation whose plugin lies looks like this:
                # the same distribution, one JAR different.
                local lying_kind="steps"
                local lying_key="example.uppercase.ghost"
                case "$distribution" in
                    lying-capabilities) lying_kind="capabilities"; lying_key="example.uppercase.ghostCapability" ;;
                    lying-capabilities-omitted)
                                        lying_kind="capabilities-omitted"
                                        lying_key="plugin.event-emission" ;;
                    lying-directives)   lying_kind="directives";   lying_key="example.uppercase.ghostDirective" ;;
                    lying-events)       lying_kind="events";       lying_key="example.uppercase.ghostEvent" ;;
                esac
                local installed_dist="$repo_root/pipelinek/pipeline-application/build/install/pipelinek"
                local private_dist="$root/cwd/distribution"
                if [[ ! -d "$installed_dist/lib" ]]; then
                    printf 'FAIL %-52s the distribution has no lib directory to copy\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the distribution has no lib directory to copy")
                    return
                fi
                if ! cp -R "$installed_dist" "$private_dist"; then
                    printf 'FAIL %-52s the distribution could not be copied\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the distribution could not be copied")
                    return
                fi
                local private_plugin
                private_plugin="$(ls "$private_dist/lib/"example-uppercase-plugin-*.jar 2>/dev/null | head -1 || true)"
                if [[ -z "$private_plugin" ]]; then
                    printf 'FAIL %-52s the copied distribution has no example plugin to replace\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the copied distribution has no example-uppercase-plugin JAR")
                    return
                fi
                if ! build_lying_plugin_fixture "$private_plugin" "$lying_kind" "$lying_key"; then
                    printf 'FAIL %-52s the lying plugin fixture could not be built\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the lying plugin fixture could not be built")
                    return
                fi
                scenario_product="$private_dist/bin/pipelinek"
                ;;
            *)
                printf 'FAIL %-52s unknown distribution fixture: %s\n' "$name" "$distribution"
                failed=$((failed + 1))
                failures+=("$name: unknown distribution fixture '$distribution'")
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
            || ! binary credentials add --kind "$seed_kind" --from-stdin \
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

    # The scenario's private ROOT, one level above its working directory. A scenario that
    # needs to reach the run's own database, its control root, or a file of its own that
    # must NOT live in the working directory (the working directory is what the launcher
    # compares between runs) needs to be told where those are: the launcher is the only
    # one that knows. It is one path, not a language: the scenario derives what it needs.
    export PIPELINEK_E2E_ROOT="$root"

    # A scenario that kills its own process needs to know WHICH process to kill,
    # and the launcher is the only one that can say where to put that answer.
    # The file lives beside the store, NOT in the scenario's working directory:
    # the working directory is compared between the two runs, and a PID file
    # would differ between them (two processes, two PIDs) and look like "the
    # effect repeated".
    if [[ "$resume" == 1 || "$cancel" == 1 || "$recover" == 1 ]]; then
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
    if [[ "$durable" == 1 || "$durable_once" == 1 || "$resume" == 1 || "$cancel" == 1 || "$events_refusal" != "" || "$events_resume" != "" || "$step_verdict" != "" || "$run_state" != "" || "$recover" == 1 ]]; then
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
    binary run --isolated --db "$root/events.db" --control-root "$root/control" \
        ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
        >"$stdout" 2>"$stderr" || code=$?
    cd "$caller_pwd"
    count_run

    if [[ "$run_volatile" == 1 ]]; then
        # The volatile execution gets its OWN working directory and its OWN
        # PIPELINEK_E2E_HOME. The two executions are two different runs of the
        # same pipeline in two different worlds, and a scenario that writes a
        # marker has to see only ITS world's marker.
        #
        # They used to share one directory, and a scenario that asserts an EXACT
        # world could not pass in the second execution: the first execution's
        # marker was still there, so the second read it as its own effect. The
        # reading it produced — "the effect repeated" — is exactly what a
        # scenario that runs in both worlds cannot distinguish from "the world
        # was already like this", and it is a false accusation: the two runs
        # never shared a world to begin with.
        #
        # The control root was already per-execution (`control` and
        # `control-volatile`); the working directory was the one that was not,
        # and that is where a scenario's markers live.
        mkdir -p "$root/cwd-volatile"
        local durable_home="$PIPELINEK_E2E_HOME"
        export PIPELINEK_E2E_HOME="$root/cwd-volatile"
        cd "$root/cwd-volatile"
        binary run --isolated --control-root "$root/control-volatile" \
            ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
            >"$vstdout" 2>"$vstderr" || vcode=$?
        cd "$caller_pwd"
        export PIPELINEK_E2E_HOME="$durable_home"
        count_run
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
        binary run --isolated --db "$root/events.db" --control-root "$root/control" \
            ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"
        count_run
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
        binary run --isolated --db "$root/events.db" --control-root "$root/control" \
            ${plugin_jar_args[@]+"${plugin_jar_args[@]}"} ${plugin_trust_args[@]+"${plugin_trust_args[@]}"} "$script" \
            >"$root/stdout.rerun.txt" 2>"$root/stderr.rerun.txt" || second=$?
        cd "$caller_pwd"
        count_run
        code="$second"
        # The verdict is the SECOND run's: the first one died before it could
        # say anything about the outcome of the run.
        verdict_stdout="$root/stdout.rerun.txt"
        verdict_stderr="$root/stderr.rerun.txt"
    fi

    if [[ "$recover" == 1 ]]; then
        # The FIRST run is the crash, for the same reason a cancelled scenario's
        # first run is: a scenario that says it will die and finishes cleanly
        # has not exercised what it claims.
        if [[ "$code" == 0 ]]; then
            printf 'FAIL %-52s the run was supposed to crash and finished cleanly\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run was supposed to crash and did not")
            return
        fi

        local recover_run_id=""
        local recover_record
        for recover_record in "$root"/control/last-run/*; do
            [[ -f "$recover_record" ]] || continue
            recover_run_id="$(head -1 "$recover_record")"
            break
        done
        if [[ -z "$recover_run_id" ]]; then
            printf 'FAIL %-52s the crashed run left no run id, so it cannot be recovered\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the crashed run left no run id")
            return
        fi

        # The recover of a run that did NOT finish its work must not close it.
        # The run's first step finished, so its journal HAS a terminal
        # operation row; only the RUN's own terminal fact may end a run, and
        # this run never got one.
        local recover_code=0
        binary runs recover "$recover_run_id" --db "$root/events.db" \
            --control-root "$root/control" \
            >"$root/stdout.recover.txt" 2>"$root/stderr.recover.txt" || recover_code=$?
        if [[ "$recover_code" != 0 ]]; then
            printf 'FAIL %-52s the recover of the half-finished run exited %s, expected 0\n' \
                "$name" "$recover_code"
            show_printed "$root/stderr.recover.txt"
            failed=$((failed + 1))
            failures+=("$name: the recover of the half-finished run exited $recover_code")
            return
        fi
        if ! grep -aqF -- "nothing was recovered" "$root/stdout.recover.txt"; then
            printf 'FAIL %-52s the recover closed a run that had not finished its work\n' "$name"
            show_printed "$root/stdout.recover.txt"
            failed=$((failed + 1))
            failures+=("$name: the recover closed a run that had not finished its work")
            return
        fi

        # And the run's own state says so too: it is still running, because
        # the recover did not close it.
        local recover_state_code=0
        binary inspect "$recover_run_id" --db "$root/events.db" \
            --control-root "$root/control" --state \
            >"$root/stdout.recover-state.txt" 2>"$root/stderr.recover-state.txt" || recover_state_code=$?
        if [[ "$recover_state_code" != 0 ]]; then
            printf 'FAIL %-52s reading the run state exited %s, expected 0\n' \
                "$name" "$recover_state_code"
            show_printed "$root/stderr.recover-state.txt"
            failed=$((failed + 1))
            failures+=("$name: reading the run state exited $recover_state_code")
            return
        fi
        if ! grep -aqF -- "run-state running" "$root/stdout.recover-state.txt"; then
            printf 'FAIL %-52s the run was not running after the recover refused it\n' "$name"
            show_printed "$root/stdout.recover-state.txt"
            failed=$((failed + 1))
            failures+=("$name: the run was not running after the recover refused it")
            return
        fi

        # The verdict is the recover's: the run that died could not say
        # anything about its own outcome.
        verdict_stdout="$root/stdout.recover.txt"
        verdict_stderr="$root/stderr.recover.txt"
        code=0
    fi

    if [[ "$run_state" != "" ]]; then
        # The id of the run this scenario produced. The launcher reads it from
        # the run's own record of its last run, for the same reason the cancel
        # flow reads it there: the id is a UUID nobody can know in advance.
        local state_run_id=""
        local state_record
        for state_record in "$root"/control/last-run/*; do
            [[ -f "$state_record" ]] || continue
            state_run_id="$(head -1 "$state_record")"
            break
        done
        if [[ -z "$state_run_id" ]]; then
            printf 'FAIL %-52s the run left no run id, so its state cannot be read\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run left no run id")
            return
        fi

        # The introspection plane answers about the RUN, through the published
        # port, and the answer is the run's own terminal fact: the event lane's
        # `RunFinished` bookend.
        local state_code=0
        binary inspect "$state_run_id" --db "$root/events.db" --control-root "$root/control" --state \
            >"$root/stdout.state.txt" 2>"$root/stderr.state.txt" || state_code=$?
        if [[ "$state_code" != 0 ]]; then
            printf 'FAIL %-52s reading the run state exited %s, expected 0\n' "$name" "$state_code"
            show_printed "$root/stderr.state.txt"
            failed=$((failed + 1))
            failures+=("$name: reading the run state exited $state_code")
            return
        fi
        if ! grep -aqF -- "$run_state" "$root/stdout.state.txt"; then
            printf 'FAIL %-52s the run state did not say: %s\n' "$name" "$run_state"
            show_printed "$root/stdout.state.txt"
            failed=$((failed + 1))
            failures+=("$name: the run state did not say: $run_state")
            return
        fi

        # A run nobody knows is not a run that finished. It is a refusal, it
        # says so, and it does not invent a state to answer with.
        local unknown_code=0
        binary inspect "no-such-run-$state_run_id" --db "$root/events.db" --control-root "$root/control" --state \
            >"$root/stdout.state-unknown.txt" 2>"$root/stderr.state-unknown.txt" || unknown_code=$?
        if [[ "$unknown_code" != 2 ]]; then
            printf 'FAIL %-52s reading the state of an unknown run exited %s, expected 2\n' \
                "$name" "$unknown_code"
            show_printed "$root/stderr.state-unknown.txt"
            failed=$((failed + 1))
            failures+=("$name: reading the state of an unknown run exited $unknown_code")
            return
        fi
        if ! grep -aqF -- "does not know run" "$root/stderr.state-unknown.txt"; then
            printf 'FAIL %-52s the refusal for an unknown run did not say it does not know it\n' "$name"
            show_printed "$root/stderr.state-unknown.txt"
            failed=$((failed + 1))
            failures+=("$name: the refusal for an unknown run did not name the unknown run")
            return
        fi
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
        binary runs cancel "$run_id" --db "$root/events.db" --control-root "$root/control" \
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
        binary runs cancel "$run_id" --db "$root/events.db" --control-root "$root/control" \
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
        binary events "$run_id" --db "$root/events.db" \
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
                binary events "no-such-run-$run_id" --db "$root/events.db" \
                    >"$root/stdout.events-refusal.txt" 2>"$root/stderr.events-refusal.txt" || refusal_code=$?
                ;;
            cursor-beyond-tail)
                binary events "$run_id" --db "$root/events.db" \
                    --after-cursor "evt-cursor-v1:$run_id:999999999" \
                    >"$root/stdout.events-refusal.txt" 2>"$root/stderr.events-refusal.txt" || refusal_code=$?
                ;;
            no-event-history)
                # A file that is not an event store at all: an EMPTY file. SQLite treats a
                # zero-length file as an empty database, so the reader can open it — and what
                # it finds inside is a database with no history in it.
                : >"$root/empty.db"
                binary events "$run_id" --db "$root/empty.db" \
                    >"$root/stdout.events-refusal.txt" 2>"$root/stderr.events-refusal.txt" || refusal_code=$?
                # And the reader must not have CREATED a database in it. A reader that opens
                # the file with a writer's permissions turns a zero-length file into a
                # database with the event schema in it; a reader that cannot write leaves
                # the file exactly as it found it.
                if [[ -s "$root/empty.db" ]]; then
                    printf 'FAIL %-52s the reader wrote to a database it was only asked to read\n' "$name"
                    failed=$((failed + 1))
                    failures+=("$name: the reader wrote to the file it was asked to read")
                    return
                fi
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
        expected="$(binary events "$run_id" --db "$root/events.db" --limit 1000 \
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
                page="$(binary events "$run_id" --db "$root/events.db" --limit "$resume_limit" \
                    --kind "$resume_kind" --fields SEQ 2>"$root/stderr.events-resume.txt")" || read_code=$?
            else
                page="$(binary events "$run_id" --db "$root/events.db" --limit "$resume_limit" \
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
        sv_terminals="$(binary events "$sv_run_id" --db "$root/events.db" \
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

    if [[ -n "$events_follow" ]]; then
        # The scenario writes a history that has already ended, and the launcher acts as an
        # EXTERNAL client that follows it WITH A FILTER.
        #
        # What is asserted is that the follow ENDS: the run is over, and a client that waits for
        # more of it has to be released. The filter decides what the client is handed; it does
        # not decide whether the run is over. The run's terminal fact is the `RunFinished` event,
        # and the follow has to see it in the STORE, not among the facts the filter let through.
        #
        # The bound is a wall clock and not a step count, because the failure this scenario
        # exists to catch is a wait that never returns, and only a clock can catch that.
        if [[ "$code" != 0 ]]; then
            printf 'FAIL %-52s the scenario run exited %s, expected 0 (it is the fixture the follower reads)\n' \
                "$name" "$code"
            show_printed "$root/stderr.txt"
            failed=$((failed + 1))
            failures+=("$name: the scenario run exited $code")
            return
        fi

        local follow_run_id=""
        local record
        for record in "$root"/control/last-run/*; do
            [[ -f "$record" ]] || continue
            follow_run_id="$(head -1 "$record")"
            break
        done
        if [[ -z "$follow_run_id" ]]; then
            printf 'FAIL %-52s the run left no run id, so there is no history to follow\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the run left no run id")
            return
        fi

        local follow_kind="${events_follow%% *}"
        local follow_budget="${events_follow##* }"
        if [[ "$follow_kind" == "$follow_budget" ]]; then
            printf 'FAIL %-52s events-follow needs <kind> <seconds>, got: %s\n' "$name" "$events_follow"
            failed=$((failed + 1))
            failures+=("$name: events-follow needs <kind> <seconds>")
            return
        fi

        local follow_code=0
        binary_within "$follow_budget" events "$follow_run_id" --db "$root/events.db" \
            --follow --kind "$follow_kind" \
            >"$root/stdout.events-follow.txt" 2>"$root/stderr.events-follow.txt" || follow_code=$?

        if [[ "$follow_code" == 124 ]]; then
            # 124 is what `timeout` reports when it had to stop the wait. The run had already
            # ended, so the follow had everything it needed to stop and did not.
            printf 'FAIL %-52s the follow with a filter never ended on a run that had finished\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the follow with a filter never ended")
            return
        fi
        if [[ "$follow_code" != 0 ]]; then
            printf 'FAIL %-52s the follow with a filter exited %s\n' "$name" "$follow_code"
            show_printed "$root/stderr.events-follow.txt"
            failed=$((failed + 1))
            failures+=("$name: the follow with a filter exited $follow_code")
            return
        fi

        # The terminal fact is what ENDS the follow; the filter still decides what is delivered.
        if grep -q '"kind":"RunFinished"' "$root/stdout.events-follow.txt"; then
            printf 'FAIL %-52s the follow delivered a fact the filter excluded\n' "$name"
            failed=$((failed + 1))
            failures+=("$name: the follow delivered a fact the filter excluded")
            return
        fi
        if ! grep -q "\"kind\":\"$follow_kind\"" "$root/stdout.events-follow.txt"; then
            printf 'FAIL %-52s the follow delivered no %s fact, so it ended without following anything\n' \
                "$name" "$follow_kind"
            failed=$((failed + 1))
            failures+=("$name: the follow delivered no $follow_kind fact")
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
        fs_failures="$(binary events "$fs_run_id" --db "$root/events.db" \
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
    if [[ "$recover" == 1 ]]; then
        label="$name (recover)"
    elif [[ "$run_state" != "" ]]; then
        label="$name (run-state)"
    elif [[ "$cancel" == 1 ]]; then
        label="$name (cancel)"
    elif [[ "$events_refusal" != "" ]]; then
        label="$name (events-refusal)"
    elif [[ "$events_resume" != "" ]]; then
        label="$name (events-resume)"
    elif [[ "$step_verdict" != "" ]]; then
        label="$name (step-verdict)"
    elif [[ "$failure_stage" != "" ]]; then
        label="$name (failure-stage)"
    elif [[ "$events_follow" != "" ]]; then
        label="$name (events-follow)"
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
    # $1 label, $2 stdout file, $3 stderr file, $4 exit code, $5 control root,
    # $6 journal database, or empty when the execution had no journal to read.
    # Returns non-zero when an expectation did not hold, and leaves the reason
    # in $check_detail for the caller to report.
    check_execution() {
        local ex_label="$1" ex_stdout="$2" ex_stderr="$3" ex_code="$4" ex_control="$5"
        local ex_db="$6"
        local wanted
        local acceptable=""

        if [[ "$ex_code" != "$expect_exit" ]]; then
            printf 'FAIL %-52s exit %s, expected %s\n' "$ex_label" "$ex_code" "$expect_exit"
            show_printed "$ex_stdout"
            show_printed "$ex_stderr"
            check_detail="exit $ex_code, expected $expect_exit"
            return 1
        fi

        # The run's own conclusion, when the execution under test WAS a run.
        #
        # A scenario that watches a process end is watching the weakest thing
        # about it. The exit code does not say what the run concluded, and it
        # does not say that the run concluded anything: a run that dies between
        # its last step and its terminal bookend leaves an exit code and no
        # verdict. The product publishes that conclusion in its event lane, and
        # the launcher reads it from there, so the verdict and the exit code
        # become two independent facts about the same run and can be compared.
        #
        # The verdict is what the scenario's guarantee is really about. A run
        # that caught a failure and continued ends well, exactly like a run that
        # had nothing to catch, and the exit code cannot tell the two apart;
        # the published conclusion can, and it is the conclusion the scenario
        # declares. A run that ends successfully without publishing a verdict
        # has said nothing about itself, and a scenario that accepts that as a
        # pass accepts silence.
        #
        # A run that FAILED may legitimately leave no verdict: that is what a
        # run that died looks like. Only the successful side is a promise.
        if [[ -n "$ex_db" ]]; then
            local run_id=""
            run_id="$(run_id_of "$ex_stderr")"
            if [[ -n "$run_id" ]]; then
                local verdict="" run_outcome="" run_steps=0
                verdict="$(read_run_verdict "$ex_db" "$run_id" || true)"
                if [[ -z "$verdict" ]]; then
                    if [[ "$ex_code" == 0 ]]; then
                        printf 'FAIL %-52s the run %s exited 0 without publishing a verdict\n' \
                            "$ex_label" "$run_id"
                        check_detail="the run $run_id exited 0 without publishing a verdict"
                        return 1
                    fi
                else
                    run_outcome="$(sed -n 's/^outcome=\([a-z]*\).*/\1/p' <<<"$verdict")"
                    run_steps="$(sed -n 's/.*steps=\([0-9]*\).*/\1/p' <<<"$verdict")"
                    scenario_guarantees=$((scenario_guarantees + 1))
                    scenario_steps=$((scenario_steps + run_steps))
                    scenario_outcome="$run_outcome"
                    # The conclusions a run may reach, and which of them agree
                    # with a process that ended well: a clean success, and the
                    # unstable one a run reaches when it caught a failure and
                    # carried on. A process that did not end well concluded
                    # either a failure or an abort, and the exit code cannot
                    # tell those two apart either.
                    case "$expect_outcome" in
                        success)  acceptable="success" ;;
                        unstable) acceptable="unstable" ;;
                        failure)  acceptable="failure aborted" ;;
                        *)        acceptable="$expect_outcome" ;;
                    esac
                    if [[ " $acceptable " != *" $run_outcome "* ]]; then
                        printf 'FAIL %-52s the run %s concluded %s, expected %s\n' \
                            "$ex_label" "$run_id" "$run_outcome" "$expect_outcome"
                        check_detail="the run $run_id concluded $run_outcome, expected $expect_outcome"
                        return 1
                    fi
                fi
            fi
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
            run_id="$(run_id_of "$ex_stderr")"
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
                binary console --control-dir "$ex_control" "$run_id" "$op_id" \
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

    # The two worlds, compared with each other.
    #
    # The launcher held each execution to the scenario's expectations and never
    # compared the executions with one another: the two worlds were two chances
    # to fail, not one guarantee that they agree. A defect that lives in one
    # world only, and that no expectation names, passed the suite twice.
    #
    # What is compared is what a run SAYS and what it COMMITS, because those are
    # the two things that have to be true in every world:
    #
    #   - the account: the console the run printed and the diagnostics it wrote,
    #     with the identity, the clock and the two roots normalized away. A run
    #     that tells a different story in the two worlds is two different runs.
    #   - the bytes: what the run committed to the Output Plane, read back with
    #     the product's own reader. Two worlds that commit different bytes are
    #     two different runs, whatever their console says.
    #
    # The place where the process output lands between two console lines is NOT
    # compared, and that is deliberate: the observer that delivers it polls, so
    # that place is a projection and not a promise, and a comparison that
    # depended on it would be a coin toss.
    compare_worlds() {
        local cw_durable_out="$1" cw_durable_err="$2" cw_durable_control="$3"
        local cw_volatile_out="$4" cw_volatile_err="$5" cw_volatile_control="$6"

        local durable_run volatile_run
        durable_run="$(run_id_of "$cw_durable_err")"
        volatile_run="$(run_id_of "$cw_volatile_err")"

        # The comparisons this scenario is owed, counted before any of them
        # happens: a comparison that failed was still promised, and a census
        # that shrank when something failed would report a smaller suite than
        # the one that ran.
        scenario_guarantees=$((scenario_guarantees + 1))
        if [[ -n "$durable_run" && -n "$volatile_run" ]]; then
            scenario_guarantees=$((scenario_guarantees + 1))
        fi

        local durable_account volatile_account
        durable_account="$(world_account "$cw_durable_out" "$cw_durable_err" "$root")"
        volatile_account="$(world_account "$cw_volatile_out" "$cw_volatile_err" "$root")"
        if [[ "$durable_account" != "$volatile_account" ]]; then
            printf 'FAIL %-52s the two execution paths told different stories\n' "$label"
            diff <(printf '%s\n' "$durable_account") <(printf '%s\n' "$volatile_account") \
                | head -12 | sed 's/^/       | /' || true
            world_detail="the two execution paths told different stories"
            return 1
        fi

        if [[ -n "$durable_run" && -n "$volatile_run" ]]; then
            local durable_bytes="$root/world.durable.bytes" volatile_bytes="$root/world.volatile.bytes"
            world_committed "$cw_durable_control" "$durable_run" | normalize_identity >"$durable_bytes"
            world_committed "$cw_volatile_control" "$volatile_run" | normalize_identity >"$volatile_bytes"
            if ! cmp -s "$durable_bytes" "$volatile_bytes"; then
                printf 'FAIL %-52s the two execution paths committed different bytes\n' "$label"
                printf '       | the durable path committed %s byte(s), the volatile path %s\n' \
                    "$(wc -c <"$durable_bytes" | tr -d ' ')" "$(wc -c <"$volatile_bytes" | tr -d ' ')"
                diff "$durable_bytes" "$volatile_bytes" 2>/dev/null | head -12 | sed 's/^/       | /' || true
                world_detail="the two execution paths committed different bytes"
                return 1
            fi
        fi
        return 0
    }

    # The checks this scenario made BEYOND watching a process end. Counted from
    # what the launcher did, not from what the scenario declared: a mode the
    # launcher ran is a check the launcher made, whether or not the scenario
    # said so in a directive. A scenario that reads a run's state, replays it
    # or follows its events has checked the product, and a census that called
    # it a scenario that "only watched the exit code" was counting the wrong
    # thing.
    local projections=0
    if [[ "$cancel" == 1 ]]; then projections=$((projections + 1)); fi
    if [[ "$recover" == 1 ]]; then projections=$((projections + 1)); fi
    if [[ "$events_refusal" != "" ]]; then projections=$((projections + 1)); fi
    if [[ "$events_resume" != "" ]]; then projections=$((projections + 1)); fi
    if [[ "$step_verdict" != "" ]]; then projections=$((projections + 1)); fi
    if [[ "$failure_stage" != "" ]]; then projections=$((projections + 1)); fi
    if [[ "$events_follow" != "" ]]; then projections=$((projections + 1)); fi
    if [[ "$run_state" != "" ]]; then projections=$((projections + 1)); fi
    # A pipeline that ran twice checked that its effect did not repeat, which
    # is a guarantee no single execution can make.
    if [[ "$durable" == 1 || "$resume" == 1 ]]; then projections=$((projections + 1)); fi
    scenario_guarantees=$((scenario_guarantees + projections))

    # The promises of the executions that are about to be checked, counted
    # before either one is: a scenario that promised a comparison and failed
    # it still promised it, and a census that shrank when something failed
    # would report a smaller suite than the one that ran.
    #
    # The verdict execution and the volatile one are counted apart, because
    # they are two runs of the product and each one is compared against its
    # OWN output: one promise, two runs, two comparisons.
    scenario_guarantees=$((scenario_guarantees + content_promises))
    if [[ "$expect_exit" != "0" ]]; then
        scenario_guarantees=$((scenario_guarantees + 1))
    fi
    if [[ "$run_volatile" == 1 ]]; then
        scenario_guarantees=$((scenario_guarantees + content_promises))
        if [[ "$expect_exit" != "0" ]]; then
            scenario_guarantees=$((scenario_guarantees + 1))
        fi
    fi

    # The execution that decides the verdict, against every expectation the
    # scenario declares. For a resumed scenario it is the SECOND run: the
    # first one died before it could say anything.
    local check_detail=""
    local world_detail=""
    if ! check_execution "$label" "$verdict_stdout" "$verdict_stderr" "$code" "$root/control" \
        "$root/events.db"; then
        failed=$((failed + 1))
        failures+=("$label: $check_detail")
        return
    fi

    # The volatile path, when the scenario ran it. Both paths are held to the
    # same expectations: a scenario is ok when BOTH met them.
    #
    # The volatile execution has no journal, so it has no event lane and no
    # verdict to read: what it leaves is its exit code and its output. That is
    # not a weaker check, it is a different one, and the census counts it as
    # what it is.
    if [[ "$run_volatile" == 1 ]]; then
        if ! check_execution "$label (volatile)" "$vstdout" "$vstderr" "$vcode" \
            "$root/control-volatile" ""; then
            failed=$((failed + 1))
            failures+=("$label (volatile): $check_detail")
            return
        fi

        # The two worlds, one against the other. Each one met the expectations
        # on its own; this asks the question neither of them could answer alone:
        # did they do the SAME thing?
        if ! compare_worlds "$verdict_stdout" "$verdict_stderr" "$root/control" \
            "$vstdout" "$vstderr" "$root/control-volatile"; then
            failed=$((failed + 1))
            failures+=("$label: $world_detail")
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

    # The clock has the last word even here: a scenario that ran out of it
    # did not check its guarantee, so the body does not get to say "ok".
    if [[ -f "$clock_marker" ]]; then
        return
    fi

    # What this row COMPARED, next to what it ran. A green that does not say
    # what it checked cannot be weighed against what it cost, and the two
    # numbers answer different questions: the row ran one scenario, and it
    # compared some number of guarantees about it.
    #
    # A scenario whose guarantee lives in the pipeline's own steps compares
    # nothing HERE and can still fail: the exit code the harness watched is
    # carrying that guarantee, one step at a time, inside the run. The row has
    # to tell those two apart, because a reader who sees only "ok" cannot tell
    # a scenario with five comparisons from one whose guarantee is a single
    # `sh` that failed the run.
    if (( scenario_guarantees == 0 )); then
        printf 'ok   %-52s exit %s  checks=0 (the pipeline carries it)\n' "$label" "$code"
    else
        printf 'ok   %-52s exit %s  checks=%s\n' "$label" "$code" "$scenario_guarantees"
    fi
    passed=$((passed + 1))
}

# --- The clock of one scenario, and the label that says it --------------------
#
# A scenario that passes has to say what it COST, not only that it passed: the
# price of the gate is the number an operator needs before deciding whether to
# wait for it again, and a report that only says "ok" makes that number
# measurable by hand and by nothing else.
now_ms() {
    local now
    now="$(date +%s%3N 2>/dev/null || true)"
    if [[ "$now" =~ ^[0-9]+$ ]]; then
        printf '%s' "$now"
    else
        # `date +%3N` is a GNU extension; where it is missing the clock still
        # works, it just counts whole seconds. A coarse number beats no number.
        printf '%s' "$(( $(date +%s) * 1000 ))"
    fi
}

elapsed_label() {
    local ms="$1"
    printf '%d.%01ds' "$(( ms / 1000 ))" "$(( (ms % 1000) / 100 ))"
}

scenario_slug() {
    printf '%s' "${1#"$here"/}" | tr '/.' '--'
}

# How many scenarios run at once. They are isolated BY DESIGN -- each one gets
# its own root, its own working directory, its own credential store, its own
# durable database and its own control root -- so they do not need to take
# turns. The ceiling is a knob, not a constant: a smaller machine gets fewer
# workers, and `PIPELINEK_E2E_JOBS=1` restores the serial behaviour exactly,
# which is what a scenario that only fails under contention needs to diagnose.
#
# The serial run was measured, and it does not fit: 56 scenarios at a median of
# about 7 s each reached scenario 49 of 56 when the stage clock of 780 s
# expired, and a stage that stops is a gate that reports gaps. Eight workers
# brings the same suite inside the stage clock on the same machine.
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

# --- The stage, in parallel --------------------------------------------------
#
# Each scenario writes its verdict into its own file, and the parent prints those
# files in SCENARIO ORDER at the end. Two reasons: the report keeps the shape a
# serial run had, so two logs can be compared; and the summary line -- the only
# thing the gate parses -- keeps meaning the same thing.
#
# The stage clock no longer decides WHICH scenarios run. It used to, and that
# turned slowness into a lie: the serial stage reached scenario 49 of 56 when
# its 780 s expired, and the seven it never reached were reported as gaps, so a
# stage that was too slow looked like a stage with holes in it. Now every
# selected scenario is started and bounded by its OWN clock, and the stage clock
# is a post-hoc fact about the whole stage: it is reported when it is true, and
# it no longer decides what counts as covered.
verdict_dir="$cycle_root/verdicts"
mkdir -p "$verdict_dir"

running=0
for script in ${selected[@]+"${selected[@]}"}; do
    slug="$(scenario_slug "$script")"
    ( run_scenario "$script" ) > "$verdict_dir/$slug.txt" 2>&1 &
    running=$((running + 1))
    if (( running >= jobs_limit )); then
        # `wait -n` returns when the next job finishes. Its exit status is
        # whatever that scenario's subshell ended with, which is NOT the
        # launcher's verdict -- the verdict travels in the file -- so it is
        # deliberately not turned into a failure here.
        wait -n || true
        running=$((running - 1))
    fi
done
# Same reason for the last one.
wait || true

scenario_index="${#selected[@]}"
passed=0; failed=0; blocked=0; seeded=0; durable_runs=0; failures=()
observed_scenarios=0
observed_expectations=0
observed_executions=0
finished_steps=0
unstable_runs=0

for script in ${selected[@]+"${selected[@]}"}; do
    slug="$(scenario_slug "$script")"
    file="$verdict_dir/$slug.txt"
    if [[ ! -f "$file" ]]; then
        failed=$((failed + 1))
        failures+=("${script#"$here"/}: the scenario produced no verdict at all")
        continue
    fi

    # The human lines, in scenario order. The markers are the machine's reading
    # of the same file and do not belong in the report.
    #
    # A scenario that produced a verdict has to have produced a LINE. A report
    # with fewer lines than scenarios hides one of them, and the reader cannot
    # tell WHICH one is missing -- they can only count. The count is taken
    # before the cost line is added, so the cost line cannot excuse an empty
    # verdict, and a scenario without a line is a failure of the harness rather
    # than a pass nobody can see.
    human_lines="$(grep -v -e '^__VERDICT__ ' -e '^__FAILURE__ ' -e '^__COUNTS__ ' \
                         -e '^__ELAPSED__ ' -e '^[[:space:]]*$' "$file" | wc -l || true)"
    grep -v -e '^__VERDICT__ ' -e '^__FAILURE__ ' -e '^__COUNTS__ ' -e '^__ELAPSED__ ' \
        "$file" || true

    local_ms="$(grep -m 1 '^__ELAPSED__ ' "$file" 2>/dev/null | sed 's/^__ELAPSED__ //' || true)"
    if [[ "$local_ms" =~ ^[0-9]+$ ]]; then
        printf '     %-52s %s\n' "${script#"$here"/}" "$(elapsed_label "$local_ms")"
    fi

    if (( human_lines == 0 )); then
        failed=$((failed + 1))
        failures+=("${script#"$here"/}: the scenario produced a verdict but no line in the report")
        continue
    fi

    counts="$(grep -m 1 '^__COUNTS__ ' "$file" 2>/dev/null || true)"
    local_observed="$(sed -n 's/.*observed=\([0-9][0-9]*\).*/\1/p' <<<"$counts")"
    local_executions="$(sed -n 's/.*executions=\([0-9][0-9]*\).*/\1/p' <<<"$counts")"
    local_steps="$(sed -n 's/.*steps=\([0-9][0-9]*\).*/\1/p' <<<"$counts")"
    local_outcome="$(sed -n 's/.*outcome=\([a-z]*\).*/\1/p' <<<"$counts")"
    local_durable="$(sed -n 's/.*durable=\([0-9][0-9]*\).*/\1/p' <<<"$counts")"
    local_seeded="$(sed -n 's/.*seeded=\([0-9][0-9]*\).*/\1/p' <<<"$counts")"
    # A counter that reads a corrupted value is a number nobody can trust, and
    # these counts are here to be trusted: they are ignored rather than added.
    [[ "$local_observed" =~ ^[0-9]+$ ]] && observed_expectations=$((observed_expectations + local_observed))
    [[ "$local_executions" =~ ^[0-9]+$ ]] && observed_executions=$((observed_executions + local_executions))
    if [[ "$local_observed" =~ ^[0-9]+$ ]] && (( local_observed > 0 )); then
        observed_scenarios=$((observed_scenarios + 1))
    fi
    [[ "$local_steps"   =~ ^[0-9]+$ ]] && finished_steps=$((finished_steps + local_steps))
    # A run that caught a failure and continued is a weaker green than a clean
    # one, and the exit code cannot tell them apart. The count is here so a
    # reader of the census knows how many of the greens were of that kind.
    [[ "$local_outcome" == "unstable" ]] && unstable_runs=$((unstable_runs + 1))
    [[ "$local_durable" =~ ^[0-9]+$ ]] && durable_runs=$((durable_runs + local_durable))
    [[ "$local_seeded"  =~ ^[0-9]+$ ]] && seeded=$((seeded + local_seeded))

    verdict_line="$(grep -m 1 '^__VERDICT__ ' "$file" 2>/dev/null || true)"
    case "${verdict_line#__VERDICT__ }" in
        ok)      passed=$((passed + 1)) ;;
        fail)    failed=$((failed + 1)) ;;
        blocked) blocked=$((blocked + 1)) ;;
        *)       failed=$((failed + 1))
                 failures+=("${script#"$here"/}: the scenario produced no readable verdict") ;;
    esac

    while IFS= read -r reason; do
        [[ -n "$reason" ]] && failures+=("$reason")
    done < <(grep '^__FAILURE__ ' "$file" 2>/dev/null | sed 's/^__FAILURE__ //' || true)
done

# Every selected scenario reached exactly one bucket -- passed, failed or
# blocked. One that reached none is named above as a failure rather than left
# for whoever reads the counts to notice. The gate derives the same gap from the
# same numbers, and the two cannot disagree because neither invents a fifth.
if (( passed + failed + blocked != scenario_index )); then
    printf 'run.sh: %d scenario(s) selected, %d passed, %d failed, %d blocked -- %d skipped.\n' \
        "$scenario_index" "$passed" "$failed" "$blocked" \
        "$(( scenario_index - passed - failed - blocked ))" >&2
    printf '  A skipped scenario is a declared gap, not a pass.\n' >&2
    exit 1
fi

printf '\n%d scenario(s), %d passed, %d failed, %d blocked, %d credential(s) seeded, %d durable rerun(s)\n' \
    "$scenario_index" "$passed" "$failed" "$blocked" "$seeded" "$durable_runs"

# What the run CHECKED, next to what it EXECUTED. A gate that cannot say which
# of its checks were real is a gate whose cost cannot be weighed against what
# it bought.
#
# The census counts what was COMPARED, not what was DECLARED. Those are not
# the same thing, and the difference decides where the work goes: a scenario
# whose guarantee lives in the pipeline's own steps declares nothing, and a
# census that counted declarations reported it as a scenario that asserted
# nothing while the exit code it watched was carrying exactly that guarantee.
# A number that misreports what the suite buys points the work at
# declarations instead of guarantees, and a declaration is the cheapest thing
# to add.
#
# The steps are what the green actually exercised: every step the product
# finished inside the run that decided the scenario's verdict.
#
# The executions are what the suite COST: a scenario that runs on both
# execution paths is checked twice, and the number of checks it made is not
# the number of guarantees it carries. One guarantee, compared on two paths,
# is one guarantee and two checks, and a census that said "one guarantee"
# for that scenario would be describing a suite that does half the work.
printf '%d of %d executed scenario(s) had a guarantee checked (%d checks over %d executions, %d steps finished, %d ended unstable); %d asserted nothing\n' \
    "$observed_scenarios" "$(( passed + failed ))" "$observed_expectations" "$observed_executions" \
    "$finished_steps" "$unstable_runs" "$(( passed + failed - observed_scenarios ))"

# The stage clock, as the fact it now is. Every scenario reported, so nothing is
# hidden by this line: the stage was simply too slow for the budget it was given.
stage_over_budget=0
if (( $(date +%s) > stage_deadline )); then
    stage_over_budget=1
    printf 'run.sh: the stage took longer than its clock of %ss.\n' "$STAGE_TIMEOUT_SECONDS" >&2
    printf '  Every selected scenario reported a verdict; the stage as a whole is\n' >&2
    printf '  too slow for the gate. Fewer workers, or a smaller suite, not a\n' >&2
    printf '  suite that quietly stops half way.\n' >&2
fi

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

# Nothing RAN is not a pass. A stage where every selected scenario was blocked or
# missing has measured nothing, and reporting "0 failed" about it is the same
# false green as running zero scenarios.
if (( passed + failed == 0 )); then
    printf 'run.sh: no scenario was executed (%d selected, %d blocked).\n' \
        "$scenario_index" "$blocked" >&2
    printf '  A gate that runs NOTHING is not a gate that passed.\n' >&2
    exit 2
fi

if (( failed > 0 || blocked > 0 )); then
    printf 'failures:\n'
    printf '  - %s\n' "${failures[@]}"
    exit 1
fi
if (( stage_over_budget == 1 )); then
    exit 1
fi