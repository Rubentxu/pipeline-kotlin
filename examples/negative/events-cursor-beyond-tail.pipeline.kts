//@ durable-once
//@ events-refusal cursor-beyond-tail
//@ expect-exit 2
//@ expect-stderr Error: cursor
//@ expect-stderr past the tail

// THE GUARANTEE UNDER TRIAL
//
// A cursor that points past the end of the history is a DIFFERENT refusal from a run that
// does not exist, and the two have different remedies: one means "reset the cursor", the
// other means "stop looking for the run".
//
// Before this, both came back as an empty page with exit code 0 — and the follow contract,
// which is published, folded them into a single "the run does not exist" case, so a consumer
// of the SDK could not tell them apart either.
//
// The run below is the FIXTURE: it writes a history, and the launcher then asks the read
// verb to continue from a position beyond its end. The verdict is the refusal's.
pipeline {
    stages {
        stage("Fixture") {
            echo("this run exists, and is what the refusal is asked about")
        }
    }
}
