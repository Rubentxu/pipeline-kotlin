// P4/P7: one branch succeeds, the other fails.
// Current productive policy (grounded): aggregate outcome = failure.

pipeline {
    stages {
        stage("ParallelFailure") {
            parallel {
                branch("ok") {
                    echo("ok")
                }
                branch("bad") {
                    sh("exit 3")
                }
            }
        }
    }
}
