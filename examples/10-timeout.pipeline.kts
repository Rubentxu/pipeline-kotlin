// Timeout: the inner step runs longer than the budget, so the block deadline
// aborts it and the run finishes FAILURE — an expected-timeout demo.
//
// Run: examples/run.sh 10-timeout.pipeline.kts
pipeline {
    stages {
        stage("timeout-demo") {
            timeout(time = 2, unit = "SECONDS") {
                sh("echo 'starting slow work'; sleep 30; echo 'never printed'")
            }
        }
    }
}
