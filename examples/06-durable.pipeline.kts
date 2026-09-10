// Durable pipeline: run it with a SQLite journal to get durable execution.
//
//   examples/run.sh 06-durable.pipeline.kts --db /tmp/hello-journal.db
//
// With --db the runner journals every operation, fingerprints its inputs and
// reuses the prior run's terminal state on rerun (pass --rerun to force a
// fresh run, --resume to continue an interrupted one).
pipeline {
    stages {
        stage("prepare") {
            echo("preparing workspace")
        }
        stage("expensive") {
            sh("echo 'expensive work happening'; sleep 1; echo done")
        }
        stage("finish") {
            echo("pipeline finished")
        }
    }
}
