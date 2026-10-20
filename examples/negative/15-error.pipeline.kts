//@ expect-exit 1
//@ expect-stderr Pipeline finished with FAILURE
//@ expect-stderr cause [USER]: test error message
// The `error` Step is the subject: it must fail the run. An exit-0
// expectation here would let an engine that swallowed the typed USER
// failure pass. The substrings pin the report, not just the outcome: the
// outcome line says what happened, and the cause line says WHY and of which
// kind, which is the difference between a report a person can act on and one
// they have to guess about. Both execution paths print the same report, so
// both of them are asserted by the same scenario.
import dev.rubentxu.pipeline.v2.domain.FailureKind
pipeline {
    stages {
        stage("error-step") {
            error("test error message", failureKind = FailureKind.USER)
        }
    }
}
