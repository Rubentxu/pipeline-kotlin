//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr capabilities that differ from the contract
//@ expect-stderr example.uppercase.announcedCased
//@ expect-stderr declared=[example.uppercase.case-table]
//@ expect-stderr contract=[example.uppercase.case-table, plugin.event-emission]
//@ distribution lying-capabilities-omitted

// The subject is a plugin whose manifest forgets to declare a capability its own code demands.
//
// The other drift in this family is the opposite one: a Step claims a capability that its
// contract never asks for, and that one was observed against a real artifact first. This is the
// other direction, and it is the one an operator feels: the Step says it needs two capabilities,
// and the manifest only promises one.
//
// The launcher hands the run a copy of the installed distribution whose example plugin has one
// thing changed: the per-Step declaration of `example.uppercase.announcedCased` no longer lists
// the event emission capability that the Step's own contract requires. Everything else is the
// publisher's own -- the identity, the version, the publisher, the api range, and the plugin's
// global capability list -- so the artifact is admitted. A manifest that under-promises is still
// a well formed manifest.
//
// The second gate compares the declaration with the contract in BOTH directions, and the two
// do not agree: the contract requires a capability the manifest does not declare, and the
// artifact is refused before the run exists.
//
// Both sides of the disagreement are named, because one side alone would not tell the operator
// what to do. A Step that declares one capability and demands two is not a plugin that lies
// about what it will use; it is a plugin whose manifest forgot to mention what it needs, and the
// difference is who has to fix it.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
