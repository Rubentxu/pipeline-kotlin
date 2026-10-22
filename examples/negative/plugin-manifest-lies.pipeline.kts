//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr declared Steps with no implementation
//@ expect-stderr example.uppercase.ghost
//@ distribution lying-uppercase

// The subject is a plugin that ADMITS and then lies.
//
// The launcher hands the run a copy of the installed distribution whose example plugin has one
// thing changed: its manifest declares a Step the plugin does not implement. Everything else is
// the publisher's own -- the identity, the version, the publisher, the api range -- so the
// artifact is admitted, which is the point: a manifest is a document, and a document that lies is
// still a well formed document. The first gate cannot see the lie, because the lie is only
// visible against the plugin's CODE.
//
// The second gate can: it loads the contributor and compares what the manifest DECLARED with
// what the contributor actually PROVIDED. The declared Step has no definition, the comparison is
// not consistent, and the artifact is refused before the run exists.
//
// The refusal has to name the artifact AND the drift. A run that merely failed would leave open
// whether the plugin was skipped, and a skipped plugin is the failure this scenario exists to
// rule out: the pipeline below would run perfectly well without the plugin, so a green run here
// would be indistinguishable from a run that quietly ran with one plugin fewer. The ghost Step is
// named because an operator who reads "refused" without "what was wrong" still has to guess.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
