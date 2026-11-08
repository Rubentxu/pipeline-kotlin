// Negative twin: expected shell failure, PipelineK should exit 1 with SCRIPT failure.
pipeline {
    stages {
        stage("intentional-failure") {
            sh("exit 7")
        }
    }
}
