//@ expect-stdout DOCTOR_HEALTHY_EXIT
//@ expect-stdout DOCTOR_SAID_WRITABLE
//@ expect-stdout DOCTOR_DEFECTIVE_EXIT
//@ expect-stdout DOCTOR_SAID_UNWRITABLE
//@ expect-stdout CAPABILITIES_ONE_LINE
//@ expect-stdout CAPABILITIES_SCHEMA_V2
//@ expect-stdout CAPABILITIES_ONE_VOCABULARY
//@ expect-stdout ONE_VERSION_THREE_FACES
//@ expect-stdout OPERATIONS_ADDRESS_THEIR_OWN_BYTES

// The product, asked about itself.
//
// THE GUARANTEE UNDER TRIAL
//
// A product that talks about itself has to be truthful twice over: what it
// PRINTS has to be what it MEASURED, and what it publishes has to be a
// vocabulary a consumer can read by value. The health report used to break
// the first rule — it decided its verdict by looking for a word in the text
// it had just printed — and the capability catalogue used to break the
// second one, by spelling one of its three status values in another
// language. Both were invisible from the inside, because the code that
// produced them was the code that read them back.
//
// Eight facts are checked here, and each of them can fail on its own:
//
//   - the doctor answers 0 for an installation it finds healthy, and says so
//     in words;
//   - the doctor answers 2 for an installation it finds defective, and says
//     so in words;
//   - the catalogue is one line of JSON, announces the version of the
//     vocabulary it publishes, and spells every value of that vocabulary the
//     same way;
//   - the three faces of the version — the human one, the SDK one and the
//     catalogue one — report the same version of the same build.
//
// WHY THE FIRST DIRECTORY IS NAMED "NOT WRITABLE"
//
// The report decides what to answer by what it measured, and the NAME of the
// directory is not what it measured. A report that read its own sentences
// would print "(writable)" about this directory and answer "broken", because
// the sentence it printed contains the word it was looking for. The
// directory is called that on purpose: it is the one input that catches a
// report that confuses what it said with what it found.
//
// WHY THE OTHER DIRECTORY IS /proc
//
// The report has to be caught telling the truth in BOTH directions, and the
// second direction needs a directory that cannot be written into. Permission
// bits would say so for everybody but the superuser, who is not stopped by
// them; the kernel's own filesystem stops that one too. Nothing is written
// there: the report's own probe is what fails, and that failure is the fact
// under test.
//
// WHY THE CHECKS DO NOT ABORT THE RUN
//
// Each one prints its own marker when it holds and its own failure token
// when it does not, and the run carries on to the next one. The verdict comes
// from the `expect-stdout` directives above: a missing marker is a red row
// that NAMES the guarantee that was lost, instead of a run that stopped at
// the first failure and hid the rest. The failure tokens never contain an
// expected marker, so a failure can never be read as a pass.
//
// HOW THE PRODUCT IS REACHED
//
// Through the entry point the launcher writes for this scenario, which carries
// this scenario's own clock: a question that hangs stops with the scenario
// instead of hanging the suite.

val product = System.getenv("PIPELINEK_E2E_PRODUCT")
    ?: error("PIPELINEK_E2E_PRODUCT is not set: this scenario must run under examples/run.sh")

