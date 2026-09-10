// Timeout and retry fixture exercising retry and timeout semantics.

pipeline {
    stages {
        stage("RetryTest") {
            agent("linux-agent")

            // E-EM-11: stage-level options { retry(...) } is ambient DSL metadata with
            // no runtime materialization; the canonical retry block step is the
            // certified producer of RetryAttemptStarted/Finished.
            retry(count = 3) {
                echo("Testing retry mechanism")
                sh("echo retry-step")
            }
        }

        stage("TimeoutTest") {
            agent("linux-agent")

            // E-EM-11: the canonical timeout block step is the certified producer
            // of TimeoutScheduled at admission.
            timeout(30, "SECONDS") {
                echo("Testing timeout mechanism")
                sh("echo timeout-step")
            }
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
