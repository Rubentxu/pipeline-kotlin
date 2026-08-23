// Timeout and retry fixture exercising retry and timeout semantics.

pipeline {
    stages {
        stage("RetryTest") {
            agent("linux-agent")

            options {
                retry(count = 3, delaySeconds = 2)
            }

            echo("Testing retry mechanism")
            sh("make flaky-task")
        }

        stage("TimeoutTest") {
            agent("linux-agent")

            options {
                timeout = 30
            }

            echo("Testing timeout mechanism")
            sh("make long-running-task")
        }

        stage("ErrorHandling") {
            agent("linux-agent")

            echo("Testing error recording")
            error("Recorded error condition", "SCRIPT")
            sh("make error-task")
        }
    }
}
