# Jenkins familiarity north star

## Goal

Migration from Jenkins Pipeline should feel syntactic, not conceptual.

### Preserve

- `pipeline`, `agent`, `environment`, `options`, `parameters`, `tools`, `stages`, `stage`, `steps`, `when`, `post`, `parallel`, `matrix`, `script` mental model;
- well-known step names: `sh`, `echo`, `error`, `sleep`, `dir`, `withEnv`, `withCredentials`, `retry`, `timeout`, `catchError`, `archiveArtifacts`, `junit`, checkout/git family and others admitted by the familiarity catalogue;
- named parameters where Kotlin permits a natural mapping.

### Improve with Kotlin

- closed stage-body types rather than runtime validation of mutually-exclusive constructs;
- typed enums/values instead of magic strings;
- overloaded/typed runtime-returning APIs instead of Groovy-style return type changes based on booleans;
- generated compile-time plugin façades;
- IDE completion and compiler diagnostics.

## Compatibility levels

- **F0 — Name familiarity:** name resembles Jenkins; no migration claim.
- **F1 — Surface familiarity:** parameters/shape recognizable, semantics may differ.
- **F2 — Behavioral familiarity:** tested behavior equivalent for declared cases.
- **F3 — Migration compatibility:** automated/mechanical migration recipe exists and divergences are documented.

A compatibility level MUST be backed by executable fixtures. A step cannot claim F2/F3 solely from signature similarity.

## DSL example

```kotlin
pipeline {
    agent(any)
    options {
        timestamps()
        timeout(time = 1, unit = HOURS)
    }
    stages {
        stage("Test") {
            when { branch("main") }
            steps {
                sh("./gradlew test")
                junit("**/build/test-results/*.xml")
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
    }
}
```
