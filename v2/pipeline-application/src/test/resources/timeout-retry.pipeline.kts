// Timeout and retry fixture exercising retry and timeout semantics.

pipeline {
    stages {
        stage("RetryTest") {
            agent("linux-agent")

            options {
                retry(count = 3, delaySeconds = 2)
            }

            echo("Testing retry mechanism")
            sh("echo retry-step")
        }

        stage("TimeoutTest") {
            agent("linux-agent")

            options {
                timeout = 30
            }

            echo("Testing timeout mechanism")
            sh("echo timeout-step")
        }

        stage("ErrorHandling") {
            agent("linux-agent")

            echo("Testing error recording")
            catchError {
                error("Recorded error condition", "SCRIPT")
            }
            sh("echo error-handling-done")
        }
    }
}
