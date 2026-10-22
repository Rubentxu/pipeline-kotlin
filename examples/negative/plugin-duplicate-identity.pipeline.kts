//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr DuplicateIdentity
//@ expect-stderr uppercase.jar
//@ expect-stderr the same identity was already claimed by
//@ expect-stderr example-uppercase-plugin-0.1.0.jar
//@ expect-stderr the whole composition is refused
//@ plugin-jar repacked-uppercase

// The subject is a composition where two DIFFERENT artifacts claim the same plugin identity, and
// the refusal has to say which of them claimed it first, and that the composition is refused.
//
// The launcher hands the run two artifacts with one identity: the distribution's own copy of the
// example plugin, and -- offered on the command line -- the operator's copy of that same plugin,
// packaged a second time. Both are HONEST: the manifest is the publisher's and the code is the
// publisher's, so neither artifact is broken in any way. They differ in their bytes, which is
// what makes them two artifacts rather than one artifact offered twice, and their identities are
// the same, so the second one cannot be admitted beside the first.
//
// Nothing else is wrong with this composition, which is the point: the only reason to refuse it
// is the identity, so the refusal this scenario reads can only be about the identity. A scenario
// that also bent the manifest would be asking the reader to believe in two defects at once and
// would not be able to say which one produced the refusal.
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
