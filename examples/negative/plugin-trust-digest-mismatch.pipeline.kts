//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr RefusedDigestMismatch
//@ plugin-jar bundled-uppercase
//@ plugin-trust signed-then-tampered

// The subject is the swap: the operator approved some bytes, and the bytes on disk are not them.
//
// The launcher signs the plugin's bytes and then changes them, so the publisher's signature
// still speaks about the good bytes while the file carries different ones. The operator's row
// pins the digest they vetted, and the runtime measures the file it is about to load. The two
// are different quantities and the artifact is refused.
//
// The refusal has to name the artifact AND the kind of refusal. A run that merely failed would
// leave open whether the plugin was skipped, and a skipped plugin is the failure this scenario
// exists to rule out: the pipeline below would run perfectly well without it, so a green run
// would be indistinguishable from a run that quietly ran with one plugin fewer.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
