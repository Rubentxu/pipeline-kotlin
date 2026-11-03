//@ expect-exit 1
//@ failure-stage second/sh-0=1

// THE GUARANTEE UNDER TRIAL
//
// A consumer of the event lane attributes a failure to its stage FROM THE FAILURE
// EVENT. It does not join the failure with another event to find out, and it never
// has to guess.
//
// The failure event used to name the step and not the stage. The step index is the
// index INSIDE a stage, so the same index means a different step in each stage of
// the run: joining the failure with another event by that index was not even
// unambiguous between stages. And a failure that names no stage is not a failure
// in the first stage — reading the absent stage as stage 0 invents a stage that
// nobody said the failure belonged to.
//
// The launcher is the consumer. It asks the event lane for the failure events
// ONLY — never the step's terminal event, never the stage's own events — and reads
// the stage each one names.
//
// Before the fix, this scenario failed like this:
//
//     the external reader could not attribute the failure to its stage
//     | expected:  second/sh-0=1
//     | delivered: second/sh-0=-1
//
// The run fails on purpose: the point of the scenario is a failure in the SECOND
// stage, so that a reader that read the absent stage as stage 0 would attribute
// it to the first one and look right by accident.

pipeline {
    stages {
        stage("first") {
            sh("echo 'this one works'")
        }
        stage("second") {
            sh("exit 4")
        }
    }
}
