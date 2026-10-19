//@ durable-once
//@ expect-exit 1
//@ expect-stderr the action ship failed
//@ expect-stderr saga [checkout]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)

// The SAME chain as the scenario before it, with ONE thing changed: the provider cannot
// promise that a failed call left nothing behind.
//
// That single change flips the outcome, and the flip is the point:
//
//   1. The third action FAILS, and nobody can say whether it took effect.
//   2. So the engine does NOT compensate — not the third action, and NOT the first two
//      either, even though their effects are known. The walk over the obligations stops
//      at the newest one that demands something, because undoing an older resource while
//      a newer one is in doubt can destroy the state the newer compensation would have
//      needed.
//   3. The run ends in NEEDS_INTERVENTION, with the reason the engine stopped.
//
// Compare the two scenarios. Same three actions, same third action failing, same world
// afterwards — reserve and charge happened, and NOTHING was undone — but the first one ends
// SETTLED and this one ends NEEDS_INTERVENTION. The difference is not in the engine and not
// in the chain: it is in what the provider could promise about its own failure.
//
// The run FAILS. A Saga that needs a human is not a Saga that recovered, and the exit code
// says so. The stderr says WHY it failed (the action) and what the engine concluded (the
// intervention), and those are two different facts: the first is what broke, the second is
// what is left for a person to do.
//
// The assertion lives in `post { always { } }` because the stage dies at the failing action.
// The log is an EXACT match: every compensation of the chain appends its own line, so a
// compensation that should not have run is a line that must not be there.
//
// The markers live in PIPELINEK_E2E_HOME: the launcher's private directory for this
// scenario, unique per scenario and removed with it.
//
// `durable-once` because the Saga needs a REAL durable store: the promises, the lifecycle
// and the intervention live in SQLite, not in the process's memory. The scenario is not run
// twice, because the compensation happens once — here, not at all.
import example.saga.SagaChainScript
import example.saga.SagaProviderContract
import example.saga.sagaChain

val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")

pipeline {
    stages {
        stage("Checkout") {
            sagaChain(
                sagaId = "checkout",
                // THE ONE DIFFERENCE. The provider cannot promise that a failed call left
                // nothing behind, so the failure of the third action is an UNKNOWN, not an
                // absence — and the engine stops instead of undoing what it cannot prove.
                providerContract = SagaProviderContract.FailureMayHaveApplied,
                actions = listOf(
                    SagaChainScript(
                        actionId = "reserve",
                        compensationId = "release",
                        resourceRef = "stock/42",
                        forwardCommand = "echo reserve >> '$home/effects.log'",
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
                        // The third action FAILS, and this time the provider CANNOT say
                        // whether it left an effect behind.
                        forwardCommand = "exit 1",
                        compensateCommand = "echo unship >> '$home/effects.log'",
                    ),
                ),
            )

            post {
                always {
                    // The two forwards happened, and NOTHING was compensated. Not the third
                    // action, whose effect is unknown — and not the first two, whose effects
                    // are known, because the engine stops at the newest obligation that
                    // demands something.
                    //
                    // Compensating here would be compensating on a guess: the third action
                    // may have taken effect, and undoing the older resources underneath it
                    // would leave a world nobody can repair.
                    sh(
                        "test \"$(cat '$home/effects.log')\" = \"$(printf 'reserve\\ncharge')\" " +
                            "|| { echo 'SAGA-COMPENSATED-WHILE-IN-DOUBT' >&2; exit 1; }",
                    )
                }
            }

        }
    }
}
