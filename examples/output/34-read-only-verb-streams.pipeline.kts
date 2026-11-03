//@ expect-stdout CAPABILITIES_ANSWER_ON_STDOUT
//@ expect-stdout SDK_INFO_ANSWER_ON_STDOUT
//@ expect-stdout DOCTOR_ANSWER_ON_STDOUT
//@ expect-stdout CAPABILITIES_REFUSAL_ON_STDERR

// The two streams of a read-only verb, at the boundary of the process.
//
// THE GUARANTEE UNDER TRIAL
//
// A verb that answers has to answer on stdout, and a verb that refuses has to
// refuse on stderr, and NEITHER stream may carry the other's bytes. A consumer
// reads the answer by reading stdout and nothing else: the catalogue of the
// build, the coordinates of the SDK, the health of the installation. A
// consumer that reads a refusal reads stderr. Mixing the two is not a
// cosmetic problem — a script that writes stdout to a file and reads stderr
// for the reason gets a file with the reason in it and a reason that is the
// answer.
//
// WHY THIS IS A SCENARIO AND NOT A UNIT TEST
//
// The verbs answer to the streams they are GIVEN, and there is a unit test
// that reads what each verb wrote to the stream it handed it. That test says
// nothing about the place where those streams come from: the entry point
// resolves them once, at the boundary of the process, and the unit tests
// never go through that place because the entry point owns the process and
// cannot be called from a test that has to keep running.
//
// So the two arguments can be exchanged at that one line and every unit test
// in the tree stays green, while the whole CLI starts answering on the wrong
// stream. This scenario is the instrument for that line, because it is the
// only thing that runs the real binary and reads its two streams apart.
//
// THE THREE ANSWERS AND THE ONE REFUSAL
//
// Three verbs answer and one request is refused, and they are different verbs
// on purpose: the catalogue and the SDK coordinates are machine-readable
// documents, the health report is a human-readable text, and the refusal is a
// sentence naming the option that was not understood. A boundary that sends
// the answer to stderr breaks all three the same way, and a boundary that
// sends the refusal to stdout breaks the one that matters most: a consumer
// that trusted stdout would read a refusal as an answer.
//
// The refusal is asked of the catalogue because that verb owns no option at
// all, so any option at all is one it cannot understand: the refusal is
// certain, and it is the shared one, the same sentence every verb of this CLI
// says about a request it cannot read.
//
// The two streams are read SEPARATELY, into two files, and each file is
// checked for what it must hold AND for what it must not. A check that only
// asked whether stdout held the answer would be satisfied by a boundary that
// wrote the answer to BOTH streams, and a consumer that reads stdout would
// still be right by accident. The emptiness of the other stream is half of
// the guarantee.
//
// The markers print only when the fact they name holds, and the failure
// tokens never contain a marker, so a failure can never be read as a pass.

val product = System.getenv("PIPELINEK_E2E_PRODUCT")
    ?: error("PIPELINEK_E2E_PRODUCT is not set: this scenario must run under examples/run.sh")

pipeline {
    stages {
        stage("streams") {
            // The catalogue. The answer goes to stdout and NOTHING goes to
            // stderr; the refusal goes to stderr and NOTHING goes to stdout.
            sh(
                """
                '${product}' capabilities > capabilities.out 2> capabilities.err
                echo ${'$'}? > capabilities.code

                if [ "$(cat capabilities.code)" = "0" ] && grep -qF '"schema":"pipelinek.capabilities/2"' capabilities.out; then
                    if [ -s capabilities.err ]; then
                        echo "the catalogue answered and stderr carried something too:" >&2
                        cat capabilities.err >&2
                        echo LEG_capabilities_stderr_not_empty
                    else
                        echo CAPABILITIES_ANSWER_ON_STDOUT
                    fi
                else
                    echo "the catalogue did not answer on stdout with its own schema:" >&2
                    cat capabilities.out >&2
                    cat capabilities.err >&2
                    echo LEG_capabilities_answer_not_on_stdout
                fi

                '${product}' capabilities --view > refusal.out 2> refusal.err
                echo ${'$'}? > refusal.code

                if [ "$(cat refusal.code)" = "2" ] && grep -qF -- '--view' refusal.err; then
                    if [ -s refusal.out ]; then
                        echo "the refusal reached stderr and stdout carried something too:" >&2
                        cat refusal.out >&2
                        echo LEG_refusal_stdout_not_empty
                    else
                        echo CAPABILITIES_REFUSAL_ON_STDERR
                    fi
                else
                    echo "the refusal did not reach stderr naming the option it did not understand:" >&2
                    cat refusal.out >&2
                    cat refusal.err >&2
                    echo LEG_refusal_not_on_stderr
                fi
                """.trimIndent(),
            )

            // The other two answering verbs, the same question asked of them.
            // Two more verbs is what turns "this verb" into "the verbs": the
            // catalogue and the SDK coordinates are one shape and the health
            // report is another, and the boundary is one line that serves all
            // of them.
            sh(
                """
                '${product}' sdk-info > sdk-info.out 2> sdk-info.err
                echo ${'$'}? > sdk-info.code
                if [ "$(cat sdk-info.code)" = "0" ] && grep -qF '"schema":"pipelinek.sdk/1"' sdk-info.out && [ ! -s sdk-info.err ]; then
                    echo SDK_INFO_ANSWER_ON_STDOUT
                else
                    echo "the SDK coordinates did not answer on stdout with nothing else on stderr:" >&2
                    cat sdk-info.out >&2
                    cat sdk-info.err >&2
                    echo LEG_sdk_info_answer_not_on_stdout
                fi

                '${product}' doctor > doctor.out 2> doctor.err
                if [ -s doctor.out ] && [ ! -s doctor.err ]; then
                    echo DOCTOR_ANSWER_ON_STDOUT
                else
                    echo "the health report did not answer on stdout with nothing else on stderr:" >&2
                    cat doctor.out >&2
                    cat doctor.err >&2
                    echo LEG_doctor_answer_not_on_stdout
                fi
                """.trimIndent(),
            )
        }
    }
}
