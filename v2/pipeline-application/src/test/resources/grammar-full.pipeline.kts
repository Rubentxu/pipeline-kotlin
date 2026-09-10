// Full DSL grammar fixture exercising the canonical M2-R1 grammar:
// agent, environment (withEnv), options, post, steps, parallel, retry, timeout
// plus error/sleep step types (via catchError).
//
// G2 contract: a stage body is EITHER linear OR parallel — never both.
// The historical fixture mixed parallel + sibling echo in the Deploy stage and
// was rejected fail-closed; Deploy is now pure-parallel.

pipeline {
    stages {
        stage("Build") {
            withEnv(listOf("JAVA_HOME=/usr/lib/jvm/java-17", "GRADLE_HOME=/opt/gradle")) {
                echo("Environment configured")
            }

            agent("linux-agent", "grpc://agent.example.com:9090")

            echo("Starting build")
            sh("echo compile done")
            sleep(2)
            catchError {
                error("Simulated build error", "SCRIPT")
            }
        }

        stage("Test") {
            agent("linux-agent")

            // NOTE (E-EM-11 inventory): stage-level options { retry(...) } is ambient DSL
            // metadata with no runtime materialization today (no OptionSpec("retry")
            // consumer; stage timeout projects to ShOptions only, without a
            // TimeoutScheduled scheduling transition). The canonical block steps below
            // are the certified producers of RetryAttempt*/TimeoutScheduled.
            retry(count = 2) {
                timeout(300, "SECONDS") {
                    echo("Running tests")
                    sh("echo tests done")
                }
            }
        }

        stage("Deploy") {
            agent("linux-agent")

            parallel {
                branch("db-migration") {
                    echo("Running database migrations")
                    sh("echo migrations done")
                }
                branch("app-deploy") {
                    echo("Deploying application")
                    sh("echo deploy done")
                }
            }
        }
    }
}
