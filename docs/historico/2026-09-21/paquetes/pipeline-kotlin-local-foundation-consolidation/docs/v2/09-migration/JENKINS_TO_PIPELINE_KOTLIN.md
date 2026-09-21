# Jenkins to pipeline-kotlin migration

## Strategy

Migration toolchain operates in three tiers:

### Tier A — deterministic declarative conversion
Recognized Declarative Pipeline AST/directives and supported steps map mechanically to Kotlin builders.

### Tier B — assisted conversion
Known Groovy idioms map to typed Kotlin equivalents with explicit notes (for example return-type-sensitive `sh`).

### Tier C — manual boundary
Dynamic Groovy/metaprogramming/shared-library constructs that cannot be proven equivalent are emitted as diagnostics/TODOs. Never silently approximate semantics.

## Example

Jenkins:

```groovy
pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh './gradlew build'
      }
    }
  }
}
```

Kotlin:

```kotlin
pipeline {
    agent(any)
    stages {
        stage("Build") {
            steps {
                sh("./gradlew build")
            }
        }
    }
}
```

## Success metric

Track corpus coverage by directives and step invocations, not just files. A file with one unsupported dynamic expression is not counted as silently successful.
