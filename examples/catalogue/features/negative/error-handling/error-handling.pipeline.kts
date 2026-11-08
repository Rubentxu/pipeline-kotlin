pipeline {
    stages {
        stage("error-and-milestone") {
            warnError("intentionally flaky section") {
                catchError {
                    sh("exit 1")
                }
            }
            milestone(ordinal = 1, label = "post-error")
        }
        stage("post-milestone") {
            echo("milestone reached")
        }
    }
}
