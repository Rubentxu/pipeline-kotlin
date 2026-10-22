//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr capabilities that differ from the contract
//@ expect-stderr example.uppercase.ghostCapability
//@ distribution lying-capabilities

// The subject is a plugin that claims to need something its own code never asks for.
//
// The launcher hands the run a copy of the installed distribution whose example plugin has one
// thing changed: its manifest declares a capability for one of its Steps that the Step's contract
// does not require. Everything else is the publisher's own -- the identity, the version, the
// publisher, the api range -- so the artifact is admitted. A manifest that over-promises is still
// a well formed manifest, and the first gate reads documents, not code.
//
// The second gate compares the declaration with the contract: the Step says it needs a capability
// that its own contract never asks for, the two do not agree, and the artifact is refused before
// the run exists.
//
// The capability is NAMED, and that is the point of the scenario. A refusal that said only
// "the plugin lies" would leave the operator hunting: a capability is a promise about what the
// plugin will do with the data it is given, so which capability was promised and what the contract
// actually requires is the difference between "this plugin is broken" and "this plugin asks for
// something it will never use". The refusal has to carry both sides of the disagreement.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
