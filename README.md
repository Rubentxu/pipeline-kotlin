# PipelineK

A local-first CI/CD engine with a Jenkins-familiar Kotlin DSL.
PipelineK runs pipelines locally with durable execution, real typed
events, and structured failures — without a controller, agent, or
remote state.

```bash
# Install
sdk install pipelinek 0.39.0

# Verify the install
pipelinek version          # → pipeline 0.39.0
pipelinek doctor           # jdk / os / workdir / writable

# Run your first pipeline
pipelinek run examples/01-hello.pipeline.kts
```

## What is PipelineK

PipelineK is a single-binary pipeline runner. You write `.pipeline.kts`
files in a typed Kotlin DSL that resembles Jenkins' Groovy syntax, and
PipelineK compiles, validates, and executes them locally. Every step
emits a typed event; every shell process is journaled with a SHA-256
fingerprint of its inputs; every run produces a durable record that you
can resume after a crash.

You keep your data local. PipelineK does not phone home, does not
schedule remotely, and does not need a controller.

## Quickstart

### 1. Install

Recommended: SDKMAN.

```bash
curl -s "https://get.sdkman.io" | bash
sdk install pipelinek 0.39.0
```

Fallback: download the ZIP from
[GitHub Releases](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0)
and put the unpacked `bin/pipelinek` on your `PATH`.

### 2. Write a pipeline

Create `pipeline.kts`:

```kotlin
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/*.jar")
            echo("PIPELINE-OK")
        }
    }
}
```

### 3. Validate and run

```bash
pipelinek validate pipeline.kts
pipelinek run --workspace . pipeline.kts
```

The full walk-through (workspaces, `--db`, secret redaction, run
inspection) is in
[`docs/user/quickstart.md`](docs/user/quickstart.md).

## Examples

The [`examples/`](examples/) directory contains ten runnable
pipelines you can execute against the installed binary. Run them
through the harness script:

```bash
# Build the binary once (or let examples/run.sh do it on demand)
./gradlew -p v2 :pipeline-application:installDist

# Run a single example
examples/run.sh 03-shell.pipeline.kts

# Run all ten with assertions on exit code and event contracts
examples/run.sh
```

| Example | What it shows |
|---|---|
| `01-hello.pipeline.kts` | Minimal pipeline: one stage, one `echo` |
| `02-multi-stage.pipeline.kts` | Stages execute in declaration order |
| `03-shell.pipeline.kts` | Real OS processes via `sh`, including a shell `for` loop |
| `04-kotlin-control-flow.pipeline.kts` | Kotlin control flow inside `script {}` blocks |
| `05-failing-step.pipeline.kts` | Typed failure: `sh` exits 3 → `StepFailed(kind=SCRIPT)`, exit code 1 |
| `06-durable.pipeline.kts` | Durable execution with `--db`: journal, fingerprints, crash resume |
| `07-catch-error.pipeline.kts` | Nested `catchError`: inner `FAILURE` → outer `UNSTABLE`, pipeline continues |
| `08-parallel.pipeline.kts` | Two concurrent branches with their own durable identity |
| `09-retry.pipeline.kts` | `retry`: first attempt fails, second succeeds |
| `10-timeout.pipeline.kts` | `timeout` deadline aborts an over-running `sh` |

See [`examples/README.md`](examples/README.md) for the durable-execution
demo, the event-contract details, and the known limitations of each
example.

## Capabilities

PipelineK `0.39.0` ships with:

- Compile, validate, and inspect `.pipeline.kts` pipelines.
- Durable local execution with a SQLite journal (`--db`) and a
  control-root for state isolation.
- Typed event stream: `CompilationStarted`, `RunStarted`,
  `StageStarted`, `StepStarted`, `StepFinished`, `StepFailed`,
  `RunFinished`, `EchoOutputCaptured`.
- Jenkins-familiar `pipeline { stages { stage { ... } } }` shape.
- Block steps: `parallel`, `retry`, `timeout`, `catchError`.
- Kotlin `script {}` blocks with real Kotlin control flow.
- Typed plugin and capability contracts (`StepContract`,
  `requiredCapabilities`, registry-based discovery).
- Local credentials, with secret redaction at the durable shell seam.
- Local `artifacts`, `stash`, `unstash`, `archiveArtifacts`,
  `publishHTML`, `writeFile`, `pwd`, `isUnix`, `load`, `milestone`,
  `cleanWs`, `deleteDir`, `waitUntil`, `unstable`, `warnError`.

### System requirements

- **Java**: 21 or newer (certified on Temurin 21.0.8 and 24.0.2).
- **OS**: Linux, macOS, Windows via WSL. The distribution is
  `UNIVERSAL` per SDKMAN; it ships both `bin/pipelinek` (UNIX) and
  `bin/pipelinek.bat` (Windows).
- **Disk**: ~200 MB for the distribution plus per-run control data.
- **Concurrency**: one pipeline run per CLI invocation. No daemon mode.

## Documentation

The full user documentation lives in [`docs/user/`](docs/user/):

- [`installation.md`](docs/user/installation.md) — install on
  Linux/macOS/Windows (WSL).
- [`quickstart.md`](docs/user/quickstart.md) — your first pipeline,
  end to end.
- [`cli-reference.md`](docs/user/cli-reference.md) — every CLI flag
  and exit code.
- [`pipeline-dsl.md`](docs/user/pipeline-dsl.md) — the certified DSL
  surface.
- [`configuration-and-workspace.md`](docs/user/configuration-and-workspace.md) —
  `--workspace`, `--db`, `--control-root`.
- [`credentials-and-security.md`](docs/user/credentials-and-security.md) —
  secret redaction.
- [`events-and-troubleshooting.md`](docs/user/events-and-troubleshooting.md) —
  typed events, transcripts, recovery.
- [`upgrading.md`](docs/user/upgrading.md) — SDKMAN upgrade, rollback.
- [`cheat-sheet.md`](docs/user/cheat-sheet.md) — short, copyable,
  exit-code table.

## Releases

The current published release is
[`pipelinek 0.39.0`](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0).
SDKMAN registration is in progress.
