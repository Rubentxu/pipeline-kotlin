//@ durable-once
//@ events-refusal no-event-history
//@ expect-exit 2
//@ expect-stderr Error: db has no event history

// THE GUARANTEE UNDER TRIAL
//
// A reader pointed at a file that holds no history says THAT, and leaves the file
// exactly as it found it.
//
// The second half is the one with teeth. A reader that opens the producer's file with
// a writer's permissions does not merely read it: it creates the event schema in it,
// migrates the journal and backfills it. Point such a reader at a file that is not an
// event store and it stops being whatever it was — it becomes a database. The launcher
// checks that the empty file it hands to the reader is STILL EMPTY afterwards, because
// "the reader refused politely" and "the reader refused without touching anything" are
// different guarantees, and only the second one is worth anything to the owner of the
// file.
//
// The refusal itself must also be specific. "Unknown run" would send the operator
// looking for a run that was never the problem; the problem is the FILE.
//
// The run below is the FIXTURE: it writes a history, and the launcher then points the
// read verb at a file that is not that history. The verdict is the refusal's, not the
// run's.
pipeline {
    stages {
        stage("Fixture") {
            echo("this run exists, and is what the refusal is asked about")
        }
    }
}
