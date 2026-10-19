//@ expect-stdout is unix: true

// The subject is a detected boolean reaching the script as a typed value. The
// step's effect emits a single typed domain event carrying the boolean, and the
// echo below proves the value arrived rather than merely running the step.
//
// A failure here means one of: the step could not be resolved in the registry,
// the handler regressed and emitted nothing or the wrong boolean, or the
// compiler routed through a path that no longer exists. Exiting 0 alone would
// not tell those three apart, which is why the launcher is told what to look
// for on stdout.
pipeline {
    stages {
        stage("isUnix-step") {
            val unix = isUnix()
            if (unix) {
                echo("is unix: true")
            } else {
                echo("is unix: false")
            }
        }
    }
}
