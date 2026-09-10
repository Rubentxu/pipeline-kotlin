// Pure-parallel fixture: parallel body is the ONLY body of the stage
// (G2 contract: parallel + sibling steps in one stage is fail-closed;
// see parallel-mixed-body-sibling.pipeline.kts for the negative case).

pipeline {
    stages {
        stage("ParallelTest") {
            parallel {
                branch("branch-a") {
                    echo("A1")
                    sh("echo A-running")
                    echo("A2")
                }
                branch("branch-b") {
                    echo("B1")
                    sh("echo B-running")
                    echo("B2")
                }
            }
        }
    }
}
