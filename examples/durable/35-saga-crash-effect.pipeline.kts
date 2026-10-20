//@ resume
//@ expect-exit 1
//@ expect-stderr the write-ahead for reserve was refused
//@ expect-stderr saga [checkout]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)

// A Saga whose forward effect HAPPENED and whose answer was LOST.
//
// The window this scenario walks through: the effect left the process, the
// process died before it could record the answer, and the next process finds a
// row that says "a call is on its way out" — which is not the same thing as
// "nothing happened".
//
// The run dies for real, in the middle of the first action: the command makes
// its effect visible in the world and then kills the process with SIGKILL. No
// shutdown hook, no flush, no chance to tidy up — the kind of death a write
//-ahead is supposed to survive.
//
// What the RESUMED run has to do is the whole point:
//
//   1. It must NOT make the effect a second time. The effect already happened;
//      a second "reserve" would be a second reservation of the same stock, and
//      nothing in the row would show it. The launcher compares the world
//      before and after the resumed run, so a repeated effect fails the
//      scenario even if every exit code is right.
//   2. It must NOT compensate on a guess either. Nobody knows whether the
//      first attempt landed, so the engine asks for a human: the run ends
//      NEEDS_INTERVENTION (UNKNOWN_EFFECT), which is the honest answer when
//      the provider cannot be asked what happened.
//
// The exit code is 1 and the write-ahead refusal is on stderr: the resumed run
// refused to re-authorise a call that was already authorised, and it says so.
//
// The world is asserted exactly, in `post { always { } }`, because the stage
// dies at the first action and the check has to watch what the world looks
// like AFTER the death it expects.
import example.saga.SagaChainScript
import example.saga.sagaChain

val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")

// Where the launcher wants this process's PID. The launcher is the only one
// that can say so: the file must live outside the scenario's working
// directory, because that directory is compared between the two runs and two
// processes have two PIDs.
val pidFile = System.getenv("PIPELINEK_E2E_PID_FILE")
    ?: error("this scenario needs the launcher to say where to write its PID")
java.io.File(pidFile).writeText(ProcessHandle.current().pid().toString())

// The death itself: kill the process that runs the pipeline, by the PID the
// script just wrote. `cat` failing (no PID file) makes the command fail, so a
// scenario that cannot really crash fails loudly instead of pretending.
val crash = "kill -9 \$(cat '$pidFile')"

pipeline {
    stages {
        stage("Checkout") {
            sagaChain(
                sagaId = "checkout",
                actions = listOf(
                    SagaChainScript(
                        actionId = "reserve",
                        compensationId = "release",
                        resourceRef = "stock/42",
                        // The effect lands in the world, and the process dies
                        // before the answer is recorded. The row will say
                        // "on its way out".
                        forwardCommand = "echo reserve >> '$home/effects.log'; " + crash,
                        // Never reached: the resumed run does not compensate on
                        // a guess, so this command must not run. It writes to
                        // the log anyway, so the exact match below can prove
                        // it did not.
                        compensateCommand = "echo release >> '$home/effects.log'",
                    ),
                    SagaChainScript(
                        actionId = "charge",
                        compensationId = "refund",
                        resourceRef = "payment/42",
                        forwardCommand = "echo charge >> '$home/effects.log'",
                        compensateCommand = "echo refund >> '$home/effects.log'",
                    ),
                    SagaChainScript(
                        actionId = "ship",
                        compensationId = "unship",
                        resourceRef = "parcel/42",
                        forwardCommand = "echo ship >> '$home/effects.log'",
                        compensateCommand = "echo unship >> '$home/effects.log'",
                    ),
                ),
            )

            post {
                always {
                    // Exactly ONE line: the first action's effect, once. A
                    // resumed run that dispatched it again would leave two,
                    // and a resumed run that compensated on a guess would
                    // leave a "release" as well.
                    sh(
                        "test \"$(cat '$home/effects.log')\" = \"reserve\" " +
                            "|| { echo 'SAGA-REPEATED-OR-MADE-UP-ITS-MIND' >&2; exit 1; }",
                    )
                }
            }
        }
    }
}
