//@ durable-once
//@ events-resume ParallelBranchStarted 2
//@ expect-exit 0

// THE GUARANTEE UNDER TRIAL
//
// An external client reads the event lane by cursor: it asks for a page, gets
// facts and a cursor, disconnects, comes back with the cursor and continues.
// What it must get is EVERY fact, once: none skipped, none repeated.
//
// This scenario is the fixture that makes the guarantee observable. The run
// below uses a `parallel` block on purpose: the branches of a parallel block
// produce their events ADJACENTLY (three `ParallelBranchStarted` in a row, for
// three branches), and that adjacency is what a page boundary can cut in the
// middle. A history whose rows of the same kind are spread out cannot show the
// defect at all, however many times it is read.
//
// The launcher is the client. It reads the history in pages of TWO facts, and
// every page is a separate invocation of the verb — a real disconnection, with
// the cursor as the only thing that travels. Then it compares what it collected
// against the full read of the same history, and it says WHICH fact was lost
// or repeated when they differ.
//
// Before the fix, this scenario failed like this:
//
//     the external reader did not resume cleanly (kind=ParallelBranchStarted limit=2)
//     | expected:  6 7 8
//     | delivered: 6 7
//     | lost:      8
//
// The cursor after the first page pointed at the store's position (row 8) while
// row 8 had never been handed out, so the client resumed AFTER the fact it had
// not received. Exit code 0, no error, no gap marker: the fact was simply gone.

pipeline {
    stages {
        stage("parallel-branches") {
            parallel {
                branch("a") { echo("a") }
                branch("b") { echo("b") }
                branch("c") { echo("c") }
            }
        }
    }
}
