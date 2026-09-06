// Failing pipeline: sh exits with code 3.
// Expected behavior: the step fails with a typed failure (kind=SCRIPT),
// StepFailed is emitted exactly once, and the CLI exits non-zero.
// Run: examples/run.sh 05-failing-step.pipeline.kts   (exit code 1 is EXPECTED)
pipeline {
    stages {
        stage("ok") {
            echo("this stage runs")
        }
        stage("boom") {
            sh("echo 'about to fail' && exit 3")
        }
        stage("never-reached") {
            echo("this stage must NOT run")
        }
    }
}
