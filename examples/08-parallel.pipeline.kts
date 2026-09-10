// Parallel branches: two branches execute concurrently through the canonical
// spine, each with its own durable identity (b0:/b1: journal keys).
//
// Run: examples/run.sh 08-parallel.pipeline.kts
// Durable demo: run twice with the same --db; the second run reuses the
// completed branch aggregate and launches no branch step again.
pipeline {
    stages {
        stage("parallel-demo") {
            parallel {
                branch("left") {
                    echo("left branch")
                    sh("echo left-work")
                }
                branch("right") {
                    echo("right branch")
                    sh("echo right-work")
                }
            }
        }
        stage("after-parallel") {
            echo("both branches joined")
        }
    }
}
