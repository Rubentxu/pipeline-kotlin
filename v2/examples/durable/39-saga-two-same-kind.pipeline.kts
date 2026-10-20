//@ durable-once
//@ expect-exit 1
//@ expect-stderr the action ship failed
//@ expect-stderr saga [checkout]: SETTLED
//
// The chain whose bodies are ALL the same kind.
//
// The Saga chain has six bodies — a forward and a compensation per action — and they are
// all `core.withEnv` blocks. What tells them apart is the NAME the plugin gave each one:
// `example.saga.reserve.forward`, `example.saga.charge.compensate`, and so on.
//
// This scenario is the proof that the seam admits that case at all. A body used to be
// addressed by its KIND, so two children of one kind collided and the engine refused to
// guess which one was meant: a chain like this could not even be written, and a plugin
// with a real body per action had to invent a kind per body — kinds that are the core's
// to define and not the plugin's.
//
// So the assertion here is not "the chain compensated". It is WHICH BODY ran: every line
// of the log is the ADDRESS of the body that produced it, and the four lines are the four
// bodies the chain ran, in the order it ran them.
//
//   1. `example.saga.reserve.forward` — the first action's forward.
//   2. `example.saga.charge.forward`  — the second action's forward. Same kind as the
//      first one: only the name tells them apart.
//   3. `example.saga.charge.compensate` — the SECOND action's compensation. It ran first,
//      because the compensation is LIFO.
//   4. `example.saga.reserve.compensate` — the FIRST action's compensation.
//
// The third action's forward FAILS before it does anything (the provider's contract says
// a failure leaves no effect, and the plugin reports it honestly), so its compensation
// never ran and its body never left a trace: the log has no `example.saga.ship.*` line,
// and its absence is part of the claim.
//
// The first action's compensation also checks, FROM INSIDE THE CHAIN, that the second one
// was compensated before it — that is the LIFO order observed by the body that depends on
// it, and not only by the log at the end.
//
// A seam that resolved by kind would fail here in two ways: it would refuse the ambiguous
// name, or it would dispatch the first body of the kind it found. The log would then show
// a name that does not belong to the body that ran.
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
                        // The body writes ITS OWN ADDRESS to the log, so the trace says
                        // which body ran and not merely that something ran.
                        forwardCommand = "echo \"\$PIPELINEK_SLOT\" >> '$home/effects.log'",
                        // The compensation of the FIRST action checks that the SECOND one
                        // was compensated before it: that IS the LIFO order, observed from
                        // inside the chain.
                        compensateCommand = "grep -q '^example.saga.charge.compensate$' '$home/effects.log' " +
                            "|| { echo 'SAGA-DID-NOT-COMPENSATE-IN-ORDER' >&2; exit 1; }; " +
                            "echo \"\$PIPELINEK_SLOT\" >> '$home/effects.log'",
                    ),
                    SagaChainScript(
                        actionId = "charge",
                        compensationId = "refund",
                        resourceRef = "payment/42",
                        forwardCommand = "echo \"\$PIPELINEK_SLOT\" >> '$home/effects.log'",
                        compensateCommand = "echo \"\$PIPELINEK_SLOT\" >> '$home/effects.log'",
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
                        compensateCommand = "echo \"\$PIPELINEK_SLOT\" >> '$home/effects.log'",
                    ),
                ),
            )

            post {
                always {
                    sh(
                        "test \"$(cat '$home/effects.log')\" = " +
                            "\"$(printf 'example.saga.reserve.forward\\nexample.saga.charge.forward\\n" +
                            "example.saga.charge.compensate\\nexample.saga.reserve.compensate')\" " +
                            "|| { echo 'SAGA-DISPATCHED-A-BODY-BY-ITS-KIND' >&2; exit 1; }",
                    )
                }
            }

        }
    }
}
