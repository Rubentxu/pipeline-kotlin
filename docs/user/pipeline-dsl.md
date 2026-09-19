# PipelineK — Pipeline DSL

**Release verified against**: `pipelinek 0.39.0` against the certified
`local-core-v1` ledger. The shape of every construct below was observed
in the published binary.

The DSL is a Kotlin DSL embedded in a regular `.kts` file. You get the
full Kotlin language at the file level; the DSL only governs the body
of `pipeline { ... }`.

## Minimum structure

```kotlin
pipeline {
    stages {
        stage("name") {
            // any sequence of StepSpec expressions
        }
    }
}
```

Steps inside a `stage` are executed **in order**. The next step only
runs if the previous one exited `0`. A non-zero step exit fails the
stage, the run, and the CLI exits with `1`.

## Certified StepSpecs

These are the StepSpecs shipped with `pipelinek 0.39.0`. Anything beyond
this list is either registered by an installed plugin or out of scope
for the v1 contract.

| Step | Signature | What it does |
|---|---|---|
| `echo(message)` | `(String) -> Unit` | Emits a single typed `EchoOutputCaptured` event with the literal message. Does not run a process. |
| `sh(command)` | `(String) -> Unit` | Runs the command in the workspace via a real shell (`/bin/sh -c` on UNIX, `cmd /c` on Windows). Exit `0` → step passes; anything else → step fails with `failureKind=SCRIPT`. |

The list of StepSpecs above comes from running `pipelinek validate` on
real-world fixtures (Gradle, Maven, Node). It is the **certified set**
for `0.39.0`. New StepSpecs land through plugins, not by changing this
file.

## What the DSL is **not** (v1)

- **No `steps { ... }` block** in v0.39.0. Putting `echo(...)` /
  `sh(...)` directly inside `stage { ... }` is the correct form.
- **No `parallel { ... }` / `retry { ... }` / `script { ... }`** in
  v0.39.0. The block-step family is planned but not certified in this
  release; see the project roadmap.
- **No `agent { ... }`** in v0.39.0. PipelineK is local-only and
  does not provision remote executors.
- **No `when { ... }`, no `options { ... }`, no `parameters { ... }`**.
- **No `post { always { ... } }` / `success { ... }` / `failure { ... }`**
  blocks. State handling happens at the durable layer (`--db`,
  `--control-root`), not in the DSL.

If you write any of the above, `pipelinek validate` will return exit
code `2` with a compile-time diagnostic. That is intentional — only
certified shapes compile.

## Full example

```kotlin
// Real Gradle fixture (the same one PipelineK uses in its own UAT)
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/*.jar")
            echo("GRADLE-DEMO-OK")
        }
    }
}
```

This pipeline runs `./gradlew build`, then asserts a jar was produced,
then emits the marker. If `./gradlew build` fails, the next two steps
are skipped, the run fails, and the CLI exits `1`.

## Why the DSL looks like Jenkins

The DSL shape (`pipeline { stages { stage { ... } } }`) is **familiar**
to Jenkins users, but the semantics are PipelineK's own. Jenkins-familiar
constructs are only honored when they are certified. Anything else
either compiles as plain Kotlin or fails to compile.
