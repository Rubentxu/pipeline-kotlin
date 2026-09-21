# Reference pipeline used across milestones

This pipeline is the vertical acceptance fixture. It intentionally touches the important cross-cutting paths.

```kotlin
pipeline {
    agent(any)

    environment {
        "CI" set "true"
    }

    options {
        timestamps()
        timeout(time = 30, unit = MINUTES)
    }

    stages {
        stage("Checkout") {
            steps {
                checkout(
                    git(
                        url = "https://example.invalid/repo.git",
                        branch = "main",
                    )
                )
            }
        }

        stage("Build") {
            steps {
                withEnv("BUILD_MODE=ci") {
                    withCredentials(
                        usernamePassword(
                            credentialsId = "repo",
                            usernameVariable = "REPO_USER",
                            passwordVariable = "REPO_PASS",
                        )
                    ) {
                        sh("./gradlew assemble")
                    }
                }
            }
        }

        stage("Verify") {
            parallel {
                stage("Test") {
                    steps {
                        sh("./gradlew test")
                        junit("**/build/test-results/*.xml")
                    }
                }
                stage("Lint") {
                    steps { sh("./gradlew lint") }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts(
                artifacts = "build/reports/**",
                allowEmptyArchive = true,
            )
        }
    }
}
```

Exact helper names may change before DSL API freeze; the structural intent is normative.
