# PipelineK — Quickstart

**Release verified against**: `pipelinek 0.39.0`. The pipeline below runs
end-to-end on a fresh checkout of any Gradle JVM project.

## Your first pipeline

Create a file called `pipeline.kts` next to your Gradle project root
(the directory that contains `settings.gradle.kts` / `settings.gradle`):

```kotlin
// pipeline.kts — minimum real pipeline: build, check artifact, marker
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/*.jar")
            echo("MY-FIRST-PIPELINE-OK")
        }
    }
}
```

Each call inside `stage { ... }` runs **in order**. The next call only
runs if the previous one exited `0`. If any call exits non-zero, the
stage fails, the run fails, and the process exits with `1`.

## Validate (no side effects)

```bash
pipelinek validate pipeline.kts
# → VALIDATION SUCCESSFUL
```

`validate` compiles the script and reports type / DSL errors. It does
**not** run any of the steps.

## Run

```bash
pipelinek run --workspace . pipeline.kts
# → RunFinished outcome=success
# → exit code 0
```

`--workspace .` tells PipelineK the directory that holds your project's
toolchain files (`gradlew`, `mvnw`, `package.json`, etc.). Without it,
PipelineK uses the parent of the run's `--control-root` and may not
find your project root.

If your toolchain is on `PATH` (e.g. installed via `asdf`, SDKMAN, or
Homebrew), no further configuration is needed.

## What you'll see

For a successful run, the CLI emits a stream of typed JSON events to
**stdout** (`CompilationStarted`, `RunStarted`, `StageStarted`,
`StepStarted`, `StepFinished`, `StageFinished`, `RunFinished`). Each
event has `runId`, `sequence`, `occurredAt`, and step-specific payload.

For a failing step, the failure is emitted as a `StepFailed` event with
`failureKind` and `message`, and the run exits with code `1`.

## Inspect what happened

Each run also leaves a **durable SQLite journal** at `--db <path>` and a
**control-root** at `--control-root <path>`. These let you resume,
re-run, or inspect after the fact.

See [`events-and-troubleshooting.md`](events-and-troubleshooting.md) for
the full picture.
