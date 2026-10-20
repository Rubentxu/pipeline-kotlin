//@ expect-exit 1
//@ step-verdict verdict/sh-0=success verdict/sh-1=failure

// THE GUARANTEE UNDER TRIAL
//
// A consumer of the event lane reads a step's RESULT from the step's own terminal
// event. It does not infer the result from the absence of another event, and it
// does not have to join two events to learn that a step failed.
//
// The step boundary emits the terminal event from a `finally`, so the terminal
// event of a FAILED step is emitted too. Before the verdict was published, that
// event carried no result at all: a consumer had to notice the separate failure
// event and join the two by step index — a join that is not even unambiguous
// between stages, because the failure event does not say which stage it belongs
// to. A consumer that read "the step finished" as "the step succeeded" was
// reading a failure as a success, and it was the reading the vocabulary invited.
//
// The launcher is the consumer. It asks the event lane for the step terminal
// events ONLY — never the failure event — and reads each one's verdict. If a
// terminal event carried no verdict, the consumer could not tell a success from
// a failure, and this is where that shows.
//
// Before the fix, this scenario failed like this:
//
//     the external reader could not read the step verdicts
//     | expected:  verdict/sh-0=success verdict/sh-1=failure
//     | delivered: verdict/sh-0= verdict/sh-1=
//
// The run fails on purpose: the point of the scenario is a step that failed
// beside a step that did not, so that the consumer has to tell them apart from
// the event lane alone.

pipeline {
    stages {
        stage("verdict") {
            sh("echo 'this one works'")
            sh("echo 'this one refuses' && exit 3")
        }
    }
}
