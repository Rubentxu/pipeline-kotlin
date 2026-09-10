// Retry with a self-healing command: the first attempt fails, the second
// succeeds, using a marker file (deterministic, no timing).
//
// Run: examples/run.sh 09-retry.pipeline.kts --db /tmp/retry-journal.db
// Durable demo: re-running with the same --db replays the journaled attempts
// instead of executing a third one.
pipeline {
    stages {
        stage("retry-demo") {
            retry(count = 3) {
                sh("""
                    if [ -f /tmp/pipeline-retry-done ]; then
                        echo 'attempt: success'
                    else
                        touch /tmp/pipeline-retry-done
                        echo 'attempt: failing once'
                        exit 1
                    fi
                """.trimIndent())
            }
        }
        stage("confirmed") {
            echo("retry ended successfully")
        }
    }
}
