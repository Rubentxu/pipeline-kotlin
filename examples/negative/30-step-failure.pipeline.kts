//@ expect-exit 1
//@ expect-stderr Pipeline finished with FAILURE
//@ expect-stderr cause [SCRIPT]: shell exited with code 1
// A command that fails must fail the whole run, and the report must say WHICH
// failure it was. The typed `error` step has a scenario of its own; this one
// covers the other kind, the one a shell brings: the command exits with a code,
// and the run must name that as a SCRIPT failure instead of collapsing it into
// a generic error. An exit-0 expectation here would let an engine that
// swallowed the command's failure pass, and expecting only "it failed" would
// accept any failure at all, which is the difference between a scenario that
// pins the behaviour and one that only pins that something broke.
pipeline {
    stages {
        stage("failing-command") {
            sh("echo 'the command refuses to succeed' && exit 1")
        }
    }
}
