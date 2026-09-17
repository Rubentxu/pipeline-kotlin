// examples/testing/junit-then-publish.pipeline.kts
//
// LFC-2E3-R2 — the linkage fixture. Exercises the full first vertical of
// LFC-2E3 inside ONE run, through the installed CLI and the plugin JAR:
//
//   pipeline-application run \
//     --plugin-jar examples/testing-plugin/build/libs/testing-plugin-0.1.0-SNAPSHOT.jar \
//     examples/testing/junit-then-publish.pipeline.kts
//
//   stage("testResults")    -> core.junit       : JUnit XML -> typed TestReport
//   stage("publishReport")  -> core.publishHTML : HTML report tree -> PublishedReport
//
// Both Steps run through the SAME canonical durable spine, the SAME plugin
// coordinate (`pipeline.testing`) and two SEPARATELY declared capabilities
// (`testing.filesystem.operations` read-only, `testing.publish.operations`
// writing). They are correlated by run identity and ordered by stage, which is
// the linkage this slice delivers — not by an out-of-band controller.
//
// The JUnit XML fed in here CONTAINS a failed and an errored testcase. The run
// still finishes SUCCESS, because reporting test outcomes faithfully is not the
// same thing as failing the build (the central LFC-2E3 invariant).
//
// Known limitation (documented, not worked around): there is no mechanism yet
// for one Step to consume another Step's typed OUTPUT as its INPUT, so
// `publishHTML` is pointed at the HTML report directory directly rather than
// being handed the parsed TestReport value. Value piping between Steps is a
// separate generic capability; this slice deliberately does not invent it.
//
// Expected terminal outcome: SUCCESS.

import pipeline.testing.junit.junit
import pipeline.testing.publish.publishHTML

pipeline {
    stages {
        stage("testResults") {
            junit(reportPaths = listOf("examples/testing/junit-failures.xml"))
        }
        stage("publishReport") {
            publishHTML(
                reportName = "Unit Tests",
                reportDir = "examples/testing/html-report",
                reportFiles = "index.html",
                targetDir = "build/testing-published/unit-tests",
            )
        }
    }
}
