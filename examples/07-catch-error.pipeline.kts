// Nested catchError: the inner scope re-throws (buildResult=FAILURE), the outer
// scope suppresses it as UNSTABLE, and the pipeline continues to the next step.
//
// Run: examples/run.sh 07-catch-error.pipeline.kts
//
// Contract (ERR-S-007): exactly two CatchErrorTriggered events, inner FAILURE
// first then outer UNSTABLE, and the echo after the scopes still executes.
pipeline {
    stages {
        stage("catch-demo") {
            catchError(
                buildResult = "UNSTABLE",
                stageResult = "UNSTABLE",
            ) {
                catchError(
                    buildResult = "FAILURE",
                    stageResult = "FAILURE",
                ) {
                    sh("echo inner-body-failing")
                    sh("exit 1")
                }
            }
            echo("continues after nested catch")
        }
    }
}
