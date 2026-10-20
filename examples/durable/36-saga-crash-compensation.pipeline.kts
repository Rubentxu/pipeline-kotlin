//@ resume
//@ expect-exit 1
//@ expect-stderr the write-ahead for reserve was refused
//@ expect-stderr saga [checkout]: NEEDS_INTERVENTION (COMPENSATION_UNCERTAIN)

// A Saga whose COMPENSATION happened and whose answer was LOST.
//
// The window this scenario walks through is the last of the three: the engine
// had already decided to compensate, the compensation body left the process,
// the body did its work, and the process died before the engine could record
// the answer. The row now says "the compensation is on its way out", and
// "on its way out" is not "did not happen" — the resource may be back, and
// nobody knows.
//
// What the RESUMED run has to do is the whole point:
//
//   1. It must NOT compensate a second time. The refund already happened; a
//      second one would take the customer's money twice, and the row would
//      look exactly like the row of a first attempt.
//   2. It must NOT compensate the OTHER action either. The obligations are
//      walked from the most recent to the oldest, and the walk STOPS at the
//      one that cannot be settled. So the compensation of "reserve" (the
//      "release") never runs: the log proves it by NOT having that line.
//   3. It must say why: the run ends NEEDS_INTERVENTION
//      (COMPENSATION_UNCERTAIN), which is the honest answer when the provider
//      cannot be asked whether the refund landed.
//
// The first run dies for real, in the middle of the compensation body: the
// command makes the refund visible in the world and then kills the process
// with SIGKILL.
//
// The world is asserted exactly, in `post { always { } }`: "reserve", "charge"
// and "refund", and NOT "release" — the compensation of the action that was
// not reached.
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
// script just wrote.
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
                        forwardCommand = "echo reserve >> '$home/effects.log'",
                        // Never reached: the walk stops at the obligation that
                        // cannot be settled, and the oldest action is behind
                        // it. It writes to the log anyway, so the exact match
                        // below can prove it did not.
                        compensateCommand = "echo release >> '$home/effects.log'",
                    ),
                    SagaChainScript(
                        actionId = "charge",
                        compensationId = "refund",
                        resourceRef = "payment/42",
                        forwardCommand = "echo charge >> '$home/effects.log'",
                        // The compensation lands in the world, and the process
                        // dies before the engine can record the answer. This
                        // is the window: the refund may already be done.
                        compensateCommand = "echo refund >> '$home/effects.log'; " + crash,
                    ),
                    SagaChainScript(
                        actionId = "ship",
                        compensationId = "unship",
                        resourceRef = "parcel/42",
                        // The third action FAILS before it does anything: the
                        // provider's contract, reported honestly as "no
                        // effect", so there is nothing to undo.
                        forwardCommand = "exit 1",
                        compensateCommand = "echo unship >> '$home/effects.log'",
                    ),
                ),
            )

            post {
                always {
                    // Three lines: the two forward effects and the ONE refund
                    // that happened before the crash. A second refund, or the
                    // "release" of the action that was not reached, would make
                    // this match fail.
                    sh(
                        "test \"$(cat '$home/effects.log')\" = " +
                            "\"$(printf 'reserve\\ncharge\\nrefund')\" " +
                            "|| { echo 'SAGA-REPEATED-OR-TOUCHED-A-FOREIGN-RESOURCE' >&2; exit 1; }",
                    )
                }
            }
        }
    }
}
