//@ resume
//@ expect-exit 1
//@ expect-stderr the write-ahead for reserve was refused
//@ expect-stderr saga [checkout]: SETTLED

// A Saga whose COMPENSATION happened, lost its answer, and was RECONCILED.
//
// This is the other half of the crash windows. The scenarios 35 and 36 show what happens when
// nobody can be asked: the resumed run stops and asks for a human. This one shows what happens
// when the provider CAN be asked, because it declared that its bodies recognise their own
// operation keys.
//
// The window: the compensation body did its work — the resource is back — and the process died
// before the engine could record the answer. The row says "the compensation is on its way out",
// which is not "the compensation did not happen".
//
// What the resumed run does:
//
//   1. It asks the body AGAIN, with the SAME operation key. The body recognises the key, sees
//      that it already did the work, and answers "already done" — WITHOUT doing it twice. The
//      world is the world the crashed run left, and the launcher compares it: a compensation
//      that ran twice would change the world and fail the scenario.
//   2. The answer becomes durable, the obligation is discharged, and the Saga SETTLES.
//
// The chain has TWO actions on purpose. With three, the resumed run would also compensate the
// OLDEST obligation, and the world would change for a reason that has nothing to do with what
// this scenario is about. With two, "the world did not change" is exactly the claim.
//
// The crash is real: SIGKILL from the compensation body, right after it did the work and before
// the engine could record the answer. The body's own key check is what keeps the SECOND attempt
// from killing the process again: a body that recognises its key knows the work is done.
import example.saga.SagaChainScript
import example.saga.SagaProviderContract
import example.saga.sagaChain

val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")

// Where the launcher wants this process's PID: the file lives beside the store, NOT in the
// scenario's working directory, because that directory is compared between the two runs.
val pidFile = System.getenv("PIPELINEK_E2E_PID_FILE")
    ?: error("this scenario needs the launcher to say where to write its PID")
java.io.File(pidFile).writeText(ProcessHandle.current().pid().toString())

// The crash itself: kill the process that runs the pipeline, by the PID the script wrote.
val crash = "kill -9 \$(cat '$pidFile')"

pipeline {
    stages {
        stage("Checkout") {
            sagaChain(
                sagaId = "checkout",
                // The provider RECOGNISES ITS OWN KEYS, so the engine may ask it twice.
                providerContract = SagaProviderContract.ReconcilableByKey,
                actions = listOf(
                    SagaChainScript(
                        actionId = "reserve",
                        compensationId = "release",
                        resourceRef = "stock/42",
                        // The forward effect, keyed: the key is what lets the engine ask again
                        // without asking for a second effect.
                        forwardCommand = "echo \"reserve:\$PIPELINEK_SAGA_OPERATION_KEY\" " +
                            ">> '$home/effects.log'",
                        // The compensation, keyed and IDEMPOTENT: asked about a key it has
                        // already handled, it does nothing and says so. The crash happens on
                        // the FIRST attempt only — a body that recognises its key knows the
                        // work is done, and a second crash would kill the resumed run.
                        compensateCommand = "if grep -q \"^release:\$PIPELINEK_SAGA_OPERATION_KEY\\\$\" " +
                            "'$home/effects.log'; then exit 0; fi; " +
                            "echo \"release:\$PIPELINEK_SAGA_OPERATION_KEY\" >> '$home/effects.log'; " +
                            crash,
                    ),
                    SagaChainScript(
                        actionId = "ship",
                        compensationId = "unship",
                        resourceRef = "parcel/42",
                        // The second action FAILS before it does anything: the provider's
                        // contract, reported honestly as "no effect", so there is nothing to
                        // undo and the first action's compensation is what is owed.
                        forwardCommand = "exit 1",
                        compensateCommand = "echo \"unship:\$PIPELINEK_SAGA_OPERATION_KEY\" " +
                            ">> '$home/effects.log'",
                    ),
                ),
            )

            post {
                always {
                    // Exactly two lines: the forward effect and the ONE compensation that
                    // happened before the crash. A second compensation would make the count
                    // two, and the launcher would also see the world change.
                    //
                    // The COUNTS are not the whole claim. The keys are:
                    //
                    //   - each line carries a NON-EMPTY key, so the body really received the
                    //     operation key the engine handed it (a body that got an empty key
                    //     would still write its line, and the counts alone would not notice);
                    //   - the two keys DIFFER, because the forward and the compensation are
                    //     DIFFERENT operations — the key names the operation, not the attempt;
                    //   - and the compensation ran only ONCE with the SAME key, which the
                    //     counts already prove: a second attempt with a different key would
                    //     have written a second line, and one with the same key found its own
                    //     work and did nothing.
                    sh(
                        "test \"\$(grep -c '^reserve:' '$home/effects.log')\" = 1 " +
                            "&& test \"\$(grep -c '^release:' '$home/effects.log')\" = 1 " +
                            "&& test \"\$(wc -l < '$home/effects.log')\" = 2 " +
                            "&& test -n \"\$(sed -n 's/^reserve://p' '$home/effects.log')\" " +
                            "&& test -n \"\$(sed -n 's/^release://p' '$home/effects.log')\" " +
                            "&& test \"\$(sed -n 's/^reserve://p' '$home/effects.log')\" " +
                            "!= \"\$(sed -n 's/^release://p' '$home/effects.log')\" " +
                            "|| { echo 'SAGA-REPEATED-OR-MISSED-THE-RECONCILIATION' >&2; exit 1; }",
                    )
                }
            }
        }
    }
}
