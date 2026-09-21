// WU-LPR-090 — `core.publishHTML` installed-CLI canary (Tier B #2).
//
// Exercises the registry-path `core.publishHTML` Step through the DSL surface.
// Stage "build" creates an HTML report directory under reports/ in the
// workspace and publishes it under the logical name "lpr090-html".
//
// Failures here mean one of:
//   - registry resolution broke (StepKey "core.publishHTML" not found)
//   - the registry handler regressed (no HtmlReportPublished, or wrong path shape)
//   - the DSL lowering reverted to a legacy StepSpec
//
// Storage layout: <controlRoot>/reports/<runId>/<lpr090-html>/. The reports
// directory is a SIBLING of <controlRoot>/workspace/ so cleanup of the build
// stage workspace does NOT destroy the published report.
//
// Verification: the installed CLI runs this fixture end-to-end and the
// orchestrator asserts that the events emitted include exactly one
// HtmlReportPublished (verified in UatCompat001CorpusSmokeRunTest via event
// count assertions over the --db journal).
pipeline {
    stages {
        stage("build") {
            sh("mkdir -p reports/lpr090")
            writeFile(file = "reports/lpr090/index.html", text = "<html><body>Hello LPR-090</body></html>")
            writeFile(file = "reports/lpr090/data.html", text = "<html><body>Data</body></html>")
            publishHTML(
                name = "lpr090-html",
                reportDir = "reports/lpr090",
                reportFiles = "**/*.html",
            )
        }
    }
}
