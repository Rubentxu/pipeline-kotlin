// 35-refused-run-is-still-answerable.pipeline.kts — a run refused before it starts can still be asked about.
//
//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ plugin-jar missing
//@ run-state run-state
//
// THE GUARANTEE UNDER TRIAL
//
// A durable run takes a lease on itself before it does anything else, and the record that lease
// leaves behind is what the next reader believes. So a run that is REFUSED — after the lease was
// taken, before any work started — has to give that lease back on the way out.
//
// The symptom of not giving it back is a lie that outlives the process: the record still names an
// owner that is gone, and the next reader is told the run "is being published by" a process that
// does not exist. That reader can neither learn what happened to the run nor take it over, because
// the run reads as owned.
//
// WHAT THE SCENARIO OBSERVES
//
// The launcher runs the scenario on the durable path, pointing it at a plugin artifact that is not
// there, so the run is refused with the lease already in hand. Then the launcher asks the product
// about that run through its own surface (`pipelinek inspect --state`), and the answer has to be
// an ANSWER: the reader is told about the run instead of being told about a dead owner.
//
// The expected state is the SHAPE of the answer and not its content. Which state a refused run is
// in belongs to the read side, and a scenario that froze that vocabulary here would break every
// time it grew. What is held is that the reader is answered at all, and the launcher checks that
// separately: the answer has to come with a zero exit, because a refusal to answer is exactly the
// defect this scenario exists to rule out.
//
// WHAT THIS DOES NOT CLAIM
//
// That the run reached a terminal state. A refused run did not run, and the terminal bookend is
// written by a run that did. What is claimed is the narrower thing the defect was about: the run is
// not left owned by a process that is gone.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
