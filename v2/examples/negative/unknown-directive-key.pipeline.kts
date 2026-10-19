//@ expect-exit 1
//@ expect-stderr unresolved directive 'acme.no.such.directive'
// A directive key that no contributor registers must fail CLOSED and name the
// key, never be skipped.
//
// This is the other half of plugins/lock-directive.pipeline.kts. That scenario
// would stay green if admission turned fail-OPEN and simply ignored the whole
// directives block; this one goes red in the same circumstance, and it also
// pins the specific refusal rather than accepting any nonzero exit, so a
// compiler error or an unrelated crash cannot stand in for the real guarantee.

pipeline {
    stages {
        stage("Unknown directive") {
            directives {
                directive("acme.no.such.directive", """{}""")
            }
            sh("echo should never run")
        }
    }
}