// The subject is a SAGA that settles: the engine ran the actions of the
// chain, in order, and reported the chain as SETTLED.
//
// The saga plugin is the engine's way of saying "these effects belong
// together": if one of them fails, the others are compensated. A chain
// that settles is the good case, and it is worth a canary of its own
// because the compensation path is the one everybody remembers and the
// happy path is the one nobody checks.
//
// Two witnesses. The FILE is the order the actions ran in, one line each,
// which is what proves the chain executed both of them. The STDERR line
// is the engine's own verdict on the chain (`saga [...]: SETTLED`), and
// the oracle judges it on stderr, where the engine puts it -- not on
// stdout, where the step output went.
import example.saga.SagaChainScript
import example.saga.sagaChain

val log = "/workspace/out/canary-saga-effects.log"

pipeline {
    stages {
        stage("checkout") {
            sagaChain(
                sagaId = "canary-checkout",
                actions = listOf(
                    SagaChainScript(
                        actionId = "reserve",
                        compensationId = "release",
                        resourceRef = "stock/42",
                        forwardCommand = "mkdir -p /workspace/out && echo reserve >> '$log'",
                        compensateCommand = "echo release >> '$log'",
                    ),
                    SagaChainScript(
                        actionId = "charge",
                        compensationId = "refund",
                        resourceRef = "payment/42",
                        forwardCommand = "echo charge >> '$log'",
                        compensateCommand = "echo refund >> '$log'",
                    ),
                ),
            )
            sh("test \"$(wc -l < '$log')\" -eq 2")
        }
    }
}
