//@ expect-exit 0
//@ events-follow StepFinished 30

// THE GUARANTEE UNDER TRIAL
//
// A client that follows a run with a FILTER is released when the run ends.
//
// The filter decides what the client is handed. It does not decide whether the run is over.
// The run's terminal fact is the `RunFinished` event, and the follow has to see it in the STORE
// — not in the facts the filter let through.
//
// Before the fix, this scenario hung: the follow looked for the terminal fact among the facts it
// had just delivered, and `--kind StepFinished` excludes exactly that fact. The store's cursor is
// never null, so the "no more rows" exit was unreachable, and the follow polled forever on a run
// that had finished minutes earlier. Measured against the distribution: `exit 124`, the time
// exhausted.
//
// The launcher is the client. It follows with a filter, under a wall clock, and the scenario only
// passes if the follow ENDS on its own. A clock is the only instrument that can catch a wait
// that never ends.
//
// The scenario also asserts the other half: the terminal fact is what ENDS the follow, and the
// filter still decides what is delivered. A follow that delivered the `RunFinished` would have
// delivered a fact the filter excluded.

pipeline {
    stages {
        stage("first") {
            sh("echo 'the step that ends the run'")
        }
    }
}
