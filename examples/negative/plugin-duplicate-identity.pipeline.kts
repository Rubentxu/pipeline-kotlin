//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr DuplicateIdentity
//@ expect-stderr uppercase.jar
//@ expect-stderr the same identity was already claimed by
//@ expect-stderr example-uppercase-plugin-0.1.0.jar
//@ expect-stderr the whole composition is refused
//@ distribution lying-events
//@ plugin-jar bundled-uppercase

// The subject is a composition where two DIFFERENT artifacts claim the same plugin identity, and
// the refusal has to say which of them claimed it first, and that the composition is refused.
//
// The launcher hands the run two artifacts with one identity: the distribution's own copy of the
// example plugin, whose manifest has been doctored so that it declares an Event nobody
// contributes, and -- offered on the command line -- the operator's own copy of that same
// plugin, with the publisher's bytes and the publisher's manifest. The bytes differ, so the
// runtime cannot read them as one artifact, and the identities are the same, so the second one
// cannot be admitted beside the first.
//
// The refusal names the artifact that was REJECTED, because that is the file the operator named.
// And it names the artifact that had claimed the identity FIRST, because that is the one the
// operator has to find: a
// refusal that says "another artifact already has this name" without saying which one leaves the
// operator hunting through every JAR in the installation for the one that displaced theirs.
//
// The second name is the whole point of this scenario, and it is the part that used to be
// missing: the published refusal vocabulary carries the identity twice and no artifact at all,
// so the artifact that claimed the name first used to be named nowhere.
//
// The refusal also says that the WHOLE composition is refused. One refusal stops it, so nothing
// stays in it; a detail that said the artifact "stays in the composition" would be teaching the
// operator that their pipeline is running with their plugin in it, and that is false.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
