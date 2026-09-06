// Multi-stage pipeline: stages execute in declaration order and each one
// is visible in the emitted event timeline.
// Run: examples/run.sh 02-multi-stage.pipeline.kts
pipeline {
    stages {
        stage("build") {
            echo("building artifacts...")
        }
        stage("test") {
            echo("running tests...")
        }
        stage("deploy") {
            echo("deploying (not really)...")
        }
    }
}
