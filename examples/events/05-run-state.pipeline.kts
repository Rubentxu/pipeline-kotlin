// 05-run-state.pipeline.kts — what the product says about a run that is over.
//
//@ expect-exit 0
//@ run-state run-state terminal outcome=succeeded
//
// THE GUARANTEE UNDER TRIAL
//
// An operator — or a worker in another process — has to be able to ask what
// state a run is in and get an answer that is about the RUN.
//
// The answer comes from the published introspection port, and that port's
// terminal fact is the event lane's `RunFinished` bookend: the run's own
// fact, the same bookend the writer writes when the run ends. A step that
// finished is not a run that finished, and the plane used to confuse the two.
//
// The launcher asks the question through the product's own surface
// (`pipelinek inspect --state`) and holds the answer to this exact shape,
// because a surface that answers "something" is not a surface that answers.
//
// WHAT THE SCRIPT DOES
//
// It finishes one step, and the run with it. The scenario is about the
// question asked afterwards, not about the work.
//
// WHAT THIS DOES NOT CLAIM
//
// The rule that a terminal OPERATION row does not end a RUN is pinned by the
// unit test of the adapter, which can build that state without killing a
// writer. Here the run really did end, so what is exercised is the wiring:
// that the published port has a consumer in this product and that the
// consumer answers with the run's own terminal fact.
pipeline {
    stages {
        stage("work") {
            sh("echo 'the-step-that-finished'")
        }
    }
}
