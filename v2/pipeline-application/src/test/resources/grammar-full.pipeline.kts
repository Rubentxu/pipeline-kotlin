// Full DSL grammar fixture exercising all M2-R1 capabilities:
// agent, environment, options, post, steps, parallel, retry, timeout, whenCondition, script
// Plus error and sleep step types.

pipeline {
    stages {
        stage("Build") {
            // Environment variables
            withEnv(listOf("JAVA_HOME=/usr/lib/jvm/java-17", "GRADLE_HOME=/opt/gradle")) {
                echo("Environment configured")
            }

            // Agent specification
            agent("linux-agent", "grpc://agent.example.com:9090")

            // Options with timeout and retry
            options {
                timeout = 600
                retry(count = 3, delaySeconds = 5)
            }

            // Post conditions
            post {
                always {
                    echo("Cleaning up...")
                    sh("echo clean done")
                }
                success {
                    echo("Build succeeded")
                }
                failure {
                    error("Build failed", "SCRIPT")
                }
            }

            // Steps
            echo("Starting build")
            sh("echo compile done")
            sleep(2)
            catchError {
                error("Simulated build error", "SCRIPT")
            }
        }

        stage("Test") {
            agent("linux-agent")

            options {
                timeout = 300
                retry(count = 2)
            }

            post {
                always {
                    echo("Test cleanup")
                }
            }

            echo("Running tests")
            sh("echo tests done")
        }

        stage("Deploy") {
            agent("linux-agent")

            options {
                timeout = 180
            }

            // Parallel execution
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

            echo("Deployment complete")
        }
    }
}
