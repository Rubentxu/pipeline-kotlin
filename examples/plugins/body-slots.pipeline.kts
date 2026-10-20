// The SLOT seam, exercised by an EXTERNAL plugin.
//
// The plugin owns a plan with two bodies and CHOOSES between them: the forward
// body on the happy path, the compensation body after a failure. Before the
// seam existed, a handler could only run the whole body of its own plan, so
// this choice was impossible from outside the engine.
//
// The scenario is DISCRIMINATING in both directions, and it says so with its
// body rather than with a comment:
//
//   1. The forward body FAILS. The compensation body must run — and the
//      assertion below fails the run if it did not.
//   2. The forward body SUCCEEDS. The compensation body must NOT run — and
//      the assertion below fails the run if it did.
//
// A seam that was not bound, that resolved the wrong child, or that ran both
// children when only one was asked for, breaks one of those two assertions.
//
// The markers live in PIPELINEK_E2E_HOME: the launcher's private directory for
// this scenario, unique per scenario and removed with it.
import example.block.slotPlan

val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")

pipeline {
    stages {
        stage("Slots") {
            // 1. The forward body fails, so the plan compensates.
            slotPlan(
                forwardCommand = "echo forward >> '$home/forward.log'; exit 1",
                compensateCommand = "echo compensated >> '$home/compensated.log'",
            )
            sh("test -f '$home/compensated.log'")

            // 2. The forward body succeeds, so the plan does NOT compensate.
            slotPlan(
                forwardCommand = "echo forward >> '$home/forward.log'",
                compensateCommand = "echo compensated >> '$home/should-not-exist.log'",
            )
            sh("test ! -f '$home/should-not-exist.log'")
        }
    }
}
