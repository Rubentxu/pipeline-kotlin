// examples/testing/junit-failures.pipeline.kts
//
// LFC-2E3-T4 — the fixture that proves the CENTRAL LFC-2E3 invariant:
//
//     "tests failed"  !=  "Step execution failed"
//
//   pipeline-application \
//     --plugin-jar examples/testing-plugin/build/libs/testing-plugin-0.1.0-SNAPSHOT.jar \
//     examples/testing/junit-failures.pipeline.kts
//
// The parsed report contains a FAILED testcase (assertion) and an ERRORED
// testcase (uncaught exception), plus a skipped one. The run MUST still finish
// SUCCESS:
//
//   - the handler does NOT throw when tests fail;
//   - the Step's durable typed output is `TestReport.Successful` with
//     `hasTestFailures == true`;
//   - the failure facts reach the observer through the typed output (and, once
//     the transport seam lands, through the testing events of E3-T3).
//
// Turning failing tests into a build failure is a POLICY decision that this
// cycle deliberately does not invent: it belongs to a separate Step (e.g. a
// future `policy.failOnTestFailures`) rather than to the parser that reports
// faithfully.
//
// Expected terminal outcome: SUCCESS, even though 1 test failed and 1 errored.
// A run that reported FAILURE here would mean the invariant is broken.

import pipeline.testing.junit.junit

pipeline {
    stages {
        stage("junitFailures") {
            junit(reportPaths = listOf("examples/testing/junit-failures.xml"))
        }
    }
}
