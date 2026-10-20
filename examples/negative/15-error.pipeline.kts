//@ expect-exit 1
//@ expect-stderr Pipeline finished with FAILURE: test error message
// The `error` Step is the subject: it must fail the run. An exit-0
// expectation here would let an engine that swallowed the typed USER
// failure pass, and the substring pins the message so an unrelated crash
// cannot stand in for the guarantee.
import dev.rubentxu.pipeline.v2.domain.FailureKind
pipeline {
    stages {
        stage("error-step") {
            error("test error message", failureKind = FailureKind.USER)
        }
    }
}
