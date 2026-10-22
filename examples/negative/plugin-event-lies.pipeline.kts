//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr declared Events with no contributor
//@ expect-stderr example.uppercase.ghostEvent
//@ distribution lying-events

// The subject is a plugin that declares an Event nobody contributes.
//
// The launcher hands the run a copy of the installed distribution whose example plugin has one
// thing changed: its manifest declares an Event that no contributor provides. Everything else is
// the publisher's own -- the identity, the version, the publisher, the api range -- so the artifact
// is admitted. A manifest that promises an Event is still a well formed manifest.
//
// The second gate compares the declaration with the contributions: the Event is declared and
// nobody brings it, and the artifact is refused before the run exists.
//
// This is the third family of the same drift. A Step and a Directive were already observed
// against a real installation, and an Event is the family that names what the pipeline will
// publish: a plugin that declares an event it never emits makes the operator's journal promise a
// fact that never arrives, and every reader of that journal is downstream of the lie. The gate
// refuses it at admission, naming the phantom, instead of letting the promise outlive the run.
//
// The fixture is the same doctoring over the same artifact as the other two families: one line of
// the manifest changes and nothing else does.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
