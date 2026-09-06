// Minimal pipeline: a single stage with one echo step.
// Run: examples/run.sh 01-hello.pipeline.kts
pipeline {
    stages {
        stage("hello") {
            echo("hello from pipeline-kotlin v2")
        }
    }
}
