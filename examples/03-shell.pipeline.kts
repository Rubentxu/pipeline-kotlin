// Real shell steps: each sh() launches a real OS process.
// The runner journals the invocation and its exit code.
// Run: examples/run.sh 03-shell.pipeline.kts
pipeline {
    stages {
        stage("system-info") {
            sh("uname -a")
        }
        stage("workdir") {
            sh("pwd")
        }
        stage("loop") {
            sh("for i in 1 2 3; do echo iteration-\$i; done")
        }
    }
}
