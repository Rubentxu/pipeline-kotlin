//@ storage-agnostic
//@ expect-exit 0
//@ expect-stderr saga [checkout]: SETTLED

// A Saga CHAIN that SUCCEEDS, in a real process — and in BOTH storage worlds.
//
// Every other Saga scenario was a FAILURE, and a failure is the path the engine already knew.
// The happy path is the one that did not exist: the engine had no way to say "this Saga ended
// well and its effects stay", so a chain that worked used to compensate itself — the world said
// "reserve, charge, refund, release" while the run said "success". A run that reports success
// while it undid its own work is a lie about the world, and this scenario is the one that says
// it is no longer told.
//
// The scenario says it with its body rather than with a comment:
//
//   1. Both actions succeed, so the Saga owes what they did — until the engine says otherwise.
//   2. The engine KEEPS the effects: the promises are discharged without being paid, so a
//      resumed run will not undo a Saga that worked.
//   3. NO compensation runs, and the log proves it: the world is exactly "reserve, charge".
//
// The log is the whole proof, and it is an EXACT match. Every compensation of the chain appends
// its own line — so a compensation that should not have run is a line that must not be there,
// and one extra line fails the match.
//
// The run SUCCEEDS, and that is the point: a Saga that was kept is a Saga that worked, and the
// exit code says so. The Saga SETTLES, and the stderr says which Saga it was.
//
// The assertion lives in `post { success { } }` because it is about the world AFTER a chain
// that worked. A chain that failed would not reach it: the run's exit code would already be
// wrong, and the failure would name the action that failed.
//
// The markers live in PIPELINEK_E2E_HOME: the launcher's private directory for this scenario,
// unique per scenario and removed with it.
//
// `storage-agnostic` because a Saga must NOT need a durable store to keep its promises. The
// launcher runs this scenario TWICE — once with `--db` and once without — and BOTH runs have to
// meet the expectations above. Before this, the in-memory branch bound no Saga store at all, so
// the Saga capabilities were never exposed and this very script was REFUSED at admission
// without `--db`: the same script, two behaviours, decided by a flag (P6).
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
                        // The compensation of the FIRST action would prove the LIFO order — but
                        // there is nothing to compensate. It writes to the log anyway, so the
                        // exact match below can prove it did not.
                        compensateCommand = "echo release >> '$home/effects.log'",
                    ),
                    SagaChainScript(
                        actionId = "charge",
                        compensationId = "refund",
                        resourceRef = "payment/42",
                        forwardCommand = "echo charge >> '$home/effects.log'",
                        compensateCommand = "echo refund >> '$home/effects.log'",
                    ),
                ),
            )

            post {
                success {
                    sh(
                        "test \"$(cat '$home/effects.log')\" = " +
                            "\"$(printf 'reserve\\ncharge')\" " +
                            "|| { echo 'SAGA-COMPENSATED-A-CHAIN-THAT-WORKED' >&2; exit 1; }",
                    )
                }
            }

        }
    }
}
