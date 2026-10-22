//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr declared Directives with no contributor
//@ expect-stderr example.uppercase.ghostDirective
//@ distribution lying-directives

// The subject is a plugin that declares a Directive nobody contributes.
//
// The launcher hands the run a copy of the installed distribution whose example plugin has one
// thing changed: its manifest declares a Directive that no contributor provides. Everything else is
// the publisher's own -- the identity, the version, the publisher, the api range -- so the artifact
// is admitted. A manifest that promises a Directive is still a well formed manifest.
//
// The second gate compares the declaration with the contributions: the Directive is declared and
// nobody brings it, and the artifact is refused before the run exists.
//
// Naming the Directive matters more here than in the other drifts, because a Directive is not
// merely data: it is a piece of the composition the operator invokes by name. A plugin that
// declares one it cannot deliver makes the operator's pipeline name something that does not exist,
// and the refusal is what turns that from a runtime surprise into an admission-time refusal that
// names the phantom.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
