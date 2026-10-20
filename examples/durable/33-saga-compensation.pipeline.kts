//@ durable-once
//@ expect-exit 1
//@ expect-stderr the action ship failed
//@ expect-stderr saga [checkout]: SETTLED

// A Saga CHAIN, compensated by the engine, in a real process with a real SQLite.
//
// The scenario says it with its body rather than with a comment:
//
//   1. The third action FAILS, so the Saga owes what the first two did.
//   2. The engine compensates them in the INVERSE order, and the log proves it: the last
//      two lines are the compensation of the SECOND action and of the FIRST one.
//   3. The third action's compensation NEVER runs: it left no effect, so there is nothing
//      to undo.
//
// The run FAILS, and that is the point: a Saga that was compensated is a Saga that recovered
// from a failure, and the failure is still a failure. The expectations above say so three
// ways: the exit code is 1, the stderr names the action that failed, and the stderr says the
// Saga SETTLED — which is the difference between "the failure was undone" and "the failure
// was left standing".
//
// The assertion lives in `post { always { } }` because the stage dies at the failing action:
// a scenario that watches the world AFTER the failure it expects cannot use the exit code
// alone, since its own failing assertion would look the same.
//
// The log is the whole proof, and it is an EXACT match. Every compensation of the chain
// appends its own line, including the third action's — so a compensation that should not
// have run is a line that must not be there, and one extra line fails the match. An earlier
// revision of this scenario asserted the third action's compensation with a marker file that
// nothing ever created: a check that could not fail, next to a comment claiming it proved
// something. The compensation now writes to the log like every other, and the exact match
// proves what the comment says.
//
// The markers live in PIPELINEK_E2E_HOME: the launcher's private directory for this
// scenario, unique per scenario and removed with it.
//
// `durable-once` because the Saga needs a REAL durable store: the promises, the lifecycle
// and the compensation order live in SQLite, not in the process's memory. The scenario is
// not run twice, because the compensation happens once and a second run SHOULD leave a
// different world.
import example.saga.SagaChainScript
import example.saga.sagaChain

val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")

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
                        // The compensation of the FIRST action checks that the SECOND one
                        // was compensated before it: that IS the LIFO order, observed from
                        // inside the chain. If the engine compensated in the wrong order,
                        // this body fails loudly and the marker below is what the harness
                        // looks for.
                        compensateCommand = "grep -q '^refund$' '$home/effects.log' " +
                            "|| { echo 'SAGA-DID-NOT-COMPENSATE-IN-ORDER' >&2; exit 1; }; " +
                            "echo release >> '$home/effects.log'",
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
                        // The third action FAILS, and it fails BEFORE it does anything:
                        // that is the provider's contract, and the plugin reports it
                        // honestly as "no effect".
                        forwardCommand = "exit 1",
                        // Its compensation MUST NOT RUN — the forward left no effect, so
                        // there is nothing to undo. It writes to the log anyway, so the
                        // exact match below can prove it did not.
                        compensateCommand = "echo unship >> '$home/effects.log'",
                    ),
                ),
            )

            post {
                always {
                    // The order the engine compensated in: the SECOND action first, then the
                    // FIRST. The forward order was reserve, charge; the compensation order is
                    // the inverse, and the third action's compensation never ran.
                    sh(
                        "test \"$(cat '$home/effects.log')\" = " +
                            "\"$(printf 'reserve\\ncharge\\nrefund\\nrelease')\" " +
                            "|| { echo 'SAGA-DID-NOT-COMPENSATE-IN-ORDER' >&2; exit 1; }",
                    )
                }
            }

        }
    }
}
