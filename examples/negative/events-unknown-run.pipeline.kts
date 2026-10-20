//@ durable-once
//@ events-refusal unknown-run
//@ expect-exit 2
//@ expect-stderr Error: unknown run

// THE GUARANTEE UNDER TRIAL
//
// A read of the event lane that cannot be served says WHY it cannot be served.
//
// Before this, `pipelinek events <run-inexistente>` answered with exit code 0, no output,
// and even a continuation cursor — indistinguishable from a run that exists and has not
// written anything yet. An operator could not tell "there is nothing to show" from "you
// asked for something that is not there", and a polling script could not tell either.
//
// The run below is the FIXTURE: it writes a history, and the launcher then asks the read
// verb about a run that does not exist. The verdict is the refusal's, not the run's.
pipeline {
    stages {
        stage("Fixture") {
            echo("this run exists, and is what the refusal is asked about")
        }
    }
}