pipeline {
    stages {
        stage("self-report") {
            // The health report, for a directory the product CAN write into.
            // The exit code and the sentence are two different facts, so they
            // are two different guarantees: a report can print the right
            // sentence and still answer with the wrong code.
            sh(
                """
                mkdir -p 'probe/NOT WRITABLE'
                (cd 'probe/NOT WRITABLE' && '${product}' doctor) > doctor-writable.txt 2>&1
                echo ${'$'}? > doctor-writable.code
                if [ "$(cat doctor-writable.code)" = "0" ]; then
                    echo DOCTOR_HEALTHY_EXIT
                else
                    echo "the doctor answered $(cat doctor-writable.code) for a directory it can write into" >&2
                    echo LEG_doctor_healthy_exit
                fi
                if grep -qF '(writable)' doctor-writable.txt; then
                    echo DOCTOR_SAID_WRITABLE
                else
                    echo "the doctor did not report the directory as writable:" >&2
                    cat doctor-writable.txt >&2
                    echo LEG_doctor_said_writable
                fi
                """.trimIndent(),
            )

            // The health report, for a directory NOBODY can write into. The
            // answer has to be the code that means "defective", and not merely
            // a nonzero code: a report that always answered 2 would pass a
            // check that only asks for "not zero", and would be wrong about
            // every healthy installation.
            sh(
                """
                if [ ! -d /proc ]; then
                    echo "this machine has no directory nobody can write into, so the report cannot be asked about one" >&2
                    echo LEG_doctor_defective_exit
                    echo LEG_doctor_said_unwritable
                else
                    (cd /proc && '${product}' doctor) > doctor-unwritable.txt 2>&1
                    echo ${'$'}? > doctor-unwritable.code
                    if [ "$(cat doctor-unwritable.code)" = "2" ]; then
                        echo DOCTOR_DEFECTIVE_EXIT
                    else
                        echo "the doctor answered $(cat doctor-unwritable.code) for a directory nobody can write into" >&2
                        cat doctor-unwritable.txt >&2
                        echo LEG_doctor_defective_exit
                    fi
                    if grep -qF '(NOT WRITABLE)' doctor-unwritable.txt; then
                        echo DOCTOR_SAID_UNWRITABLE
                    else
                        echo "the doctor did not report the directory as unwritable:" >&2
                        cat doctor-unwritable.txt >&2
                        echo LEG_doctor_said_unwritable
                    fi
                fi
                """.trimIndent(),
            )

            // The catalogue a consumer reads by value. Three things about it:
            // it is ONE line (a consumer parses one line), it says which
            // version of the vocabulary it publishes (so a consumer pinned to
            // the previous one can see that the vocabulary moved), and every
            // value of that vocabulary is spelled the same way.
            sh(
                """
                '${product}' capabilities > capabilities.json 2>&1
                echo ${'$'}? > capabilities.code
                if [ "$(cat capabilities.code)" = "0" ] && [ "$(wc -l < capabilities.json)" = "1" ]; then
                    echo CAPABILITIES_ONE_LINE
                else
                    echo "the catalogue is not one line of JSON:" >&2
                    cat capabilities.json >&2
                    echo LEG_capabilities_not_one_line
                fi
                if grep -qF '"schema":"pipelinek.capabilities/2"' capabilities.json; then
                    echo CAPABILITIES_SCHEMA_V2
                else
                    echo "the catalogue does not announce the vocabulary version it publishes:" >&2
                    cat capabilities.json >&2
                    echo LEG_capabilities_schema
                fi
                grep -oE '"status":"[A-Z]+"' capabilities.json | sort -u > statuses.txt
                printf '%s\n' '"status":"DEPRECATED"' '"status":"EXPERIMENTAL"' '"status":"PUBLISHED"' > expected-statuses.txt
                if cmp -s expected-statuses.txt statuses.txt; then
                    echo CAPABILITIES_ONE_VOCABULARY
                else
                    echo "the published status vocabulary is not the one this build promises:" >&2
                    diff -u expected-statuses.txt statuses.txt >&2
                    echo LEG_capabilities_vocabulary
                fi
                """.trimIndent(),
            )

            // One build, one version, three faces of it. The version a person
            // reads, the version the SDK manifest carries and the version the
            // catalogue carries are the same number: three readers of one
            // authority, and no room for a second one.
            sh(
                """
                '${product}' version | sed -n 's/^pipeline //p' > version.txt
                '${product}' sdk-info | grep -oE '"version":"[^"]+"' | head -1 | cut -d'"' -f4 > sdk-version.txt
                grep -oE '"version":"[^"]+"' capabilities.json | head -1 | cut -d'"' -f4 > capabilities-version.txt
                if cmp -s version.txt sdk-version.txt && cmp -s version.txt capabilities-version.txt; then
                    echo ONE_VERSION_THREE_FACES
                else
                    echo "the three faces of the version disagree: version=$(cat version.txt) sdk-info=$(cat sdk-version.txt) capabilities=$(cat capabilities-version.txt)" >&2
                    echo LEG_version_faces_disagree
                fi
                """.trimIndent(),
            )
        }

        // The operations of THIS run, with the identity a consumer reads.
        //
        // The run knows its own id before it does anything: the control root
        // holds the record of the run that was just started, and it is written
        // before the first step executes. So the scenario can ask the product
        // about the run it is running in, which is exactly what a consumer
        // does when it is handed a run id.
        //
        // The two branches exist so that the run has operations whose identity
        // is not the same as the name their bytes are filed under: the plane
        // keeps a stream under a FOLDED name, and a folded name cannot tell
        // two operations apart. The check is deliberately blind to the shape
        // of an identity -- it never assumes what an operation id looks like.
        // It asks the product which operations the run has, reads each one back
        // with the identity the product published, and checks that each read
        // returns that operation's own bytes and nobody else's.
        stage("operations") {
            parallel {
                branch("a") { sh("""echo 'BRANCH_A_MARKER'""") }
                branch("b") { sh("""echo 'BRANCH_B_MARKER'""") }
            }
        }

        stage("operations-are-addressable") {
            sh(
                """
                control="${'$'}PIPELINEK_E2E_CONTROL"
                run_id=""
                for record in "${'$'}control"/last-run/*; do
                    [ -f "${'$'}record" ] || continue
                    run_id="$(head -1 "${'$'}record")"
                    break
                done
                if [ -z "${'$'}run_id" ]; then
                    echo "the run left no record of itself, so its operations cannot be asked about" >&2
                    echo LEG_no_run_record
                    exit 1
                fi
                listing="$('${product}' operations "${'$'}run_id" --control-root "${'$'}control" 2>/dev/null)"
                if [ -z "${'$'}listing" ]; then
                    echo "the product named no operation for a run that committed output" >&2
                    echo LEG_operations_empty
                    exit 1
                fi
                a_operations=0
                b_operations=0
                mixed=0
                while IFS= read -r operation; do
                    [ -n "${'$'}operation" ] || continue
                    bytes="$('${product}' console --control-dir "${'$'}control" "${'$'}run_id" "${'$'}operation" 2>/dev/null)"
                    has_a=0
                    has_b=0
                    case "${'$'}bytes" in *BRANCH_A_MARKER*) has_a=1 ;; esac
                    case "${'$'}bytes" in *BRANCH_B_MARKER*) has_b=1 ;; esac
                    a_operations=$((a_operations + has_a))
                    b_operations=$((b_operations + has_b))
                    if [ "${'$'}has_a" = 1 ] && [ "${'$'}has_b" = 1 ]; then mixed=1; fi
                done <<<"${'$'}listing"
                if [ "${'$'}a_operations" = 1 ] && [ "${'$'}b_operations" = 1 ] && [ "${'$'}mixed" = 0 ]; then
                    echo OPERATIONS_ADDRESS_THEIR_OWN_BYTES
                else
                    echo "the published identities do not address the operations they name: ${'$'}a_operations operation(s) carried the first branch, ${'$'}b_operations the second, and ${'$'}mixed carried both" >&2
                    echo LEG_operations_address
                fi
                """.trimIndent(),
            )
        }
    }
}
